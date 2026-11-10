"""Prepare same-event sparse video observations for immutable evidence storage.

This validates input provenance; it does not associate vehicle identities or infer
an incident. The caller must persist the descriptor and every returned resource.
"""

from pathlib import Path
from acquisition.plate_video_review import review
from acquisition.plate_text_agreement import LEGACY_PROFILE
from evidence.plate_analysis import observation
from evidence.scene_analysis import sha

SCHEMA = "road-guardian.plate-video-analysis.v1"


def prepare(event, video_path, frames, *, work_dir):
    video = Path(video_path).resolve(strict=True)
    if not video.is_file() or not 1 <= video.stat().st_size <= 32 * 1024 * 1024:
        raise ValueError("bounded source video required")
    digest = sha(video.read_bytes())
    declared = event.get("video") or {}
    if (
        event.get("source") != "synthetic"
        or event.get("data", {}).get("source") != "synthetic"
        or event.get("evidence_valid") is not True
        or declared.get("integrity_valid") is not True
        or declared.get("input_kind") != "authored_moving_plate_scene"
        or declared.get("sha256") != digest
        or declared.get("original_sha256") != digest
        or declared.get("bytes") != video.stat().st_size
    ):
        raise ValueError("verified same-event original plate video required")
    if not isinstance(frames, list) or not 1 <= len(frames) <= 24:
        raise ValueError("one to twenty-four frame observations required")
    rows, observations, resources = [], [], {}
    profile = None
    total = 0
    for frame in frames:
        if not isinstance(frame, dict) or set(frame) != {
            "index",
            "t",
            "image_bytes",
            "bundle",
            "model_image_bytes",
        }:
            raise ValueError("complete frame observation required")
        image, model = frame["image_bytes"], frame["model_image_bytes"]
        if (
            type(image) is not bytes
            or type(model) is not bytes
            or not 1 <= len(image) <= 8 * 1024 * 1024
            or not 1 <= len(model) <= 16 * 1024 * 1024
        ):
            raise ValueError("bounded frame and model input bytes required")
        total += len(image) + len(model)
        if total > 48 * 1024 * 1024:
            raise ValueError("bounded total frame resources required")
        bundle = frame["bundle"]
        # Reuse photo contract: raw output, exact prompt and preprocessing, model
        # provenance hashes, OCR region and source pixel agreement are all checked.
        agreement = observation(image, bundle, model)
        current_profile = bundle.get("agreement_profile", LEGACY_PROFILE)
        if profile is not None and profile != current_profile:
            raise ValueError("one agreement profile per timeline required")
        profile = current_profile
        index = frame["index"]
        if type(index) is not int or not 0 <= index < 960:
            raise ValueError("bounded integer frame index required")
        photo_name, model_name = (
            f"plate-video/frame-{index:04d}.png",
            f"plate-video/model-{index:04d}.png",
        )
        resources[photo_name], resources[model_name] = image, model
        rows.append(
            dict(
                index=index,
                t=frame["t"],
                bundle=bundle,
                agreement=agreement,
                frame=dict(path=photo_name, sha256=sha(image), bytes=len(image)),
                model_input=dict(path=model_name, sha256=sha(model), bytes=len(model)),
            )
        )
        observations.append(
            dict(
                index=index,
                t=frame["t"],
                video_sha256=digest,
                image_bytes=image,
                localization=bundle["localization"],
                ocr=bundle["ocr"],
            )
        )
    timeline = review(video, observations, work_dir=work_dir, profile=profile)
    if [r["agreement"] for r in rows] != [
        r["agreement"] for r in timeline["observations"]
    ]:
        raise ValueError("timeline disagreement")
    for row, decoded in zip(rows, timeline["observations"]):
        row["t"] = decoded["t"]
    descriptor = dict(
        schema=SCHEMA,
        event_id=event["id"],
        sensor_sha256=event["hash"],
        input_video_sha256=digest,
        input_video_bytes=video.stat().st_size,
        source="local_synthetic_plate_video_analysis",
        frames=rows,
        timeline=timeline,
        requires_review=True,
        camera_evidence=False,
        recipient=None,
        limitations=[
            "Sparse selected-frame observations; unsampled frames remain unverified.",
            "No identity fusion, watchlist lookup or automatic incident.",
            "Hashes bind inputs and records, not proof of model execution.",
        ],
    )
    return descriptor, resources


