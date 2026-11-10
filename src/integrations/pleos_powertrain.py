"""Read-only Pleos cloud adapter. No OAuth issuance, live telemetry or repair verdict.

Contract: document.pleos.ai/en/api-reference/vehicle-data-api/vehicle-data-api/common/get-powertrain-data
Cloud access currently requires an eligible European vehicle and provider consent.
"""

import datetime as dt
import hashlib
import http.client
import json
import math
import re
import ssl
import time
from dataclasses import dataclass, field
from urllib.parse import urlencode

MAX_RESPONSE = 2 * 1024 * 1024
MAX_SAMPLES = 100000


class PowertrainReadError(Exception):
    """Sanitized failure; never includes a VIN, credential or provider response."""


@dataclass(frozen=True)
class PowertrainGrant:
    """Supplied by the server's consent registry, never by a client request body."""

    subject: str
    vehicle_id: str
    brand: str
    vin: str = field(repr=False)
    access_token: str = field(repr=False)
    expires_at: float
    region: str


def _object(value):
    if not isinstance(value, dict):
        raise ValueError("object required")
    return value


def _unique(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate JSON field")
        value[key] = item
    return value


def _invalid_constant(_):
    raise ValueError("non-finite JSON number")


def _finite(value):
    try:
        return type(value) in (int, float) and math.isfinite(value)
    except OverflowError:
        return False


def normalize_powertrain(raw):
    """Preserve independent arrays. Equal lengths do not establish synchronization."""
    if not isinstance(raw, bytes) or len(raw) > MAX_RESPONSE:
        raise ValueError("bounded response bytes required")
    body = json.loads(raw, object_pairs_hook=_unique, parse_constant=_invalid_constant)
    meta = _object(_object(body).get("meta"))
    success = meta.get("success")
    if (
        type(meta.get("code")) is not int
        or meta["code"] != 200
        or not (success is True or type(success) is str and success == "true")
    ):
        raise ValueError("successful provider envelope required")
    trips = _object(body.get("data")).get("trips")
    if not isinstance(trips, list) or len(trips) > 1024:
        raise ValueError("bounded trip list required")
    normalized, starts = [], set()
    total_samples = 0
    for trip in trips:
        trip = _object(trip)
        start = trip.get("triggerStartTime")
        if not isinstance(start, str) or not re.fullmatch(
            r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z", start
        ):
            raise ValueError("UTC trip start required")
        instant = dt.datetime.fromisoformat(start.replace("Z", "+00:00"))
        identity = instant.isoformat()
        if identity in starts:
            raise ValueError("duplicate trip start")
        starts.add(identity)
        length = trip.get("tripLength")
        if type(length) is not int or not 0 <= length <= 7 * 86400000:
            raise ValueError("valid trip duration required")
        battery = trip.get("batteries")
        hv = _object(battery).get("hv") if battery is not None else None
        engine = trip.get("engine")
        fields = (
            (
                "hv_pack_voltage_v",
                _object(hv).get("voltage") if hv is not None else None,
                6553,
            ),
            (
                "engine_rpm",
                _object(engine).get("rpm") if engine is not None else None,
                16383.5,
            ),
            (
                "engine_oil_temperature_c",
                _object(engine).get("oilTemperature") if engine is not None else None,
                150.5,
            ),
        )
        signals = {}
        for name, samples, maximum in fields:
            if samples is None:
                signals[name] = {
                    "state": "not_returned",
                    "values": [],
                    "invalid_count": 0,
                }
                continue
            if not isinstance(samples, list):
                raise ValueError("signal array required")
            total_samples += len(samples)
            if total_samples > MAX_SAMPLES:
                raise ValueError("too many signal samples")
            values, invalid = [], 0
            for sample in samples:
                if sample is None:
                    values.append(None)
                elif not _finite(sample):
                    raise ValueError("finite number or null required")
                elif not 0 <= sample <= maximum:
                    values.append(None)
                    invalid += 1
                else:
                    values.append(sample)
            signals[name] = {
                "state": "returned",
                "values": values,
                "invalid_count": invalid,
            }
        normalized.append(
            {
                "started_at": start,
                "duration_ms": length,
                "signals": signals,
                "sample_timestamps": None,
                "sample_hz": None,
                "aligned_samples": False,
            }
        )
    return {
        "schema": "road-guardian.powertrain-import.v1",
        "provenance": "external_response_not_attested",
        "transport": "offline_import",
        "raw_sha256": hashlib.sha256(raw).hexdigest(),
        "trips": normalized,
        "diagnosis_performed": False,
        "repair_confirmed": False,
        "comparison_ready": False,
        "missing_conditions": [
            "per_sample_time",
            "signal_synchronization",
            "vehicle_speed",
            "battery_state_of_charge",
            "electrical_load",
            "sensor_calibration",
        ],
    }


class PleosPowertrainClient:
    """Registry callback rechecks current subject/vehicle consent on every read.

    Authentication is separate from this app's events:read access token. A test
    connection factory may bind a trusted local HTTPS fixture; production has a
    fixed vendor host, normal CA/hostname validation and no redirects or retries.
    """

    def __init__(self, grant_provider, connection_factory=None, clock=time.time):
        self._grants, self._clock = grant_provider, clock
        self._connection = connection_factory or (
            lambda: http.client.HTTPSConnection(
                "api.pleos.ai", timeout=10, context=ssl.create_default_context()
            )
        )

    def _grant(self, subject, vehicle_id):
        grant = self._grants(subject, vehicle_id)
        if (
            not isinstance(grant, PowertrainGrant)
            or grant.subject != subject
            or grant.vehicle_id != vehicle_id
            or not subject
            or not vehicle_id
            or grant.region != "europe"
            or grant.brand not in ("hyundai", "kia", "genesis")
            or not isinstance(grant.vin, str)
            or not re.fullmatch(r"[A-HJ-NPR-Z0-9]{17}", grant.vin)
            or not isinstance(grant.access_token, str)
            or len(grant.access_token) > 4096
            or not re.fullmatch(r"[A-Za-z0-9._~+/-]{16,4096}={0,2}", grant.access_token)
            or not _finite(grant.expires_at)
            or grant.expires_at <= self._clock() + 30
        ):
            raise PowertrainReadError("consent_or_provider_token_required")
        return grant

    def fetch(self, subject, vehicle_id, start_date, end_date):
        try:
            if not all(
                isinstance(value, str) and re.fullmatch(r"\d{8}", value)
                for value in (start_date, end_date)
            ):
                raise ValueError()
            start, end = (
                dt.datetime.strptime(value, "%Y%m%d").date()
                for value in (start_date, end_date)
            )
            if not 0 <= (end - start).days <= 7:
                raise ValueError()
        except ValueError:
            raise PowertrainReadError("date_window_required") from None
        connection = None
        try:
            grant = self._grant(subject, vehicle_id)
            path = f"/v1/vehicles/{grant.vin}/powertrains/data?" + urlencode(
                {"startDate": start_date, "endDate": end_date, "zoneId": "UTC"}
            )
            connection = self._connection()
            connection.request(
                "GET",
                path,
                headers={
                    "Authorization": "Bearer " + grant.access_token,
                    "Brand": grant.brand,
                    "Accept": "application/json",
                    "Accept-Encoding": "identity",
                },
            )
            response = connection.getresponse()
            if response.status != 200:
                code = {
                    401: "provider_sign_in_required",
                    403: "provider_access_denied",
                    429: "provider_rate_limited",
                }.get(response.status, "provider_unavailable")
                raise PowertrainReadError(code)
            if (
                response.getheader("Content-Type", "").split(";", 1)[0].strip().lower()
                != "application/json"
                or response.getheader("Content-Encoding", "identity").lower()
                != "identity"
            ):
                raise PowertrainReadError("provider_response_invalid")
            raw = response.read(MAX_RESPONSE + 1)
            batch = normalize_powertrain(raw)
            if any(
                not start
                <= dt.datetime.fromisoformat(
                    t["started_at"].replace("Z", "+00:00")
                ).date()
                <= end
                for t in batch["trips"]
            ):
                raise PowertrainReadError("provider_response_invalid")
            # Drop the response if consent/vehicle mapping or credentials changed in flight.
            if self._grant(subject, vehicle_id) != grant:
                raise PowertrainReadError("consent_changed")
            batch.update(
                transport="https_adapter",
                vehicle_id=vehicle_id,
                retrieved_at=self._clock(),
            )
            return batch
        except PowertrainReadError:
            raise
        except Exception:
            raise PowertrainReadError("provider_read_failed") from None
        finally:
            if connection is not None:
                try:
                    connection.close()
                except Exception:
                    pass
