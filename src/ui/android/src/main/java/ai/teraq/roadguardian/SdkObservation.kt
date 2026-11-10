package ai.teraq.roadguardian

import org.json.JSONArray
import org.json.JSONObject

/** 확인 전의 작은 클라이언트 관측. 오래된 값을 복사하거나 빠진 신호를 추측하지 않는다 */
object SdkObservation {
    fun receipt(response: String, expected: JSONObject): JSONObject {
        try {
            val result = JSONObject(response)
            val raw = result.getString("canonical_json")
            require(raw.toByteArray(Charsets.UTF_8).size <= 32768)
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            require(
                result.getString("sha256") == hash && result.getString("id") == expected.getString(
                    "request_id"
                )
            )
            require(
                result.getString("provenance") == "client_reported_not_attested" && result.get("sensor_original") == false && result.get(
                    "assessment_changed"
                ) == false
            )
            require(same(JSONObject(raw), expected) && same(result.getJSONObject("data"), expected))
            return result
        } catch (_: Exception) {
            throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
        }
    }

    private fun same(first: Any?, second: Any?): Boolean = when {
        first is JSONObject && second is JSONObject -> {
            val keys = first.keys().asSequence().toSet()
            keys == second.keys().asSequence().toSet() && keys.all {
                same(
                    first.get(it),
                    second.get(it)
                )
            }
        }

        first is JSONArray && second is JSONArray -> first.length() == second.length() && (0 until first.length()).all {
            same(
                first.get(it),
                second.get(it)
            )
        }

        first is Number && second is Number -> first.toDouble() == second.toDouble()
        else -> first == second
    }

    fun create(snapshot: JSONObject, requestId: String, emulator: Boolean): JSONObject {
        require(java.util.UUID.fromString(requestId).toString() == requestId)
        require(snapshot.opt("synthetic_fallback") == false)
        val version = snapshot.getString("sdk_version")
        require(version.matches(Regex("[A-Za-z0-9.-]{1,40}")))
        fun age(value: Any?): Long? = if (value is Int || value is Long) (value as Number).toLong()
            .takeIf { it in 0..4999 } else null

        val gearAge = age(snapshot.opt("gear_age_ms"))
        val gear = snapshot.optString("gear").takeIf {
            snapshot.opt("fresh_gear_received") == true && gearAge != null && it in setOf(
                "GEAR_PARK",
                "GEAR_REVERSE",
                "GEAR_NEUTRAL",
                "GEAR_DRIVE"
            )
        }
        val speed = snapshot.optJSONObject("vehicle_speed")
        val speedAge = age(speed?.opt("age_ms"))
        val raw = speed?.opt("kilometers_per_hour")
        val kmh = (raw as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it in 0.0..400.0 && speedAge != null && speed?.opt("fresh") == true && speed.optString(
                "source"
            ) == "aaos_perf_vehicle_speed"
        }
        val sample = JSONObject().put("elapsed_ms", 0)
            .put(
                "gear",
                JSONObject().put("value", gear ?: JSONObject.NULL)
                    .put("age_ms", if (gear == null) JSONObject.NULL else gearAge)
            )
            .put(
                "speed",
                JSONObject().put("kmh", kmh ?: JSONObject.NULL)
                    .put("age_ms", if (kmh == null) JSONObject.NULL else speedAge)
            )
        return JSONObject().put("schema", "road-guardian.sdk-observation.v1")
            .put("request_id", requestId)
            .put("environment", if (emulator) "pleos_emulator" else "vehicle_unattested")
            .put("sdk_version", version).put("captured_at", System.currentTimeMillis() / 1000.0)
            .put("samples", JSONArray().put(sample))
    }
}
