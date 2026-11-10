"""Explicit contest driver-emergency scene; no sensor inference or external dispatch."""

import math

AUTHORING = {
    "schema": "road-guardian.driver-emergency.v1",
    "source": "authored_contest_scenario",
    "subject": "own_driver",
    "movement_state": "authored_no_movement",
    "response_state": "authored_unresponsive",
    "dispatch_mode": "automatic_contest",
    "recipient": "119-mock",
    "recognition_performed": False,
    "external_submission": False,
}


def dispatch_authored_driver(store, event_id):
    event = store.get(event_id)
    data = event.get("data", {})
    observation = data.get("observations", {})
    if (
        event.get("source") != "synthetic"
        or event.get("evidence_valid") is not True
        or data.get("context", {}).get("driver_emergency") != AUTHORING
        or any(
            k in observation
            for k in ("emergency_timeline", "emergency_timeline_analysis")
        )
        or observation.get("unresponsive_test_signal") is not True
        or event.get("assessment", {}).get("kind") != "emergency"
        or event.get("assessment", {}).get("recipient") != "119-mock"
        or event.get("status")
        in ("pending_confirmation", "dismissed", "input_review_required")
    ):
        raise ValueError(
            "Explicit authored driver emergency required; response timeline is separate"
        )
    location = data.get("location", {})
    if (
        not location.get("address_label")
        or not location.get("road_id")
        or any(
            type(location.get(k)) not in (int, float)
            or not math.isfinite(location[k])
            or abs(location[k]) > limit
            for k, limit in [("lat", 90), ("lon", 180)]
        )
    ):
        raise ValueError("Authored scene location required")
    video = event.get("video") or {}
    photo = video.get("input_photo") or {}
    if (
        video.get("integrity_valid") is not True
        or photo.get("integrity_valid") is not True
        or photo.get("event_id") != event_id
        or photo.get("sensor_sha256") != event.get("hash")
    ):
        raise ValueError("Verified scene photo and original sensor record required")
    return store.dispatch(event_id, "119-mock")
