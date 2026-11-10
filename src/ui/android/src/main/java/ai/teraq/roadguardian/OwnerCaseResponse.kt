package ai.teraq.roadguardian

import org.json.JSONObject

/** 작성 내용 접수만 확인한다. 차량이나 현장 확인으로 쓰지 않는다 */
object OwnerCaseResponse {
    fun parse(response: String, request: JSONObject): JSONObject {
        try {
            val event = JSONObject(response)
            val id = event.getString("id")
            require(java.util.UUID.fromString(id).toString() == id)
            require(event.getString("creation_request_id") == request.getString("request_id"))
            require(
                event.getString("source") == "owner_reported" && event.get("evidence_valid") == true && event.get(
                    "sensor_original"
                ) == false
            )
            val raw = event.getString("content_original_json")
            require(raw.toByteArray(Charsets.UTF_8).size <= 32768)
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            require(event.getString("hash") == hash)
            for (data in listOf(JSONObject(raw), event.getJSONObject("data"))) {
                require(
                    data.getString("schema") == "road-guardian.owner-record.v1" && data.getString(
                        "source"
                    ) == "owner_reported"
                )
                for (key in listOf(
                    "vehicle_id",
                    "category",
                    "title",
                    "description"
                )) require(data.get(key) == request.get(key))
                val reported = data.get("reported_at")
                val expected = request.get("reported_at")
                require(if (reported is Number && expected is Number) reported.toDouble() == expected.toDouble() else reported == expected)
                val own = data.getJSONObject("context").getJSONObject("own_vehicle")
                for (key in listOf(
                    "plate",
                    "model",
                    "color"
                )) require(own.get(key) == request.getJSONObject("own_vehicle").get(key))
                require(own.getString("source") == "owner_reported")
            }
            val assessment = event.getJSONObject("assessment")
            require(
                assessment.getString("kind") == "owner_record" && assessment.getString("category") == request.getString(
                    "category"
                )
            )
            require(
                assessment.getString("title") == request.getString("title") && assessment.getString(
                    "status"
                ) == "unassessed"
            )
            require(
                assessment.get("automatic_assessment") == false && assessment.get("responsibility_determined") == false && assessment.get(
                    "external_submission"
                ) == false
            )
            return event
        } catch (_: Exception) {
            throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
        }
    }
}
