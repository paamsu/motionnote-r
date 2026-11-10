"""Validate unchanged synthetic reference photographs; never camera evidence."""

import hashlib
import io
import re
from pathlib import Path

MAX_BYTES = 8 * 1024 * 1024


def checked(manifest, storage_root):
    from PIL import Image

    photo = manifest.get("input_photo")
    if (
        not isinstance(photo, dict)
        or manifest.get("source") != "synthetic_scene_fixture"
    ):
        raise ValueError("declared synthetic scene photo required")
    root = Path(storage_root).resolve(strict=True)
    clip = Path(manifest["path"]).resolve(strict=True)
    if not clip.is_relative_to(root):
        raise ValueError("scene storage boundary")
    if "path" in photo:
        if not isinstance(photo["path"], str):
            raise ValueError("scene photo path")
        path = Path(photo["path"]).resolve(strict=True)
    else:
        candidates = [
            clip.parent / name
            for name in ("input.png", "input.jpg", "input.jpeg")
            if (clip.parent / name).exists()
        ]
        if len(candidates) != 1:
            raise ValueError("ambiguous or missing legacy scene photo")
        path = candidates[0].resolve(strict=True)
    if (
        not path.is_relative_to(root)
        or path.parent != clip.parent
        or not path.is_file()
    ):
        raise ValueError("scene photo storage boundary")
    size = photo.get("bytes")
    if (
        type(size) is not int
        or not 1 <= size <= MAX_BYTES
        or path.stat().st_size != size
    ):
        raise ValueError("scene photo size")
    expected = photo.get("sha256")
    if not isinstance(expected, str) or not re.fullmatch("[0-9a-f]{64}", expected):
        raise ValueError("scene photo hash")
    with path.open("rb") as stream:
        blob = stream.read(MAX_BYTES + 1)
    if len(blob) != size or hashlib.sha256(blob).hexdigest() != expected:
        raise ValueError("scene photo hash mismatch")
    with Image.open(io.BytesIO(blob), formats=["PNG", "JPEG"]) as image:
        if getattr(image, "n_frames", 1) != 1:
            raise ValueError("single scene photo required")
        width, height = image.size
        if not 1 <= width <= 4096 or not 1 <= height <= 4096:
            raise ValueError("scene photo dimensions")
        if (
            type(photo.get("width")) is not int
            or type(photo.get("height")) is not int
            or (width, height) != (photo["width"], photo["height"])
        ):
            raise ValueError("scene photo dimensions changed")
        if Image.MIME[image.format] != photo.get("mime"):
            raise ValueError("scene photo MIME changed")
        image.load()
    return path


def public(manifest, storage_root, event_id, sensor_hash, evidence_valid):
    photo = manifest.get("input_photo")
    if photo is None:
        return None
    from PIL import Image

    # Only declared public fields may leave the storage boundary.
    result = (
        {key: photo.get(key) for key in ("sha256", "bytes", "mime", "width", "height")}
        if isinstance(photo, dict)
        else {}
    )
    result.update(
        source="synthetic_scene_fixture",
        camera_evidence=False,
        event_id=event_id,
        sensor_sha256=sensor_hash,
        integrity_valid=False,
    )
    try:
        if evidence_valid:
            checked(manifest, storage_root)
            result["integrity_valid"] = True
    except (OSError, ValueError, KeyError, TypeError, Image.DecompressionBombError):
        pass
    return result
