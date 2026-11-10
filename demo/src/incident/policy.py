"""Development policies; no live emergency or public-service dispatch."""

import math
import json
from diagnostics.analysis import diagnose, finite_number


def impact_input(data):
    """Validate authored impact inputs before interpreting the development threshold.

    Invalid inputs remain storable; issues prevent incident classification/dispatch.
    Timing is checked against the declared rate, not against a vehicle clock.
    """
    issues = []
    observations = data.get("observations")
    if not isinstance(observations, dict):
        observations = {}
        issues.append("observations must be an object")
    for key in (
        "airbag",
        "parked",
        "unresponsive_test_signal",
        "wiper_high",
        "visibility_low",
        "rain_sensor_wet",
        "washer_active",
    ):
        if type(observations.get(key)) is not bool:
            issues.append(key + " must be an explicit boolean")
    if "manual_help_target" not in observations or observations[
        "manual_help_target"
    ] not in (None, "119", "112"):
        issues.append("manual_help_target must be explicit null, 119 or 112")
    units = data.get("units")
    if not isinstance(units, dict) or units.get("vertical_g") != "g, gravity removed":
        issues.append("vertical_g requires gravity-removed g units")
    hz = data.get("sample_hz")
    rate_valid = finite_number(hz) and hz > 0
    if not rate_valid:
        issues.append("sample_hz must be positive and finite")
    samples = data.get("samples")
    if not isinstance(samples, list) or len(samples) < 2:
        issues.append("at least two timed impact samples required")
        return observations, None, issues
    if any(
        not isinstance(s, dict)
        or not finite_number(s.get("vertical_g"))
        or not finite_number(s.get("t"))
        for s in samples
    ):
        issues.append("impact samples require finite numeric t and vertical_g")
        return observations, None, issues
    times = [s["t"] for s in samples]
    if times[0] < 0 or any(b <= a for a, b in zip(times, times[1:])):
        issues.append("impact sample times must be nonnegative and strictly increasing")
    elif rate_valid and any(
        not math.isclose(b - a, 1 / hz, rel_tol=1e-6, abs_tol=1e-9)
        for a, b in zip(times, times[1:])
    ):
        issues.append("impact sample times do not match sample_hz")
    peak = max(abs(s["vertical_g"]) for s in samples)
    return observations, peak if not issues else None, issues


