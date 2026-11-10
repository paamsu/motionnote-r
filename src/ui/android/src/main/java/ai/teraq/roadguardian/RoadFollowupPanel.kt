package ai.teraq.roadguardian

import android.content.pm.PackageManager
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** 반복 관측을 보여 준다. 도로가 고쳐졌거나 안전하다고 판단하지 않는다 */
object RoadFollowupPanel {
    fun render(
        ui: PhoneAccountUi,
        body: LinearLayout,
        event: JSONObject,
        compactWaiting: Boolean = false,
        detailTarget: LinearLayout? = null,
        client: BackendClient? = null,
        allowed: () -> Boolean = { false }
    ) {
        if (event.optJSONObject("assessment")?.optString("kind") != "pothole") return
        val case = event.optJSONObject("road_followup_case") ?: return
        val stage = case.optString("stage")
        val scale =
            if (body.context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) (body.resources.configuration.screenWidthDp / 1000f).coerceIn(
                1f,
                1.65f
            ) else 1f

        fun dp(n: Int) = ui.dp(kotlin.math.round(n * scale).toInt())
        fun text(value: String, size: Float = 18f, bold: Boolean = false) =
            ui.text(value, size * scale, bold)
        if (!(stage == "awaiting_observation" && compactWaiting)) body.addView(
            text(
                "도로 전후 비교",
                26f,
                true
            )
        )
        if (!event.optBoolean("evidence_valid") || stage == "comparison_invalid") {
            body.addView(text("원본 또는 비교 조건 확인 실패 · 이전 결과로 대신하지 않습니다", 20f, true))
            return
        }
        if (stage == "awaiting_observation") {
            if (compactWaiting) {
                body.addView(text("이후 자료 없음 · 보수 확인 전", 20f, true))
                val details =
                    LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
                details.addView(text("같은 위치·방향·측정 조건의 이후 관측이 필요합니다", 18f))
                details.addView(text("최초 기록 보관됨 · 이후 관측 대기 · 보수 미확인", 18f))
                if (detailTarget != null) detailTarget.addView(details) else ui.disclosure(
                    body,
                    "이후 관측 조건",
                    details
                )
                return
            }
            val steps = listOf(
                CaseFlowGraphic.Step("최초 기록", "보관됨", CaseFlowGraphic.DONE),
                CaseFlowGraphic.Step("이후 관측", "자료 대기", CaseFlowGraphic.WAITING),
                CaseFlowGraphic.Step("보수 확인", "미확인", CaseFlowGraphic.WAITING)
            )
            if (body.resources.configuration.fontScale >= 1.3f)
                steps.forEach { body.addView(text("${it.label} · ${it.status}", 20f)) }
            else body.addView(
                CaseFlowGraphic(body.context, steps, ui.light, scale),
                LinearLayout.LayoutParams(-1, dp(118))
            )
            body.addView(text("같은 위치·방향·측정 조건의 이후 관측이 필요합니다", 20f, true))
            body.addView(text("이후 자료 없음 · 비교 수치와 보수 여부는 미확인", 17f))
            return
        }
        if (stage !in setOf("repeat_signal", "no_repeat_signal")) return
        val before = case.optJSONObject("before")
        val after = case.optJSONObject("after")
        val first = before?.optDouble("peak_g", Double.NaN) ?: Double.NaN
        val second = after?.optDouble("peak_g", Double.NaN) ?: Double.NaN
        if (!first.isFinite() || !second.isFinite() || first < 0 || second < 0) {
            body.addView(text("비교 수치 확인 필요", 20f))
            return
        }
        val resultLabel = if (stage == "repeat_signal") "충격이 다시 기록됐습니다" else "이번 관측에서는 충격이 줄었습니다"
        val result = text(if (client != null) "비교 기록 확인 중" else resultLabel, 21f, true)
        body.addView(result)
        val verifiedDetails = LinearLayout(body.context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (client != null) android.view.View.GONE else android.view.View.VISIBLE
        }
        if (client != null) body.addView(
            RoadComparisonPhotos(
                body.context,
                ui,
                event,
                client,
                allowed
            ) { valid ->
                result.text = when (valid) {
                    true -> resultLabel
                    false -> "비교 기록 확인 필요"
                    null -> "비교 기록 확인 중"
                }
                verifiedDetails.visibility =
                    if (valid == true) android.view.View.VISIBLE else android.view.View.GONE
            })
        val row = LinearLayout(body.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ui.cardColor)
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }
        val flow = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        val steps = listOf(
            CaseFlowGraphic.Step("최초 기록", "원본 확인", 1),
            CaseFlowGraphic.Step("이후 관측", "조건 비교", 1),
            CaseFlowGraphic.Step("보수 확인", "미확인", 0)
        )
        if (body.resources.configuration.fontScale >= 1.3f) steps.forEach {
            flow.addView(
                text(
                    "${it.label} · ${it.status}",
                    20f
                )
            )
        }
        else flow.addView(
            CaseFlowGraphic(body.context, steps, ui.light, scale),
            LinearLayout.LayoutParams(-1, dp(118))
        )
        val conditions = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        conditions.addView(text("개발 비교 조건 충족 · 연결 ${case.optInt("link_count")}건", 17f))
        conditions.addView(text("차로·노면·센서 고정 상태는 미확인", 16f))
        val bars = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        val upper = max(3.0, ceil(max(first, second)))
        if (body.resources.configuration.fontScale >= 1.3f) {
            for ((label, value) in listOf("최초 관측" to first, "이후 관측" to second))
                bars.addView(
                    text(
                        "$label  ${String.format(Locale.KOREAN, "%.3f g", value)}",
                        24f,
                        true
                    )
                )
        } else bars.addView(
            PairedObservationGraphic(
                body.context, first, second, ui.light, scale, "g", 3, "최초 관측", "이후 관측", upper,
                "최초 관측 ${String.format(Locale.KOREAN, "%.3f", first)} g, 이후 관측 ${
                    String.format(
                        Locale.KOREAN,
                        "%.3f",
                        second
                    )
                } g. 중력 제거 수직 신호 최댓값. 도로 보수나 안전 확인이 아닙니다."
            ), LinearLayout.LayoutParams(-1, dp(240))
        )
        bars.addView(text("축 0–${upper.toInt()} g · 중력 제거 수직 신호의 최댓값", 15f))
        row.addView(flow)
        row.addView(bars)
        conditions.addView(row, 0)
        var lastWide: Boolean? = null
        fun arrange(width: Int) {
            val actualWide =
                width / body.resources.displayMetrics.density >= 760 && body.resources.configuration.fontScale < 1.3f
            if (lastWide == actualWide) return
            lastWide = actualWide
            row.orientation = if (actualWide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            flow.layoutParams = if (actualWide) LinearLayout.LayoutParams(0, -2, 1.15f)
                .apply { marginEnd = dp(24) } else LinearLayout.LayoutParams(-1, -2)
            bars.layoutParams =
                if (actualWide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(
                    -1,
                    -2
                )
        }
        arrange(body.width)
        row.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            if (right > left) arrange(
                right - left
            )
        }
        conditions.addView(text("도로 보수·안전 미확인", 17f, true))
        conditions.addView(
            text(
                "이번 관측 구간의 중력 제거 수직 신호를 비교했습니다. 같은 기준 충격이 없더라도 보수 완료나 도로 안전을 뜻하지 않습니다.",
                17f
            )
        )
        if (detailTarget != null) detailTarget.addView(conditions) else {
            ui.disclosure(verifiedDetails, "관측 수치와 조건", conditions)
            body.addView(verifiedDetails)
        }
    }
}
