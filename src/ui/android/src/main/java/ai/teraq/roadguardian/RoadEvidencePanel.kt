package ai.teraq.roadguardian

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** 내부 완결성 검토 화면. OEM 도로 경고, 기관 수용과는 별개다 */
object RoadEvidencePanel {
    fun render(ui: PhoneAccountUi, body: LinearLayout, event: JSONObject) {
        if (event.optJSONObject("assessment")?.optString("kind") != "pothole") return
        val automotive =
            body.context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)
        val scale = if (automotive) (body.resources.configuration.screenWidthDp / 1000f).coerceIn(
            1f,
            1.65f
        ) else 1f

        fun text(value: String, size: Float = 18f, bold: Boolean = false) =
            ui.text(value, size * scale, bold)

        fun notice(value: String) = ui.notice(value).apply { textSize = 19f * scale }
        fun dp(value: Int) = ui.dp(kotlin.math.round(value * scale).toInt())
        body.addView(text("도로 사건 근거", 26f, true))
        val review = event.optJSONObject("road_evidence_review")
        if (review == null || review.optString("schema") != "road-guardian.road-review.v1") {
            body.addView(notice("검토 자료를 받지 못했습니다. 서버에서 사건을 다시 확인하세요."))
            return
        }
        if (!event.optBoolean("evidence_valid") || review.optString("stage") == "invalid") {
            body.addView(notice("원본 확인 실패 · 위치와 제출 자료의 판단을 보류합니다."))
            return
        }
        val synthetic = review.optString("source") == "synthetic"
        val declined = review.optString("stage") == "dismissed"
        body.addView(
            text(
                if (declined) "운전자가 해당 상황이 아니라고 확인한 기록" else if (synthetic) "합성 자료 검토 · 실제 신고 자료 아님" else "자료의 유무와 확인이 필요한 항목",
                18f,
                true
            )
        )
        val checked = review.optInt("checked_count", 0)
        val total = review.optInt("item_count", 6)
        val steps = listOf(
            CaseFlowGraphic.Step("원본", "해시 일치", CaseFlowGraphic.DONE),
            CaseFlowGraphic.Step(
                "항목 확인",
                "$checked / $total",
                if (declined) CaseFlowGraphic.BLOCKED else CaseFlowGraphic.ACTIVE
            ),
            CaseFlowGraphic.Step("기관 제출", "미연결", CaseFlowGraphic.WAITING)
        )
        if (body.resources.configuration.fontScale >= 1.3f) steps.forEach {
            body.addView(
                text(
                    "${it.label} · ${it.status}",
                    20f
                )
            )
        }
        else body.addView(
            CaseFlowGraphic(body.context, steps, ui.light, scale),
            LinearLayout.LayoutParams(-1, dp(118))
        )
        val items = review.optJSONArray("items")
        val wide =
            body.resources.configuration.screenWidthDp >= 760 && body.resources.configuration.fontScale < 1.3f
        val columns = if (wide) 2 else 1
        for (start in 0 until (items?.length() ?: 0) step columns) {
            val row = LinearLayout(body.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            for (offset in 0 until columns) {
                val item = items?.optJSONObject(start + offset)
                if (item == null) {
                    row.addView(
                        android.view.View(body.context),
                        LinearLayout.LayoutParams(0, 1, 1f)
                    )
                    continue
                }
                val state = item.optString("state")
                val done = state in setOf("present", "confirmed")
                val accent = Color.parseColor(
                    if (done) {
                        if (ui.light) "#246BD1" else "#8EB8FF"
                    } else {
                        if (ui.light) "#805000" else "#FFC777"
                    }
                )
                val tile = LinearLayout(body.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    setPadding(0, dp(10), dp(18), dp(10))
                }
                val icon = TextView(body.context).apply {
                    text = when (state) {
                        "present", "confirmed" -> "✓"
                        "synthetic" -> "시험"
                        "rejected" -> "×"
                        "invalid" -> "!"
                        else -> "?"
                    }
                    textSize = (if (state == "synthetic") 12f else 20f) * scale
                    gravity = Gravity.CENTER
                    setTextColor(accent)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(1), accent)
                    }
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                tile.addView(
                    icon,
                    LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
                val text = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
                text.addView(text(item.optString("label"), 19f, true))
                text.addView(text(item.optString("detail"), 16f))
                tile.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f))
            }
            body.addView(row)
        }
        body.addView(text("내부 자료 검토 기준입니다. 기관 제출 요건·증거 효력·접수 완료를 뜻하지 않습니다.", 16f))
        if (review.optString("submission_status") == "mock_acknowledged") body.addView(
            text(
                "모의 수신처 기록 ${
                    review.optString(
                        "mock_receipt"
                    )
                } · 실제 기관 접수 아님", 17f, true
            )
        )
        else if (review.optString("submission_status") == "mock_retry") body.addView(
            text(
                "모의 전달 실패 기록 · 실제 기관 전송 없음",
                17f
            )
        )
    }
}
