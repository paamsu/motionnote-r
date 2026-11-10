package ai.teraq.roadguardian

import android.widget.LinearLayout
import org.json.JSONObject

/** 보험 처리 상태는 서버가 확인한 접수 기록에서 가져온다 */
object CrashCasePanel {
    fun render(ui: PhoneAccountUi, body: LinearLayout, event: JSONObject) {
        if (event.optJSONObject("assessment")?.optString("kind") != "crash") return
        body.addView(ui.text("사고 증거와 보험 처리", 26f, true))
        if (!event.optBoolean("evidence_valid")) {
            body.addView(ui.notice("센서 원본을 확인하지 못했습니다. 접수 내용과 차량 정보는 원본 확인 후 검토하세요."))
            return
        }
        val data = event.optJSONObject("data")
        val samples = data?.optJSONArray("samples")?.length() ?: 0
        val video = event.optJSONObject("video")
        val case = event.optJSONObject("insurance_case")
        val state = case?.optString("status") ?: "not_configured"
        val explanation = when (state) {
            "acknowledged_mock" -> "시험 보험사 모의 접수 확인 · 실제 보험사 접수 아님"
            "ready" -> "모의 접수 준비됨 · 아직 전송하지 않았습니다"
            "retry_pending" -> "모의 전달 실패 · 같은 요청으로 재시도 대기"
            "awaiting_receipt" -> "수신처의 접수 확인을 기다리고 있습니다"
            "payload_invalid" -> "접수 자료 원본 확인 실패 · 접수 번호를 표시하지 않습니다"
            "evidence_invalid" -> "센서 원본 확인 실패 · 접수 자료 확인 필요"
            "review_required" -> when (case?.optString("reason")) {
                "ownership_unverified" -> "내 차량 소유 관계 확인 필요 · 보험 접수 대기"
                "policy_missing_or_expired" -> "보험 계약이 없거나 만료됐습니다 · 계약 확인 필요"
                "channel_unavailable" -> "보험사 연결 채널 확인 필요"
                "policy_changed" -> "접수 시도 후 차량·보험 계약 정보가 바뀌었습니다 · 자료 재검토 필요"
                else -> "보험 정보 확인 필요 · 접수 대기"
            }

            else -> "보험사 서비스 연결 전 · 자동 접수되지 않았습니다"
        }
        val accepted = EventBriefState.from(event).title == "보험 접수 완료"
        val reviewed =
            state in setOf("ready", "retry_pending", "awaiting_receipt", "acknowledged_mock")
        body.addView(
            ui.text(
                when (state) {
                    "acknowledged_mock" -> if (accepted) "보험 접수 완료" else "접수 기록 확인 필요"
                    "ready" -> "접수 자료 준비됨"
                    "retry_pending" -> "재전달 대기"
                    "review_required" -> "차량·보험 확인 필요"
                    "payload_invalid" -> "접수 자료 확인 실패"
                    else -> "보험 연결 상태 확인 필요"
                }, 21f, true
            )
        )
        val steps = listOf(
            CaseFlowGraphic.Step(
                "센서",
                if (event.optBoolean("evidence_valid")) "원본 확인" else "확인 실패",
                if (event.optBoolean("evidence_valid")) 1 else 3
            ),
            CaseFlowGraphic.Step("계약", if (reviewed) "계약 확인" else "확인 필요", if (reviewed) 1 else 3),
            CaseFlowGraphic.Step(
                "보험 접수",
                if (accepted) "접수 번호 있음" else if (state == "retry_pending") "재전달 대기" else "대기",
                if (accepted) 1 else if (state == "retry_pending") 3 else 0
            )
        )
        val wide =
            body.resources.configuration.screenWidthDp >= 760 && body.resources.configuration.fontScale < 1.3f
        val graphics = LinearLayout(body.context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val flow = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        flow.addView(
            CaseFlowGraphic(body.context, steps, ui.light),
            LinearLayout.LayoutParams(-1, ui.dp(118))
        )
        flow.addView(ui.text("센서 $samples 개 · ${data?.optInt("sample_hz", 0) ?: 0} Hz", 18f, true))
        flow.addView(
            ui.text(
                if (video == null) "영상 미취득" else if (video.optBoolean("integrity_valid")) "영상 원본 확인" else "영상 확인 실패",
                17f
            )
        )
        graphics.addView(
            flow,
            if (wide) LinearLayout.LayoutParams(0, -2, 1f)
                .apply { marginEnd = ui.dp(24) } else LinearLayout.LayoutParams(-1, -2))
        val impact =
            data?.optJSONObject("context")?.optJSONObject("impact")?.optString("area") ?: "미확인"
        val synthetic = event.optString("source") == "synthetic"
        if (body.resources.configuration.fontScale < 1.3f) graphics.addView(
            VehicleImpactGraphic(
                body.context,
                impact,
                synthetic,
                ui.light
            ),
            if (wide) LinearLayout.LayoutParams(0, ui.dp(180), 1.2f) else LinearLayout.LayoutParams(
                -1,
                ui.dp(180)
            )
        )
        else graphics.addView(ui.text("충격 위치 $impact · 접촉·책임 미확정", 21f))
        body.addView(graphics)
        if (accepted) body.addView(ui.text("접수 번호  ${case?.optString("receipt")}", 21f, true))
        if (case?.optBoolean("video_added_after_dispatch") == true)
            body.addView(ui.text("접수 후 추가한 영상 · 기존 접수에 미포함", 19f, true))
        val details = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        details.addView(
            ui.text(
                if (synthetic) "합성 사례 · 보험사 모의 처리 · 실제 접수 아님" else "기관·보험사 접수 상태는 별도 확인이 필요합니다",
                15f
            )
        )
        details.addView(ui.notice(explanation))
        if (state in setOf("ready", "retry_pending", "awaiting_receipt", "acknowledged_mock")) {
            fun supplied(key: String) =
                case?.optString(key)?.takeIf { it.isNotBlank() && it != "null" } ?: "미확인"
            details.addView(
                ui.text(
                    "보험사  ${supplied("provider_name")}\n계약  ${supplied("policy_id")}",
                    19f
                )
            )
            if (case?.optBoolean("video_added_after_dispatch") == true)
                details.addView(ui.notice("이 영상은 접수 자료를 확정한 뒤 추가됐습니다. 기존 접수에 포함된 영상으로 보지 마세요."))
            if (case?.optString("submitted_video_status") == "not_captured")
                details.addView(ui.text("접수 당시 자료: 센서 원본 · 영상 미포함", 17f))
        }
        details.addView(ui.text("긴급 구조 요청은 보험 계약 확인과 별도로 진행해야 합니다.", 17f))
        ui.disclosure(body, "보험·접수 자료 상세", details)
    }
}
