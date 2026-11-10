package ai.teraq.roadguardian

import android.widget.LinearLayout
import org.json.JSONObject

/** 작성된 상대 정보를 보여 준다. 과실이나 이미지 인식 결과를 단정하지 않는다 */
object ParkingIncidentSummary {
    fun add(ui: PhoneAccountUi, body: LinearLayout, event: JSONObject) {
        if (event.optString("source") != "synthetic" || event.opt("evidence_valid") != true) return
        val actors =
            event.optJSONObject("data")?.optJSONObject("context")?.optJSONArray("actors") ?: return
        val actor = actors.optJSONObject(0) ?: return
        val label = listOf("plate", "model").mapNotNull {
            actor.optString(it).takeIf { v -> v.isNotBlank() && v != "null" }
        }.joinToString(" · ")
        if (label.isNotBlank()) body.addView(ui.text(label, 24f, true))
        val deliveries = event.optJSONArray("dispatches")
        if (deliveries != null && (0 until deliveries.length()).any {
                val d = deliveries.optJSONObject(it)
                d?.optString("recipient") == "owner-app-mock" && d.optString("status") == "acknowledged" && d.optString(
                    "receipt"
                ).startsWith("MOCK-")
            }) body.addView(ui.text("사용자에게 기록 전달 완료", 22f))
    }
}
