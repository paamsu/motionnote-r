package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class EmergencyRecordProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val results = JSONArray()
        var activity: EmergencyDemoActivity? = null
        fun record(key: String, state: String = "responded", at: Double = 1.0) =
            JSONObject().put("state", state).put(
                "payload", JSONObject().put("request_key", key).put("as_of", at).put(
                    "timeline", JSONObject()
                        .put("schema", "road-guardian.emergency-timeline.v1")
                        .put("source", "authored_synthetic")
                        .put("profile", "authored-unresponsive-timeline-v2")
                        .put("timeout_seconds", 30).put("heartbeat_max_gap_seconds", 2).put(
                            "events",
                            JSONArray().put(
                                JSONObject().put("t", 0).put("kind", "unresponsive_onset")
                            ).put(JSONObject().put("t", 1).put("kind", "response"))
                        )
                )
            )
        try {
            val cases = listOf(
                "valid_response",
                "valid_cancel",
                "valid_timeout",
                "waiting",
                "interrupted",
                "state_mismatch",
                "gap",
                "future",
                "duplicate",
                "after_terminal",
                "wrong_profile",
                "string_time",
                "empty",
                "oversized"
            )
            for (name in cases) {
                val key = UUID.randomUUID().toString()
                val value = record(key)
                val raw = value.getJSONObject("payload")
                val timeline = raw.getJSONObject("timeline")
                val events = timeline.getJSONArray("events")
                var expected = "record_unavailable"
                when (name) {
                    "valid_response" -> expected = "responded"
                    "valid_cancel" -> {
                        value.put("state", "cancelled")
                        events.getJSONObject(1).put("kind", "cancel")
                        expected = "cancelled"
                    }

                    "valid_timeout" -> {
                        value.put("state", "timed_out")
                        raw.put("as_of", 31)
                        val sequence = JSONArray().put(events.getJSONObject(0))
                        for (i in 1..31) sequence.put(
                            JSONObject().put("t", i).put("kind", "heartbeat")
                        )
                        timeline.put("events", sequence)
                        expected = "timed_out"
                    }

                    "waiting", "interrupted" -> {
                        value.put("state", name)
                        timeline.put("events", JSONArray().put(events.getJSONObject(0)))
                        raw.put("as_of", if (name == "waiting") 0 else 5)
                        expected = "interrupted"
                    }

                    "state_mismatch" -> value.put("state", "timed_out")
                    "gap" -> {
                        raw.put("as_of", 5)
                        events.getJSONObject(1).put("t", 5)
                    }

                    "future" -> events.getJSONObject(1).put("t", 2)
                    "duplicate" -> events.getJSONObject(1).put("t", 0)
                    "after_terminal" -> {
                        raw.put("as_of", 2)
                        events.put(JSONObject().put("t", 2).put("kind", "heartbeat"))
                    }

                    "wrong_profile" -> timeline.put("profile", "unknown")
                    "string_time" -> raw.put("as_of", "1")
                }
                val original = when (name) {
                    "empty" -> ""
                    "oversized" -> " ".repeat(262145)
                    else -> value.toString()
                }
                val dir = File(targetContext.filesDir, "emergency-demo").apply { mkdirs() }
                val file = File(dir, "$key.json")
                file.writeText(original)
                // Ensure this isolated fixture is latest without rewriting any existing record.
                file.setLastModified(
                    maxOf(
                        System.currentTimeMillis(),
                        (dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0) + 1000
                    )
                )
                try {
                    activity = startActivitySync(
                        Intent(
                            targetContext,
                            EmergencyDemoActivity::class.java
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ) as EmergencyDemoActivity
                    Thread.sleep(300)
                    fun field(n: String) = EmergencyDemoActivity::class.java.getDeclaredField(n)
                        .apply { isAccessible = true }
                    runOnMainSync {
                        check(field("state").get(activity) == expected) {
                            "$name state=${
                                field("state").get(
                                    activity
                                )
                            } expected=$expected"
                        }
                        check(!field("saving").getBoolean(activity))
                        check(
                            (field("finalPayload").get(activity) != null) == (expected in setOf(
                                "responded",
                                "cancelled",
                                "timed_out"
                            ))
                        )
                        val texts = mutableListOf<String>()
                        fun walk(v: View) {
                            if (v is TextView && v.isShown) texts.add(v.text.toString())
                            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                        }
                        walk(activity!!.window.decorView)
                        check("기록 확보 완료" !in texts)
                    }
                    check(file.readText() == original)
                    if (name in setOf("valid_response", "state_mismatch", "empty")) {
                        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                        File(targetContext.filesDir, "emergency-record-$name.png").outputStream()
                            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    results.put(
                        JSONObject().put("case", name).put("state", expected)
                            .put("original_unchanged", true).put("upload_started", false)
                    )
                } finally {
                    runOnMainSync { activity?.finish() }
                    activity = null
                    check(file.renameTo(File(dir, "$key.record-test")))
                }
            }
            File(
                targetContext.filesDir,
                "emergency-record-results.json"
            ).writeText(results.toString(2))
            result.putString("stream", "PASS emergency record cases=${results.length()}\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
