"""Deterministic fixtures. All coordinates, identifiers and signals are synthetic."""

import math
import random
from acquisition.context import context_for

CATALOG = {
    "pothole": "포트홀 충격",
    "speed_bump": "과속방지턱 비교",
    "rain": "폭우와 시야 저하",
    "washer": "워셔액 비교",
    "diagnosis": "전압 이상 진단",
    "healthy": "정상 비교 자료",
    "crash": "충돌과 보험 접수",
    "emergency": "운전자 무응답",
    "help_119": "운전자 119 요청",
    "help_112": "운전자 112 요청",
    "parking": "주차 중 충격",
    "violation": "보호구역 주정차 후보",
    "reckless": "급격한 주행 변화",
    "wanted": "인가 목록 일치 후보",
}


def generate(name: str, seed: int = 7) -> dict:
    if name not in CATALOG:
        raise ValueError("unknown scenario")
    rng = random.Random(seed)
    context = context_for(name)
    samples = []
    for i in range(600):
        t = i / 20
        z = rng.gauss(0, 0.025)
        if name in ("pothole", "crash", "parking"):
            z += 2.4 * math.exp(-(((t - 10) / 0.09) ** 2))
        elif name == "speed_bump":
            z += 0.65 * math.exp(-(((t - 10) / 0.6) ** 2))
        samples.append(
            {
                "t": t,
                "vertical_g": round(z, 4),
                "voltage": round(
                    (11.7 if name == "diagnosis" and t > 8 else 13.9)
                    + rng.gauss(0, 0.025),
                    3,
                ),
                "rpm": 1200,
                "speed_kph": 0 if name in ("parking", "emergency") else 42,
                "temperature_c": 22,
            }
        )
    return {
        "schema": "road-guardian.input.v1",
        "source": "synthetic",
        "scenario": name,
        "context": context,
        "seed": seed,
        "vehicle_id": "demo-hyundai-01",
        "brand": "Hyundai",
        "location": {
            "lat": 37.5,
            "lon": 127.0,
            "accuracy_m": 8,
            "address_label": "서울특별시 서초구 사평대로 인근",
            "address_source": "scenario_fixture",
            "road_id": "demo-road-east",
            "heading": 90,
        },
        "samples": samples,
        "sample_hz": 20,
        "units": {"vertical_g": "g, gravity removed", "voltage": "V", "rpm": "rpm"},
        "observations": {
            "road_surface": (
                "depression"
                if name == "pothole"
                else "raised" if name == "speed_bump" else "unknown"
            ),
            "airbag": name == "crash",
            "parked": name == "parking",
            "wiper_high": name in ("rain", "washer"),
            "visibility_low": name == "rain",
            "rain_sensor_wet": name == "rain",
            "washer_active": name == "washer",
            "weather_context": {
                "source": "authored_synthetic",
                "condition": "rain" if name == "rain" else "unknown",
            },
            "unresponsive_test_signal": name == "emergency",
            "manual_help_target": {"help_119": "119", "help_112": "112"}.get(name),
            "restricted_zone": name == "violation",
            "stationary_seconds": 180 if name == "violation" else 0,
            "abrupt_manoeuvres": 4 if name == "reckless" else 0,
            "authorized_fixture_match": name == "wanted",
            "plate": context["actors"][0]["plate"] if context["actors"] else None,
            "relative_motion": (
                {
                    "source": "authored_synthetic",
                    "actor_id": "vehicle-1",
                    "reference": "ego_vehicle",
                    "units": {"time": "s", "lateral": "m"},
                    "points": [
                        {"t": i, "ego_lateral_m": 0.0, "target_lateral_m": value}
                        for i, value in enumerate(
                            [
                                0,
                                0,
                                1.75,
                                3.5,
                                3.5,
                                1.75,
                                0,
                                0,
                                -1.75,
                                -3.5,
                                -3.5,
                                -1.75,
                                0,
                            ]
                        )
                    ],
                }
                if name == "reckless"
                else None
            ),
        },
        "baseline": {
            "voltage": 13.9,
            "rpm": 1200,
            "temperature_c": 22,
            "source": "synthetic matched conditions",
        },
        "media": {
            "status": "not_captured",
            "reason": "synthetic sensor fixture; no real camera evidence",
        },
    }
