"""Synthetic provider catalogue. No real workshop availability is represented."""

import json
from statistics import median
from diagnostics.analysis import diagnose, finite_number


def validate_measurement_pair(before, after):
    """Development comparability only; no real repair or chronology attestation."""
    if (
        after.get("source") != "synthetic"
        or before.get("source") != "synthetic"
        or after.get("vehicle_id") != before.get("vehicle_id")
        or after.get("baseline") != before.get("baseline")
    ):
        raise ValueError("follow-up vehicle and reference conditions must match")
    rates = [data.get("sample_hz") for data in (before, after)]
    if (
        not all(finite_number(rate) and rate > 0 for rate in rates)
        or rates[0] != rates[1]
    ):
        raise ValueError("follow-up sample rates must match")
    speeds = []
    for data in (before, after):
        if diagnose(data)["verdict"] == "inconclusive":
            raise ValueError("follow-up measurement units and conditions must be valid")
        baseline = data["baseline"]
        matching = [
            s
            for s in data["samples"]
            if s.get("voltage") is not None
            and finite_number(s.get("rpm"))
            and finite_number(s.get("temperature_c"))
            and abs(s["rpm"] - baseline["rpm"]) <= 100
            and abs(s["temperature_c"] - baseline["temperature_c"]) <= 3
        ]
        if any(
            not finite_number(s.get("speed_kph")) or s["speed_kph"] < 0
            for s in matching
        ):
            raise ValueError("follow-up speed conditions required")
        speeds.append(median(s["speed_kph"] for s in matching))
    if abs(speeds[0] - speeds[1]) > 5:
        raise ValueError("follow-up speed conditions must match")
    return {
        "scope": "synthetic_vehicle_baseline_rate_speed_only",
        "sample_hz": rates[0],
        "before_speed_kph": speeds[0],
        "after_speed_kph": speeds[1],
        "unverified": [
            "electrical_load",
            "battery_charge",
            "sensor_calibration",
            "capture_chronology",
        ],
    }


CENTERS = [
    {
        "id": "bluehands-mock",
        "name": "블루핸즈 시험 센터",
        "brand": "Hyundai",
        "distance_km": 2.4,
        "available": True,
        "supports": ["electrical"],
    },
    {
        "id": "kia-service-mock",
        "name": "기아 시험 서비스센터",
        "brand": "Kia",
        "distance_km": 3.1,
        "available": True,
        "supports": ["electrical"],
    },
    {
        "id": "garage-near-mock",
        "name": "가까운 시험 정비소",
        "brand": None,
        "distance_km": 0.9,
        "available": False,
        "supports": ["electrical"],
    },
    {
        "id": "garage-mock",
        "name": "시험 정비소",
        "brand": None,
        "distance_km": 1.8,
        "available": True,
        "supports": ["electrical"],
    },
]


def select_center(brand, warranty, centers=None):
    if brand not in ("Hyundai", "Kia") or warranty not in (
        "covered",
        "not_covered",
        "unknown",
    ):
        raise ValueError("invalid vehicle or warranty status")
    if warranty == "unknown":
        return {
            "status": "warranty_check_required",
            "center": None,
            "source": "synthetic",
        }
    eligible = [
        c
        for c in (CENTERS if centers is None else centers)
        if c["available"]
        and "electrical" in c["supports"]
        and (c["brand"] == brand if warranty == "covered" else c["brand"] is None)
    ]
    if not eligible:
        return {"status": "no_capacity", "center": None, "source": "synthetic"}
    center = min(eligible, key=lambda c: c["distance_km"])
    return {
        "status": "accepted_mock",
        "center": center,
        "source": "synthetic",
        "message": "시험 정비소가 자료를 받았습니다. 실제 예약·수리 보장이 아닙니다.",
    }


def repair_case(event):
    """Read-only diagnostic journey, with recovery limited to the measured signal."""
    if event["assessment"]["kind"] != "diagnosis":
        return None
    result = {
        "stage": "measurement_review",
        "source": event["source"],
        "scope": "development_voltage_rule_only",
        "center": None,
        "before": None,
        "after": None,
        "delta_v": None,
        "next_tests": [],
        "repair_confirmed": False,
        "comparison_conditions": None,
    }
    if not event["evidence_valid"] or not event.get("diagnostic_valid", False):
        result["stage"] = "evidence_invalid"
        return result
    diagnostic = event["assessment"]["diagnostic"]
    result["before"] = diagnostic.get("metrics")
    result["next_tests"] = list(
        dict.fromkeys(
            test
            for candidate in diagnostic.get("candidates", [])
            for test in candidate.get("next_tests", [])
        )
    )
    repair = event.get("repair")
    if not repair:
        latest = next(
            (
                entry
                for entry in reversed(event.get("history", []))
                if entry["action"] == "repair_offer"
            ),
            None,
        )
        if latest:
            try:
                offer = json.loads(latest["detail"])
                result["stage"] = (
                    offer["status"]
                    if offer.get("source") == "synthetic"
                    and offer.get("status")
                    in ("warranty_check_required", "no_capacity")
                    else "service_record_unconfirmed"
                )
            except (KeyError, TypeError, ValueError):
                result["stage"] = "service_record_unconfirmed"
        return result
    offer = repair["offer"]
    if offer.get("source") != "synthetic" or offer.get("status") != "accepted_mock":
        result["stage"] = "service_record_unconfirmed"
        return result
    result.update(stage="center_accepted_mock", center=offer.get("center"))
    post = repair.get("post_repair")
    if not post:
        return result
    if not post.get("integrity_valid", False):
        result["stage"] = "follow_up_invalid"
        return result
    result.update(
        stage=post["status"],
        after=post["assessment"].get("metrics"),
        delta_v=post.get("delta_v"),
        comparison_conditions=validate_measurement_pair(event["data"], post["raw"]),
    )
    return result
