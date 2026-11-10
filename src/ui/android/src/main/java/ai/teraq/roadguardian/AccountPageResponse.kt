package ai.teraq.roadguardian

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 화면에 넘기기 전에 페이지 정보와 다음 페이지 토큰을 확인한다 */
object AccountPageResponse {
    data class Page(val events: JSONArray, val next: String?, val category: String? = null)

    fun parse(response: String, cursor: String?, category: String? = null): Page {
        try {
            val data = JSONObject(response)
            val events = data.getJSONArray("events")
            if (category != null) require(
                category in setOf(
                    "all",
                    "road",
                    "maintenance",
                    "incident"
                ) && data.getString("category") == category
            )
            require(events.length() <= 25 && data.has("next_cursor"))
            val rawNext = data.get("next_cursor")
            val next = if (rawNext === JSONObject.NULL) null else {
                require(rawNext is String)
                require(rawNext.matches(Regex("[A-Za-z0-9_-]{1,256}")) && rawNext != cursor)
                rawNext
            }
            val ids = mutableSetOf<String>()
            for (index in 0 until events.length()) {
                val event = events.getJSONObject(index)
                val id = event.get("id")
                require(id is String && UUID.fromString(id).toString() == id && ids.add(id))
                if (category != null) {
                    val actual = EvidenceScope.category(event)
                    require(actual != null && (category == "all" || category == actual))
                }
            }
            return Page(events, next, category)
        } catch (_: org.json.JSONException) {
            throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
        } catch (_: IllegalArgumentException) {
            throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
        }
    }
}
