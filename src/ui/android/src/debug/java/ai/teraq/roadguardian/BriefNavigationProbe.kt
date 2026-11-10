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

/** Actual Main rendering and return methods; no control taps or driving experiment. */
class BriefNavigationProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: MainActivity? = null
        try {
            val events = JSONArray(BackendClient().request("/api/events"))
            val all = (0 until events.length()).map { events.getJSONObject(it) }
            screen = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val a = screen
            fun field(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            fun method(n: String) =
                MainActivity::class.java.getDeclaredMethod(n).apply { isAccessible = true }

            var ready = false
            repeat(150) {
                if (!ready) {
                    runOnMainSync {
                        ready =
                            !field("busy").getBoolean(a) && (field("uxMonitor").get(a) as? VehicleUxMonitor)?.restricted == false
                    }
                    if (!ready) Thread.sleep(100)
                }
            }
            check(ready) { "Main not ready in parked state" }
            runOnMainSync {
                (field("timer").get(a) as android.os.Handler).removeCallbacks(
                    field("feedPoll").get(
                        a
                    ) as Runnable
                )
            }
            val report = JSONArray()
            for (kind in listOf("pothole", "crash", "diagnosis")) {
                val record = all.first {
                    it.optJSONObject("assessment")
                        ?.optString("kind") == kind && it.optBoolean("evidence_valid")
                }
                val event =
                    JSONObject(BackendClient().request("/api/events/${record.getString("id")}"))
                for (night in listOf(false, true)) {
                    runOnMainSync {
                        field("selected").set(a, "기록")
                        field("light").setBoolean(a, !night)
                        field("opened").set(a, event)
                        method("render").invoke(a)
                    }
                    waitForIdleSync()
                    Thread.sleep(900)
                    val shown = mutableListOf<String>()
                    val hidden = mutableListOf<String>()
                    runOnMainSync {
                        fun visit(v: View) {
                            if (v is TextView) {
                                if (v.isShown) {
                                    shown.add(v.text.toString())
                                    if (v.text.toString() == "상세 기록 펼치기") {
                                        val rect = android.graphics.Rect()
                                        check(v.getGlobalVisibleRect(rect) && rect.height() == v.height) { "Details entry clipped" }
                                    }
                                } else hidden.add(v.text.toString())
                            }
                            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                        }
                        visit(a.window.decorView)
                        check(shown.count { it == "‹ 기록" } == 1) { "Back hidden: $shown" }
                        check(shown.count { it == "상세 기록 펼치기" } == 1)
                        check(shown.none {
                            it.contains("원본·영상·저장") || it == "비교 자료 저장" || it.contains(
                                "SHA-256"
                            )
                        })
                        check(hidden.contains("비교 자료 저장")) { "Export lost" }
                        check(shown.contains(EventBriefState.from(event).title))
                    }
                    checkNotNull(uiAutomation.takeScreenshot()).let { b ->
                        File(
                            targetContext.filesDir,
                            "brief-navigation-$kind-${if (night) "night" else "day"}-v216.png"
                        ).outputStream()
                            .use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        b.recycle()
                    }
                    runOnMainSync {
                        method("closeEventDetail").invoke(a)
                        check(field("opened").get(a) == null)
                    }
                    report.put(
                        JSONObject().put("kind", kind).put("night", night)
                            .put("visible_texts", JSONArray(shown))
                            .put("export_retained_in_detail", true).put("return_method", true)
                    )
                }
            }
            File(targetContext.filesDir, "brief-navigation-v216.json").writeText(report.toString(2))
            result.putString(
                "stream",
                "PASS: Main three kinds x day/night, visible back, single details entry, export retained and return method. No direct taps.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
