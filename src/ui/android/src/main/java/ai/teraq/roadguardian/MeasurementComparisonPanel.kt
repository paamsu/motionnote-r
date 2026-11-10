package ai.teraq.roadguardian

import android.widget.LinearLayout
import android.view.View
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 확인된 비교 결과 공통 화면. 인증, 선택, 주행 제한은 화면을 띄운 쪽이 맡는다 */
object MeasurementComparisonPanel {
    fun recordLabel(run: MeasurementProtocol.Summary, position: Int): String {
        val local =
            Instant.ofEpochSecond(run.started.toLong()).atZone(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy.M.d HH:mm:ss z", Locale.KOREAN))
        return "기록 $position · $local\n${circuit(run.channel)} · 표본 ${run.count}개 · ${source(run.environment)}"
    }

    fun date(value: Double) = Instant.ofEpochSecond(value.toLong()).atOffset(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss 'UTC'"))

    fun source(value: String) = if (value == "synthetic") "합성 측정 시험" else "외부 입력 · 실측 증명 아님"
    fun circuit(value: String) = if (value == "auxiliary_12v") "보조 12V" else "고전압 팩"
    fun render(
        ui: PhoneAccountUi,
        body: LinearLayout,
        comparison: MeasurementProtocol.Comparison,
        scale: Float = 1f
    ) {
        fun dp(n: Int) = ui.dp(kotlin.math.round(n * scale).toInt())
        fun text(value: String, size: Float = 18f, bold: Boolean = false) =
            ui.text(value, size * scale, bold)

        val summary = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        summary.addView(
            text(
                if (comparison.matched) "보고된 조건이 대응합니다" else "지금은 비교를 보류합니다",
                24f,
                true
            )
        )
        summary.addView(
            text(
                source(comparison.before.summary.environment) + " · " + circuit(
                    comparison.before.summary.channel
                ), 16f
            )
        )
        summary.addView(
            text(
                "앞 · ${date(comparison.before.summary.started)}\n뒤 · ${date(comparison.after.summary.started)}",
                16f
            )
        )
        if (comparison.matched) {
            val metrics = comparison.raw.getJSONObject("metrics")
            fun n(key: String) = metrics.getDouble(key)
            val before = n("before_median_v")
            val after = n("after_median_v")
            summary.addView(
                text(
                    String.format(
                        Locale.KOREAN,
                        "대응 전압 차이 %+.3f V",
                        n("paired_delta_median_v")
                    ), 28f, true
                )
            )
            summary.addView(
                text(
                    "대응 ${metrics.getInt("matched_samples")}개 · 앞 ${(n("before_coverage") * 100).toInt()}% / 뒤 ${
                        (n(
                            "after_coverage"
                        ) * 100).toInt()
                    }%", 17f
                )
            )
            val large = body.resources.configuration.fontScale >= 1.3f
            val chart: View = if (large) text(
                String.format(
                    Locale.KOREAN,
                    "앞 중앙값 %.3f V\n뒤 중앙값 %.3f V",
                    before,
                    after
                ), 22f, true
            )
            else MeasurementComparisonGraphic(body.context, before, after, ui.light, scale)
            val row = LinearLayout(body.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ui.cardColor)
                    cornerRadius = dp(14).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            }
            row.addView(summary)
            row.addView(chart)
            body.addView(row)
            var lastWide: Boolean? = null
            fun arrange(width: Int) {
                val wide = width / body.resources.displayMetrics.density >= 760 && !large
                if (lastWide == wide) return
                lastWide = wide
                row.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                summary.layoutParams = if (wide) LinearLayout.LayoutParams(0, -2, 1f)
                    .apply { marginEnd = dp(24) } else LinearLayout.LayoutParams(-1, -2)
                chart.layoutParams = if (wide) LinearLayout.LayoutParams(
                    0,
                    dp(240),
                    1.1f
                ) else LinearLayout.LayoutParams(-1, if (large) -2 else dp(220))
            }
            arrange(body.width)
            row.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                if (right > left) arrange(
                    right - left
                )
            }
        } else {
            body.addView(summary)
            val reasons = comparison.raw.getJSONArray("reasons")
            val labels = mapOf(
                "distinct_runs_required" to "서로 다른 두 기록이 필요합니다.",
                "environment_mismatch" to "합성·외부 입력의 출처가 다릅니다.",
                "channel_mismatch" to "측정한 전압 회로가 다릅니다.",
                "instrument_id_mismatch" to "측정기 참조가 다릅니다.",
                "sample_period_ms_mismatch" to "명목 표본 주기가 다릅니다.",
                "calibration_reference_missing" to "교정 참조가 없습니다.",
                "calibration_reference_mismatch" to "교정 참조가 다릅니다.",
                "nonoverlapping_reported_chronology_required" to "앞·뒤 시각이 겹치거나 순서가 맞지 않습니다.",
                "minimum_observation_window_required" to "표본 수 또는 관측 길이가 부족합니다.",
                "reported_sample_spacing_outside_10_percent" to "표본 시각 간격을 다시 확인해야 합니다.",
                "matched_samples_insufficient" to "조건이 대응하는 표본이 부족합니다.",
                "whole_run_coverage_below_80_percent" to "전체 기록의 대응 비율이 80%보다 낮습니다."
            )
            for (i in 0 until reasons.length()) body.addView(
                ui.notice(
                    labels[reasons.getString(i)] ?: "측정 조건의 추가 확인이 필요합니다."
                )
            )
        }
        body.addView(text("실제 조건·교정·수리 성공·고장 원인 미확인", 16f))
        body.addView(text("개발용 비교 기준 · OEM 정비 기준 아님", 16f, true))
        val details = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        details.addView(
            text(
                "허용 차이: 속도 0.5 km/h · 회전수 100 rpm · 배터리 온도 2°C · 주변 온도 3°C · SOC 2%p · 전류 2A\n최소 표본 20개 · 관측 10초 · 전체 대응 80% · 시각 간격 10% 이내",
                16f
            )
        )
        for ((name, receipt) in listOf("앞" to comparison.before, "뒤" to comparison.after)) {
            val data = receipt.data
            details.addView(
                text(
                    "$name 측정기 · ${data.getString("instrument_id")}\n교정 참조 · ${
                        if (data.isNull(
                                "calibration_ref"
                            )
                        ) "미기재" else data.getString("calibration_ref")
                    }\n보관 해시 · ${receipt.summary.hash.take(16)}…", 16f
                )
            )
        }
        ui.disclosure(body, "비교 기준과 입력 출처", details)
    }
}
