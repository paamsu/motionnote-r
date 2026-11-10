"""Authored local list comparison; not OCR, police lookup, or real dispatch."""

from incident.policy import assess
import math

AUTHORIZED_PLATES = ("DEMO-001",)
AUTHORING = {
    "schema": "road-guardian.wanted-auto.v1",
    "source": "authored_contest_scenario",
    "dispatch_mode": "automatic_contest",
    "recipient": "112-mock",
    "list_id": "contest-wanted-list-v1",
    "recognition_performed": False,
    "external_lookup": False,
    "external_submission": False,
    "target_actor_id": "vehicle-1",
    "target_photo_sha256": "4a7f0da72354f1843871a46687c9de3affd358d402a97cfbb07ea5ed6da2b31f",
}


def dispatch_authored_wanted(store, event_id):
    event = store.get(event_id)
    data = event.get("data", {})
    context = data.get("context", {})
    obs = data.get("observations", {})
    if (
        event.get("source") != "synthetic"
        or data.get("source") != "synthetic"
        or event.get("evidence_valid") is not True
        or context.get("wanted_auto") != AUTHORING
        or event.get("status")
        not in ("evidence_ready", "dispatch_ready", "acknowledged")
        or obs.get("authorized_fixture_match") is not True
    ):
        raise ValueError("explicit matching authored wanted scene required")
    location = data.get("location", {})
    if (
        not isinstance(location, dict)
        or any(
            not isinstance(location.get(k), str) or not location[k].strip()
            for k in ("address_label", "road_id")
        )
        or any(
            type(location.get(k)) not in (int, float)
            or not math.isfinite(location[k])
            or abs(location[k]) > limit
            for k, limit in (("lat", 90), ("lon", 180))
        )
    ):
        raise ValueError("valid authored location required")
    plate = obs.get("plate")
    actors = context.get("actors")
    if (
        not isinstance(plate, str)
        or plate not in AUTHORIZED_PLATES
        or not isinstance(actors, list)
        or len(actors) != 1
        or not isinstance(actors[0], dict)
        or actors[0].get("id") != AUTHORING["target_actor_id"]
        or actors[0].get("plate") != plate
        or actors[0].get("recognition_status") != "not_run"
    ):
        raise ValueError("authored target and local list must match")
    fresh = assess(data)
    assessment = event.get("assessment", {})
    if any(
        assessment.get(k) != v or fresh.get(k) != v
        for k, v in [("kind", "wanted"), ("recipient", "112-mock")]
    ):
        raise ValueError("wanted assessment required")
    video = event.get("video") or {}
    photo = video.get("input_photo") or {}
    if (
        video.get("source") != "synthetic_scene_fixture"
        or video.get("camera_evidence") is not False
        or video.get("integrity_valid") is not True
        or photo.get("integrity_valid") is not True
        or photo.get("event_id") != event_id
        or photo.get("sensor_sha256") != event.get("hash")
        or photo.get("sha256") != AUTHORING["target_photo_sha256"]
    ):
        raise ValueError("bound target photo and original evidence required")
    return store.dispatch(event_id, "112-mock")
