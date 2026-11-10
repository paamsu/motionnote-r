"""Portable authored content and optional private additions, without sensor/fact claims."""

import hashlib
import json
import zipfile
import io
import uuid
from evidence.observations import Observations
from evidence.overview import owner_assessment
from evidence.case_photos import checked_capture_context


def encode(value):
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode()


def write_owner_package(
    event, output, review_note=None, observations=None, photo_bundle=None
):
    if (
        event.get("source") != "owner_reported"
        or event.get("evidence_valid") is not True
        or event.get("sensor_original") is not False
        or event.get("status") != "owner_recorded"
    ):
        raise ValueError("valid authored record required")
    raw = event["content_original_json"].encode()
    data = json.loads(raw)
    if (
        hashlib.sha256(raw).hexdigest() != event["hash"]
        or data != event["data"]
        or event["assessment"] != owner_assessment(data)
    ):
        raise ValueError("authored content integrity failure")
    files = {"authored-original.json": raw}
    note_revision = 0
    if review_note is not None:
        if (
            review_note.get("schema") != "road-guardian.private-review-note.v1"
            or review_note.get("event_id") != event["id"]
            or review_note.get("scope") != "current_subject_only"
            or review_note.get("evidence_statement") is not False
        ):
            raise ValueError("private note scope mismatch")
        note_revision = review_note["revision"]
        if type(note_revision) is not int or not 0 <= note_revision < 2147483648:
            raise ValueError("invalid note revision")
        if not isinstance(review_note["note"], str) or len(review_note["note"]) > 2000:
            raise ValueError("invalid private note")
        if note_revision > 0:
            files["private-review-note.json"] = encode(review_note)
        elif review_note["note"]:
            raise ValueError("unexpected unsaved note")
    observation_count = 0
    if observations is not None:
        if (
            observations.get("schema") != "road-guardian.sdk-observations.v1"
            or observations.get("event_id") != event["id"]
            or observations.get("scope") != "current_subject_only"
        ):
            raise ValueError("private observation scope mismatch")
        records = observations["observations"]
        if not isinstance(records, list) or len(records) > 64:
            raise ValueError("invalid observation count")
        seen = set()
        for record in records:
            stored = record["canonical_json"]
            if (
                not isinstance(stored, str)
                or len(stored.encode()) > 32768
                or hashlib.sha256(stored.encode()).hexdigest() != record["sha256"]
                or json.loads(stored) != record["data"]
                or Observations.validate(record["data"]) != stored
                or record["id"] != record["data"]["request_id"]
                or record["id"] in seen
                or record.get("provenance") != "client_reported_not_attested"
                or record.get("sensor_original") is not False
                or record.get("assessment_changed") is not False
            ):
                raise ValueError("private observation integrity failure")
            seen.add(record["id"])
        observation_count = len(records)
        files["private-sdk-observations.json"] = encode(observations)
    photo_count = 0
    if photo_bundle is not None:
        from PIL import Image

        index, images = photo_bundle
        if (
            index.get("schema") != "road-guardian.case-photo-index.v1"
            or index.get("event_id") != event["id"]
            or index.get("scope") != "current_subject_only"
        ):
            raise ValueError("private photo scope mismatch")
        entries = index["photos"]
        if not isinstance(entries, list) or len(entries) > 4:
            raise ValueError("photo count exceeds limit")
        paths = set()
        for photo in entries:
            if "capture_context" in photo:
                checked_capture_context(photo["capture_context"])
            identity = photo["id"]
            if not isinstance(identity, str) or str(uuid.UUID(identity)) != identity:
                raise ValueError("photo UUID required")
            path = "photos/" + identity + ".jpg"
            if (
                path in paths
                or photo.get("path") != path
                or photo.get("event_id") != event["id"]
                or photo.get("schema") != "road-guardian.case-photo.v1"
                or photo.get("scope") != "current_subject_only"
                or photo.get("provenance") != "owner_supplied_not_attested"
                or photo.get("sensor_original") is not False
                or photo.get("metadata_removed") is not True
                or photo.get("transformation") != "server_jpeg_reencode"
            ):
                raise ValueError("photo identity or provenance mismatch")
            blob = images.get(path)
            if (
                not isinstance(blob, bytes)
                or not 1 <= len(blob) <= 1024 * 1024
                or type(photo.get("bytes")) is not int
                or len(blob) != photo["bytes"]
                or hashlib.sha256(blob).hexdigest() != photo.get("sha256")
            ):
                raise ValueError("photo bytes mismatch")
            if any(
                type(photo.get(key)) is not int or not 1 <= photo[key] <= 1600
                for key in ("width", "height")
            ):
                raise ValueError("photo dimensions required")
            with Image.open(io.BytesIO(blob), formats=["JPEG"]) as decoded:
                if decoded.format != "JPEG" or decoded.size != (
                    photo["width"],
                    photo["height"],
                ):
                    raise ValueError("photo dimensions mismatch")
                decoded.load()
            paths.add(path)
            files[path] = blob
        if set(images) != paths:
            raise ValueError("unlisted photo bytes")
        photo_count = len(entries)
        files["photo-index.json"] = encode(index)
    report = {
        "schema": "road-guardian.owner-export.v2",
        "id": event["id"],
        "created": event["created"],
        "source": "owner_reported",
        "status": "owner_recorded",
        "content_path": "authored-original.json",
        "content_sha256": event["hash"],
        "sensor_original": False,
        "automatic_assessment": False,
        "responsibility_determined": False,
        "external_submission": False,
        "assessment": event["assessment"],
        "private_note_revision": note_revision,
        "sdk_observation_count": observation_count,
        "private_note_status": (
            "feature_disabled"
            if review_note is None
            else "included" if note_revision else "not_present"
        ),
        "sdk_observation_status": (
            "feature_disabled" if observations is None else "included"
        ),
        "private_photo_status": (
            "feature_disabled" if photo_bundle is None else "included"
        ),
        "photo_count": photo_count,
        "photo_index_path": None if photo_bundle is None else "photo-index.json",
        "limitations": [
            "Owner-authored content is not verified scene evidence or proof of vehicle ownership.",
            "Additional SDK values are client-reported, not OEM-attested sensor originals.",
            "Attached photos are re-encoded owner uploads, not camera originals or scene attestation.",
            "If the photo feature is disabled, attached photos are not queried or exported.",
            "No automatic diagnosis, fault determination, agency submission or acceptance.",
            "Hashes detect file changes; they do not establish authenticity or a third-party signature.",
        ],
    }
    files["owner-report.json"] = encode(report)
    files["README.txt"] = (
        "NAEPYEON - OWNER-AUTHORED PRIVATE RECORD\n"
        "authored-original.json preserves stored authored content, not a sensor original.\n"
        "owner-report.json describes the export and identifies omitted or included private additions.\n"
        "private-review-note.json, if present, is the exporting account latest private note.\n"
        "private-sdk-observations.json, if present, contains the exporting account client-reported observations.\n"
        "photo-index.json and photos/*.jpg, if present, preserve a snapshot of the account re-encoded attachments.\n"
        "The report photo status explicitly identifies when photos were not queried because the feature is disabled.\n"
        "The account subject and credentials are not included. No real-world verification or agency submission.\n"
        "manifest.json binds file names, byte sizes and SHA-256; it is not an OEM signature.\n"
    ).encode()
    files["manifest.json"] = encode(
        {
            "schema": "road-guardian.owner-package.v2",
            "event_id": event["id"],
            "source": "owner_reported",
            "sensor_original": False,
            "external_submission": False,
            "files": [
                {
                    "path": name,
                    "bytes": len(blob),
                    "sha256": hashlib.sha256(blob).hexdigest(),
                }
                for name, blob in sorted(files.items())
            ],
        }
    )
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, blob in sorted(files.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, blob)
