package ai.teraq.roadguardian

import org.json.JSONObject

data class PrivateReviewNote(val eventId: String, val revision: Int, val text: String) {
    companion object {
        fun parse(response: String, eventId: String): PrivateReviewNote {
            try {
                val note = JSONObject(response)
                require(
                    note.getString("schema") == "road-guardian.private-review-note.v1" && note.getString(
                        "event_id"
                    ) == eventId
                )
                require(note.getString("scope") == "current_subject_only" && note.getBoolean("evidence_statement") == false)
                val revision = note.get("revision")
                require(revision is Int && revision >= 0)
                val text = note.get("note")
                require(text is String && text.length <= 2000 && (revision > 0 || text.isEmpty()))
                return PrivateReviewNote(eventId, revision, text)
            } catch (_: org.json.JSONException) {
                throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
            } catch (_: IllegalArgumentException) {
                throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
            }
        }
    }
}
