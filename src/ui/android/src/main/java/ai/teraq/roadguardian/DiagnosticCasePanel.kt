package ai.teraq.roadguardian

import android.widget.LinearLayout
import org.json.JSONObject

/** 근거 중심의 진단·수리 요약. 전압 변화만으로 수리 완료로 보지 않는다 */
object DiagnosticCasePanel {
    fun render(ui: PhoneAccountUi, body: LinearLayout, event: JSONObject) {
        if (event.optJSONObject("assessment")?.optString("kind") != "diagnosis") return
        val scale =
            if (body.context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) (body.resources.configuration.screenWidthDp / 1000f).coerceIn(
                1f,
                1.65f
            ) else 1f

        fun text(value: String, size: Float = 18f, bold: Boolean = false) =
            ui.text(value, size * scale, bold)

        fun dp(value: Int) = ui.dp(kotlin.math.round(value * scale).toInt())
        body.addView(text("재측정 결과", 26f, true))
        val case = DiagnosticEvidenceReview.checkedCase(event)
        if (case == null) {
            body.addView(text("측정 자료 확인 실패 · 판단 보류", 21f, true))
            body.addView(text("원본과 비교 조건을 확인하기 전에는 측정값을 표시하지 않습니다", 17f))
            return
        }
        val stage = case?.optString("stage") ?: "unavailable"
        val invalid = stage in setOf("evidence_invalid", "unavailable")
        val measured = stage in setOf("observed_signal_recovered", "follow_up_required")
        val center = case?.optJSONObject("center")
        val label = when (stage) {
            "observed_signal_recovered" -> "재측정 전압 회복"
            "follow_up_required" -> "저전압 지속 · 서비스센터 방문 추천"
            "center_accepted_mock" -> "센터 접수 · 현재 측정 대기"
            "warranty_check_required" -> "보증 확인 대기"
            "no_capacity" -> "센터 작업 여력 확인 필요"
            "follow_up_invalid", "evidence_invalid" -> "자료 확인 실패 · 판단 보류"
            "measurement_review" -> "점검 근거 준비됨"
            else -> "진단 연결 상태 확인 필요"
        }
        body.addView(text(label, 21f, true))
        val steps = listOf(
            CaseFlowGraphic.Step("이전 기록", if (invalid) "확인 필요" else "원본 보관", if (invalid) 3 else 1),
            CaseFlowGraphic.Step(
                if (center != null) "센터 접수" else "기록 보관",
                if (center != null) "접수 기록" else if (stage == "warranty_check_required") "보증 확인" else if (stage == "no_capacity") "여력 확인" else "원본 유지",
                if (stage in setOf("warranty_check_required", "no_capacity")) 3 else 1
            ),
            CaseFlowGraphic.Step(
                "현재 기록",
                if (measured) "전압 비교" else if (stage == "follow_up_invalid") "확인 실패" else "대기",
                if (measured) 1 else if (stage == "follow_up_invalid") 3 else 0
            )
        )
        val before = if (invalid) null else case?.optJSONObject("before")
        fun value(metrics: JSONObject?, key: String) =
            metrics?.optDouble(key, Double.NaN)?.takeIf { it.isFinite() }

        val large = body.resources.configuration.fontScale >= 1.3f
        val wide = body.resources.configuration.screenWidthDp >= if (large) 1100 else 760
        val graphicRow = LinearLayout(body.context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val flow = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        flow.addView(
            CaseFlowGraphic(body.context, steps, ui.light, scale),
            LinearLayout.LayoutParams(-1, dp(if (large) 138 else 118))
        )
        val conditionsPanel =
            LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        val own =
            event.optJSONObject("data")?.optJSONObject("context")?.optJSONObject("own_vehicle")
        conditionsPanel.addView(
            text(
                listOfNotNull(
                    own?.optString("plate")?.takeIf { it.isNotBlank() },
                    own?.optString("model")?.takeIf { it.isNotBlank() }).joinToString(" · ")
                    .ifBlank { "차량 식별 정보 미확인" }, 18f, true
            )
        )
        val baseline = event.optJSONObject("data")?.optJSONObject("baseline")
        val rpm = baseline?.optDouble("rpm", Double.NaN)
        val temperature = baseline?.optDouble("temperature_c", Double.NaN)
        if (rpm != null && temperature != null && rpm.isFinite() && temperature.isFinite())
            conditionsPanel.addView(text("비교 기준 ${rpm.toInt()} rpm · $temperature °C", 16f))
        conditionsPanel.addView(text("조건 일치 ${before?.optInt("matched_samples", 0) ?: 0}개 표본", 16f))
        case?.optJSONObject("comparison_conditions")?.let { conditions ->
            conditionsPanel.addView(
                text(
                    "구간 속도 ${conditions.optDouble("before_speed_kph")} → ${
                        conditions.optDouble(
                            "after_speed_kph"
                        )
                    } km/h", 16f
                )
            )
        }
        val chart = VoltageComparisonGraphic(
            body.context,
            value(before, "voltage_median_v"),
            value(before, "baseline_v"),
            if (measured) value(case?.optJSONObject("after"), "voltage_median_v") else null,
            stage == "observed_signal_recovered",
            ui.light,
            scale
        )
        val chartHeight = dp(if (large) 240 else 210)
        graphicRow.addView(
            flow,
            if (wide) LinearLayout.LayoutParams(0, -2, 1f)
                .apply { marginEnd = dp(24) } else LinearLayout.LayoutParams(-1, -2))
        graphicRow.addView(
            chart,
            if (wide) LinearLayout.LayoutParams(
                0,
                chartHeight,
                1.2f
            ) else LinearLayout.LayoutParams(-1, chartHeight)
        )
        body.addView(graphicRow)
        conditionsPanel.addView(text("합성 전압 시험 · 전체 고장 해결을 뜻하지 않습니다", 15f))
        if (measured) conditionsPanel.addView(text("전기 부하·충전량·센서 교정·관측 순서는 미확인", 15f))
        if (invalid) return
        val reasons = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        reasons.addView(conditionsPanel)
        center?.optString("name")?.let { reasons.addView(text("모의 센터 접수 기록 · $it", 18f, true)) }
        reasons.addView(text("합성 전압 비교 시험입니다. 실차 종합 진단이나 부품 교환 판단이 아닙니다.", 17f))
        val diagnostic = event.optJSONObject("assessment")?.optJSONObject("diagnostic")
        val candidates = diagnostic?.optJSONArray("candidates")
        if (candidates != null) for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            reasons.addView(text(candidate.optString("component"), 22f, true))
            fun details(key: String, label: String) {
                val values = candidate.optJSONArray(key) ?: return
                reasons.addView(text("$label\n" + (0 until values.length()).joinToString("\n") {
                    values.optString(
                        it
                    )
                }, 18f))
            }
            details("support", "측정 근거")
            details("counterevidence", "원인을 확정할 수 없는 이유")
            details("next_tests", "추가 검사")
        }
        ui.disclosure(body, "비교 조건·점검 근거", reasons)
    }
}
