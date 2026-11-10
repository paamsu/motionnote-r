"""Private owner-authored records. No synthetic generator, detector, diagnosis or dispatch."""

import hashlib
import json
import math
import time
import unicodedata
import uuid
from evidence.overview import owner_assessment


class CaseConflict(ValueError):
    pass


class CaseLimit(ValueError):
    pass


def canonical(value):
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )


class OwnerCases:
    def __init__(self, store):
        self.store = store

    @staticmethod
    def checked(body):
        if not isinstance(body, dict) or set(body) != {
            "schema",
            "request_id",
            "vehicle_id",
            "category",
            "reported_at",
            "title",
            "description",
            "own_vehicle",
        }:
            raise ValueError("owner case fields required")
        if body["schema"] != "road-guardian.owner-case.v1":
            raise ValueError("unsupported owner case schema")
        key = body["request_id"]
        if not isinstance(key, str) or str(uuid.UUID(key)) != key:
            raise ValueError("canonical request UUID required")
        vehicle = body["vehicle_id"]
        if not isinstance(vehicle, str) or not 1 <= len(vehicle) <= 128:
            raise ValueError("registered vehicle required")
        if body["category"] not in ("road", "maintenance", "incident"):
            raise ValueError("case category required")
        reported = body["reported_at"]
        if reported is not None and (
            type(reported) not in (int, float)
            or not 0 < reported <= time.time() + 300
            or not math.isfinite(reported)
        ):
            raise ValueError("valid reported time or null required")

        def text(value, limit, required=False, multiline=False):
            if (
                not isinstance(value, str)
                or len(value) > limit
                or (required and not value.strip())
                or any(
                    unicodedata.category(c) in ("Cc", "Cs")
                    and not (multiline and c in "\n\t")
                    for c in value
                )
            ):
                raise ValueError("invalid owner text")

        text(body["title"], 100, True)
        text(body["description"], 4000, multiline=True)
        own = body["own_vehicle"]
        if not isinstance(own, dict) or set(own) != {"plate", "model", "color"}:
            raise ValueError("owner vehicle fields required")
        for name, limit in (("plate", 40), ("model", 60), ("color", 30)):
            text(own[name], limit)
        raw = canonical(body)
        if len(raw.encode()) > 16384:
            raise ValueError("owner case too large")
        return raw

    def create(self, principal, body):
        if "events:create" not in principal.scopes:
            raise PermissionError("case creation scope required")
        raw = self.checked(body)
        if body["vehicle_id"] not in principal.vehicles:
            raise KeyError("vehicle not registered")
        digest = hashlib.sha256(raw.encode()).hexdigest()
        with self.store.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            if db.execute(
                "SELECT EXISTS(SELECT 1 FROM events e WHERE e.source='owner_reported' AND NOT EXISTS(SELECT 1 FROM private_case_owners p WHERE p.event_id=e.id))"
            ).fetchone()[0]:
                raise ValueError("private case ownership index unavailable")
            prior = db.execute(
                "SELECT event_id,request_hash FROM private_case_owners WHERE subject=? AND request_id=?",
                (principal.subject, body["request_id"]),
            ).fetchone()
            if prior:
                if prior["request_hash"] != digest:
                    raise CaseConflict("case request reused with different content")
                event = self.store.get(prior["event_id"])
                if not event["evidence_valid"]:
                    raise ValueError("stored case integrity failure")
                return event
            if (
                db.execute(
                    "SELECT COUNT(*) FROM private_case_owners WHERE subject=?",
                    (principal.subject,),
                ).fetchone()[0]
                >= 1000
            ):
                raise CaseLimit("private case limit reached")
            identity = str(uuid.uuid4())
            created = time.time()
            data = {
                "schema": "road-guardian.owner-record.v1",
                "source": "owner_reported",
                "vehicle_id": body["vehicle_id"],
                "category": body["category"],
                "reported_at": body["reported_at"],
                "title": body["title"],
                "description": body["description"],
                "context": {
                    "source": "owner_reported",
                    "own_vehicle": dict(body["own_vehicle"], source="owner_reported"),
                    "actors": [],
                    "unknowns": [
                        "사용자가 직접 작성한 내용 · 사실 여부와 차량 정보 별도 확인 필요"
                    ],
                },
            }
            stored = canonical(data)
            assessment = owner_assessment(data)
            db.execute(
                "INSERT INTO events VALUES(?,?,?,?,?,?,?,?)",
                (
                    identity,
                    "owner:" + identity,
                    created,
                    "owner_reported",
                    stored,
                    hashlib.sha256(stored.encode()).hexdigest(),
                    canonical(assessment),
                    "owner_recorded",
                ),
            )
            db.execute(
                "INSERT INTO private_case_owners VALUES(?,?,?,?)",
                (identity, principal.subject, body["request_id"], digest),
            )
            db.execute(
                "INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)",
                (
                    identity,
                    created,
                    "owner_case_created",
                    canonical(
                        {"source": "owner_reported", "automatic_assessment": False}
                    ),
                ),
            )
        return self.store.get(identity)
