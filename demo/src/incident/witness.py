"""Authored contest emergency encounter, never a person-recognition or live dispatch API."""


def dispatch_authored_witness(store, event_id):
    event = store.get(event_id)
    witness = event.get("data", {}).get("context", {}).get("witness_help", {})
    required = {
        "schema": "road-guardian.witness-help.v1",
        "subject": "person_needs_help",
        "reporter": "witness",
        "recognition_performed": False,
        "source": "authored_contest_scenario",
        "recipient": "119-mock",
        "dispatch_mode": "automatic_contest",
        "emergency_state": "authored_emergency_patient",
    }
    if (
        event.get("source") != "synthetic"
        or event.get("evidence_valid") is not True
        or event.get("status")
        in ("pending_confirmation", "dismissed", "input_review_required")
        or witness != required
        or event.get("assessment", {}).get("kind") != "manual_help"
        or event["assessment"].get("recipient") != "119-mock"
    ):
        raise ValueError("Explicit authored emergency encounter required")
    video = event.get("video") or {}
    photo = video.get("input_photo") or {}
    if (
        video.get("integrity_valid") is not True
        or photo.get("integrity_valid") is not True
        or photo.get("event_id") != event_id
        or photo.get("sensor_sha256") != event.get("hash")
    ):
        raise ValueError("Verified encounter photo and sensor record required")
    # Store.dispatch preserves the raw event and returns the existing acknowledged receipt on retries.
    return store.dispatch(event_id, "119-mock")
