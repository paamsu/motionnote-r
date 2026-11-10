"""Shared bounded six-frame inference validation and decoder replay."""

import io
import json
import math
import re
import subprocess
from PIL import Image
from evidence.scene_analysis import sha, hexhash

TRANSFORM = "decoded RGB; LANCZOS aspect thumbnail max384; metadata stripped"
SELECTION = "round(i*(N-1)/5), i=0..5; chronological"
MAX_FRAME = 1024 * 1024


def dims(value, width, height):
    if (
        not isinstance(value, list)
        or len(value) != 2
        or any(
            type(v) is not int or not 1 <= v <= bound
            for v, bound in zip(value, (width, height))
        )
    ):
        raise ValueError("bounded dimensions required")


def checked(result, raw, prompt, blobs, *, fields, parse_output):
    if not isinstance(result, dict) or set(result) != fields:
        raise ValueError("complete local video inference result required")
    if (
        not isinstance(raw, str)
        or not 1 <= len(raw.encode()) <= 32768
        or not isinstance(prompt, str)
        or not 1 <= len(prompt.encode()) <= 16384
    ):
        raise ValueError("bounded raw output and prompt required")
    if (
        not isinstance(result["id"], str)
        or re.fullmatch("[0-9a-f]{32}", result["id"]) is None
    ):
        raise ValueError("anonymous inference ID required")
    for key in (
        "input_sha256",
        "model_identity_sha256",
        "prompt_sha256",
        "raw_output_sha256",
    ):
        if not hexhash(result[key]):
            raise ValueError("SHA-256 required")
    if result["raw_output_sha256"] != sha(raw.encode()) or result[
        "prompt_sha256"
    ] != sha(prompt.encode()):
        raise ValueError("raw or prompt hash mismatch")
    if (
        result["visual_analysis_performed"] is not True
        or result["temporal_analysis_performed"] is not True
        or result["camera_evidence"] is not False
        or type(result["max_tokens"]) is not int
        or result["max_tokens"] != 256
        or type(result["temperature"]) not in (int, float)
        or result["temperature"] != 0
        or type(result["stream_chunks"]) is not int
        or not 1 <= result["stream_chunks"] <= 4096
        or type(result["elapsed_seconds"]) not in (int, float)
        or not 0 <= result["elapsed_seconds"] <= 3600
        or not math.isfinite(result["elapsed_seconds"])
    ):
        raise ValueError("execution contract")
    for key, value in parse_output(raw).items():
        if type(result[key]) is not type(value) or result[key] != value:
            raise ValueError("raw output/result mismatch")
    data = result["video_frames"]
    if not isinstance(data, dict) or set(data) != {
        "all_frame_pts_seconds",
        "frame_count",
        "original_dimensions",
        "full_decode_verified",
        "selection",
        "transform",
        "frames",
    }:
        raise ValueError("frame contract")
    if (
        type(data["frame_count"]) is not int
        or not 6 <= data["frame_count"] <= 1024
        or data["full_decode_verified"] is not True
        or data["selection"] != SELECTION
        or data["transform"] != TRANSFORM
    ):
        raise ValueError("frame selection contract")
    dims(data["original_dimensions"], 1920, 1080)
    pts = data["all_frame_pts_seconds"]
    if (
        not isinstance(pts, list)
        or len(pts) != data["frame_count"]
        or any(
            type(t) not in (int, float) or not 0 <= t <= 16 or not math.isfinite(t)
            for t in pts
        )
        or pts[0] != 0
        or any(b <= a for a, b in zip(pts, pts[1:]))
    ):
        raise ValueError("strict bounded ordered PTS required")
    rows = data["frames"]
    if not isinstance(rows, list) or len(rows) != 6 or len(blobs) != 6:
        raise ValueError("exactly six actual input frames required")
    indices = [round(i * (len(pts) - 1) / 5) for i in range(6)]
    for index, row, blob in zip(indices, rows, blobs):
        if not isinstance(row, dict) or set(row) != {
            "index",
            "pts_seconds",
            "path",
            "sha256",
            "dimensions",
        }:
            raise ValueError("frame row contract")
        if (
            type(row["index"]) is not int
            or row["index"] != index
            or type(row["pts_seconds"]) not in (int, float)
            or row["pts_seconds"] != pts[index]
        ):
            raise ValueError("frame index/PTS mismatch")
        if row["path"] != f"{result['id']}-frame-{index:04d}.png" or not hexhash(
            row["sha256"]
        ):
            raise ValueError("anonymous frame identity")
        dims(row["dimensions"], 384, 384)
        if (
            not isinstance(blob, bytes)
            or not 1 <= len(blob) <= MAX_FRAME
            or sha(blob) != row["sha256"]
        ):
            raise ValueError("frame bytes/hash mismatch")
        with Image.open(io.BytesIO(blob), formats=["PNG"]) as image:
            if (
                image.mode != "RGB"
                or list(image.size) != row["dimensions"]
                or getattr(image, "n_frames", 1) != 1
            ):
                raise ValueError("single bounded RGB frame required")
            image.load()


def verify_derivation(path, result, blobs):
    """Bounded decoder replay at attachment only, without importing test code."""

    def run(command):
        return subprocess.run(command, capture_output=True, timeout=45, check=True)

    probe = run(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_streams",
            "-show_frames",
            "-show_entries",
            "stream=width,height,codec_name:frame=best_effort_timestamp_time",
            "-of",
            "json",
            str(path),
        ]
    )
    info = json.loads(probe.stdout)
    data = result["video_frames"]
    if (
        len(info["streams"]) != 1
        or [info["streams"][0]["width"], info["streams"][0]["height"]]
        != data["original_dimensions"]
    ):
        raise ValueError("video dimensions mismatch")
    pts = [float(frame["best_effort_timestamp_time"]) for frame in info["frames"]]
    if pts != data["all_frame_pts_seconds"]:
        raise ValueError("actual video PTS mismatch")
    decoded = run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-xerror",
            "-i",
            str(path),
            "-map",
            "0:v:0",
            "-f",
            "null",
            "-",
        ]
    )
    if decoded.stderr.strip():
        raise ValueError("video decode errors")
    for row, blob in zip(data["frames"], blobs):
        index = row["index"]
        decoded = run(
            [
                "ffmpeg",
                "-v",
                "error",
                "-xerror",
                "-i",
                str(path),
                "-vf",
                rf"select=eq(n\,{index})",
                "-frames:v",
                "1",
                "-f",
                "image2pipe",
                "-vcodec",
                "png",
                "-",
            ]
        )
        if decoded.stderr.strip():
            raise ValueError("frame decode errors")
        with Image.open(io.BytesIO(decoded.stdout)) as image:
            image.load()
            rgb = image.convert("RGB")
            rgb.thumbnail((384, 384), Image.Resampling.LANCZOS)
            clean = Image.new("RGB", rgb.size)
            clean.paste(rgb)
            rgb.close()
        output = io.BytesIO()
        clean.save(output, format="PNG")
        clean.close()
        if output.getvalue() != blob:
            raise ValueError("model frame does not derive from stored video")
