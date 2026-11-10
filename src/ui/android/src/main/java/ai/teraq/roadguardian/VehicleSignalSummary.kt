package ai.teraq.roadguardian

import org.json.JSONObject
import java.util.Locale

/** 최신 관측만 요약한다. 값을 받았다고 주행 가능 상태로 보지 않는다 */
data class VehicleSignalSummary(
    val gear: String,
    val gearLabel: String,
    val speed: String,
    val wiper: String,
    val airbag: String,
    val deployed: Boolean,
    val tires: List<Boolean>
) {
    companion object {
        fun from(data: JSONObject): VehicleSignalSummary {
            val gear = if (data.optBoolean("fresh_gear_received")) when (data.optString("gear")) {
                "GEAR_PARK" -> "P"
                "GEAR_REVERSE" -> "R"
                "GEAR_NEUTRAL" -> "N"
                "GEAR_DRIVE" -> "D"
                else -> "—"
            } else "—"
            val speedData = data.optJSONObject("vehicle_speed")
            val rawSpeed = speedData?.optDouble("kilometers_per_hour", Double.NaN) ?: Double.NaN
            val speed =
                if (speedData?.optBoolean("fresh") == true && rawSpeed.isFinite()) String.format(
                    Locale.KOREAN,
                    "%.0f",
                    rawSpeed
                ) else "—"
            val front = data.optJSONObject("wipers")?.optJSONObject("FRONT_WINDSHIELD")
            val wiper = if (front?.optBoolean("fresh") == true && front.optString("state").let {
                    it.isNotBlank() && it !in setOf(
                        "null",
                        "UNKNOWN",
                        "UNAVAILABLE",
                        "ERROR"
                    )
                }) {
                if (front.optString("state") == "OFF") "꺼짐 응답" else "상태 수신"
            } else "미확인"
            val seats = data.optJSONObject("airbags")?.optJSONObject("seats")
            val items =
                seats?.keys()?.asSequence()?.mapNotNull { seats.optJSONObject(it) }?.toList()
                    .orEmpty()
            val deployed = items.any { it.optBoolean("fresh") && it.opt("deployed") == true }
            val airbag = when {
                deployed -> "전개 응답 있음"
                items.isNotEmpty() && items.size == seats?.length() && items.all {
                    it.optBoolean("fresh") && it.opt(
                        "deployed"
                    ) == false
                } -> "조회 좌석 미전개"

                else -> "미확인"
            }
            val wheels = data.optJSONObject("tires")?.optJSONObject("wheels")
            val tires = PleosTireMonitor.WHEELS.map {
                wheels?.optJSONObject(it)?.optJSONObject("pressure")?.optBoolean("fresh") == true
            }
            return VehicleSignalSummary(
                gear, when (gear) {
                    "P" -> "주차 기어"
                    "R" -> "후진 기어"
                    "N" -> "중립 기어"
                    "D" -> "주행 기어"
                    else -> "기어 미확인"
                }, speed, wiper, airbag, deployed, tires
            )
        }
    }

    val pressureLabel get() = if (tires.all { it }) "공기압 값 수신 · 안전 판정 전" else "현재 공기압 미확인"
    val description get() = "차량 신호. $gearLabel. 속도 ${if (speed == "—") "미확인" else "$speed km/h"}. 앞 와이퍼 $wiper. 에어백 $airbag. $pressureLabel. 고장과 사고 여부는 별도 확인이 필요합니다."
}