def restore(event, video_path, descriptor, resources, *, work_dir):
    """Rebuild from stored inputs; reject changed metadata or missing/extra files."""
    if not isinstance(descriptor, dict) or descriptor.get("schema") != SCHEMA:
        raise ValueError("plate video analysis schema required")
    rows = descriptor.get("frames")
    if (
        not isinstance(rows, list)
        or not 1 <= len(rows) <= 24
        or not isinstance(resources, dict)
    ):
        raise ValueError("bounded stored frame resources required")
    frames, required = [], set()
    try:
        for row in rows:
            index = row["index"]
            if type(index) is not int or not 0 <= index < 960:
                raise ValueError("bounded integer frame index required")
            photo_name, model_name = (
                f"plate-video/frame-{index:04d}.png",
                f"plate-video/model-{index:04d}.png",
            )
            required.update((photo_name, model_name))
            frames.append(
                dict(
                    index=index,
                    t=row["t"],
                    bundle=row["bundle"],
                    image_bytes=resources[photo_name],
                    model_image_bytes=resources[model_name],
                )
            )
    except (KeyError, TypeError) as error:
        raise ValueError("stored frame resources missing") from error
    if set(resources) != required:
        raise ValueError("unexpected stored frame resources")
    expected, _ = prepare(event, video_path, frames, work_dir=work_dir)
    # Compare canonical JSON as well as Python equality to distinguish bool from int.
    from evidence.scene_analysis import canonical

    if canonical(expected) != canonical(descriptor):
        raise ValueError("stored video analysis differs from original inputs")
    return expected


ACTION = "plate_video_analysis_attached"


def original_video(db, event_id, root):
    import json

    row = db.execute(
        "SELECT manifest FROM media WHERE event_id=?", (event_id,)
    ).fetchone()
    if row is None:
        raise ValueError("original plate video missing")
    path = Path(json.loads(row["manifest"])["path"]).resolve(strict=True)
    if not path.is_relative_to(Path(root).resolve(strict=True)) or not path.is_file():
        raise ValueError("original video outside evidence storage")
    return path


def read(db, event, root, *, content=False):
    import json
    import subprocess
    from evidence.scene_analysis import canonical, pairs

    event_id = event["id"]
    row = db.execute(
        "SELECT * FROM plate_video_analyses WHERE event_id=?", (event_id,)
    ).fetchone()
    history = db.execute(
        "SELECT detail FROM history WHERE event_id=? AND action=?", (event_id, ACTION)
    ).fetchall()
    sizes = db.execute(
        "SELECT name,length(image) AS size FROM plate_video_resources WHERE event_id=?",
        (event_id,),
    ).fetchall()
    if row is None and not history and not sizes:
        return None
    try:
        if (
            row is None
            or len(row["report"].encode()) > 2 * 1024 * 1024
            or len(sizes) > 48
        ):
            raise ValueError("bounded complete plate video record required")
        saved = json.loads(row["report"], object_pairs_hook=pairs)
        if (
            sha(row["report"].encode()) != row["record_hash"]
            or len(history) != 1
            or history[0]["detail"] != canonical(saved)
        ):
            raise ValueError("plate video history mismatch")
        if (
            any(not 1 <= item["size"] <= 16 * 1024 * 1024 for item in sizes)
            or sum(item["size"] for item in sizes) > 48 * 1024 * 1024
        ):
            raise ValueError("bounded plate video resources required")
        blobs = db.execute(
            "SELECT name,image FROM plate_video_resources WHERE event_id=?", (event_id,)
        ).fetchall()
        resources = {item["name"]: bytes(item["image"]) for item in blobs}
        expected = restore(
            event, original_video(db, event_id, root), saved, resources, work_dir=root
        )
        public = dict(expected, integrity_valid=True)
        return (public, resources) if content else public
    except (
        ValueError,
        TypeError,
        KeyError,
        OverflowError,
        OSError,
        subprocess.SubprocessError,
    ):
        if content:
            raise ValueError("plate video analysis integrity failure") from None
        return dict(
            schema=SCHEMA,
            event_id=event_id,
            integrity_valid=False,
            status="unavailable",
            requires_review=True,
            camera_evidence=False,
            recipient=None,
        )


def attach(store, event_id, frames):
    import time
    from evidence.scene_analysis import canonical

    root = Path(store.path).parent / "video-fixtures"
    with store.connect() as db:
        db.execute("BEGIN IMMEDIATE")
        event = store.get(event_id)
        expected, resources = prepare(
            event, original_video(db, event_id, root), frames, work_dir=root
        )
        previous = read(db, event, root)
        if previous is not None:
            if previous != dict(expected, integrity_valid=True):
                raise ValueError("immutable plate video analysis conflict")
            return previous
        encoded = canonical(expected)
        if len(encoded.encode()) > 2 * 1024 * 1024:
            raise ValueError("bounded plate video report required")
        db.execute(
            "INSERT INTO plate_video_analyses VALUES(?,?,?)",
            (event_id, encoded, sha(encoded.encode())),
        )
        db.executemany(
            "INSERT INTO plate_video_resources VALUES(?,?,?)",
            [(event_id, name, blob) for name, blob in resources.items()],
        )
        db.execute(
            "INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)",
            (event_id, time.time(), ACTION, encoded),
        )
    return dict(expected, integrity_valid=True)
