package ai.teraq.roadguardian

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 이번 실행 동안만 쓰는 합성 사건 알림 상태. 선택해도 주행 권한은 바뀌지 않는다 */
class DriverEventNoticeState {
    companion object {
        /** 목록 선택과 시스템 알림에 같은 검사 기준을 쓴다 */
        fun isEligible(row: JSONObject): Boolean = DriverEventNoticeState().eligible(row)

        /** 접수 문구 대신 정해진 전달 상태로 비교한다. 관계없는 수정으로 다시 알리지 않게 한다 */
        fun helpDeliveryState(row: JSONObject): String? = runCatching {
            val assessment = row.getJSONObject("assessment")
            if (assessment.optString("kind") !in setOf(
                    "emergency",
                    "manual_help"
                )
            ) return@runCatching null
            val recipient = assessment.optString("recipient")
            if (recipient !in setOf("112-mock", "119-mock")) return@runCatching null
            val deliveries = row.optJSONArray("dispatches")
            val matches =
                (0 until (deliveries?.length() ?: 0)).mapNotNull { deliveries?.optJSONObject(it) }
                    .filter { it.optString("recipient") == recipient }
            if (matches.size > 1) return@runCatching null
            val delivery = matches.singleOrNull()
            val state = EmergencyDelivery.state(
                recipient,
                delivery?.optString("recipient"),
                delivery?.optString("status"),
                delivery?.optString("receipt"),
                row.opt("evidence_valid") == true
            )
            when (row.optString("status")) {
                "evidence_ready", "dispatch_ready" -> if (delivery == null || state == "waiting") "waiting" else null
                "acknowledged" -> state.takeIf { it == "acknowledged" }
                "retry_pending" -> state.takeIf { it == "retry" }
                else -> null
            }
        }.getOrNull()

        fun helpIdentitySuffix(row: JSONObject): String = helpDeliveryState(row)?.let {
            ":${row.getJSONObject("assessment").getString("recipient")}:$it"
        }.orEmpty()
    }

    private val seen = mutableSetOf<String>()
    private val observedIds = mutableSetOf<String>()
    private val helpStages = mutableMapOf<String, Int>()
    private fun helpStage(row: JSONObject) = when (helpDeliveryState(row)) {
        "waiting" -> 0
        "retry" -> 1
        "acknowledged" -> 2
        else -> null
    }

    private var initialized = false
    private var active: JSONObject? = null
    private var startedAt = -1L
    private var observedAt = -1L
    private var lastNow = -1L

    @Synchronized
    fun invalidate() {
        initialized = false
        active = null
        startedAt = -1
        observedAt = -1
    }

    private fun clock(now: Long): Boolean {
        if (now < 0 || now < lastNow) {
            invalidate()
            return false
        }
        lastNow = now
        return true
    }

    private fun id(row: JSONObject): String? {
        val value = row.opt("id") as? String ?: return null
        return value.takeIf {
            runCatching { UUID.fromString(it).toString() == it }.getOrDefault(
                false
            )
        }
    }

    private fun rank(row: JSONObject) = when (row.optJSONObject("assessment")?.optString("kind")) {
        "violation" -> if (TrafficZoneAutoNotice.received(row)) 1 else 0
        "wanted" -> if (WantedAutoNotice.received(row)) 4 else 0
        "emergency" -> 5
        "manual_help" -> 4
        "crash" -> 3
        "diagnosis" -> 2
        "pothole", "parking" -> 1
        "rain" -> if (EventBriefState.authoredRainCandidate(row)) 1 else 0
        "observation" -> if (EventBriefState.weatherTimelineState(row) != null || VideoLaneAnalysis.repeatedCandidate(
                row
            )
        ) 1 else 0

        else -> 0
    }

    private fun token(row: JSONObject): String {
        val post = row.optJSONObject("repair")?.optJSONObject("post_repair")?.opt("hash") as? String
        return id(row) + helpIdentitySuffix(row) + WantedAutoNotice.suffix(row) + TrafficZoneAutoNotice.suffix(
            row
        ) + (if (row.optJSONObject("assessment")?.optString("kind") == "diagnosis" && post?.matches(
                Regex("[0-9a-f]{64}")
            ) == true
        ) ":$post" else "")
    }

