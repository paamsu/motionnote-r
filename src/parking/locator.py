"""Parking memory and conservative elevator cue interpretation."""

import math
import time


def fixture():
    return {
        "source": "synthetic",
        "vehicle_id": "demo-hyundai-01",
        "parked_at": time.time() - 3 * 86400,
        "place": "테스트 주차장",
        "floor": "B2",
        "landmark": "24번 기둥",
        "bay": None,
        "vehicle": {"model": "아이오닉 6", "color": "흰색", "plate": "DEMO-OWN-01"},
        "evidence": [
            {"type": "sign", "text": "B2", "source": "scenario_fixture"},
            {"type": "sign", "text": "24", "source": "scenario_fixture"},
        ],
        "scene_asset": "parking_scene_v1",
        "scene_source": "ai_generated_scenario",
        "floor_status": "scenario_known",
        "current_phone_floor": None,
    }


def elevator_cue(samples):
    if not isinstance(samples, list) or any(not isinstance(s, dict) for s in samples):
        raise ValueError("sensor sample list required")
    if len(samples) < 5:
        return {
            "prompt_parking": False,
            "reason": "insufficient_samples",
            "current_floor": None,
        }
    if any(
        not all(
            isinstance(s.get(k), (int, float))
            and not isinstance(s.get(k), bool)
            and math.isfinite(s[k])
            for k in ("t", "pressure_hpa", "vertical_accel")
        )
        for s in samples
    ):
        raise ValueError(
            "finite time, pressure and gravity-removed acceleration required"
        )
    ordered = sorted(samples, key=lambda x: x["t"])
    if any(a["t"] == b["t"] for a, b in zip(ordered, ordered[1:])):
        raise ValueError("distinct sample times required")
    duration = ordered[-1]["t"] - ordered[0]["t"]
    pressure_delta = ordered[-1]["pressure_hpa"] - ordered[0]["pressure_hpa"]
    pulse = any(abs(s["vertical_accel"]) > 0.15 for s in ordered)
    # Rising pressure can indicate descent, but never proves a floor or elevator by itself.
    candidate = 5 <= duration <= 90 and 0.25 <= pressure_delta <= 3 and pulse
    return {
        "prompt_parking": candidate,
        "reason": "possible_descent" if candidate else "no_descent_cue",
        "pressure_delta_hpa": round(pressure_delta, 3),
        "current_floor": None,
        "label": "내려가는 이동 후보" if candidate else "이동 신호 미확인",
    }


def locate(parking, signs):
    if not isinstance(signs, list) or not all(isinstance(s, str) for s in signs):
        raise ValueError("sign text list required")
    normalized = {s.upper().strip() for s in signs}
    floors = {s for s in normalized if s.startswith("B") and s[1:].isdigit()}
    if len(floors) > 1:
        return {
            "status": "conflicting_signs",
            "current_floor": None,
            "parking": parking,
        }
    observed = next(iter(floors), None)
    return {
        "status": (
            "same_floor"
            if observed == parking["floor"]
            else "different_floor" if observed else "floor_unknown"
        ),
        "current_floor": observed,
        "parking": parking,
        "source": "synthetic_sign_input",
        "landmark_matches": str(parking["landmark"].split("번")[0]) in normalized,
    }
