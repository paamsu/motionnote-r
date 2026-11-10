"""Internal road-evidence review, never agency eligibility or submission approval."""

import hashlib
import json
import math
import re


def number(value):
    try:
        return (
            isinstance(value, (int, float))
            and not isinstance(value, bool)
            and math.isfinite(value)
        )
    except OverflowError:
        return False


def road_review(event):
    if event.get("assessment", {}).get("kind") != "pothole":
        return None
    data = event.get("data", {})
    try:
        raw = json.dumps(
            data,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode()
        intact = event.get("evidence_valid") is True and hashlib.sha256(
            raw
        ).hexdigest() == event.get("hash")
    except (TypeError, ValueError, OverflowError):
        intact = False
    result = {
        "schema": "road-guardian.road-review.v1",
        "criteria": "internal_completeness_only",
        "agency_requirements_verified": False,
        "external_submission": False,
        "source": event.get("source"),
        "submission_status": "not_configured",
        "mock_receipt": None,
        "items": [],
    }
    items = result["items"]

    def item(key, label, state, detail):
        items.append({"key": key, "label": label, "state": state, "detail": detail})

    item(
        "original",
        "원본 자료",
        "present" if intact else "invalid",
        "저장 원본의 해시 일치" if intact else "원본 확인 실패 · 다른 항목 판단 보류",
    )
    if not intact:
        return dict(result, stage="invalid", checked_count=0, item_count=6)
    location = data.get("location") if isinstance(data.get("location"), dict) else {}
    lat, lon, accuracy = (location.get(k) for k in ("lat", "lon", "accuracy_m"))
    positioned = (
        number(lat)
        and -90 <= lat <= 90
        and number(lon)
        and -180 <= lon <= 180
        and number(accuracy)
        and accuracy >= 0
    )
    item(
        "location",
        "위치와 오차",
        "present" if positioned else "missing",
        (
            f"위치 메타데이터 있음 · 오차 {accuracy:g} m"
            if positioned
            else "좌표 또는 위치 오차 확인 필요"
        ),
    )
    heading, road = location.get("heading"), location.get("road_id")
    directed = (
        number(heading)
        and 0 <= heading < 360
        and isinstance(road, str)
        and bool(road.strip())
    )
    item(
        "direction",
        "도로와 진행 방향",
        "present" if directed else "missing",
        (
            "도로 ID와 진행 방향 있음 · 차로 확인은 별도"
            if directed
            else "도로 ID 또는 진행 방향 확인 필요"
        ),
    )
    captured = data.get("capture_started_at")
    timed = number(captured) and captured > 0
    item(
        "observation_time",
        "관측 시각",
        "present" if timed else "missing",
        (
            "원본에 관측 시작 시각 기록됨 · 시계 정확도는 별도"
            if timed
            else "관측 시작 시각 없음 · 서버 저장 시각으로 대신하지 않음"
        ),
    )
    confirmation = None
    for record in event.get("history", []):
        if isinstance(record, dict) and record.get("action") == "driver_confirmation":
            try:
                detail = json.loads(record["detail"])
                confirmation = (
                    detail.get("accepted") if isinstance(detail, dict) else None
                )
                if not isinstance(confirmation, bool):
                    confirmation = None
            except (KeyError, ValueError, TypeError):
                confirmation = None
    rejected = confirmation is False or event.get("status") == "dismissed"
    item(
        "driver_confirmation",
        "운전자 확인",
        "rejected" if rejected else "confirmed" if confirmation is True else "missing",
        (
            "해당하지 않는 상황으로 확인"
            if rejected
            else (
                "도로 상황 확인 기록 있음"
                if confirmation is True
                else "운전자 확인 기록 필요"
            )
        ),
    )
    video = event.get("video")
    if not video:
        media_state, media_detail = "missing", "영상 미취득"
    elif not isinstance(video, dict) or video.get("integrity_valid") is not True:
        media_state, media_detail = "invalid", "영상 원본 확인 실패"
    elif (
        video.get("camera_evidence") is not True
        or video.get("source") == "synthetic_test_pattern"
        or event.get("source") == "synthetic"
    ):
        media_state, media_detail = (
            "synthetic",
            "시험 영상 · 실제 현장 촬영 근거로 세지 않음",
        )
    else:
        media_state, media_detail = (
            "present",
            "카메라 자료 메타데이터 있음 · 촬영 범위와 내용 확인은 별도",
        )
    item("video", "현장 영상", media_state, media_detail)
    for delivery in event.get("dispatches", []):
        if (
            not isinstance(delivery, dict)
            or delivery.get("recipient") != "municipality-mock"
        ):
            continue
        receipt = delivery.get("receipt")
        if (
            delivery.get("status") == "acknowledged"
            and isinstance(receipt, str)
            and re.fullmatch(r"MOCK-[0-9a-f]{8}", receipt)
        ):
            result.update(submission_status="mock_acknowledged", mock_receipt=receipt)
        elif delivery.get("status") == "retry_pending":
            result["submission_status"] = "mock_retry"
    checked = sum(i["state"] in ("present", "confirmed") for i in items)
    stage = (
        "dismissed"
        if rejected
        else "synthetic_draft" if event.get("source") == "synthetic" else "review_only"
    )
    return dict(result, stage=stage, checked_count=checked, item_count=len(items))
