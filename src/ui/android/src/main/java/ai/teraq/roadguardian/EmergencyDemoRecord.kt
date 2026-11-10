package ai.teraq.roadguardian

import org.json.JSONObject

/** 저장된 표시 상태를 쓰기 전에 고정 시연 설정으로 다시 계산한다 */
internal object EmergencyDemoRecord {
    private fun keys(value: JSONObject, expected: Set<String>) {
        require(value.keys().asSequence().toSet() == expected)
    }

    private fun number(value: JSONObject, key: String): Double {
        val raw = value.get(key)
        require(raw is Number)
        return raw.toDouble().also { require(it.isFinite()) }
    }

    fun validate(record: JSONObject, key: String): String {
        keys(record, setOf("state", "payload"))
        val raw = record.getJSONObject("payload")
        keys(raw, setOf("timeline", "as_of", "request_key"))
        require(raw.get("request_key") == key)
        val at = number(raw, "as_of")
        require(at in 0.0..3600.0)
        val timeline = raw.getJSONObject("timeline")
        keys(
            timeline,
            setOf(
                "schema",
                "source",
                "profile",
                "timeout_seconds",
                "heartbeat_max_gap_seconds",
                "events"
            )
        )
        require(timeline.get("schema") == "road-guardian.emergency-timeline.v1")
        require(timeline.get("source") == "authored_synthetic")
        require(timeline.get("profile") == "authored-unresponsive-timeline-v2")
        require(
            number(timeline, "timeout_seconds") == 30.0 && number(
                timeline,
                "heartbeat_max_gap_seconds"
            ) == 2.0
        )
        val events = timeline.getJSONArray("events")
        require(events.length() in 1..1000)
        var last = -1.0
        var terminal: String? = null
        var terminalAt = 0.0
        var coverageLast = 0.0
        var gap = false
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            keys(event, setOf("t", "kind"))
            val t = number(event, "t")
            val kind = event.get("kind")
            require(t in 0.0..at && t > last && terminal == null)
            require(
                if (i == 0) t == 0.0 && kind == "unresponsive_onset" else kind in setOf(
                    "heartbeat",
                    "response",
                    "cancel"
                )
            )
            if (t <= 30.0) {
                if (t - coverageLast > 2.0) gap = true
                coverageLast = t
            }
            if (kind == "response" || kind == "cancel") {
                terminal = kind as String
                terminalAt = t
            }
            last = t
        }
        val coverageEnd = minOf(if (terminal != null) terminalAt else at, 30.0)
        if (coverageEnd - coverageLast > 2.0) gap = true
        val derived = when {
            gap -> "inconclusive"
            terminal != null && terminalAt <= 30.0 -> if (terminal == "response") "responded" else "cancelled"
            at >= 30.0 -> "timed_out"
            else -> "waiting"
        }
        val state = record.get("state")
        if (state == "interrupted") {
            require(terminal == null && last < 30.0)
            return "interrupted"
        }
        require(
            state in setOf(
                "waiting",
                "responded",
                "cancelled",
                "timed_out"
            ) && state == derived
        )
        return if (state == "waiting") "interrupted" else state as String
    }
}
