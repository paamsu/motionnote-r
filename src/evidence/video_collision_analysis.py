"""Immutable six-frame contact candidates; never confirms a crash or changes incident policy."""

import json
from pathlib import Path
import tempfile
import time
from PIL import Image
from evidence.scene_analysis import canonical, sha, pairs

SCHEMA = "road-guardian.video-collision-analysis.v1"
ACTION = "local_video_collision_analysis_attached"
TRANSFORM = "decoded RGB; LANCZOS aspect thumbnail max384; metadata stripped"
SELECTION = "round(i*(N-1)/5), i=0..5; chronological"
STATES = {"contact", "separated", "unclear"}
MOTIONS = {
    "approach_contact",
    "separating",
    "pass_without_contact",
    "static",
    "unclear",
}
FIELDS = {
    "id",
    "input_sha256",
    "visual_analysis_performed",
    "temporal_analysis_performed",
    "camera_evidence",
    "model_identity_sha256",
    "prompt_sha256",
    "max_tokens",
    "temperature",
    "video_frames",
    "raw_output_sha256",
    "status",
    "class",
    "reason",
    "motion",
    "motion_reason",
    "format_valid",
    "stream_chunks",
    "elapsed_seconds",
}
MAX_VIDEO = 64 * 1024 * 1024
MAX_FRAME = 1024 * 1024


def parse(raw):
    try:
        obj = json.loads(raw, object_pairs_hook=pairs)
        if (
            not isinstance(obj, dict)
            or set(obj) != {"class", "reason", "motion", "motion_reason"}
            or type(obj["class"]) is not str
            or obj["class"] not in STATES
            or type(obj["motion"]) is not str
            or obj["motion"] not in MOTIONS
            or any(
                type(obj[k]) is not str or not 1 <= len(obj[k].strip()) <= 1000
                for k in ("reason", "motion_reason")
            )
        ):
            raise ValueError("output contract")
        return dict(
            status=(
                "abstained"
                if obj["class"] == "unclear" or obj["motion"] == "unclear"
                else "candidate"
            ),
            **obj,
            format_valid=True,
        )
    except (ValueError, TypeError):
        return dict(
            status="abstained",
            **{"class": "unclear"},
            reason="Model output did not satisfy the fixed JSON contract.",
            motion="unclear",
            motion_reason="Invalid output contract.",
            format_valid=False,
        )


from evidence.sampled_video import checked as checked_sampled, verify_derivation


def checked(result, raw, prompt, blobs):
    checked_sampled(result, raw, prompt, blobs, fields=FIELDS, parse_output=parse)


def descriptor(event, result, raw, prompt, blobs):
    checked(result, raw, prompt, blobs)
    video = event.get("video") or {}
    if (
        event.get("source") != "synthetic"
        or event.get("data", {}).get("source") != "synthetic"
        or event.get("evidence_valid") is not True
        or video.get("source") != "synthetic_scene_fixture"
        or video.get("integrity_valid") is not True
        or video.get("camera_evidence") is not False
        or video.get("input_kind") != "authored_collision_scene"
        or video.get("sha256") != result["input_sha256"]
        or video.get("original_sha256") != result["input_sha256"]
    ):
        raise ValueError("same-event unchanged synthetic video required")
    frames = [
        dict(
            index=row["index"],
            pts_seconds=row["pts_seconds"],
            sha256=row["sha256"],
            bytes=len(blob),
            dimensions=row["dimensions"],
            path=f"video-collision/frame-{i:04d}.png",
        )
        for i, (row, blob) in enumerate(zip(result["video_frames"]["frames"], blobs))
    ]
    return dict(
        schema=SCHEMA,
        event_id=event["id"],
        sensor_sha256=event["hash"],
        input_video_sha256=result["input_sha256"],
        source="local_video_collision_inference",
        camera_evidence=False,
        physical_contact_confirmed=False,
        visual_analysis_performed=True,
        temporal_analysis_performed=True,
        model_identity_sha256=result["model_identity_sha256"],
        prompt_sha256=result["prompt_sha256"],
        raw_output_sha256=result["raw_output_sha256"],
        contact_class=result["class"],
        motion=result["motion"],
        motion_reason=result["motion_reason"],
        status=result["status"],
        reason=result["reason"],
        format_valid=result["format_valid"],
        requires_review=True,
        result=result,
        raw_output=raw,
        prompt=prompt,
        frames=frames,
        limitations=[
            "Six sampled synthetic frames; visible contact and motion are unconfirmed model candidates, not continuous tracking, physical collision or sensor evidence.",
            "Known synthetic controls include a near-pass false contact and a reversed-contact miss; do not trigger insurance or emergency actions from these candidates.",
            "Authored incident policy is unchanged; hashes preserve records, not proof of model execution.",
        ],
    )


