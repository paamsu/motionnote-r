"""Track color-coded actors in declared synthetic test scenes only.

This is a controlled pixel experiment, not a road-camera vehicle detector.
Calibration and actor colors are explicit test inputs. No filename or expected
scenario is used to compute the observed trajectory or lane transitions.
"""

import hashlib
import json
import math
from pathlib import Path
import subprocess

from PIL import Image, ImageChops

PROFILE = "color-coded-topdown-v1"


def _center(frame, actor):
    red, green, blue = frame.split()
    low_red = red.point(lambda value: 255 if value < 90 else 0)
    if actor == "target":
        mask = ImageChops.multiply(
            low_red, blue.point(lambda value: 255 if value > 150 else 0)
        )
        mask = ImageChops.multiply(
            mask, green.point(lambda value: 255 if 60 < value < 190 else 0)
        )
    else:
        mask = ImageChops.multiply(
            low_red, green.point(lambda value: 255 if value > 145 else 0)
        )
        mask = ImageChops.multiply(
            mask, blue.point(lambda value: 255 if value < 145 else 0)
        )
    bounds = mask.getbbox()
    if bounds is None:
        return None
    left, top, right, bottom = bounds
    area = mask.histogram()[255]
    # Reject tiny detections, split distractors and truncated actors.
    if not (
        50 <= area <= 1800
        and 8 <= right - left <= 32
        and 15 <= bottom - top <= 55
        and area / ((right - left) * (bottom - top)) >= 0.45
        and 0 < left < right < frame.width
        and 0 < top < bottom < frame.height
    ):
        return None
    return (left + right) / 2


def analyze(video, *, profile, pixels_per_metre, lane_width_m=3.5):
    if profile != PROFILE:
        raise ValueError("explicit controlled synthetic profile required")
    if (
        type(pixels_per_metre) not in (int, float)
        or not math.isfinite(pixels_per_metre)
        or not 8 <= pixels_per_metre <= 20
        or lane_width_m != 3.5
    ):
        raise ValueError("bounded explicit synthetic calibration required")
    path = Path(video).resolve(strict=True)
    if not path.is_file() or not 1 <= path.stat().st_size <= 64 * 1024 * 1024:
        raise ValueError("bounded local synthetic video required")
    original_hash = hashlib.sha256(path.read_bytes()).hexdigest()
    metadata = json.loads(
        subprocess.check_output(
            [
                "ffprobe",
                "-v",
                "error",
                "-show_entries",
                "format=duration:stream=codec_name,codec_type,width,height,start_time",
                "-of",
                "json",
                str(path),
            ],
            timeout=15,
        )
    )
    streams = metadata["streams"]
    duration = float(metadata["format"]["duration"])
    if (
        len(streams) != 1
        or streams[0].get("codec_type") != "video"
        or streams[0].get("codec_name") != "h264"
        or not 20 <= duration <= 60
        or not 1 <= streams[0].get("width", 0) <= 1920
        or not 1 <= streams[0].get("height", 0) <= 1080
        or abs(float(streams[0].get("start_time", 0))) > 0.001
    ):
        raise ValueError("bounded single H264 stream starting at zero required")
    raw = subprocess.check_output(
        [
            "ffmpeg",
            "-nostdin",
            "-v",
            "error",
            "-xerror",
            "-i",
            str(path),
            "-vf",
            "fps=2,scale=320:180",
            "-frames:v",
            "121",
            "-f",
            "rawvideo",
            "-pix_fmt",
            "rgb24",
            "-",
        ],
        timeout=45,
    )
    frame_bytes = 320 * 180 * 3
    count = len(raw) // frame_bytes
    if len(raw) % frame_bytes or not 40 <= count <= 120:
        raise ValueError("complete bounded decoded sequence required")
    if hashlib.sha256(path.read_bytes()).hexdigest() != original_hash:
        raise ValueError("video changed during analysis")
    points = []
    missing = []
    for index in range(count):
        frame = Image.frombytes(
            "RGB", (320, 180), raw[index * frame_bytes : (index + 1) * frame_bytes]
        )
        target, ego = _center(frame, "target"), _center(frame, "ego")
        if target is None or ego is None:
            missing.append(index)
            continue
        points.append(
            {
                "t": index / 2,
                "ego_lateral_m": 0.0,
                "target_lateral_m": round((target - ego) / pixels_per_metre, 3),
            }
        )
    result = {
        "schema": "road-guardian.synthetic-motion.v1",
        "source": "synthetic_video_tracking",
        "profile": profile,
        "video_sha256": original_hash,
        "pixels_per_metre": pixels_per_metre,
        "lane_width_m": lane_width_m,
        "sample_hz": 2,
        "decoded_frames": count,
        "missing_frames": missing,
        "status": "inconclusive",
        "lane_transitions": None,
        "abrupt_manoeuvres": None,
        "candidate": None,
        "points": points,
        "scope": "color-coded controlled synthetic pixels only; no real vehicle recognition or intent verdict",
    }
    # No interpolation through occlusion and no negative verdict from missing actors.
    if missing:
        return result
    current = None
    pending = None
    stable = 0
    transitions = 0
    abrupt = 0
    last_current_at = None
    for point in points:
        position = point["target_lateral_m"] / lane_width_m
        nearest = int(math.floor(position + 0.5))
        if abs(position - nearest) > 0.3:
            pending = None
            stable = 0
            continue
        if nearest == current:
            last_current_at = point["t"]
        if nearest == pending:
            stable += 1
        else:
            pending = nearest
            stable = 1
        if stable >= 2 and nearest != current:
            if current is not None:
                transitions += abs(nearest - current)
                if (
                    abs(nearest - current) == 1
                    and last_current_at is not None
                    and point["t"] - last_current_at <= 2
                ):
                    abrupt += 1
            current = nearest
            last_current_at = point["t"]
    result.update(
        status="measured",
        lane_transitions=transitions,
        abrupt_manoeuvres=abrupt,
        candidate=abrupt >= 3,
    )
    return result


