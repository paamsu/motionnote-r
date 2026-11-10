"""Selected measurement pair snapshot. Hashes are integrity checks, not attestation."""

import hashlib
import io
import json
import zipfile
from diagnostics.condition_comparison import compare_runs, validate_run
from evidence.owner_package import encode
from evidence.package import LimitedArchive

MAX_BYTES = 3 * 1024 * 1024
NAMES = {
    "authored-original.json",
    "before.json",
    "after.json",
    "comparison.json",
    "report.json",
    "README.txt",
    "manifest.json",
}


def build_measurement_package(event, before, after, comparison):
    raw = event["content_original_json"].encode()
    if (
        event["source"] != "owner_reported"
        or event["evidence_valid"] is not True
        or event["data"]["category"] != "maintenance"
        or json.loads(raw) != event["data"]
        or hashlib.sha256(raw).hexdigest() != event["hash"]
    ):
        raise ValueError("valid authored maintenance record required")
    for receipt in (before, after):
        original = validate_run(receipt["data"])
        if (
            receipt["event_id"] != event["id"]
            or receipt["scope"] != "current_subject_only"
            or receipt["canonical_json"] != original
            or receipt["id"] != receipt["data"]["run_id"]
            or receipt["data"]["vehicle_id"] != event["data"]["vehicle_id"]
            or hashlib.sha256(original.encode()).hexdigest() != receipt["sha256"]
            or receipt["provenance"] != "reported_measurements_not_attested"
            or receipt["sensor_original"] is not False
            or receipt["assessment_changed"] is not False
        ):
            raise ValueError("measurement receipt mismatch")
    expected = {
        "schema": "road-guardian.case-condition-comparison.v1",
        "event_id": event["id"],
        "scope": "current_subject_only",
        "case_sha256": event["hash"],
        "before_id": before["id"],
        "after_id": after["id"],
        "comparison": compare_runs(before["data"], after["data"]),
    }
    if comparison != expected:
        raise ValueError("measurement comparison mismatch")
    files = {
        "authored-original.json": raw,
        "before.json": encode(before),
        "after.json": encode(after),
        "comparison.json": encode(comparison),
    }
    files["report.json"] = encode(
        {
            "schema": "road-guardian.measurement-export.v1",
            "event_id": event["id"],
            "case_sha256": event["hash"],
            "before_id": before["id"],
            "after_id": after["id"],
            "scope": "current_subject_only",
            "provenance": "reported_measurements_not_attested",
            "hash_basis": "canonical_validated_run_utf8",
            "sensor_original": False,
            "conditions_verified": False,
            "repair_confirmed": False,
            "diagnosis_performed": False,
            "external_submission": False,
            "photos_included": False,
            "notes_included": False,
        }
    )
    files["README.txt"] = (
        "NAEPYEON - SELECTED REPORTED MEASUREMENT COMPARISON\n"
        "authored-original.json is the authored maintenance record, not verified scene evidence.\n"
        "before.json and after.json preserve the selected private receipts and canonical run JSON.\n"
        "comparison.json is rederived from those two runs, including a not_comparable result.\n"
        "Reported conditions, vehicle identity, clock, calibration and repair are not attested.\n"
        "Development tolerances are not OEM service criteria. No diagnosis or repair-success claim.\n"
        "Photos, notes, other runs, account subject and credentials are not included.\n"
        "No agency submission. File hashes do not prove authenticity or an OEM signature.\n"
    ).encode()
    files["manifest.json"] = encode(
        {
            "schema": "road-guardian.measurement-package.v1",
            "event_id": event["id"],
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
    if set(files) != NAMES or sum(map(len, files.values())) > MAX_BYTES:
        raise ValueError("measurement package limit")
    output = io.BytesIO()
    with zipfile.ZipFile(
        LimitedArchive(output, MAX_BYTES), "w", compression=zipfile.ZIP_DEFLATED
    ) as archive:
        for name, blob in sorted(files.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, blob)
    return output.getvalue()
