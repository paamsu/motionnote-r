"""Durable provider-independent delivery scheduling. No network transport is installed."""

import hashlib
import json
import math
import time
import uuid
from evidence.overview import comparison_notification_eligible


class NotificationQueue:
    def __init__(self, store, clock=time.time):
        self.store = store
        self.clock = clock

    def claim(self, lease_seconds=60):
        if not 1 <= lease_seconds <= 300:
            raise ValueError("lease must be between 1 and 300 seconds")
        now = self.clock()
        if not math.isfinite(now):
            raise ValueError("finite clock required")
        with self.store.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            while True:
                # Select queue metadata first; never materialize every due event's sensor payload.
                row = db.execute(
                    """SELECT * FROM notification_queue
                    WHERE (state='pending' AND available_at<=?)
                       OR (state='leased' AND lease_until<=?)
                    ORDER BY created, id LIMIT 1""",
                    (now, now),
                ).fetchone()
                if row is None:
                    return None
                if row["attempts"] >= 5:
                    db.execute(
                        "UPDATE notification_queue SET state='exhausted',lease_token=NULL,lease_until=NULL WHERE id=?",
                        (row["id"],),
                    )
                    continue
                event = db.execute(
                    "SELECT raw,hash,source FROM events WHERE id=?", (row["event_id"],)
                ).fetchone()
                valid = False
                if event is not None and isinstance(event["raw"], str):
                    try:
                        data = json.loads(event["raw"])
                        valid = (
                            isinstance(data, dict)
                            and data.get("vehicle_id") == row["vehicle_id"]
                            and event["source"] == "synthetic"
                            and hashlib.sha256(event["raw"].encode()).hexdigest()
                            == event["hash"]
                        )
                    except (ValueError, TypeError, UnicodeError):
                        pass
                if not valid:
                    db.execute(
                        "UPDATE notification_queue SET state='invalid',lease_token=NULL,lease_until=NULL WHERE id=?",
                        (row["id"],),
                    )
                    continue
                if row["purpose"] != "comparison_available":
                    db.execute(
                        "UPDATE notification_queue SET state='excluded_scope',lease_token=NULL,lease_until=NULL WHERE id=?",
                        (row["id"],),
                    )
                    continue
                try:
                    eligible = comparison_notification_eligible(
                        self.store.get(row["event_id"])
                    )
                except (KeyError, TypeError, ValueError, AttributeError, OverflowError):
                    eligible = False
                if not eligible:
                    db.execute(
                        "UPDATE notification_queue SET state='invalid',lease_token=NULL,lease_until=NULL WHERE id=?",
                        (row["id"],),
                    )
                    continue
                token = str(uuid.uuid4())
                db.execute(
                    "UPDATE notification_queue SET state='leased',attempts=attempts+1,lease_token=?,lease_until=? WHERE id=?",
                    (token, now + lease_seconds, row["id"]),
                )
                # The transport must resolve current authorized devices at send time.
                # Do not put plate, location, evidence or a stale recipient token in a push payload.
                return {
                    "id": row["id"],
                    "event_id": row["event_id"],
                    "vehicle_id": row["vehicle_id"],
                    "source": event["source"],
                    "lease_token": token,
                    "attempt": row["attempts"] + 1,
                    "idempotency_key": row["id"],
                    "purpose": row["purpose"],
                }

    def current(self, identity, lease_token):
        now = self.clock()
        if not math.isfinite(now):
            raise ValueError("finite clock required")
        with self.store.connect() as db:
            row = db.execute(
                "SELECT state,lease_token,lease_until,purpose FROM notification_queue WHERE id=?",
                (identity,),
            ).fetchone()
        return bool(
            row
            and row["purpose"] == "comparison_available"
            and row["state"] == "leased"
            and row["lease_token"] == lease_token
            and row["lease_until"] > now
        )

    def finish(self, identity, lease_token, accepted=False, simulated=False):
        if (
            not isinstance(accepted, bool)
            or not isinstance(simulated, bool)
            or (accepted and simulated)
        ):
            raise ValueError("one explicit delivery outcome required")
        now = self.clock()
        if not math.isfinite(now):
            raise ValueError("finite clock required")
        with self.store.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT * FROM notification_queue WHERE id=?", (identity,)
            ).fetchone()
            if (
                not row
                or row["purpose"] != "comparison_available"
                or row["state"] != "leased"
                or row["lease_token"] != lease_token
                or row["lease_until"] <= now
            ):
                raise ValueError("delivery lease is no longer current")
            state = (
                "simulated"
                if simulated
                else (
                    "provider_accepted"
                    if accepted
                    else ("exhausted" if row["attempts"] >= 5 else "pending")
                )
            )
            delay = min(30 * 2 ** (row["attempts"] - 1), 900)
            db.execute(
                """UPDATE notification_queue SET state=?, available_at=?,lease_token=NULL,lease_until=NULL
                WHERE id=?""",
                (state, now + delay, identity),
            )
            return state

    def get(self, identity):
        with self.store.connect() as db:
            row = db.execute(
                "SELECT * FROM notification_queue WHERE id=?", (identity,)
            ).fetchone()
        if row is None:
            raise KeyError(identity)
        return dict(row)