def event_input(report):
    """Create a new synthetic sensor/event input, retaining the decoded-video report."""
    if (
        report.get("schema") != "road-guardian.synthetic-motion.v1"
        or report.get("status") != "measured"
        or report.get("profile") != PROFILE
        or type(report.get("lane_transitions")) is not int
        or type(report.get("abrupt_manoeuvres")) is not int
        or not 0 <= report["abrupt_manoeuvres"] <= report["lane_transitions"]
        or report.get("candidate") is not (report["abrupt_manoeuvres"] >= 3)
        or report.get("source") != "synthetic_video_tracking"
    ):
        raise ValueError("measured controlled synthetic report required")
    from acquisition.scenarios import generate
    from acquisition.context import context_for

    data = generate("healthy")
    data["scenario"] = "reckless" if report["candidate"] else "healthy"
    data["observations"]["abrupt_manoeuvres"] = report["abrupt_manoeuvres"]
    data["observations"]["relative_motion"] = {
        "source": report["source"],
        "actor_id": "vehicle-1",
        "reference": "ego_vehicle",
        "units": {"time": "s", "lateral": "m"},
        "points": report["points"],
        "video_sha256": report["video_sha256"],
        "profile": PROFILE,
    }
    data["observations"]["synthetic_motion_analysis"] = report
    if report["candidate"]:
        context = context_for("reckless")
        context["actors"] = [
            {
                "id": "vehicle-1",
                "color": "파랑",
                "source": "synthetic_color_tracking",
                "recognition_status": "controlled_color_only",
                "relationship": "합성 영상의 색 표식 차량",
            }
        ]
        context["facts"] = [
            {
                "label": "차선 이동",
                "value": str(report["lane_transitions"]) + "회 · 합성 영상 계산",
            }
        ]
        context["unknowns"] = [
            "실제 차량 인식·번호판 인식 미실행",
            "주행 의도·책임 미판정",
        ]
        data["context"] = context
    return data
