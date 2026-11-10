package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.*

class SharedRoadNoticeProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val out = Bundle()
        var checks = 0
        fun checkValue(ok: Boolean) {
            check(ok) { "boundary $checks" }
            checks++
        }

        val now = 100000.0
        val receiver =
            JSONObject().put("source", "synthetic").put("brand", "Kia").put("authenticated", true)
                .put("lat", 37.5).put("lon", 126.997).put("heading", 90).put("accuracy_m", 8)
                .put("road_id", "demo-road-east").put("speed_kph", 42)
        val loc = JSONObject(receiver.toString()).put("lon", 127.0)
        val d = (127.0 - 126.997) * 111000 * cos(Math.toRadians(37.5))
        val warning =
            JSONObject().put("id", "a".repeat(16)).put("source", "synthetic").put("kind", "pothole")
                .put("latest_at", now - 1).put("expires_at", now + 86399).put("location", loc)
                .put("distance_m", d.roundToInt()).put("eta_seconds", ceil(d / (42 / 3.6)))

        fun feed() = JSONObject().put("schema", "road-guardian.shared-road-feed.v1")
            .put("source", "synthetic").put("mode", "synthetic-only").put("generated_at", now)
            .put("vehicle", JSONObject(receiver.toString()))
            .put("warnings", JSONArray().put(JSONObject(warning.toString())))
        try {
            checkValue(SharedRoadNotice.checked(feed(), now)?.title == "전방 포트홀 주의")
            for (key in listOf("source", "schema", "mode")) {
                checkValue(SharedRoadNotice.checked(feed().put(key, "wrong"), now) == null)
            }
            checkValue(SharedRoadNotice.checked(feed(), now + 15) != null)
            checkValue(SharedRoadNotice.checked(feed(), now + 15.001) == null)
            checkValue(SharedRoadNotice.checked(feed(), now - 2) != null)
            checkValue(SharedRoadNotice.checked(feed(), now - 2.001) == null)
            checkValue(SharedRoadNotice.checked(feed(), now + 16) == null)
            for (change in listOf("opposite", "passed", "brand", "road", "auth", "accuracy")) {
                val f = feed()
                val r = f.getJSONObject("vehicle")
                when (change) {
                    "opposite" -> r.put("heading", 270)
                    "passed" -> r.put("lon", 127.003)
                    "brand" -> r.put("brand", "Other")
                    "road" -> r.put("road_id", "other")
                    "auth" -> r.put("authenticated", false)
                    else -> r.put("accuracy_m", 31)
                }
                checkValue(SharedRoadNotice.checked(f, now) == null)
            }
            for (key in listOf("expires_at", "distance_m", "eta_seconds")) {
                val f = feed()
                f.getJSONArray("warnings").getJSONObject(0).put(key, 0)
                checkValue(SharedRoadNotice.checked(f, now) == null)
            }
            out.putString("result", "PASS $checks checks; no UI or network exercised")
            finish(Activity.RESULT_OK, out)
        } catch (t: Throwable) {
            out.putString("error", t.toString())
            finish(Activity.RESULT_CANCELED, out)
        }
    }
}
