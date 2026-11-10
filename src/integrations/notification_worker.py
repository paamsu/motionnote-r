"""Bounded queue worker: explicit simulation or operator-configured HTTPS relay."""

import argparse
import json
import hashlib
from typing import Protocol
from evidence.store import Store
from integrations.notification_queue import NotificationQueue
from evidence.overview import comparison_notification_eligible


class Transport(Protocol):
    mode: str

    def send(self, recipient: str, payload: dict, idempotency_key: str) -> bool:
        """Return True only when this transport accepted the request; deduplicate by key."""
        ...


class SyntheticTransport:
    mode = "synthetic"

    def __init__(self):
        self.requests = {}

    def send(self, recipient, payload, idempotency_key):
        if (
            not recipient.startswith("synthetic-device:")
            or payload.get("source") != "synthetic"
        ):
            raise ValueError("synthetic destination and payload required")
        request = (recipient, dict(payload))
        previous = self.requests.setdefault(idempotency_key, request)
        if previous != request:
            raise ValueError("idempotency key conflict")
        return True


class DurableSyntheticTransport(SyntheticTransport):
    """Persist simulated receipts across restarts; contains no raw device handles."""

    def __init__(self, store):
        super().__init__()
        self.store = store
        with store.connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS synthetic_notification_receipts(
                delivery_key TEXT PRIMARY KEY, request_hash TEXT NOT NULL)""")

    def send(self, recipient, payload, idempotency_key):
        if (
            not recipient.startswith("synthetic-device:")
            or payload.get("source") != "synthetic"
        ):
            raise ValueError("synthetic destination and payload required")
        digest = hashlib.sha256(
            json.dumps(
                [recipient, payload],
                sort_keys=True,
                separators=(",", ":"),
                ensure_ascii=True,
            ).encode()
        ).hexdigest()
        with self.store.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            previous = db.execute(
                "SELECT request_hash FROM synthetic_notification_receipts WHERE delivery_key=?",
                (idempotency_key,),
            ).fetchone()
            if previous is not None and previous[0] != digest:
                raise ValueError("idempotency key conflict")
            db.execute(
                "INSERT OR IGNORE INTO synthetic_notification_receipts VALUES(?,?)",
                (idempotency_key, digest),
            )
        return True


class NotificationWorker:
    def __init__(
        self, queue, resolve_recipient, transport: Transport, allow_external=False
    ):
        # External transport is opt-in; the CLI requires current server ownership bindings.
        if transport.mode != "synthetic" and not (
            allow_external and transport.mode == "relay"
        ):
            raise ValueError("external relay requires explicit opt-in")
        self.queue = queue
        self.resolve_recipient = resolve_recipient
        self.transport = transport

    def run_one(self):
        item = self.queue.claim()
        if item is None:
            return "idle"
        identity, token = item["id"], item["lease_token"]
        accepted = False
        try:
            event = self.queue.store.get(item["event_id"])
            if (
                not event["evidence_valid"]
                or event["source"] != "synthetic"
                or event["data"].get("vehicle_id") != item["vehicle_id"]
                or not comparison_notification_eligible(event)
            ):
                raise ValueError("evidence changed before delivery")
            recipient = self.resolve_recipient(item["vehicle_id"])
            if not self.queue.current(identity, token):
                return "lease_lost"
            if recipient:
                if not comparison_notification_eligible(
                    self.queue.store.get(item["event_id"])
                ):
                    raise ValueError("comparison changed before delivery")
                prefix = (
                    "synthetic-device:"
                    if self.transport.mode == "synthetic"
                    else "relay-device:"
                )
                if not isinstance(recipient, str) or not recipient.startswith(prefix):
                    raise ValueError("recipient kind does not match transport")
                payload = {
                    "event_id": item["event_id"],
                    "source": "synthetic",
                    "type": "event_available",
                }
                # Deduplicate retries for the same destination, not a later authorized replacement.
                # Hash the tuple so the provider key contains no raw device handle.
                delivery_key = hashlib.sha256(
                    json.dumps(
                        ["notification-v1", item["idempotency_key"], recipient],
                        ensure_ascii=True,
                        separators=(",", ":"),
                    ).encode()
                ).hexdigest()
                accepted = self.transport.send(recipient, payload, delivery_key) is True
        except Exception:
            # Provider bodies, recipient handles and credentials must not appear in worker output.
            accepted = False
        try:
            return self.queue.finish(
                identity,
                token,
                simulated=accepted and self.transport.mode == "synthetic",
                accepted=accepted and self.transport.mode == "relay",
            )
        except ValueError:
            return "lease_lost"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--simulate", action="store_true")
    mode.add_argument("--relay-config")
    parser.add_argument("--max-jobs", type=int, default=10)
    parser.add_argument("--access-policy")
    parser.add_argument("--recipient-registry")
    args = parser.parse_args()
    if not 1 <= args.max_jobs <= 100:
        parser.error("--max-jobs must be between 1 and 100")
    queue = NotificationQueue(Store(args.database))
    if bool(args.access_policy) != bool(args.recipient_registry):
        parser.error(
            "--access-policy and --recipient-registry must be supplied together"
        )
    if args.relay_config and not args.access_policy:
        parser.error("relay requires access policy and recipient registry")
    if args.access_policy:
        from backend.access import AccessPolicy
        from integrations.notification_recipient import FileRecipientResolver

        resolver = FileRecipientResolver(
            AccessPolicy.from_file(args.access_policy),
            args.recipient_registry,
            prefix="relay-device:" if args.relay_config else "synthetic-device:",
        )
    else:
        resolver = lambda vehicle: "synthetic-device:" + vehicle
    if args.relay_config:
        from integrations.notification_relay import HttpsNotificationRelay

        transport = HttpsNotificationRelay.from_file(args.relay_config)
    else:
        transport = DurableSyntheticTransport(queue.store)
    worker = NotificationWorker(
        queue, resolver, transport, allow_external=bool(args.relay_config)
    )
    results = []
    for _ in range(args.max_jobs):
        result = worker.run_one()
        results.append(result)
        if result == "idle":
            break
    output = {
        "mode": transport.mode,
        "phone_delivery_verified": False,
        "results": results,
    }
    if transport.mode == "synthetic":
        output.update(mode="synthetic-only", external_deliveries=0)
    print(json.dumps(output))


if __name__ == "__main__":
    main()