    private fun eligible(row: JSONObject): Boolean = runCatching {
        if (id(row) == null || row.opt("source") != "synthetic" || row.opt("evidence_valid") != true || rank(
                row
            ) == 0
        ) return@runCatching false
        val hash = row.opt("hash") as? String ?: return@runCatching false
        if (!hash.matches(Regex("[0-9a-f]{64}"))) return@runCatching false
        val kind = row.getJSONObject("assessment").getString("kind")
        val help = kind in setOf("emergency", "manual_help")
        if ((help || kind == "parking") && !BuildConfig.SYNTHETIC_BACKEND) return@runCatching false
        if (help && helpDeliveryState(row) == null) return@runCatching false
        val stage = row.optJSONObject("repair_case")?.optString("stage")
        val followup =
            kind == "diagnosis" && stage in setOf("observed_signal_recovered", "follow_up_required")
        val status = row.opt("status")
        if (status !in setOf(
                "evidence_ready",
                "dispatch_ready"
            ) && !(kind == "pothole" && status == "pending_confirmation") && !(followup && status == "repair_reviewed") && !(help && status in setOf(
                "acknowledged",
                "retry_pending"
            )) && !(kind == "wanted" && WantedAutoNotice.received(row)) && !(kind == "violation" && TrafficZoneAutoNotice.received(
                row
            ))
        ) return@runCatching false
        if (followup) {
            val post = row.optJSONObject("repair")?.optJSONObject("post_repair")
                ?: return@runCatching false
            val postHash = post.opt("hash") as? String ?: return@runCatching false
            if (post.opt("integrity_valid") != true || post.opt("source") != "synthetic" || !postHash.matches(
                    Regex("[0-9a-f]{64}")
                )
            ) return@runCatching false
        }
        if (kind == "crash" && row.optJSONObject("insurance_case")
                ?.optString("status") != "ready"
        ) return@runCatching false
        if (kind == "diagnosis" && (row.opt("diagnostic_valid") != true ||
                    row.optJSONObject("repair_case")?.optString("stage") !in setOf(
                "measurement_review",
                "observed_signal_recovered",
                "follow_up_required"
            ))
        ) return@runCatching false
        if (kind == "violation") return@runCatching TrafficZoneAutoNotice.received(row)
        if (kind == "wanted") return@runCatching WantedAutoNotice.received(row)
        if (help) return@runCatching true
        if (kind == "parking") return@runCatching EventBriefState.from(row).title == "주차 중 충격 기록"
        // 화면과 같은 표시 코드로 해석한다
        EventBriefState.from(row).title in setOf(
            "전방 시야 저하",
            "포트홀 감지",
            "사고 감지",
            "저전압 확인",
            "저전압 다시 감지",
            "재측정 전압 회복",
            "전압 이상 없음",
            "시야 변화 확인 중",
            "워셔 사용 구간",
            "시야 저하 신호 해제",
            "차선 변경 기록 확인"
        )
    }.getOrDefault(false)

    private fun expire(now: Long) {
        if (active != null && (startedAt < 0 || observedAt < 0 || now - startedAt >= 20_000 || now - observedAt >= 10_000)) active =
            null
    }

    @Synchronized
    fun accept(rows: JSONArray, now: Long) {
        if (!clock(now)) return
        expire(now)
        val unique = linkedMapOf<String, JSONObject>()
        val duplicates = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val key = id(row) ?: continue
            if (unique.containsKey(key)) duplicates.add(key)
            else runCatching { EvidenceJson.copy(row) }.getOrNull()?.let { unique[key] = it }
        }
        val valid = unique.filter { (key, value) ->
            val stage = helpStage(value)
            key !in duplicates && eligible(value) && (stage == null ||
                    (key !in observedIds || helpStages.containsKey(key)) && stage >= (helpStages[key]
                ?: 0))
        }
        valid.forEach { (key, value) -> helpStage(value)?.let { helpStages[key] = it } }
        observedIds.addAll(unique.keys)
        if (!initialized) {
            seen.addAll(unique.values.map { token(it) })
            initialized = true
            active = null
            observedAt = now
            return
        }
        active = active?.let { valid[id(it)] }
        val candidate = valid.entries.filter { token(it.value) !in seen }
            .sortedWith(compareByDescending<Map.Entry<String, JSONObject>> { rank(it.value) }.thenBy { it.key })
            .firstOrNull()?.value
        if (candidate != null && (active == null || rank(candidate) > rank(active!!))) {
            active = candidate
            startedAt = now
        }
        seen.addAll(unique.values.map { token(it) })
        observedAt = now
    }

    /** 백그라운드 전달은 선택된 사건을 소비한다. 보이는 패널은 자기 알림을 그대로 둘 수 있다 */
    @Synchronized
    fun takeCurrent(now: Long): JSONObject? {
        val event = current(now)
        active = null
        return event
    }

    @Synchronized
    fun current(now: Long): JSONObject? {
        if (!clock(now)) return null
        expire(now)
        return if (initialized) active?.let { EvidenceJson.copy(it) } else null
    }
}
