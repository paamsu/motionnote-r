package ai.teraq.roadguardian

import org.json.JSONObject
import kotlin.math.*

/** 작성된 수신 피드(크기 제한). 내 차 감지 결과나 전달 확인으로 쓰지 않는다 */
internal object SharedRoadNotice {
    data class Notice(val key: String, val title: String, val status: String, val expiresAt: Double)

    fun checked(feed: JSONObject, now: Double): Notice? {
        if (!BuildConfig.SYNTHETIC_BACKEND || !(BuildConfig.DEBUG || BuildConfig.CONTEST_DEMO)) return null
        if (feed.optString("schema") != "road-guardian.shared-road-feed.v1" || feed.optString("source") != "synthetic" || feed.optString(
                "mode"
            ) != "synthetic-only"
        ) return null
        val generated = feed.optDouble("generated_at", Double.NaN)
        if (!now.isFinite() || !generated.isFinite() || now - generated !in -2.0..15.0) return null
        val receiver = feed.optJSONObject("vehicle") ?: return null
        if (receiver.optString("source") != "synthetic" || receiver.opt("authenticated") != true || receiver.optString(
                "brand"
            ) !in setOf("Hyundai", "Kia") || !position(receiver)
        ) return null
        val warnings = feed.optJSONArray("warnings") ?: return null
        if (warnings.length() > 100) return null
        for (i in 0 until warnings.length()) {
            val w = warnings.optJSONObject(i) ?: continue
            if (w.optString("source") != "synthetic" || w.optString("kind") != "pothole") continue
            val id = w.optString("id")
            if (!id.matches(Regex("[0-9a-f]{16}"))) continue
            val latest = w.optDouble("latest_at", Double.NaN)
            val expires = w.optDouble("expires_at", Double.NaN)
            if (!latest.isFinite() || !expires.isFinite() || latest > generated || now >= expires || expires - latest !in 1.0..86400.0) continue
            val loc = w.optJSONObject("location") ?: continue
            if (!position(loc) || receiver.optString("road_id") != loc.optString("road_id")) continue
            val heading = receiver.getDouble("heading")
            val diff = abs((heading - loc.getDouble("heading") + 540.0) % 360.0 - 180.0)
            val dx = (loc.getDouble("lon") - receiver.getDouble("lon")) * 111000 * cos(
                Math.toRadians(receiver.getDouble("lat"))
            )
            val dy = (loc.getDouble("lat") - receiver.getDouble("lat")) * 111000
            val distance = hypot(dx, dy)
            val ahead = dx * sin(Math.toRadians(heading)) + dy * cos(Math.toRadians(heading))
            val speed = receiver.optDouble("speed_kph", Double.NaN)
            val stated = w.optDouble("distance_m", Double.NaN)
            val eta = w.optDouble("eta_seconds", Double.NaN)
            if (diff >= 45 || ahead <= 0 || distance > 2000 || !speed.isFinite() || speed <= 0 || speed > 200) continue
            if (!stated.isFinite() || abs(stated - distance) > 1 || !eta.isFinite() || eta !in 1.0..120.0 || eta != ceil(
                    distance / (speed / 3.6)
                ).coerceAtLeast(1.0)
            ) continue
            val lane = w.optJSONObject("lane_guidance")
            val laneNumber = lane?.opt("lane_number")
            val laneCount = lane?.opt("lane_count")
            val verifiedLane =
                lane?.optString("source") == "authored_synthetic" && lane.optString("road_id") == loc.optString(
                    "road_id"
                ) && laneNumber is Int && laneCount is Int && laneNumber in 1..laneCount && laneCount <= 8
            val caution = if (verifiedLane) "${laneNumber}차로 위험 · 감속하세요" else "속도를 줄이세요"
            return Notice(
                "shared:$id:$latest",
                "전방 포트홀 주의",
                "약 ${stated.roundToInt()} m 앞 · $caution",
                expires
            )
        }
        return null
    }

    private fun position(p: JSONObject): Boolean {
        fun number(key: String, low: Double, high: Double): Boolean {
            val n = p.optDouble(key, Double.NaN)
            return p.opt(key) is Number && n.isFinite() && n in low..high
        }
        return number("lat", -90.0, 90.0) && number("lon", -180.0, 180.0) && number(
            "heading",
            0.0,
            359.999999
        ) && number("accuracy_m", 0.0, 30.0) && p.optString("road_id").isNotBlank()
    }
}
