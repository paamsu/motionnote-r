"""Synthetic ownership and insurance registry. Not a policy lookup service."""

from copy import deepcopy

PROFILES = {
    "demo-hyundai-01": {
        "vehicle_id": "demo-hyundai-01",
        "source": "synthetic",
        "brand": "Hyundai",
        "model": "아이오닉 6",
        "color": "흰색",
        "plate": "DEMO-OWN-01",
        "vin": "DEMO-VIN-00000001",
        "ownership_verified": True,
        "warranty": "covered",
        "insurance": {
            "provider_id": "insurer-demo",
            "provider_name": "시험 보험사",
            "policy_id": "POLICY-DEMO-001",
            "valid_from": 1767225600,
            "valid_until": 1893456000,
            "status": "active",
            "channel": "insurance-mock",
        },
    }
}


def profile_for(vehicle_id, profiles=None):
    profile = (PROFILES if profiles is None else profiles).get(vehicle_id)
    return deepcopy(profile) if profile else None


def insurance_envelope(event_id, data, evidence_hash, now, profiles=None):
    profile = profile_for(data["vehicle_id"], profiles)
    if (
        not profile
        or profile.get("source") != "synthetic"
        or not profile.get("ownership_verified")
    ):
        return {
            "status": "insurance_check_required",
            "reason": "ownership_unverified",
            "source": "synthetic",
        }
    policy = profile.get("insurance") or {}
    if (
        policy.get("status") != "active"
        or not policy.get("policy_id")
        or not policy.get("valid_from", float("inf"))
        <= now
        < policy.get("valid_until", 0)
    ):
        return {
            "status": "insurance_check_required",
            "reason": "policy_missing_or_expired",
            "source": "synthetic",
        }
    if policy.get("channel") != "insurance-mock":
        return {
            "status": "insurance_check_required",
            "reason": "channel_unavailable",
            "source": "synthetic",
        }
    return {
        "status": "ready",
        "source": "synthetic",
        "event_id": event_id,
        "vehicle": {
            key: profile.get(key)
            for key in ("vehicle_id", "brand", "model", "color", "plate", "vin")
        },
        "insurance": policy,
        "location": deepcopy(data["location"]),
        "evidence": {
            "sensor_sha256": evidence_hash,
            "video_status": data["media"]["status"],
        },
        "prepared_at": now,
    }


def insurance_case(event, now, profiles=None):
    """Read-only case state. A mock receipt never becomes a live insurer acknowledgement."""
    if event["assessment"]["kind"] != "crash" and not (
        event["assessment"]["kind"] == "parking" and event.get("source") == "synthetic"
    ):
        return None
    summary = {
        "status": "not_configured",
        "source": event["source"],
        "reason": None,
        "provider_name": None,
        "policy_id": None,
        "receipt": None,
        "submitted_video_status": None,
        "video_added_after_dispatch": False,
    }
    if not event["evidence_valid"]:
        summary.update(status="evidence_invalid", reason="sensor_integrity_failure")
        return summary
    if event["source"] != "synthetic":
        return summary
    delivery = next(
        (d for d in event["dispatches"] if d["recipient"] == "insurance-mock"), None
    )
    if delivery:
        payload = delivery.get("payload")
        if not delivery.get("payload_valid") or not payload:
            summary.update(
                status="payload_invalid", reason="dispatch_integrity_failure"
            )
            return summary
        policy = payload.get("insurance", {})
        summary.update(
            provider_name=policy.get("provider_name"), policy_id=policy.get("policy_id")
        )
        submitted = payload.get("evidence", {})
        summary["submitted_video_status"] = submitted.get("video_status")
        video = event.get("video")
        summary["video_added_after_dispatch"] = bool(
            video and video.get("sha256") != submitted.get("video_sha256")
        )
        if delivery["status"] == "acknowledged" and delivery.get("receipt"):
            summary.update(status="acknowledged_mock", receipt=delivery["receipt"])
        else:
            current = insurance_envelope(
                event["id"], event["data"], event["hash"], now, profiles
            )
            if current["status"] != "ready":
                summary.update(status="review_required", reason=current.get("reason"))
            elif any(current[key] != payload[key] for key in ("vehicle", "insurance")):
                summary.update(status="review_required", reason="policy_changed")
            else:
                summary["status"] = (
                    "retry_pending"
                    if delivery["status"] == "retry_pending"
                    else "awaiting_receipt"
                )
        return summary
    prepared = insurance_envelope(
        event["id"], event["data"], event["hash"], now, profiles
    )
    if prepared["status"] == "ready":
        summary.update(
            status="ready",
            provider_name=prepared["insurance"]["provider_name"],
            policy_id=prepared["insurance"]["policy_id"],
        )
    else:
        summary.update(status="review_required", reason=prepared.get("reason"))
    return summary
