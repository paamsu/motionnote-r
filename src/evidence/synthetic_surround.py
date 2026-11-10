"""One unchanged synthetic atlas with four authored quadrants, not four camera captures."""

import hashlib
import io
import json
import re
import time
from pathlib import Path

MAX_BYTES = 8 * 1024 * 1024
REGIONS = {
    "front": [0, 0, 0.5, 0.5],
    "rear": [0.5, 0, 0.5, 0.5],
    "left": [0, 0.5, 0.5, 0.5],
    "right": [0.5, 0.5, 0.5, 0.5],
}


def canonical(value):
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )


def checked(blob):
    from PIL import Image

    if not 1 <= len(blob) <= MAX_BYTES:
        raise ValueError("surround byte limit")
    try:
        with Image.open(io.BytesIO(blob), formats=["PNG"]) as image:
            if (
                image.format != "PNG"
                or getattr(image, "n_frames", 1) != 1
                or not 2 <= image.width <= 4096
                or not 2 <= image.height <= 4096
            ):
                raise ValueError("bounded single PNG atlas required")
            image.load()
            return image.size
    except (OSError, SyntaxError, Image.DecompressionBombError) as error:
        raise ValueError("decodable PNG atlas required") from error


def metadata(event_id, sensor_hash, blob, width, height):
    return dict(
        schema="road-guardian.synthetic-surround.v1",
        event_id=event_id,
        sensor_sha256=sensor_hash,
        source="synthetic",
        camera_evidence=False,
        sha256=hashlib.sha256(blob).hexdigest(),
        bytes=len(blob),
        mime="image/png",
        width=width,
        height=height,
        directions=list(REGIONS),
        regions=REGIONS,
    )


def read(db, event_id, sensor_hash, sensor_valid, content=False):
    row = db.execute(
        "SELECT * FROM synthetic_surround WHERE event_id=?", (event_id,)
    ).fetchone()
    history = db.execute(
        "SELECT detail FROM history WHERE event_id=? AND action='synthetic_surround_attached' ORDER BY seq",
        (event_id,),
    ).fetchall()
    if row is None and not history:
        return None
    try:
        if row is None:
            raise ValueError("surround original missing")
        blob = bytes(row["image"])
        meta = json.loads(row["metadata"])
        width, height = checked(blob)
        if not sensor_valid or meta != metadata(
            event_id, sensor_hash, blob, width, height
        ):
            raise ValueError("surround binding mismatch")
        if (
            hashlib.sha256(row["metadata"].encode() + b"\0" + blob).hexdigest()
            != row["record_hash"]
        ):
            raise ValueError("surround record changed")
        if len(history) != 1 or json.loads(history[0]["detail"]) != meta:
            raise ValueError("surround history mismatch")
        result = dict(
            meta, integrity_valid=True, url="/api/events/" + event_id + "/surround"
        )
        return (result, blob) if content else result
    except (ValueError, TypeError, KeyError, OverflowError):
        if content:
            raise ValueError("surround integrity failure") from None
        # Never advertise corrupt bytes as a usable atlas.
        return dict(
            schema="road-guardian.synthetic-surround.v1",
            event_id=event_id,
            sensor_sha256=sensor_hash,
            source="synthetic",
            camera_evidence=False,
            integrity_valid=False,
            url="/api/events/" + event_id + "/surround",
        )


def attach(store, event_id, path, expected_sha256):
    if not isinstance(expected_sha256, str) or not re.fullmatch(
        "[0-9a-f]{64}", expected_sha256
    ):
        raise ValueError("surround SHA-256 required")
    with Path(path).open("rb") as stream:
        blob = stream.read(MAX_BYTES + 1)
    width, height = checked(blob)
    if hashlib.sha256(blob).hexdigest() != expected_sha256:
        raise ValueError("surround input hash mismatch")
    with store.connect() as db:
        db.execute("BEGIN IMMEDIATE")
        event = db.execute(
            "SELECT source,raw,hash FROM events WHERE id=?", (event_id,)
        ).fetchone()
        if event is None:
            raise KeyError(event_id)
        if (
            event["source"] != "synthetic"
            or hashlib.sha256(event["raw"].encode()).hexdigest() != event["hash"]
            or json.loads(event["raw"]).get("source") != "synthetic"
        ):
            raise ValueError("intact synthetic event required")
        meta = metadata(event_id, event["hash"], blob, width, height)
        previous = read(db, event_id, event["hash"], True)
        if previous is not None:
            if previous != dict(
                meta, integrity_valid=True, url="/api/events/" + event_id + "/surround"
            ):
                raise ValueError("immutable surround attachment conflict")
            return previous
        encoded = canonical(meta)
        record_hash = hashlib.sha256(encoded.encode() + b"\0" + blob).hexdigest()
        db.execute(
            "INSERT INTO synthetic_surround VALUES(?,?,?,?)",
            (event_id, encoded, blob, record_hash),
        )
        db.execute(
            "INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)",
            (event_id, time.time(), "synthetic_surround_attached", encoded),
        )
        return dict(
            meta, integrity_valid=True, url="/api/events/" + event_id + "/surround"
        )
