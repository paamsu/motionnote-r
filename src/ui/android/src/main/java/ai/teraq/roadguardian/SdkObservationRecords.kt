package ai.teraq.roadguardian

import org.json.JSONObject

/** 크기 제한이 있는 개인 기록. 표시 전에 바이트 무결성과 값 타입을 확인한다 */
data class SdkObservationRecord(
    val id: String,
    val receivedAt: Double,
    val capturedAt: Double,
    val environment: String,
    val sdkVersion: String,
    val samples: List<Sample>
) {
    data class Sample(
        val elapsedMs: Int,
        val gear: String?,
        val gearAge: Long?,
        val speed: Double?,
        val speedAge: Long?
    )

    companion object {
        fun parse(response: JSONObject, eventId: String): List<SdkObservationRecord> {
            try {
                require(
                    response.getString("schema") == "road-guardian.sdk-observations.v1" && response.getString(
                        "event_id"
                    ) == eventId && response.getString("scope") == "current_subject_only"
                )
                val values = response.getJSONArray("observations")
                require(values.length() <= 64)
                val seen = mutableSetOf<String>()
                fun number(value: Any): Double {
                    require(value is Number)
                    return value.toDouble().also { require(it.isFinite()) }
                }
                return (0 until values.length()).map { index ->
                    val record = values.getJSONObject(index)
                    val data = record.getJSONObject("data")
                    SdkObservation.receipt(record.toString(), data)
                    val id = record.getString("id")
                    require(java.util.UUID.fromString(id).toString() == id && seen.add(id))
                    require(data.getString("schema") == "road-guardian.sdk-observation.v1")
                    val environment = data.getString("environment")
                    require(environment in setOf("pleos_emulator", "vehicle_unattested"))
                    val version = data.get("sdk_version")
                    require(version is String && version.matches(Regex("[A-Za-z0-9.-]{1,40}")))
                    val received = number(record.get("received_at"))
                    val captured = number(data.get("captured_at"))
                    require(received > 0 && captured > 0)
                    val samples = data.getJSONArray("samples")
                    require(samples.length() in 1..32)
                    var previous = -1
                    val decoded = (0 until samples.length()).map { sampleIndex ->
                        val sample = samples.getJSONObject(sampleIndex)
                        val elapsed = sample.get("elapsed_ms")
                        require(elapsed is Int && elapsed > previous && elapsed <= 60000)
                        previous = elapsed
                        val gear = sample.getJSONObject("gear")
                        val speed = sample.getJSONObject("speed")
                        fun age(obj: JSONObject, key: String): Long? {
                            if (obj.get(key) == JSONObject.NULL) {
                                require(obj.get("age_ms") == JSONObject.NULL)
                                return null
                            }
                            val value = obj.get("age_ms")
                            require(value is Int || value is Long)
                            return (value as Number).toLong().also { require(it in 0..4999) }
                        }

                        val gearAge = age(gear, "value")
                        val speedAge = age(speed, "kmh")
                        val gearValue = if (gearAge == null) null else gear.get("value").also {
                            require(
                                it is String && it in setOf(
                                    "GEAR_PARK",
                                    "GEAR_REVERSE",
                                    "GEAR_NEUTRAL",
                                    "GEAR_DRIVE"
                                )
                            )
                        } as String
                        val speedValue =
                            if (speedAge == null) null else number(speed.get("kmh")).also {
                                require(it in 0.0..400.0)
                            }
                        Sample(elapsed, gearValue, gearAge, speedValue, speedAge)
                    }
                    SdkObservationRecord(id, received, captured, environment, version, decoded)
                }.sortedWith(compareBy<SdkObservationRecord> { it.receivedAt }.thenBy { it.id })
            } catch (_: Exception) {
                throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
            }
        }
    }
}
