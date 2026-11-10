"""Local development relay. No account pairing, coordinates, photos or production push."""

import math
import time
import uuid


class ArrivalRelay:
    def __init__(self, store):
        self.store = store
        with store.connect() as db:
            db.execute(
                "CREATE TABLE IF NOT EXISTS parking_arrivals(id TEXT PRIMARY KEY, parked_at REAL NOT NULL, received_at REAL NOT NULL)"
            )

    def accept(self, body):
        if (
            set(body) != {"id", "parked_at", "source"}
            or body["source"] != "pleos_emulator"
        ):
            raise ValueError("development Pleos emulator arrival required")
        try:
            identifier = str(uuid.UUID(body["id"]))
        except (ValueError, TypeError, AttributeError):
            raise ValueError("UUID arrival id required") from None
        at = body["parked_at"]
        if (
            isinstance(at, bool)
            or not isinstance(at, (int, float))
            or not math.isfinite(at)
            or not 0 < at <= time.time() + 60
        ):
            raise ValueError("valid parking timestamp required")
        with self.store.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            old = db.execute(
                "SELECT parked_at FROM parking_arrivals WHERE id=?", (identifier,)
            ).fetchone()
            if old and old["parked_at"] != at:
                raise ValueError("arrival id reused with different timestamp")
            db.execute(
                "INSERT OR IGNORE INTO parking_arrivals VALUES(?,?,?)",
                (identifier, at, time.time()),
            )
        return {"id": identifier, "accepted": True, "mode": "development-relay"}

    def latest(self):
        with self.store.connect() as db:
            row = db.execute(
                "SELECT * FROM parking_arrivals ORDER BY parked_at DESC, received_at DESC LIMIT 1"
            ).fetchone()
        return (
            None
            if row is None
            else dict(row, source="pleos_emulator", mode="development-relay")
        )
