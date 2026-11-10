"""Synthetic third-party evidence inventory, never a violation or wanted-person verdict."""

from incident.policy import assess
import re


def traffic_review(event):
    kind = event.get("assessment", {}).get("kind")
    if kind not in ("violation", "wanted", "reckless"):
        return None
    data = event.get("data", {})
    result = {
        "schema": "road-guardian.traffic-review.v1",
        "kind": kind,
        "source": event.get("source"),
        "verdict_verified": False,
        "recognition_performed": False,
        "external_submission": False,
        "stage": "invalid",
        "subject": None,
        "stationary_seconds": None,
        "fixture_flag": False,
        "review_confirmed": False,
        "dismissed": False,
        "relative_motion": None,
        "abrupt_manoeuvres": None,
        "mock_receipt": None,
        "mock_delivery": "not_sent",
    }
    if (
        not isinstance(data, dict)
        or event.get("evidence_valid") is not True
        or event.get("source") != "synthetic"
        or data.get("source") != "synthetic"
    ):
        return result
    try:
        if assess(data)["kind"] != kind:
            return result
    except (TypeError, ValueError, AttributeError, OverflowError, KeyError):
        return result
    context = data.get("context")
    actors = context.get("actors") if isinstance(context, dict) else None
    actor = (
        actors[0]
        if isinstance(actors, list) and actors and isinstance(actors[0], dict)
        else {}
    )

    def supplied(key):
        value = actor.get(key)
        return (
            value if isinstance(value, str) and 0 < len(value.strip()) <= 128 else None
        )

    observations = data.get("observations", {})
    duration = observations.get("stationary_seconds")
    valid_duration = type(duration) in (int, float) and 0 <= duration <= 86400
    result.update(
        stage="synthetic_review",
        subject={key: supplied(key) for key in ("plate", "model", "color")},
        stationary_seconds=duration if valid_duration else None,
        fixture_flag=observations.get(
            "restricted_zone" if kind == "violation" else "authorized_fixture_match"
        )
        is True,
    )
    stationary = observations.get("synthetic_stationary_analysis")
    if kind == "violation" and isinstance(stationary, dict):
        from acquisition.synthetic_stationary import event_input

        try:
            expected = event_input(
                stationary, restricted_zone=observations.get("restricted_zone")
            )["observations"]
            video = event.get("video") or {}
            if (
                duration == expected["stationary_seconds"]
                and observations.get("stationary_motion")
                == expected["stationary_motion"]
                and video.get("integrity_valid") is True
                and video.get("sha256") == stationary["video_sha256"]
                and video.get("original_sha256") == stationary["video_sha256"]
            ):
                result["stationary_motion"] = expected["stationary_motion"]
        except (ValueError, TypeError, KeyError):
            pass
    if kind == "reckless":
        count = observations.get("abrupt_manoeuvres")
        result["abrupt_manoeuvres"] = (
            count if type(count) is int and 0 <= count <= 1000 else None
        )
        motion = observations.get("relative_motion")
        analysis = observations.get("synthetic_motion_analysis")
        decoded_source = (
            isinstance(motion, dict)
            and isinstance(analysis, dict)
            and motion.get("source")
            == analysis.get("source")
            == "synthetic_video_tracking"
            and motion.get("profile")
            == analysis.get("profile")
            == "color-coded-topdown-v1"
            and analysis.get("status") == "measured"
            and analysis.get("missing_frames") == []
            and type(analysis.get("abrupt_manoeuvres")) is int
            and analysis["abrupt_manoeuvres"] == count
            and analysis.get("candidate") is True
            and analysis.get("points") == motion.get("points")
            and isinstance(motion.get("video_sha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", motion["video_sha256"]) is not None
            and motion["video_sha256"] == analysis.get("video_sha256")
        )
        if (
            isinstance(motion, dict)
            and (motion.get("source") == "authored_synthetic" or decoded_source)
            and motion.get("reference") == "ego_vehicle"
            and motion.get("actor_id") == actor.get("id")
            and motion.get("units") == {"time": "s", "lateral": "m"}
        ):
            points = motion.get("points")
            if isinstance(points, list) and 2 <= len(points) <= 120:
                valid = True
                previous = -1
                for point in points:
                    if not isinstance(point, dict):
                        valid = False
                        break
                    t = point.get("t")
                    ego = point.get("ego_lateral_m")
                    target = point.get("target_lateral_m")
                    if not (
                        type(t) in (int, float)
                        and 0 <= t <= 120
                        and previous < t
                        and type(ego) in (int, float)
                        and -20 <= ego <= 20
                        and type(target) in (int, float)
                        and -20 <= target <= 20
                    ):
                        valid = False
                        break
                    previous = t
                if valid:
                    result["relative_motion"] = motion
    result["review_confirmed"] = event.get("status") in (
        "dispatch_ready",
        "retry_pending",
        "acknowledged",
    )
    result["dismissed"] = event.get("status") == "dismissed"
    if kind == "reckless":
        return result
    expected = "public-report-mock" if kind == "violation" else "112-mock"
    for delivery in event.get("dispatches", []):
        if not isinstance(delivery, dict) or delivery.get("recipient") != expected:
            continue
        receipt = delivery.get("receipt")
        if (
            delivery.get("status") == "acknowledged"
            and isinstance(receipt, str)
            and re.fullmatch(r"MOCK-[0-9a-f]{8}", receipt)
        ):
            result.update(mock_receipt=receipt, mock_delivery="acknowledged")
        elif delivery.get("status") == "retry_pending":
            result["mock_delivery"] = "retry_pending"
    return result