def assess(data: dict) -> dict:
    o, peak, input_issues = impact_input(data)
    kind, title, recipient, confirm = "observation", "관측 자료", None, False
    emergency_assessment = None
    higher_priority = o.get("airbag") is True or (
        data.get("source") == "synthetic"
        and o.get("manual_help_target") in ("119", "112")
    )
    if not higher_priority and (
        "emergency_timeline" in o or "emergency_timeline_analysis" in o
    ):
        try:
            from acquisition.emergency_timeline import analyze

            claimed = o.get("emergency_timeline_analysis")
            if not isinstance(claimed, dict) or data.get("source") != "synthetic":
                raise ValueError("synthetic timeline report required")
            checked = analyze(o.get("emergency_timeline"), as_of=claimed.get("as_of"))
            if json.dumps(checked, sort_keys=True, allow_nan=False) != json.dumps(
                claimed, sort_keys=True, allow_nan=False
            ) or o.get("unresponsive_test_signal") is not (
                checked["status"] == "timed_out"
            ):
                raise ValueError("timeline result and final signal must agree")
            emergency_assessment = {
                "source": "authored_timeline_evaluation",
                "status": checked["status"],
                "camera_analysis_performed": False,
                "clinical_detection_performed": False,
            }
            if checked["status"] == "inconclusive":
                raise ValueError("timeline coverage is inconclusive")
        except (ValueError, TypeError, KeyError):
            input_issues.append(
                "emergency timeline requires consistent, complete authored temporal inputs"
            )
            emergency_assessment = {
                "source": "authored_timeline_evaluation",
                "status": "withheld",
                "camera_analysis_performed": False,
                "clinical_detection_performed": False,
            }
    collision_assessment = None
    collision_candidate = False
    # This optional input is a declared demo observation, never a camera result
    # or an inference from a vertical impact. Independent urgent signals win.
    collision_priority = higher_priority or (
        not input_issues
        and data.get("source") == "synthetic"
        and o.get("unresponsive_test_signal") is True
    )
    if not collision_priority and "collision_observation" in o:
        collision = o["collision_observation"]
        collision_assessment = {
            "source": "authored_collision_observation",
            "status": "withheld",
            "visual_analysis_performed": False,
            "collision_confirmed": False,
        }
        if (
            not isinstance(collision, dict)
            or set(collision) != {"source", "state"}
            or data.get("source") != "synthetic"
            or type(collision.get("source")) is not str
            or collision["source"] != "authored_collision_observation"
            or type(collision.get("state")) is not str
            or collision["state"] not in ("candidate", "no_collision", "unknown")
        ):
            input_issues.append(
                "collision_observation requires explicit synthetic authored source and supported state"
            )
        else:
            state = collision["state"]
            collision_assessment.update(
                state=state,
                status={
                    "candidate": "authored_collision_candidate",
                    "no_collision": "authored_no_collision",
                    "unknown": "unknown",
                }[state],
            )
            collision_candidate = state == "candidate"
    weather_assessment = None
    weather_timeline_assessment = None
    road_assessment = None
    # A declared surface is not a camera finding. Keep urgent incident inputs
    # ahead of ancillary road context, and preserve unknown vs malformed data.
    urgent = (
        collision_candidate
        or o.get("airbag") is True
        or (o.get("parked") is True and peak is not None and peak > 1.5)
        or (
            data.get("source") == "synthetic"
            and (
                o.get("manual_help_target") in ("119", "112")
                or o.get("unresponsive_test_signal") is True
            )
        )
    )
    if not input_issues and not urgent:
        surface = o.get("road_surface", "unknown")
        valid_surface = type(surface) is str and surface in (
            "depression",
            "raised",
            "unknown",
        )
        road_assessment = {
            "source": "declared_surface_and_impact",
            "visual_analysis_performed": False,
            "status": "withheld",
        }
        if not valid_surface:
            input_issues.append("road_surface must be depression, raised or unknown")
            road_assessment["reason"] = "road_surface_invalid"
        else:
            road_assessment["surface"] = surface
            road_assessment["reason"] = (
                "impact_below_threshold" if peak <= 1.5 else "surface_not_depression"
            )
            if peak > 1.5 and surface == "depression":
                road_assessment.update(
                    status="declared_pothole_candidate",
                    reason="declared_depression_with_impact",
                )
    road_candidate = (
        peak is not None and peak > 1.5 and o.get("road_surface") == "depression"
    )
    if (
        not urgent
        and not road_candidate
        and ("weather_timeline" in o or "weather_timeline_analysis" in o)
    ):
        try:
            from acquisition.weather_timeline import analyze

            claimed = o.get("weather_timeline_analysis")
            if data.get("source") != "synthetic" or not isinstance(claimed, dict):
                raise ValueError("synthetic weather timeline report required")
            checked = analyze(o.get("weather_timeline"), as_of=claimed.get("as_of"))
            if json.dumps(checked, sort_keys=True, allow_nan=False) != json.dumps(
                claimed, sort_keys=True, allow_nan=False
            ):
                raise ValueError("weather timeline report changed")
            if checked["status"] == "inconclusive":
                raise ValueError("weather timeline coverage is incomplete")
            last = checked["timeline"]["samples"][-1]
            if any(
                o.get(key) is not last[key]
                for key in (
                    "wiper_high",
                    "visibility_low",
                    "rain_sensor_wet",
                    "washer_active",
                )
            ):
                raise ValueError("weather final observations disagree with timeline")
            weather_timeline_assessment = {
                "source": "authored_weather_timeline_evaluation",
                "status": checked["status"],
                "camera_analysis_performed": False,
                "as_of": checked["as_of"],
            }
        except (ValueError, TypeError, KeyError):
            input_issues.append(
                "weather timeline requires consistent, complete authored temporal inputs"
            )
            weather_timeline_assessment = {
                "source": "authored_weather_timeline_evaluation",
                "status": "withheld",
                "camera_analysis_performed": False,
            }
    if input_issues:
        title = "입력 확인 필요"
    elif (
        o.get("manual_help_target") in ("119", "112")
        and data.get("source") == "synthetic"
    ):
        target = o["manual_help_target"]
        kind, title, recipient = "manual_help", target + " 도움 요청", target + "-mock"
    elif o.get("airbag") is True:
        kind, title, recipient = "crash", "충돌 신호 감지", "insurance-mock"
    elif (
        o.get("unresponsive_test_signal") is True and data.get("source") == "synthetic"
    ):
        kind, title, recipient = "emergency", "무응답 대응 시험", "119-mock"
    elif collision_candidate:
        kind, title, recipient = "crash", "충돌 후보 확인", "insurance-mock"
    elif o.get("parked") is True and peak > 1.5:
        kind, title, recipient = "parking", "주차 중 충격 감지", "owner-app-mock"
    elif peak > 1.5 and o.get("road_surface") == "depression":
        kind, title, recipient, confirm = (
            "pothole",
            "도로 파손 후보",
            "municipality-mock",
            True,
        )
    elif (
        weather_timeline_assessment is not None
        and weather_timeline_assessment["status"] != "sustained_candidate"
    ):
        title = {
            "collecting": "시야 변화 확인 중",
            "washer_suppressed": "워셔 사용 구간",
            "recovered": "시야 저하 신호 해제",
        }.get(weather_timeline_assessment["status"], "입력 확인 필요")
    elif (
        o.get("wiper_high") is True
        and o.get("visibility_low") is True
        and o.get("rain_sensor_wet") is True
        and o.get("washer_active") is False
    ):
        # These authored labels constrain a demo candidate; they are not sensed
        # weather or a classifier of car washes/tunnels. Keep higher-priority
        # incidents above this branch and retain the original input unchanged.
        context = o.get("weather_context")
        weather_assessment = {
            "status": "withheld",
            "source": "authored_context_gate",
            "visual_analysis_performed": False,
        }
        if "weather_context" not in o:
            weather_assessment["reason"] = "weather_context_missing"
            title = "시야 저하 원인 확인"
        elif (
            not isinstance(context, dict)
            or context.get("source") != "authored_synthetic"
            or type(context.get("condition")) is not str
            or context["condition"]
            not in ("rain", "car_wash", "tunnel", "manual_wiper", "unknown")
            or data.get("source") != "synthetic"
        ):
            input_issues.append(
                "weather_context requires synthetic input and authored_synthetic source with a supported condition"
            )
            weather_assessment["reason"] = "weather_context_invalid"
            title = "입력 확인 필요"
        elif context["condition"] != "rain":
            weather_assessment.update(
                reason="rain_not_established", condition=context["condition"]
            )
            title = "시야 저하 원인 확인"
        else:
            weather_assessment.update(
                status="authored_rain_candidate", condition="rain"
            )
            kind, title = "rain", "전방 시야 저하"
    else:
        # Lower-priority authored observations must not use Python truthiness
        # or implicit numeric conversion to create a report candidate.
        for key in ("restricted_zone", "authorized_fixture_match"):
            if type(o.get(key)) is not bool:
                input_issues.append(key + " must be an explicit boolean")
        stationary = o.get("stationary_seconds")
        manoeuvres = o.get("abrupt_manoeuvres")
        if not finite_number(stationary) or stationary < 0:
            input_issues.append("stationary_seconds must be finite and nonnegative")
        if type(manoeuvres) is not int or manoeuvres < 0:
            input_issues.append("abrupt_manoeuvres must be a nonnegative integer")
        if input_issues:
            title = "입력 확인 필요"
        elif o["restricted_zone"] is True and stationary >= 120:
            kind, title, recipient, confirm = (
                "violation",
                "주정차 위반 후보",
                "public-report-mock",
                True,
            )
        elif (
            o["authorized_fixture_match"] is True and data.get("source") == "synthetic"
        ):
            kind, title, recipient = "wanted", "시험 목록 번호 일치", "112-mock"
        elif manoeuvres >= 3:
            kind, title = "reckless", "급격한 주행 변화"
    report = diagnose(data)
    if kind == "observation" and not input_issues:
        if report["verdict"] == "inspection_required":
            kind, title = "diagnosis", "차량 점검 필요"
        elif weather_assessment is None and weather_timeline_assessment is None:
            title = "관측 자료 확인"
    if weather_assessment is not None:
        radar = o.get("radar_status")
        radar_valid = (
            data.get("source") == "synthetic"
            and isinstance(radar, dict)
            and radar.get("source") == "authored_synthetic"
            and radar.get("state")
            in ("available", "degraded", "unavailable", "unknown")
        )
        weather_assessment["radar"] = {
            "source": "authored_synthetic" if radar_valid else "unavailable",
            "state": radar["state"] if radar_valid else "unknown",
            "live_sensor_read": False,
        }
    return {
        "kind": kind,
        "title": title,
        "recipient": recipient,
        "status": (
            "input_review_required"
            if input_issues
            else "pending_confirmation" if confirm else "evidence_ready"
        ),
        "input_issues": input_issues,
        "weather_assessment": weather_assessment,
        "weather_timeline_assessment": weather_timeline_assessment,
        "emergency_assessment": emergency_assessment,
        "collision_assessment": collision_assessment,
        "road_assessment": road_assessment,
        "requires_confirmation": confirm,
        "diagnostic": report,
        "metrics": {"peak_vertical_g": round(peak, 3) if peak is not None else None},
        "policy_version": (
            "development-v9"
            if weather_timeline_assessment is not None
            else "development-v8"
        ),
        "source": data["source"],
        "limitations": [
            "개발 시험 기준이며 실차 성능 미검증",
            "기관 연결은 모의 수신처",
        ],
    }


def approaching(hazard: dict, vehicles: list[dict], now: float) -> list[str]:
    if hazard["expires_at"] <= now:
        return []
    result = []
    loc = hazard["location"]
    for v in vehicles:
        if not v.get("authenticated") or v.get("brand") not in ("Hyundai", "Kia"):
            continue
        if v.get("road_id") != loc["road_id"] or v.get("eta_seconds", -1) not in range(
            1, 121
        ):
            continue
        difference = abs((v["heading"] - loc["heading"] + 180) % 360 - 180)
        distance = math.hypot(
            (v["lat"] - loc["lat"]) * 111000, (v["lon"] - loc["lon"]) * 88000
        )
        heading = math.radians(v["heading"])
        ahead = (loc["lon"] - v["lon"]) * 111000 * math.cos(
            math.radians(v["lat"])
        ) * math.sin(heading) + (loc["lat"] - v["lat"]) * 111000 * math.cos(heading)
        if (
            ahead > 0
            and difference < 45
            and distance <= 2000
            and v.get("accuracy_m", 1000) <= 30
        ):
            result.append(v["id"])
    return result
