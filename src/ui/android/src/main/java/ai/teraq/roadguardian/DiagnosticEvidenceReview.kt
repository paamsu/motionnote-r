package ai.teraq.roadguardian

import android.widget.LinearLayout
import org.json.JSONObject
import kotlin.math.abs

/** 서버가 확인한 합성 측정 기록을 같은 기준으로 검사해 표시한다 */
object DiagnosticEvidenceReview {
    private val measured = setOf("observed_signal_recovered", "follow_up_required")
    private fun number(row: JSONObject, key: String): Double {
        val value = row.get(key)
        require(value is Number && value.toDouble().isFinite())
        return value.toDouble()
    }

    private fun metrics(row: JSONObject) {
        val voltage = number(row, "voltage_median_v")
        val baseline = number(row, "baseline_v")
        val delta = number(row, "delta_v")
        require(voltage >= 0 && baseline > 0 && abs(voltage - baseline - delta) <= 0.002)
        val count = row.get("matched_samples")
        require(count is Int && count >= 20)
    }

    private fun sameMetrics(a: JSONObject, b: JSONObject) {
        metrics(a)
        metrics(b)
        for (key in listOf("voltage_median_v", "baseline_v", "delta_v", "matched_samples")) require(
            number(a, key) == number(b, key)
        )
    }

    /** null이면 측정값과 회복 표시를 그리지 않는다 */
    fun checkedCase(event: JSONObject): JSONObject? = runCatching {
        require(
            event.opt("source") == "synthetic" && event.opt("evidence_valid") == true && event.opt(
                "diagnostic_valid"
            ) == true
        )
        val id = event.getString("id")
        require(java.util.UUID.fromString(id).toString() == id)
        require((event.opt("hash") as? String)?.matches(Regex("[0-9a-f]{64}")) == true)
        val assessment = event.getJSONObject("assessment")
        require(assessment.getString("kind") == "diagnosis")
        val diagnostic = assessment.getJSONObject("diagnostic")
        require(
            diagnostic.opt("verdict") in setOf(
                "inspection_required",
                "no_anomaly_in_observed_signals"
            )
        )
        val case = event.getJSONObject("repair_case")
        require(case.opt("source") == "synthetic")
        val stage = case.getString("stage")
        val expected = when (stage) {
            "measurement_review", "warranty_check_required", "no_capacity" -> "evidence_ready"
            "center_accepted_mock" -> "service_accepted"
            in measured -> "repair_reviewed"
            else -> error("Unknown measurement state")
        }
        require(event.opt("status") == expected)
        sameMetrics(case.getJSONObject("before"), diagnostic.getJSONObject("metrics"))
        val initialDelta = number(case.getJSONObject("before"), "delta_v")
        // 서버는 반올림 전 값으로 판정한다. 반올림한 값이 경계에 있으면 두 판정이 모두 맞을 수 있다
        require(if (diagnostic.opt("verdict") == "inspection_required") initialDelta <= -1 + 0.0005 else initialDelta >= -1 - 0.0005)
        if (stage in measured) {
            val post = event.getJSONObject("repair").getJSONObject("post_repair")
            require(
                post.opt("integrity_valid") == true && post.opt("source") == "synthetic" && post.opt(
                    "status"
                ) == stage
            )
            require((post.opt("hash") as? String)?.matches(Regex("[0-9a-f]{64}")) == true)
            val raw = post.getJSONObject("raw")
            require(raw.opt("source") == "synthetic" && raw.toString().length <= 2 * 1024 * 1024)
            require(raw.getJSONArray("samples").length() >= 20)
            val after = case.getJSONObject("after")
            val assessed = post.getJSONObject("assessment")
            sameMetrics(after, assessed.getJSONObject("metrics"))
            val before = case.getJSONObject("before")
            require(number(before, "baseline_v") == number(after, "baseline_v"))
            require(
                abs(
                    number(after, "voltage_median_v") - number(
                        before,
                        "voltage_median_v"
                    ) - number(case, "delta_v")
                ) <= 0.002
            )
            require(number(case, "delta_v") == number(post, "delta_v"))
            val recovered = stage == "observed_signal_recovered"
            require((number(after, "delta_v") >= -1) == recovered)
            require(assessed.opt("verdict") == if (recovered) "no_anomaly_in_observed_signals" else "inspection_required")
            val title = EventBriefState.from(event).title
            require(if (recovered) title == "재측정 전압 회복" else title in setOf("저전압 다시 감지", "저전압 확인"))
        }
        case
    }.getOrNull()

    fun canOpen(event: JSONObject): Boolean = checkedCase(event)?.optString("stage") in measured

    fun render(ui: PhoneAccountUi, body: LinearLayout, event: JSONObject) {
        DiagnosticCasePanel.render(ui, body, event)
        if (!canOpen(event)) return
        val post = event.getJSONObject("repair").getJSONObject("post_repair")
        val source = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        source.addView(ui.text("연결된 합성 재측정 원문 · 서버 무결성 확인값", 20f, true))
        source.addView(
            ui.text(
                "사건 ID\n${event.getString("id")}\n최초 원문 SHA-256\n${event.getString("hash")}\n재측정 원문 SHA-256\n${
                    post.getString(
                        "hash"
                    )
                }", 17f
            )
        )
        source.addView(ui.text("아래 값은 합성 입력입니다. 실측·수리 완료를 뜻하지 않습니다.", 17f))
        source.addView(ui.text(post.getJSONObject("raw").toString(2), 16f))
        ui.disclosure(body, "재측정 원문과 식별 정보", source)
    }
}
