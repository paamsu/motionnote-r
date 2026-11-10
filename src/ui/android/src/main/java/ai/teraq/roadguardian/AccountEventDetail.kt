package ai.teraq.roadguardian

import android.widget.LinearLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 받은 근거와 불확실한 점을 그대로 보여 준다. 책임이나 인식 결과는 추정하지 않는다 */
object AccountEventDetail {
    private fun value(obj: JSONObject?, key: String): String =
        obj?.optString(key)?.takeIf { it.isNotBlank() && it != "null" } ?: "미확인"

    fun render(ui: PhoneAccountUi, body: LinearLayout, event: JSONObject) {
        body.removeAllViews()
        EventVisualBrief.render(ui, body, event)
        val details = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        renderDetails(ui, details, event)
        ui.disclosure(body, "상세 기록", details)
    }

    private fun renderDetails(ui: PhoneAccountUi, body: LinearLayout, event: JSONObject) {
        if (OwnerCasePanel.render(ui, body, event)) return
        if (TrafficCasePanel.render(ui, body, event)) return
        val context = event.optJSONObject("data")?.optJSONObject("context")
        val timestamp = event.optDouble("created", Double.NaN)
        val date = if (timestamp.isFinite() && timestamp > 0) SimpleDateFormat(
            "M월 d일 HH:mm",
            Locale.KOREAN
        ).format(Date((timestamp * 1000).toLong())) else "기록 시각 미확인"
        val evidenceKind = event.optJSONObject("assessment")?.optString("kind")
        if (evidenceKind in setOf("pothole", "crash")) {
            ui.evidenceHeading(body, if (evidenceKind == "crash") "충돌 근거 정리" else "도로 근거 정리")
            body.addView(ui.text("서버 저장 $date", 15f))
        } else ui.heading(
            body,
            if (VideoCollisionAnalysis.present(event)) EventBriefState.from(event).title else value(
                event.optJSONObject("assessment"),
                "title"
            ),
            date
        )
        val synthetic =
            event.optString("source") == "synthetic" || context?.optString("source") == "scenario_fixture"
        body.addView(
            ui.text(
                if (synthetic) "합성 시연 · 실제 차량 측정 자료 아님" else "서버 수신 기록 · 원본 확인 필요",
                15f
            )
        )
        if (evidenceKind in setOf("pothole", "crash") && !event.optBoolean("evidence_valid")) {
            body.addView(ui.notice("원본 확인 실패 · 차량과 상황 정보 판단을 보류합니다."))
            return
        }
        val graphic = event.optJSONObject("assessment")?.optString("kind") in setOf(
            "crash",
            "diagnosis",
            "emergency",
            "manual_help",
            "pothole"
        )
        val details = if (graphic) LinearLayout(body.context).apply {
            orientation = LinearLayout.VERTICAL
        } else body
        if (graphic) {
            EvidenceDossierPanel.render(ui, body, event, details)
            DiagnosticCasePanel.render(ui, body, event)
            EmergencyCasePanel.render(ui, body, event)
            RoadFollowupPanel.render(ui, body, event, compactWaiting = true, detailTarget = details)
        }
        fun row(label: String, value: String) {
            details.addView(ui.text(label, 16f, true))
            details.addView(ui.text(value, 21f))
        }
        if (VideoCollisionAnalysis.present(event)) {
            row("영상 접촉 판독", VideoCollisionAnalysis.detail(event))
            details.addView(ui.text("합성 영상 6시점 · 실제 충돌 확인 아님", 15f))
            details.addView(ui.text("접촉·시간 순서 오판 가능 · 센서 판단과 별도", 15f))
        }
        row("장소", value(context, "location_label"))
        val own = context?.optJSONObject("own_vehicle")
        row(
            "내 차량",
            listOf(
                value(own, "model"),
                value(own, "color"),
                value(own, "plate")
            ).joinToString(" · ")
        )
        if (evidenceKind in setOf(
                "crash",
                "parking",
                "pothole"
            ) || context?.optJSONObject("impact") != null
        )
            row("충격 위치", value(context?.optJSONObject("impact"), "area"))
        val actors = context?.optJSONArray("actors")
        details.addView(ui.text("주변 차량", 24f, true))
        if (actors == null || actors.length() == 0) details.addView(ui.text("확인된 주변 차량 정보가 없습니다."))
        else for (i in 0 until actors.length()) {
            val actor = actors.optJSONObject(i) ?: continue
            row(
                "차량 ${i + 1}",
                "${value(actor, "plate")}\n${value(actor, "model")} · ${
                    value(
                        actor,
                        "color"
                    )
                }\n${value(actor, "relationship")}"
            )
            if (actor.optString("recognition_status") == "not_run") details.addView(
                ui.text(
                    "영상 인식 미실행",
                    16f
                )
            )
        }
        val facts = context?.optJSONArray("facts")
        if (facts != null && facts.length() > 0) {
            details.addView(ui.text("기록된 상황", 24f, true))
            for (i in 0 until facts.length()) {
                val fact = facts.optJSONObject(i) ?: continue
                row(value(fact, "label"), value(fact, "value"))
            }
        }
        details.addView(ui.text("아직 확인하지 못한 내용", 24f, true))
        val unknowns = context?.optJSONArray("unknowns")
        if (unknowns == null || unknowns.length() == 0) details.addView(ui.text("추가 확인 사항이 제공되지 않았습니다. 접촉 여부와 책임을 확정하는 자료는 아닙니다."))
        else for (i in 0 until unknowns.length()) details.addView(ui.text(unknowns.optString(i)))
        if (graphic) ui.disclosure(body, "자료와 확인 사항", details)
    }
}
