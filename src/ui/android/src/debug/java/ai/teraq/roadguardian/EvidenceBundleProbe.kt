package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject

class EvidenceBundleProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val e = JSONObject(
                targetContext.assets.open("diagnostic-cases-v135.json").bufferedReader()
                    .use { it.readText() }).getJSONObject("unresolved")

            fun labels() = EvidenceBundleStrip.items(e).map { it.second }
            check("전후 측정" in labels())
            e.put(
                "video",
                JSONObject().put("integrity_valid", true)
                    .put("input_kind", "authored_collision_scene").put(
                    "input_photo",
                    JSONObject().put("integrity_valid", true).put("event_id", e.getString("id"))
                        .put("sensor_sha256", e.getString("hash"))
                )
            )
            check("사진" in labels() && "영상" in labels())
            e.getJSONObject("video").getJSONObject("input_photo").put("event_id", "other")
            check("사진" !in labels())
            e.getJSONObject("video").put("integrity_valid", false)
            check("영상" !in labels())
            e.getJSONObject("repair").getJSONObject("post_repair").put("integrity_valid", false)
            check("전후 측정" !in labels())
            e.put("evidence_valid", false)
            check(labels().isEmpty())
            result.putString(
                "stream",
                "PASS: inventory binding, invalid media/measurement refusal, invalid event empty. Metadata checks only.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
