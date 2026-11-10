package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File

class EventMapProbe : Instrumentation() {
    override fun onCreate(a: Bundle?) {
        super.onCreate(a)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            val client = BackendClient("http://127.0.0.1:9464")
            val id =
                JSONObject(client.request("/api/dev/surround-views-fixture")).getJSONObject("ids")
                    .getString("pothole")
            val event = JSONObject(client.request("/api/events/$id"))
            check(EventLocationPanel.location(event) != null)
            for (bad in listOf(Double.NaN, 91.0, "37.5", true)) {
                val changed = JSONObject(event.toString())
                changed.getJSONObject("data").getJSONObject("location")
                    .put("lat", if (bad is Double && bad.isNaN()) JSONObject.NULL else bad)
                check(EventLocationPanel.location(changed) == null)
            }
            val missing = JSONObject(event.toString())
            missing.getJSONObject("data").remove("location")
            check(EventLocationPanel.location(missing) == null)
            RoadSurroundBriefPreviewActivity.endpoint = "http://127.0.0.1:9464"
            val file = File(targetContext.filesDir, "event-map/$id-${event.getString("hash")}.json")
            val prefs = targetContext.getSharedPreferences(
                "road_demo_receipts",
                android.content.Context.MODE_PRIVATE
            )
            val prefix = "$id:${event.getString("hash")}:"
            prefs.edit().remove(prefix + "report").remove(prefix + "compensation").commit()
            var original: ByteArray? = null
            for (night in listOf(false, true)) {
                activity = startActivitySync(
                    Intent(
                        targetContext,
                        RoadSurroundBriefPreviewActivity::class.java
                    ).putExtra("event", event.toString()).putExtra("night", night)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                val screen = activity!!
                fun nodes(): List<View> {
                    val out = mutableListOf<View>()
                    fun walk(v: View) {
                        if (!v.isShown) return
                        out.add(v)
                        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                    }
                    walk(screen.window.decorView)
                    return out
                }

                var done = false
                repeat(1200) {
                    if (!done) {
                        runOnMainSync {
                            done = nodes().filterIsInstance<TextView>().map { it.text.toString() }
                                .let { it.contains("사건 위치 · 지도 보관") && it.contains("기록 확보 완료") }
                        }
                        if (!done) Thread.sleep(50)
                    }
                }
                if (!done) {
                    runOnMainSync {
                        File(targetContext.filesDir, "event-map-labels.txt").writeText(
                            nodes().filterIsInstance<TextView>()
                                .joinToString("\n") { it.text.toString() })
                    }
                    uiAutomation.takeScreenshot()?.let { b ->
                        File(targetContext.filesDir, "event-map-failure.png").outputStream()
                            .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        b.recycle()
                    }
                    error("Map snapshot not persisted")
                }
                val bytes = file.readBytes()
                val record = JSONObject(bytes.toString(Charsets.UTF_8))
                check(
                    record.getString("event_id") == id && record.getString("event_hash") == event.getString(
                        "hash"
                    )
                )
                check(record.getString("source") == "app_map_render_at_event_coordinates")
                check(
                    record.getDouble("lat") == event.getJSONObject("data").getJSONObject("location")
                        .getDouble("lat")
                )
                if (original == null) original = bytes else check(original!!.contentEquals(bytes))
                Thread.sleep(500)
                runOnMainSync {
                    val all = nodes()
                    check(all.filterIsInstance<TextView>().any { it.text.toString() == "기록 확보 완료" })
                    val map = all.single { it.contentDescription == "사건 좌표 기준 지도 스냅샷" }
                    val rect = Rect()
                    check(map.getGlobalVisibleRect(rect) && rect.width() == map.width && rect.height() == map.height)
                    check(all.filterIsInstance<TextView>().none { it.text.toString() == "주변 차량" })
                }
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(
                    targetContext.filesDir,
                    "event-map-${if (night) "night" else "day"}.png"
                ).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                if (!night) {
                    for (label in listOf("안전신문고 전송", "피해보상 접수")) {
                        Thread.sleep(250)
                        var issue: Throwable? = null
                        runOnMainSync {
                            try {
                                val button = nodes().filterIsInstance<android.widget.Button>()
                                    .single { it.text.toString() == label }
                                val r = Rect()
                                check(button.getGlobalVisibleRect(r) && r.height() == button.height)
                                check(button.performClick())
                            } catch (t: Throwable) {
                                issue = t
                            }
                        }
                        issue?.let { throw it }
                    }
                    check(
                        prefs.getBoolean(
                            prefix + "report",
                            false
                        ) && prefs.getBoolean(prefix + "compensation", false)
                    )
                    check(
                        !prefs.getBoolean(
                            prefix + "report:external_sent",
                            true
                        ) && !prefs.getBoolean(prefix + "compensation:external_sent", true)
                    )
                    Thread.sleep(400)
                    val receipt = checkNotNull(uiAutomation.takeScreenshot())
                    File(targetContext.filesDir, "event-map-receipts.png").outputStream()
                        .use { receipt.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    receipt.recycle()
                } else {
                    var ready = false
                    runOnMainSync {
                        ready = nodes().filterIsInstance<TextView>().map { it.text.toString() }
                            .containsAll(listOf("안전신문고 전송 완료", "피해보상 접수 완료"))
                    }
                    check(ready)
                }
                File(targetContext.filesDir, "event-map-record.json").writeBytes(bytes)
                runOnMainSync { screen.finish() }
                activity = null
            }
            result.putString(
                "stream",
                "PASS: event-bound map snapshot persisted, same bytes on reopen, day/night native layout, invalid coordinates rejected\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