def record_hash(encoded, blobs):
    return sha(
        encoded.encode()
        + b"\0"
        + b"".join(i.to_bytes(1, "big") + blob for i, blob in enumerate(blobs))
    )


def read(db, event, content=False):
    row = db.execute(
        "SELECT * FROM video_collision_analyses WHERE event_id=?", (event["id"],)
    ).fetchone()
    frames = db.execute(
        "SELECT ordinal,length(image) AS size FROM video_collision_frames WHERE event_id=? ORDER BY ordinal",
        (event["id"],),
    ).fetchall()
    history = db.execute(
        "SELECT detail FROM history WHERE event_id=? AND action=? ORDER BY seq",
        (event["id"], ACTION),
    ).fetchall()
    if row is None and not frames and not history:
        return None
    try:
        if row is None or [r["ordinal"] for r in frames] != list(range(6)):
            raise ValueError("missing analysis or frames")
        if len(row["report"].encode()) > 262144 or any(
            not 1 <= r["size"] <= MAX_FRAME for r in frames
        ):
            raise ValueError("stored analysis bounds")
        saved = json.loads(row["report"], object_pairs_hook=pairs)
        blobs = [
            bytes(r["image"])
            for r in db.execute(
                "SELECT image FROM video_collision_frames WHERE event_id=? ORDER BY ordinal",
                (event["id"],),
            )
        ]
        expected = descriptor(
            event, saved["result"], saved["raw_output"], saved["prompt"], blobs
        )
        if (
            canonical(saved) != canonical(expected)
            or row["record_hash"] != record_hash(row["report"], blobs)
            or len(history) != 1
            or canonical(json.loads(history[0]["detail"], object_pairs_hook=pairs))
            != canonical(expected)
        ):
            raise ValueError("analysis history or record binding mismatch")
        public = dict(expected, integrity_valid=True)
        return (public, blobs) if content else public
    except (
        ValueError,
        KeyError,
        TypeError,
        OSError,
        OverflowError,
        Image.DecompressionBombError,
    ):
        if content:
            raise ValueError("video collision analysis integrity failure") from None
        return dict(
            schema=SCHEMA,
            event_id=event["id"],
            sensor_sha256=event["hash"],
            source="local_video_collision_inference",
            camera_evidence=False,
            physical_contact_confirmed=False,
            visual_analysis_performed=False,
            temporal_analysis_performed=False,
            status="unavailable",
            integrity_valid=False,
        )


def attach(store, event_id, result, *, raw_output, prompt, frame_paths):
    if not isinstance(frame_paths, (list, tuple)) or len(frame_paths) != 6:
        raise ValueError("six frame paths required")
    blobs = []
    for path in frame_paths:
        with Path(path).open("rb") as stream:
            blobs.append(stream.read(MAX_FRAME + 1))
    event = store.get(event_id)
    expected = descriptor(event, result, raw_output, prompt, blobs)
    previous = event.get("video_collision_analysis")
    if previous is not None:
        if previous != dict(expected, integrity_valid=True):
            raise ValueError("immutable video collision conflict")
        return previous
    # Decode an isolated byte-identical snapshot to avoid mutable source-path races.
    with tempfile.TemporaryDirectory(
        prefix="collision-verify-", dir=Path(store.path).parent
    ) as work:
        snapshot = Path(work) / "source.mp4"
        with store.video_file(event_id).open("rb") as source, snapshot.open(
            "wb"
        ) as destination:
            size = 0
            while chunk := source.read(65536):
                size += len(chunk)
                if size > MAX_VIDEO:
                    raise ValueError("bounded source video required")
                destination.write(chunk)
        if sha(snapshot.read_bytes()) != result["input_sha256"]:
            raise ValueError("video changed before verification")
        verify_derivation(snapshot, result, blobs)
    with store.connect() as db:
        db.execute("BEGIN IMMEDIATE")
        current = store.get(event_id)
        if descriptor(current, result, raw_output, prompt, blobs) != expected:
            raise ValueError("video relationship changed")
        previous = read(db, current)
        if previous is not None:
            if previous != dict(expected, integrity_valid=True):
                raise ValueError("immutable video collision conflict")
            return previous
        encoded = canonical(expected)
        db.execute(
            "INSERT INTO video_collision_analyses VALUES(?,?,?)",
            (event_id, encoded, record_hash(encoded, blobs)),
        )
        db.executemany(
            "INSERT INTO video_collision_frames VALUES(?,?,?)",
            [(event_id, i, blob) for i, blob in enumerate(blobs)],
        )
        db.execute(
            "INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)",
            (event_id, time.time(), ACTION, encoded),
        )
    return dict(expected, integrity_valid=True)
