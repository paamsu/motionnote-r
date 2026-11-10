"""Import declared local synthetic scene assets for demo playback, never visual inference."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import uuid


def digest(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            result.update(block)
    return result.hexdigest()


def import_scene(directory, media_root, fixture, scenario, *, stationary_report=None):
    required = {
        "schema",
        "scenario",
        "photo",
        "photo_sha256",
        "video",
        "video_sha256",
        "video_kind",
    }
    if (
        not isinstance(fixture, dict)
        or set(fixture) != required
        or fixture["schema"] != "road-guardian.synthetic-scene.v1"
    ):
        raise ValueError("synthetic scene fields required")
    if fixture["scenario"] != scenario or fixture["video_kind"] not in (
        "static_photo_encoded_as_video",
        "authored_temporal_scene",
        "authored_visibility_scene",
        "authored_moving_plate_scene",
        "authored_road_scene",
        "authored_lane_scene",
        "authored_collision_scene",
        "authored_stationary_scene",
    ):
        raise ValueError("scene must match the authored event")
    visibility = fixture["video_kind"] == "authored_visibility_scene"
    if visibility and (
        scenario not in ("rain", "washer") or stationary_report is not None
    ):
        raise ValueError("bounded authored visibility source required")
    moving_plate = fixture["video_kind"] == "authored_moving_plate_scene"
    if moving_plate and (scenario != "wanted" or stationary_report is not None):
        raise ValueError("bounded authored plate source required")
    road = fixture["video_kind"] == "authored_road_scene"
    if road and (
        scenario not in ("pothole", "speed_bump", "healthy")
        or stationary_report is not None
    ):
        raise ValueError("bounded authored road source required")
    lane = fixture["video_kind"] == "authored_lane_scene"
    if lane and (scenario != "reckless" or stationary_report is not None):
        raise ValueError("bounded authored lane source required")
    collision = fixture["video_kind"] == "authored_collision_scene"
    if collision and (scenario != "crash" or stationary_report is not None):
        raise ValueError("bounded authored collision source required")
    stationary_scene = fixture["video_kind"] == "authored_stationary_scene"
    if stationary_scene and (scenario != "violation" or stationary_report is not None):
        raise ValueError(
            "authored stationary illustration required; not a measured report"
        )
    short_source = visibility or moving_plate or road or lane or collision
    root = Path(media_root).resolve(strict=True)
    paths = {}
    for kind, maximum in [("photo", 8 * 1024 * 1024), ("video", 64 * 1024 * 1024)]:
        value = fixture[kind]
        if not isinstance(value, str):
            raise ValueError("relative asset path required")
        relative = Path(value)
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("relative asset path required")
        path = (root / relative).resolve(strict=True)
        if (
            not path.is_relative_to(root)
            or not path.is_file()
            or not 1 <= path.stat().st_size <= maximum
        ):
            raise ValueError("scene asset bounds")
        if digest(path) != fixture[kind + "_sha256"]:
            raise ValueError("scene asset hash mismatch")
        paths[kind] = path
    from PIL import Image

    with Image.open(paths["photo"], formats=["PNG", "JPEG"]) as photo:
        if not (1 <= photo.width <= 4096 and 1 <= photo.height <= 4096):
            raise ValueError("scene photo dimensions")
        if getattr(photo, "n_frames", 1) != 1:
            raise ValueError("single scene photo required")
        photo.load()
        photo_width, photo_height = photo.size
        photo_mime = Image.MIME[photo.format]
    metadata = json.loads(
        subprocess.check_output(
            [
                "ffprobe",
                "-v",
                "error",
                "-show_entries",
                "format=duration:stream=codec_name,codec_type,width,height,pix_fmt,color_primaries,color_transfer,color_space,color_range",
                "-of",
                "json",
                str(paths["video"]),
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
        or streams[0].get("pix_fmt") != "yuv420p"
        or not (2 if short_source else 20)
        <= duration
        <= (
            16
            if short_source
            else 150 if stationary_report is not None or stationary_scene else 60
        )
        or not 1 <= streams[0].get("width", 0) <= 4096
        or not 1 <= streams[0].get("height", 0) <= 2160
    ):
        raise ValueError("bounded H264 scene video required")
    if stationary_report is not None:
        from acquisition.synthetic_stationary import analyze, PROFILE

        if fixture["video_kind"] != "authored_temporal_scene" or not isinstance(
            stationary_report, dict
        ):
            raise ValueError("controlled stationary temporal source required")
        actual = analyze(
            paths["video"],
            profile=PROFILE,
            pixels_per_metre=stationary_report.get("pixels_per_metre"),
        )
        if actual != stationary_report or actual["status"] != "measured":
            raise ValueError("stationary report does not match decoded video")
    from evidence.video import VideoBuffer, Segment

    buffer = VideoBuffer()
    buffer.append(Segment(paths["video"], 0, duration))
    if short_source:
        buffer.window(duration / 2, duration / 2, duration / 2)
    else:
        buffer.window(10, 5, 5)
    if (
        fixture["video_kind"] == "static_photo_encoded_as_video"
        and streams[0].get("color_transfer") != "iec61966-2-1"
    ):
        raise ValueError("still-photo video must declare the sRGB transfer function")
    colors = {
        key: streams[0].get(key, default)
        for key, default in [
            ("color_primaries", "bt709"),
            ("color_transfer", "bt709"),
            ("color_space", "bt709"),
            ("color_range", "tv"),
        ]
    }
    color_args = [
        argument
        for key, flag in [
            ("color_primaries", "-color_primaries"),
            ("color_transfer", "-color_trc"),
            ("color_space", "-colorspace"),
            ("color_range", "-color_range"),
        ]
        for argument in (flag, colors[key])
    ]
    # Decode the complete input, rather than trusting the container header alone.
    subprocess.run(
        [
            "ffmpeg",
            "-nostdin",
            "-v",
            "error",
            "-xerror",
            "-i",
            str(paths["video"]),
            "-f",
            "null",
            "-",
        ],
        check=True,
        timeout=45,
    )
    target = Path(directory) / ("scene-" + uuid.uuid4().hex)
    target.mkdir(parents=True)
    source = target / "source.mp4"
    photo = target / ("input" + paths["photo"].suffix.lower())
    clip = target / "clip.mp4"
    shutil.copyfile(paths["video"], source)
    shutil.copyfile(paths["photo"], photo)
    if (
        digest(source) != fixture["video_sha256"]
        or digest(photo) != fixture["photo_sha256"]
    ):
        raise ValueError("scene changed during import")
    full_source = fixture["video_kind"] in (
        "authored_temporal_scene",
        "authored_visibility_scene",
        "authored_moving_plate_scene",
        "authored_road_scene",
        "authored_lane_scene",
        "authored_collision_scene",
        "authored_stationary_scene",
    )
    if full_source:
        # Preserve the entire measured time axis in playback and ZIP, byte for byte.
        shutil.copyfile(source, clip)
    else:
        subprocess.run(
            [
                "ffmpeg",
                "-nostdin",
                "-n",
                "-v",
                "error",
                "-ss",
                "5",
                "-i",
                str(source),
                "-t",
                "10",
                "-an",
                "-c:v",
                "libx264",
                "-preset",
                "fast",
                "-pix_fmt",
                "yuv420p",
                *color_args,
                "-movflags",
                "+faststart",
                str(clip),
            ],
            check=True,
            timeout=45,
        )
    return {
        "status": "synthetic_clip",
        "source": "synthetic_scene_fixture",
        "path": str(clip.resolve()),
        "sha256": digest(clip),
        "bytes": clip.stat().st_size,
        "mime": "video/mp4",
        "trigger_seconds": (
            duration / 2
            if short_source
            else min(120, duration) if stationary_report is not None else 10
        ),
        "start_seconds": 0 if full_source else 5,
        "end_seconds": duration if full_source else 15,
        "duration_seconds": duration if full_source else 10,
        "original_sha256": fixture["video_sha256"],
        "camera_evidence": False,
        "colors": colors,
        "scene": scenario,
        "input_kind": fixture["video_kind"],
        "input_photo": {
            "path": str(photo.resolve()),
            "sha256": fixture["photo_sha256"],
            "bytes": photo.stat().st_size,
            "mime": photo_mime,
            "width": photo_width,
            "height": photo_height,
        },
        "visual_analysis_performed": False,
        "temporal_event_verified": False,
        **(
            {
                "time_alignment": "independent_authored_video_axis; playback marker is not a measured incident time"
            }
            if short_source or stationary_scene
            else {}
        ),
    }
