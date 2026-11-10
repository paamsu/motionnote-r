package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Local synthetic HTTP comparison-to-Main flow; no UI taps, push, OEM changes or actual driving. */
class ComparisonSpeechMainProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: MainActivity? = null
        try {
            activity = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val a = activity
            fun field(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            val render =
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
            val poll = MainActivity::class.java.getDeclaredMethod("pollComparisons")
                .apply { isAccessible = true }
            val notice = field("comparisonNotice").get(a) as ComparisonNoticeState
            fun waitUntil(test: () -> Boolean) {
                val end = System.currentTimeMillis() + 15000
                while (System.currentTimeMillis() < end) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(50)
                }
                error("Main notice condition timeout")
            }

            fun shown(): List<String> {
                val values = mutableListOf<String>()
                fun visit(v: View) {
                    if (v is TextView && v.isShown) values.add(v.text.toString())
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(a.window.decorView)
                return values
            }
            waitUntil {
                !field("busy").getBoolean(a) && !field("comparisonLoading").getBoolean(a) && (field(
                    "uxMonitor"
                ).get(a) as? VehicleUxMonitor)?.restricted == false
            }
            // This probe exercises the explicitly enabled path, without changing saved settings.
            runOnMainSync { field("comparisonSpeechEnabled").setBoolean(a, true) }
            val client = BackendClient()
            val event = JSONObject(
                client.request(
                    "/api/scenarios/run",
                    JSONObject().put("scenario", "diagnosis")
                        .put("request_key", "notice-v206-${UUID.randomUUID()}")
                )
            )
            val id = event.getString("id")
            runOnMainSync {
                notice.invalidate()
                poll.invoke(a)
            }
            waitUntil { !field("comparisonLoading").getBoolean(a) }
            runOnMainSync { check(!notice.active(android.os.SystemClock.elapsedRealtime())) }
            client.request(
                "/api/events/$id/repair-offer",
                JSONObject().put("brand", "Hyundai").put("warranty", "covered")
            )
            client.request(
                "/api/events/$id/repair-complete",
                JSONObject().put("scenario", "healthy")
                    .put("technician_note", "v206 synthetic comparison notice input")
            )
            val compared = JSONObject(client.request("/api/events/$id"))
            check(EvidenceScope.canNotifyComparison(compared))
            runOnMainSync { poll.invoke(a) }
            waitUntil { notice.active(android.os.SystemClock.elapsedRealtime()) }
            val speech = field("comparisonSpeech").get(a) as ComparisonSpeech
            waitUntil { speech.completions == 1 }
            check(speech.starts == 1) { "Automatic notice speech did not start exactly once" }
            val rows = JSONArray()
            runOnMainSync {
                render.invoke(a)
                check(shown().contains("시험 비교 자료 준비"))
                rows.put(
                    JSONObject().put("case", "parked live HTTP result")
                        .put("texts", JSONArray(shown()))
                )
            }
            runOnMainSync {
                val state = field("drivingState").get(a) as DrivingState
                state.useSimulation(true)
                state.setSimulatedMoving(true)
                render.invoke(a)
                val text = shown()
                check(text.contains("시험 비교 자료 준비") && text.contains("자세한 내용은 주차 후 확인하세요"))
                check(text.none { it.contains(id) })
                rows.put(
                    JSONObject().put(
                        "case",
                        "debug simulated drive with unchanged OEM permission"
                    ).put("texts", JSONArray(text))
                )
            }
            waitForIdleSync()
            Thread.sleep(300)
            uiAutomation.takeScreenshot()?.let { b ->
                File(targetContext.filesDir, "comparison-notice-main-v206.png").outputStream()
                    .use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            runOnMainSync {
                (field("uxMonitor").get(a) as VehicleUxMonitor).close()
                render.invoke(a)
                check(shown().none { it == "시험 비교 자료 준비" })
                rows.put(
                    JSONObject().put("case", "OEM unknown hides notice")
                        .put("texts", JSONArray(shown()))
                )
                callActivityOnPause(a)
                check(!notice.active(android.os.SystemClock.elapsedRealtime()))
            }
            File(
                targetContext.filesDir,
                "comparison-notice-live-v206.json"
            ).writeText(
                JSONObject().put("event_id", id).put("source", "synthetic")
                    .put("automatic_speech_starts", speech.starts)
                    .put("automatic_speech_completions", speech.completions).put(
                    "comparison_state",
                    compared.getJSONObject("case_overview").getString("state")
                ).put("rows", rows).put(
                    "scope",
                    "actual local HTTP to Main, debug simulated D only, no real vehicle motion or direct taps"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: actual local comparison automatically completed one bundled spoken notice; baseline silent, parked and debug D shown, OEM unknown and pause hidden.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
