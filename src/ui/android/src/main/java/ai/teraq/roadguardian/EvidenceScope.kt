package ai.teraq.roadguardian

import org.json.JSONObject

/** 표시 범위만 정한다. 계정·차량 권한은 따로 확인한다 */
object EvidenceScope {
    val categories = mapOf("pothole" to "road", "diagnosis" to "maintenance", "crash" to "incident")
    fun category(event: JSONObject): String? {
        val assessment = event.optJSONObject("assessment") ?: return null
        // 주행 알림에 나온 공모전 사건은 기록 목록에서도 열 수 있어야 한다
        // 표시용 분류다. 계정 권한이나 감지 결과와는 관계없다
        val contestCategory =
            if (assessment.optString("kind") == "observation" && VideoLaneAnalysis.repeatedCandidate(
                    event
                )
            ) "road"
            else mapOf(
                "reckless" to "road",
                "violation" to "road",
                "wanted" to "incident",
                "parking" to "incident"
            )[assessment.optString("kind")]
        if (contestCategory != null) {
            return if (BuildConfig.SYNTHETIC_BACKEND && event.optString("source") == "synthetic" &&
                event.opt("evidence_valid") == true && event.optString("status") !in setOf(
                    "dismissed",
                    "input_review_required"
                )
            ) contestCategory else null
        }
        if (assessment.optString("kind") in setOf("emergency", "manual_help")) {
            // 공모전 표시에만 쓴다. 실제 서비스나 소유자 계정 분류는 넓히지 않는다
            return if (BuildConfig.SYNTHETIC_BACKEND && event.optString("source") == "synthetic" &&
                event.opt("evidence_valid") == true && event.optString("status") !in setOf(
                    "dismissed",
                    "input_review_required"
                ) &&
                assessment.optString("recipient") in setOf("112-mock", "119-mock")
            ) "help" else null
        }
        // 보존된 입력을 분류만 한다. 시나리오 이름이 감지 결과를 뜻하지는 않는다
        if (event.optString("source") == "synthetic" && event.optString("status") == "input_review_required")
            return categories[event.optJSONObject("data")?.optString("scenario")]
        if (assessment.optString("kind") == "owner_record" && event.optString("source") == "owner_reported")
            return assessment.optString("category")
                .takeIf { it in setOf("road", "maintenance", "incident") }
        return categories[assessment.optString("kind")]
    }

    fun helpGraphic(event: JSONObject): String? = if (category(event) == "help")
        if (event.getJSONObject("assessment")
                .getString("recipient") == "112-mock"
        ) "help_112" else "help_119" else null

    fun helpStatus(event: JSONObject): String? =
        if (category(event) == "help") "기록 보관 · ${EventBriefState.from(event).next}" else null

    fun canNotifyComparison(event: JSONObject): Boolean {
        if (event.optJSONObject("assessment")?.optString("kind") in setOf(
                "observation",
                "reckless",
                "violation",
                "wanted",
                "parking"
            )
        ) return false
        val category = category(event) ?: return false
        val summary = event.optJSONObject("case_overview") ?: return false
        return event.optBoolean("evidence_valid") && category in setOf("road", "maintenance") &&
                summary.optString("schema") == "road-guardian.case-overview.v1" &&
                summary.optString("category") == category && summary.optString("state") == "comparison_available"
    }
}
