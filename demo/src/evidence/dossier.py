"""Inventory of supplied evidence, never a submission, authenticity or fault verdict."""

import hashlib
import json
import math


def dossier(event):
    kind = event.get("assessment", {}).get("kind")
    if kind not in ("pothole", "crash"):
        return None
    result = {
        "schema": "road-guardian.evidence-dossier.v1",
        "kind": kind,
        "source": event.get("source"),
        "external_submission": False,
        "responsibility_determined": False,
        "agency_requirements_verified": False,
        "items": [],
    }

    def add(key, label, state, detail):
        result["items"].append(dict(key=key, label=label, state=state, detail=detail))

    data = event.get("data")
    try:
        raw = json.dumps(
            data,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode()
        intact = (
            isinstance(data, dict)
            and event.get("evidence_valid") is True
            and hashlib.sha256(raw).hexdigest() == event.get("hash")
        )
    except (TypeError, ValueError, OverflowError):
        intact = False
    if not intact:
        add(
            "original",
            "원본 자료",
            "invalid",
            "원본 확인 실패 · 차량과 상황 정보 판단 보류",
        )
        return dict(result, state="invalid", attention_count=1)
    add(
        "original",
        "원본 자료",
        "available",
        "저장한 자료와 해시 일치 · 사실 여부의 증명은 아님",
    )

    def finite(value):
        try:
            return type(value) in (float, int) and math.isfinite(value)
        except OverflowError:
            return False

    captured = data.get("capture_started_at")
    timed = finite(captured) and captured > 0
    add(
        "observation_time",
        "관측 시각",
        "available" if timed else "missing",
        (
            "원본에 관측 시작 시각 있음 · 시계 정확도 미확인"
            if timed
            else "관측 시작 시각 없음 · 서버 저장 시각과 구분"
        ),
    )
    location = data.get("location")
    location = location if isinstance(location, dict) else {}
    lat, lon, accuracy = (location.get(key) for key in ("lat", "lon", "accuracy_m"))
    located = (
        finite(lat)
        and -90 <= lat <= 90
        and finite(lon)
        and -180 <= lon <= 180
        and finite(accuracy)
        and accuracy >= 0
    )
    add(
        "location",
        "관측 위치",
        "available" if located else "missing",
        (
            f"좌표와 오차 {accuracy:g} m 기록 · 현장 위치 확인은 별도"
            if located
            else "좌표 또는 위치 오차 확인 필요"
        ),
    )
    context = data.get("context")
    context = context if isinstance(context, dict) else {}
    own = context.get("own_vehicle")
    own = own if isinstance(own, dict) else {}
    identified = all(
        isinstance(own.get(key), str) and own[key].strip() for key in ("plate", "model")
    )
    synthetic = (
        event.get("source") == "synthetic"
        or context.get("source") == "scenario_fixture"
    )
    add(
        "vehicle",
        "차량 식별",
        (
            "test"
            if identified and synthetic
            else "needs_review" if identified else "missing"
        ),
        (
            "가상 번호판·차종 설정 · 영상 인식 결과 아님"
            if identified and synthetic
            else (
                "차량 정보 있음 · 소유 관계와 영상 대조 필요"
                if identified
                else "번호판 또는 차종 정보 없음"
            )
        ),
    )
    video = event.get("video")
    state = "missing"
    detail = "현장 영상 미취득"
    if isinstance(video, dict):
        if video.get("integrity_valid") is not True:
            state, detail = "invalid", "영상 원본 확인 실패"
        elif (
            synthetic
            or video.get("source") == "synthetic_test_pattern"
            or video.get("camera_evidence") is not True
        ):
            state, detail = "test", "시험 영상 · 현장 촬영 자료로 세지 않음"
        else:
            state, detail = (
                "needs_review",
                "영상 해시 일치 · 촬영 시각·범위·내용 대조 필요",
            )
    add("video", "현장 영상", state, detail)
    if kind == "pothole":
        comparison = event.get("road_followup_case") or {}
        stage = comparison.get("stage")
        add(
            "followup",
            "이후 관측",
            (
                "available"
                if stage in ("repeat_signal", "no_repeat_signal")
                else "invalid" if stage == "comparison_invalid" else "missing"
            ),
            (
                "조건 비교 자료 있음 · 도로 보수·안전 확인은 별도"
                if stage in ("repeat_signal", "no_repeat_signal")
                else "비교할 관측 자료 확인 필요"
            ),
        )
    else:
        add(
            "responsibility",
            "접촉과 책임",
            "needs_review",
            "차량 위치·충격 신호만으로 접촉 상대와 책임을 확정할 수 없음",
        )
    return dict(
        result,
        state="review_required",
        attention_count=sum(item["state"] != "available" for item in result["items"]),
    )
