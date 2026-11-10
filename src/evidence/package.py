"""Portable source-separated record packages. Not an agency submission or attestation."""

import copy
import hashlib
import io
import json
import zipfile
import tempfile
from contextlib import contextmanager

MAX_PACKAGE_BYTES = 40 * 1024 * 1024


class LimitedArchive:
    """Seekable ZIP sink that rejects writes beyond the disk budget before writing."""

    def __init__(self, output, limit):
        self.output = output
        self.limit = limit

    def write(self, data):
        if self.tell() + len(data) > self.limit:
            raise ValueError("evidence package exceeds download limit")
        return self.output.write(data)

    def tell(self):
        return self.output.tell()

    def seek(self, offset, whence=0):
        return self.output.seek(offset, whence)

    def flush(self):
        return self.output.flush()


def encode(value):
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode()


def write_package(
    store, event_id, output, review_note=None, observations=None, photo_bundle=None
):
    event = store.get(event_id)
    if event["source"] == "owner_reported":
        from evidence.owner_package import write_owner_package

        return write_owner_package(
            event, output, review_note, observations, photo_bundle
        )
    if photo_bundle is not None:
        raise ValueError("photos require an authored record export")
    if event["source"] != "synthetic":
        raise ValueError("synthetic export only")
    if not event["evidence_valid"]:
        raise ValueError("sensor evidence integrity failure")
    if any(d.get("payload_valid") is False for d in event["dispatches"]):
        raise ValueError("dispatch evidence integrity failure")
    raw = encode(event["data"])
    if hashlib.sha256(raw).hexdigest() != event["hash"]:
        raise ValueError("sensor serialization mismatch")
    files = {"sensor-original.json": raw}
    repair = event.get("repair")
    repair_report = None
    if repair:
        post = repair.get("post_repair")
        repair_report = {
            "offer": repair["offer"],
            "technician_note": repair.get("technician_note"),
            "post_repair": None,
            "case": event.get("repair_case"),
        }
        if post:
            if not post.get("integrity_valid"):
                raise ValueError("repair follow-up evidence integrity failure")
            post_raw = encode(post["raw"])
            if hashlib.sha256(post_raw).hexdigest() != post["hash"]:
                raise ValueError("repair follow-up serialization mismatch")
            files["repair-follow-up-original.json"] = post_raw
            repair_report["post_repair"] = {
                key: post[key]
                for key in ("source", "assessment", "hash", "delta_v", "status")
            }
            repair_report["post_repair"]["raw_path"] = "repair-follow-up-original.json"
    followup = event.get("road_followup_case")
    followup_source = None
    following = None
    videos = {}
    if followup and followup["stage"] in ("repeat_signal", "no_repeat_signal"):
        following = store.get(followup["followup_id"])
        following_raw = encode(following["data"])
        if (
            following["source"] != "synthetic"
            or not following["evidence_valid"]
            or following["hash"] != followup["followup_sha256"]
            or hashlib.sha256(following_raw).hexdigest() != followup["followup_sha256"]
        ):
            raise ValueError("road follow-up changed during export")
        files["follow-up-original.json"] = following_raw
        followup_video = following["video"]
        if followup_video is not None:
            if not followup_video["integrity_valid"]:
                raise ValueError("follow-up video evidence integrity failure")
            videos["follow-up-video.mp4"] = (
                store.video_file(following["id"]),
                followup_video,
            )
        followup_source = {
            "event_id": following["id"],
            "sensor_sha256": following["hash"],
            "sensor_path": "follow-up-original.json",
            "video": followup_video,
            "video_path": "follow-up-video.mp4" if followup_video is not None else None,
        }
    video = event["video"]
    if video is not None:
        if not video["integrity_valid"]:
            raise ValueError("video evidence integrity failure")
        videos["test-pattern-video.mp4"] = (store.video_file(event_id), video)
    surround = event.get("surround")
    surround_blob = None
    if surround is not None:
        if surround.get("integrity_valid") is not True:
            raise ValueError("surround evidence integrity failure")
        surround_blob = store.surround_bytes(event_id)
        if (
            len(surround_blob) != surround["bytes"]
            or hashlib.sha256(surround_blob).hexdigest() != surround["sha256"]
        ):
            raise ValueError("surround changed during export")
    streams = {
        name: (lambda path=path: path.open("rb"), meta)
        for name, (path, meta) in videos.items()
    }
    if surround_blob is not None:
        streams["surround-original.png"] = (lambda: io.BytesIO(surround_blob), surround)
    surround_views = event.get("surround_views")
    reported_views = copy.deepcopy(surround_views)
    if surround_views is not None:
        if surround_views.get("integrity_valid") is not True:
            raise ValueError("surround views integrity failure")
        for view in reported_views["views"]:
            blob = store.surround_view_bytes(event_id, view["direction"])
            if (
                len(blob) != view["bytes"]
                or hashlib.sha256(blob).hexdigest() != view["sha256"]
            ):
                raise ValueError("surround direction changed during export")
            name = "surround/" + view["direction"] + ".png"
            view["path"] = name
            streams[name] = (lambda blob=blob: io.BytesIO(blob), view)
    plate_video_analysis = event.get("plate_video_analysis")
    if plate_video_analysis is not None:
        if plate_video_analysis.get("integrity_valid") is not True:
            raise ValueError("plate video analysis integrity failure")
        analysis, resources = store.plate_video_analysis_content(event_id)
        if analysis != plate_video_analysis:
            raise ValueError("plate video analysis changed during export")
        files["plate-video-analysis.json"] = encode(analysis)
        files.update(resources)
    plate_analysis = event.get("plate_analysis")
    if plate_analysis is not None:
        if plate_analysis.get("integrity_valid") is not True:
            raise ValueError("plate analysis integrity failure")
        analysis, model_input = store.plate_analysis_content(event_id)
        if analysis != plate_analysis:
            raise ValueError("plate analysis changed during export")
        files["plate-analysis.json"] = encode(analysis)
        files["plate-model-input.png"] = model_input
    scene_analysis = event.get("scene_analysis")
    if scene_analysis is not None:
        if scene_analysis.get("integrity_valid") is not True:
            raise ValueError("scene analysis integrity failure")
        analysis, model_input = store.scene_analysis_content(event_id)
        if analysis != scene_analysis:
            raise ValueError("scene analysis changed during export")
        files["scene-analysis.json"] = encode(analysis)
        files["scene-model-input.png"] = model_input
    video_visibility_analysis = event.get("video_visibility_analysis")
    if video_visibility_analysis is not None:
        if video_visibility_analysis.get("integrity_valid") is not True:
            raise ValueError("video visibility integrity failure")
        visibility, frame_blobs = store.video_visibility_analysis_content(event_id)
        if visibility != video_visibility_analysis:
            raise ValueError("video visibility changed during export")
        files["video-visibility-analysis.json"] = encode(visibility)
        for descriptor, blob in zip(visibility["frames"], frame_blobs):
            files[descriptor["path"]] = blob
    video_road_analysis = event.get("video_road_analysis")
    if video_road_analysis is not None:
        if video_road_analysis.get("integrity_valid") is not True:
            raise ValueError("video road integrity failure")
        road, frame_blobs = store.video_road_analysis_content(event_id)
        if road != video_road_analysis:
            raise ValueError("video road changed during export")
        files["video-road-analysis.json"] = encode(road)
        for descriptor, blob in zip(road["frames"], frame_blobs):
            files[descriptor["path"]] = blob
    video_collision_analysis = event.get("video_collision_analysis")
    if video_collision_analysis is not None:
        if video_collision_analysis.get("integrity_valid") is not True:
            raise ValueError("video collision integrity failure")
        collision, frame_blobs = store.video_collision_analysis_content(event_id)
        if collision != video_collision_analysis:
            raise ValueError("video collision changed during export")
        files["video-collision-analysis.json"] = encode(collision)
        for descriptor, blob in zip(collision["frames"], frame_blobs):
            files[descriptor["path"]] = blob
    video_lane_analysis = event.get("video_lane_analysis")
    if video_lane_analysis is not None:
        if video_lane_analysis.get("integrity_valid") is not True:
            raise ValueError("video lane integrity failure")
        lane, frame_blobs = store.video_lane_analysis_content(event_id)
        if lane != video_lane_analysis:
            raise ValueError("video lane changed during export")
        files["video-lane-analysis.json"] = encode(lane)
        for descriptor, blob in zip(lane["frames"], frame_blobs):
            files[descriptor["path"]] = blob

    def photo_report(source_event, prefix):
        descriptor = copy.deepcopy(source_event.get("video"))
        if descriptor is None or descriptor.get("input_photo") is None:
            return descriptor
        photo = descriptor["input_photo"]
        if (
            photo.get("integrity_valid") is not True
            or photo.get("event_id") != source_event["id"]
            or photo.get("sensor_sha256") != source_event["hash"]
        ):
            raise ValueError("scene photo evidence integrity failure")
        suffix = {"image/png": "png", "image/jpeg": "jpg"}.get(photo.get("mime"))
        if suffix is None:
            raise ValueError("scene photo MIME")
        name = prefix + "." + suffix
        path = store.scene_photo_file(source_event["id"])
        streams[name] = (lambda path=path: path.open("rb"), copy.deepcopy(photo))
        photo["path"] = name
        return descriptor

    reported_video = photo_report(event, "input-photo")
    if following is not None:
        followup_source["video"] = photo_report(following, "follow-up-input-photo")
    report = {
        k: event[k]
        for k in (
            "id",
            "created",
            "source",
            "status",
            "assessment",
            "history",
            "dispatches",
        )
    }
    report.update(
        schema="road-guardian.export.v1",
        sensor_sha256=event["hash"],
        video=reported_video,
        road_evidence_review=event.get("road_evidence_review"),
        road_followup_case=followup,
        road_followup_source=followup_source,
        surround=(
            dict(surround, path="surround-original.png")
            if surround is not None
            else None
        ),
        surround_views=reported_views,
        scene_analysis=scene_analysis,
        plate_analysis=plate_analysis,
        plate_video_analysis=plate_video_analysis,
        video_visibility_analysis=video_visibility_analysis,
        video_road_analysis=video_road_analysis,
        video_collision_analysis=video_collision_analysis,
        video_lane_analysis=video_lane_analysis,
        repair=repair_report,
        context=event["data"].get("context"),
        external_submission=False,
        limitations=[
            "Synthetic demonstration; not real vehicle evidence.",
            "No agency submission or acceptance.",
            "Hashes detect file changes; they do not prove real-world authenticity.",
        ],
    )
    files["event-report.json"] = encode(report)
    if review_note is not None and review_note.get("revision", 0) > 0:
        if (
            review_note.get("event_id") != event_id
            or review_note.get("scope") != "current_subject_only"
        ):
            raise ValueError("private note scope mismatch")
        files["private-review-note.json"] = encode(review_note)
    if event.get("evidence_dossier") is not None:
        files["evidence-index.json"] = encode(
            dict(
                event["evidence_dossier"],
                event_id=event_id,
                sensor_sha256=event["hash"],
                original_path="sensor-original.json",
                report_path="event-report.json",
            )
        )
    files["README.txt"] = (
        "ROAD GUARDIAN - SYNTHETIC DEMONSTRATION ONLY\n"
        "This package is not an official submission and contains no real camera evidence.\n"
        "sensor-original.json preserves the canonical sensor fixture.\n"
        "input-photo and follow-up-input-photo files preserve unchanged synthetic reference images, not camera evidence.\n"
        "surround-original.png, if present, is one unchanged synthetic atlas with four authored view regions, not four independent camera captures.\n"
        "surround/*.png, if present, are separate authored synthetic view images, not real camera captures; missing directions remain explicit.\n"
        "follow-up-original.json, if present, preserves the linked synthetic road observation, not proof of repair.\n"
        "follow-up-video.mp4, if present, is the linked observation's synthetic video; event-report.json binds its event ID and sensor hash.\n"
        "repair-follow-up-original.json, if present, preserves the synthetic voltage remeasurement.\n"
        "Repair offers and technician notes are mock records; voltage recovery does not prove repair completion.\n"
        "event-report.json contains assessment, source limitations and mock dispatch history.\n"
        "evidence-index.json, if present, lists available, missing and test-only road or crash materials; it is not acceptance or fault confirmation.\n"
        "Video files are synthetic fixtures (test patterns or reference scenes), not real recordings or temporal inference proof.\n"
        "The historical name test-pattern-video.mp4 identifies the initial observation video; follow-up-video.mp4 identifies the later observation.\n"
        "manifest.json lists SHA-256 hashes and byte sizes of all other files.\n"
    ).encode()
    if video_visibility_analysis is not None:
        files["README.txt"] += (
            "Video visibility observations may miss partially visible wipers, confuse lighting and obscuration, or misstate event order.\n"
            "Not observed does not prove absence, washer completion or restored visibility; sensor and video timelines are independently authored.\n"
            "Preserved model output is an observation record, not verification of its factual accuracy.\n"
        ).encode()
    entries = [
        {"path": name, "bytes": len(blob), "sha256": hashlib.sha256(blob).hexdigest()}
        for name, blob in sorted(files.items())
    ]
    for name, (_, metadata) in streams.items():
        entries.append(
            {"path": name, "bytes": metadata["bytes"], "sha256": metadata["sha256"]}
        )
    files["manifest.json"] = encode(
        {
            "schema": "road-guardian.package.v1",
            "event_id": event_id,
            "source": event["source"],
            "external_submission": False,
            "files": sorted(entries, key=lambda item: item["path"]),
        }
    )
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        names = sorted(list(files) + list(streams))
        for name in names:
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            if name not in streams:
                archive.writestr(entry, files[name])
                continue
            digest = hashlib.sha256()
            size = 0
            open_source, metadata = streams[name]
            with open_source() as source, archive.open(entry, "w") as destination:
                while block := source.read(65536):
                    size += len(block)
                    if size > metadata["bytes"]:
                        raise ValueError("video changed during export: " + name)
                    digest.update(block)
                    destination.write(block)
            if size != metadata["bytes"] or digest.hexdigest() != metadata["sha256"]:
                raise ValueError("video changed during export: " + name)
    # Revalidate the relationship after streaming, before prepared_package exposes bytes.
    current = store.get(event_id)
    if current.get("video_lane_analysis") != video_lane_analysis:
        raise ValueError("video lane changed during export")
    if current.get("video_road_analysis") != video_road_analysis:
        raise ValueError("video road changed during export")
    if current.get("video_collision_analysis") != video_collision_analysis:
        raise ValueError("video collision changed during export")
    if current.get("video_visibility_analysis") != video_visibility_analysis:
        raise ValueError("video visibility changed during export")
    if current.get("plate_video_analysis") != plate_video_analysis:
        raise ValueError("plate video analysis changed during export")
    if current.get("plate_analysis") != plate_analysis:
        raise ValueError("plate analysis changed during export")
    if current.get("scene_analysis") != scene_analysis:
        raise ValueError("scene analysis changed during export")
    if current.get("surround_views") != surround_views:
        raise ValueError("surround views changed during export")
    if current.get("surround") != surround:
        raise ValueError("surround changed during export")
    if (
        not current["evidence_valid"]
        or current["hash"] != event["hash"]
        or current.get("road_followup_case") != followup
        or current["video"] != video
    ):
        raise ValueError("original or road follow-up changed during export")
    if following is not None:
        current_source = store.get(following["id"])
        if (
            not current_source["evidence_valid"]
            or current_source["hash"] != following["hash"]
            or current_source["video"] != following["video"]
        ):
            raise ValueError("road follow-up source changed during export")


@contextmanager
def prepared_package(
    store, event_id, review_note=None, observations=None, photo_bundle=None
):
    # Complete integrity checks before exposing headers or any archive bytes.
    with tempfile.TemporaryFile(mode="w+b") as output:
        write_package(
            store,
            event_id,
            LimitedArchive(output, MAX_PACKAGE_BYTES),
            review_note,
            observations,
            photo_bundle,
        )
        size = output.tell()
        if size > MAX_PACKAGE_BYTES:
            raise ValueError("evidence package exceeds download limit")
        output.seek(0)
        yield output, size


def build_package(store, event_id):
    """Compatibility helper for in-memory consumers; HTTP uses prepared_package."""
    with prepared_package(store, event_id) as (output, _):
        return output.read()
