package ai.teraq.roadguardian

import android.view.Gravity
import android.widget.LinearLayout
import org.json.JSONObject

/** 공모전 목격 사례(크기 제한). 사람을 분류하거나 응급 번호로 전화하지 않는다 */
object WitnessCasePanel {
    fun eligible(event: JSONObject): Boolean {
        val witness =
            event.optJSONObject("data")?.optJSONObject("context")?.optJSONObject("witness_help")
                ?: return false
        return event.optString("source") == "synthetic" && event.opt("evidence_valid") == true &&
                event.optString("status") !in setOf("dismissed", "input_review_required") &&
                event.optJSONObject("assessment")?.optString("kind") == "manual_help" &&
                event.optJSONObject("assessment")?.optString("recipient") == "119-mock" &&
                witness.optString("schema") == "road-guardian.witness-help.v1" &&
                witness.optString("subject") == "person_needs_help" && witness.optString("reporter") == "witness" &&
                witness.optString("source") == "authored_contest_scenario" && witness.opt("recognition_performed") == false &&
                witness.optString("recipient") == "119-mock"
    }

    fun automatic(event: JSONObject): Boolean {
        val witness =
            event.optJSONObject("data")?.optJSONObject("context")?.optJSONObject("witness_help")
                ?: return false
        return eligible(event) && witness.optString("dispatch_mode") == "automatic_contest" && witness.optString(
            "emergency_state"
        ) == "authored_emergency_patient"
    }

    fun render(
        ui: PhoneAccountUi, body: LinearLayout, event: JSONObject, client: BackendClient,
        allowed: () -> Boolean, onRequest: () -> Unit, onReturn: () -> Unit
    ): Boolean {
        if (!eligible(event)) return false
        val done = DriverEventNoticeState.helpDeliveryState(event) == "acknowledged"
        val wide =
            body.resources.configuration.screenWidthDp >= 1100 && body.resources.configuration.fontScale < 1.3f
        val row = LinearLayout(body.context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            isBaselineAligned = false
        }
        val stored = ui.text("현장 기록 확인 중", 30f, true)
        val photo = EventEvidencePhotoPanel(
            body.context,
            ui,
            event,
            client,
            allowed,
            fitPhotoAspect = true,
            externalStatus = stored
        )
        val info = LinearLayout(body.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        info.addView(ui.text(if (done) "119 접수 완료" else "도움이 필요한 사람", 44f, true))
        info.addView(stored)
        if (done) {
            info.addView(
                DriverEventGraphic(body.context, "record_saved", ui.light),
                LinearLayout.LayoutParams(-1, ui.dp(160))
            )
            info.addView(ui.text("위치와 현장 기록 전달 완료", 30f, true))
            info.addView(
                ui.button("기록 목록으로", true) { if (allowed()) onReturn() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(24) })
        } else info.addView(
            ui.button("119 도움 요청", true) { if (allowed()) onRequest() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(24) })
        EventLocationPanel.displayAddress(event)?.let { address ->
            info.addView(ui.text("현장 위치", 22f).apply { setPadding(0, ui.dp(32), 0, 0) })
            info.addView(ui.text(address, 26f))
        }
        if (wide) {
            row.addView(
                photo,
                LinearLayout.LayoutParams(0, -2, 1.5f).apply { marginEnd = ui.dp(32) })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            row.addView(info, LinearLayout.LayoutParams(-1, -2))
            row.addView(photo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(24) })
        }
        body.addView(row, LinearLayout.LayoutParams(-1, -2))
        return true
    }
}
