"""Operator-managed Pleos cloud consent/token mapping. Read fresh, never cached."""

import json
import os
import stat
from pathlib import Path
from integrations.pleos_powertrain import (
    PowertrainGrant,
    PowertrainReadError,
    _unique,
    _invalid_constant,
)


class PleosConsentRegistry:
    def __init__(self, path):
        self.path = Path(path)
        self._read()

    def _read(self):
        try:
            with self.path.open("rb") as stream:
                info = os.fstat(stream.fileno())
                if (
                    not stat.S_ISREG(info.st_mode)
                    or info.st_mode & 0o077
                    or info.st_uid != os.getuid()
                ):
                    raise ValueError()
                raw = stream.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise ValueError()
            config = json.loads(
                raw, object_pairs_hook=_unique, parse_constant=_invalid_constant
            )
            if (
                not isinstance(config, dict)
                or set(config) != {"schema", "grants"}
                or config["schema"] != "road-guardian.pleos-consents.v1"
            ):
                raise ValueError()
            rows = config["grants"]
            if not isinstance(rows, list) or len(rows) > 1000:
                raise ValueError()
            grants = {}
            for row in rows:
                if not isinstance(row, dict) or set(row) != {
                    "subject",
                    "vehicle_id",
                    "brand",
                    "vin",
                    "access_token",
                    "expires_at",
                    "region",
                    "enabled",
                }:
                    raise ValueError()
                if type(row["enabled"]) is not bool:
                    raise ValueError()
                for name in ("subject", "vehicle_id"):
                    if (
                        not isinstance(row[name], str)
                        or not 1 <= len(row[name]) <= 128
                        or any(ord(c) < 32 for c in row[name])
                    ):
                        raise ValueError()
                key = row["subject"], row["vehicle_id"]
                if key in grants:
                    raise ValueError()
                grant = PowertrainGrant(
                    **{k: v for k, v in row.items() if k != "enabled"}
                )
                grants[key] = grant if row["enabled"] else None
            return grants
        except Exception:
            raise PowertrainReadError("consent_registry_unavailable") from None

    def __call__(self, subject, vehicle_id):
        return self._read().get((subject, vehicle_id))
