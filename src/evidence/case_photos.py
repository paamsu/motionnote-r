"""Owner-private, re-encoded photo attachments. Not camera originals or scene attestation."""

import base64
import binascii
import hashlib
import io
import json
import re
from datetime import datetime
import time
import unicodedata
import uuid

MAX_IMAGE_BYTES = 1024 * 1024
MAX_PHOTOS = 4


class PhotoConflict(ValueError):
    pass


class PhotoLimit(ValueError):
    pass


def canonical(value):
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )


def checked_capture_context(value):
    """Validate separately authored metadata, never attest a capture or location."""
    if (
        not isinstance(value, dict)
        or set(value) != {"source", "captured_at", "place_id", "viewpoint_id"}
        or type(value["source"]) is not str
        or value["source"] not in ("authored_synthetic", "owner_reported")
    ):
        raise ValueError("complete authored capture context required")
    captured = value["captured_at"]
    if (
        not isinstance(captured, str)
        or re.fullmatch(
            r"[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:Z|[+-](?:0[0-9]|1[0-3]):[0-5][0-9]|[+-]14:00)",
            captured,
        )
        is None
        or captured.endswith("-00:00")
    ):
        raise ValueError(
            "capture timestamp requires ISO8601 seconds and explicit offset"
        )
    try:
        parsed = datetime.fromisoformat(captured.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("capture offset required")
    except ValueError:
        raise ValueError("valid capture date and offset required") from None
    for field in ("place_id", "viewpoint_id"):
        label = value[field]
        if (
            not isinstance(label, str)
            or not 1 <= len(label) <= 80
            or not label.strip()
            or label.strip() != label
            or any(
                unicodedata.category(c) in ("Cc", "Cs") or ord(c) > 0xFFFF
                for c in label
            )
        ):
            raise ValueError(
                "bounded authored place and viewpoint identifiers required"
            )
    return dict(value)


class CasePhotos:
    def __init__(self, store):
        from PIL import Image

        self.store = store
        with store.connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS private_case_photos(
                event_id TEXT NOT NULL,subject TEXT NOT NULL,id TEXT NOT NULL,created REAL NOT NULL,
                request_hash TEXT NOT NULL,metadata TEXT NOT NULL,image BLOB NOT NULL,record_hash TEXT NOT NULL,
                PRIMARY KEY(event_id,subject,id))""")

    def _allowed(self, event_id, principal):
        event = self.store.get(event_id)
        if (
            event["source"] != "owner_reported"
            or not event["evidence_valid"]
            or not self.store.visible_to(
                event_id, principal.vehicles, principal.subject
            )
        ):
            raise KeyError("event not found")

    @staticmethod
    def checked(body):
        required = {"schema", "request_id", "caption", "image_base64"}
        if not isinstance(body, dict) or set(body) not in (
            required,
            required | {"capture_context"},
        ):
            raise ValueError("photo upload fields required")
        if body["schema"] != "road-guardian.case-photo-upload.v1":
            raise ValueError("unsupported photo schema")
        key = body["request_id"]
        if not isinstance(key, str) or str(uuid.UUID(key)) != key:
            raise ValueError("canonical photo UUID required")
        caption = body["caption"]
        if (
            not isinstance(caption, str)
            or len(caption) > 200
            or any(unicodedata.category(c) in ("Cc", "Cs") for c in caption)
        ):
            raise ValueError("invalid caption")
        encoded = body["image_base64"]
        if not isinstance(encoded, str) or not 1 <= len(encoded) <= 4 * (
            (MAX_IMAGE_BYTES + 2) // 3
        ):
            raise ValueError("photo size limit")
        try:
            raw = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError):
            raise ValueError("invalid photo encoding") from None
        if not 1 <= len(raw) <= MAX_IMAGE_BYTES:
            raise ValueError("photo size limit")
        if "capture_context" in body:
            checked_capture_context(body["capture_context"])
        return key, caption, raw

    @staticmethod
    def normalized(raw):
        from PIL import Image, ImageOps, UnidentifiedImageError

        try:
            with Image.open(io.BytesIO(raw), formats=["JPEG"]) as source:
                if (
                    source.format != "JPEG"
                    or not 1 <= source.width <= 1600
                    or not 1 <= source.height <= 1600
                ):
                    raise ValueError("JPEG dimensions exceed limit")
                source.load()
                with ImageOps.exif_transpose(source) as oriented:
                    # A fresh RGB image removes EXIF, GPS, comments, ICC/XMP and source file names.
                    with Image.new("RGB", oriented.size) as clean:
                        with oriented.convert("RGB") as rgb:
                            clean.paste(rgb)
                        output = io.BytesIO()
                        clean.save(output, format="JPEG", quality=90, progressive=False)
                        blob = output.getvalue()
                        width, height = clean.size
            if len(blob) > MAX_IMAGE_BYTES:
                raise ValueError("converted photo exceeds limit")
            return blob, width, height
        except (
            UnidentifiedImageError,
            OSError,
            SyntaxError,
            Image.DecompressionBombError,
        ):
            raise ValueError("decodable JPEG required") from None

    @staticmethod
    def _view(row, content=False):
        metadata = row["metadata"]
        image = bytes(row["image"])
        meta = json.loads(metadata)
        if (
            hashlib.sha256(metadata.encode() + b"\0" + image).hexdigest()
            != row["record_hash"]
            or hashlib.sha256(image).hexdigest() != meta["sha256"]
            or len(image) != meta["bytes"]
            or meta.get("event_id") != row["event_id"]
            or meta.get("id") != row["id"]
            or meta.get("scope") != "current_subject_only"
            or meta.get("sensor_original") is not False
            or meta.get("metadata_removed") is not True
            or meta.get("provenance") != "owner_supplied_not_attested"
        ):
            raise ValueError("stored photo integrity failure")
        if "capture_context" in meta:
            checked_capture_context(meta["capture_context"])
        if content:
            meta["image_base64"] = base64.b64encode(image).decode()
        return meta

    def list(self, event_id, principal):
        self._allowed(event_id, principal)
        with self.store.connect() as db:
            rows = db.execute(
                "SELECT * FROM private_case_photos WHERE event_id=? AND subject=? ORDER BY created,id",
                (event_id, principal.subject),
            ).fetchall()
        if len(rows) > MAX_PHOTOS:
            raise ValueError("stored photo count exceeds limit")
        return {
            "schema": "road-guardian.case-photos.v1",
            "event_id": event_id,
            "scope": "current_subject_only",
            "photos": [self._view(row) for row in rows],
        }

    def get(self, event_id, photo_id, principal):
        self._allowed(event_id, principal)
        with self.store.connect() as db:
            row = db.execute(
                "SELECT * FROM private_case_photos WHERE event_id=? AND subject=? AND id=?",
                (event_id, principal.subject, photo_id),
            ).fetchone()
        if row is None:
            raise KeyError("photo not found")
        return self._view(row, True)

    def bundle(self, event_id, principal):
        self._allowed(event_id, principal)
        with self.store.connect() as db:
            rows = db.execute(
                "SELECT * FROM private_case_photos WHERE event_id=? AND subject=? ORDER BY created,id",
                (event_id, principal.subject),
            ).fetchall()
        if len(rows) > MAX_PHOTOS:
            raise ValueError("stored photo count exceeds limit")
        entries = []
        files = {}
        for row in rows:
            metadata = self._view(row)
            path = "photos/" + metadata["id"] + ".jpg"
            entries.append(dict(metadata, path=path))
            files[path] = bytes(row["image"])
        return (
            {
                "schema": "road-guardian.case-photo-index.v1",
                "event_id": event_id,
                "scope": "current_subject_only",
                "photos": entries,
            },
            files,
        )

    def save(self, event_id, principal, body):
        if "events:photos" not in principal.scopes:
            raise PermissionError("photo scope required")
        self._allowed(event_id, principal)
        key, caption, raw = self.checked(body)
        uploaded = hashlib.sha256(raw).hexdigest()
        context = (
            checked_capture_context(body["capture_context"])
            if "capture_context" in body
            else None
        )
        request_parts = [caption, uploaded]
        if context is not None:
            request_parts.append(context)
        request_hash = hashlib.sha256(canonical(request_parts).encode()).hexdigest()
        blob, width, height = self.normalized(raw)
        with self.store.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            prior = db.execute(
                "SELECT * FROM private_case_photos WHERE event_id=? AND subject=? AND id=?",
                (event_id, principal.subject, key),
            ).fetchone()
            if prior:
                if prior["request_hash"] != request_hash:
                    raise PhotoConflict("photo request changed")
                return self._view(prior)
            if (
                db.execute(
                    "SELECT COUNT(*) FROM private_case_photos WHERE event_id=? AND subject=?",
                    (event_id, principal.subject),
                ).fetchone()[0]
                >= MAX_PHOTOS
            ):
                raise PhotoLimit("photo limit reached")
            now = time.time()
            meta = {
                "schema": "road-guardian.case-photo.v1",
                "event_id": event_id,
                "id": key,
                "received_at": now,
                "caption": caption,
                "width": width,
                "height": height,
                "bytes": len(blob),
                "sha256": hashlib.sha256(blob).hexdigest(),
                "upload_sha256": uploaded,
                "scope": "current_subject_only",
                "provenance": "owner_supplied_not_attested",
                "sensor_original": False,
                "metadata_removed": True,
                "transformation": "server_jpeg_reencode",
            }
            if context is not None:
                meta["capture_context"] = context
            metadata = canonical(meta)
            record_hash = hashlib.sha256(metadata.encode() + b"\0" + blob).hexdigest()
            db.execute(
                "INSERT INTO private_case_photos VALUES(?,?,?,?,?,?,?,?)",
                (
                    event_id,
                    principal.subject,
                    key,
                    now,
                    request_hash,
                    metadata,
                    blob,
                    record_hash,
                ),
            )
        return meta
