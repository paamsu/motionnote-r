package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.graphics.Rect
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Full launcher scene gallery in actual Main; programmatic parked clicks, not driving validation. */
class ScenarioGalleryProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: MainActivity? = null
        try {
            val fixtures = JSONObject(File(targetContext.filesDir, "gallery-cases.json").readText())
            val main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            activity = main
            fun onUi(block: () -> Unit) {
                var failure: Throwable? = null
                runOnMainSync {
                    try {
                        block()
                    } catch (t: Throwable) {
                        failure = t
                    }
                }
                failure?.let { throw it }
            }

            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

            fun texts(): List<TextView> {
                val out = mutableListOf<TextView>()
                fun walk(v: View) {
                    if (!v.isShown) return
                    if (v is TextView) out.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(main.window.decorView)
                return out
            }

            fun await(test: () -> Boolean) {
                repeat(200) {
                    var ok = false
                    onUi { ok = test() }
                    if (ok) return
                    Thread.sleep(50)
                }
                error("UI timeout")
            }

            fun click(label: String) {
                onUi {
                    val v = texts().single { it.text.toString() == label }
                    v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), true)
                    check(v.performClick())
                }
                waitForIdleSync()
            }

            fun capture(name: String) {
                Thread.sleep(350)
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "gallery-$name.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            await { !field("busy").getBoolean(main) && (field("uxMonitor").get(main) as? VehicleUxMonitor)?.restricted == false }
            val rows = JSONArray()
            for (name in fixtures.keys().asSequence().toList().sorted()) {
                val fixture = fixtures.getJSONObject(name)
                val event = fixture.getJSONObject("event")
                val title = fixture.getString("expected_title")
                val row = JSONObject().put("scene", name).put("event_id", event.getString("id"))
                    .put("expected_title", title)
                try {
                    val actualFontScale = main.resources.configuration.fontScale
                    row.put("actual_font_scale", actualFontScale)
                        .put("light", fixture.optBoolean("light", true))
                    if (fixture.has("expected_font_scale")) check(
                        kotlin.math.abs(
                            actualFontScale - fixture.getDouble(
                                "expected_font_scale"
                            )
                        ) < 0.01
                    ) { "Font scale not applied: $actualFontScale" }
                    onUi {
                        val timer = field("timer").get(main) as Handler
                        for (n in listOf(
                            "feedPoll",
                            "drivingWatch"
                        )) timer.removeCallbacks(field(n).get(main) as Runnable)
                        (field("drivingState").get(main) as DrivingState).apply {
                            useSimulation(true)
                            setSimulatedMoving(false)
                        }
                        field("selected").set(main, "기록")
                        field("opened").set(main, event)
                        field("light").setBoolean(main, fixture.optBoolean("light", true))
                        MainActivity::class.java.getDeclaredMethod("render")
                            .apply { isAccessible = true }.invoke(main)
                    }
                    await { texts().any { it.text.toString() == title && it.height > 0 } }
                    if (fixture.has("expected_address")) {
                        val expected = fixture.getString("expected_address")
                        await { texts().any { it.text.toString() == expected } }
                        check(EventLocationPanel.displayAddress(event) == expected)
                        val original = event.toString()
                        for (key in listOf(
                            "lat",
                            "lon",
                            "road_id",
                            "address_label",
                            "address_source"
                        )) {
                            val altered = JSONObject(original)
                            val loc = altered.getJSONObject("data").getJSONObject("location")
                            loc.put(
                                key, when (key) {
                                    "lat" -> 38.0
                                    "lon" -> 128.0
                                    "road_id" -> "unknown"
                                    else -> ""
                                }
                            )
                            check(EventLocationPanel.displayAddress(altered) == null) { "Unexpected fallback for $key" }
                        }
                        val invalid = JSONObject(original).put("evidence_valid", false)
                        check(EventLocationPanel.displayAddress(invalid) == null)
                        check(event.toString() == original)
                        row.put("address_boundary_checks", 6)
                    }
                    fixture.optString("wait_for_text").takeIf { it.isNotBlank() }
                        ?.let { expected -> await { texts().any { it.text.toString() == expected } } }
                    Thread.sleep(1200)
                    onUi {
                        check(!field("requestFailed").getBoolean(main))
                        row.put("summary_labels", JSONArray(texts().map { it.text.toString() }))
                    }
                    if (fixture.optBoolean("check_evidence_baseline")) await {
                        fun baseline(label: String): Int {
                            val view = texts().single { it.text.toString() == label }
                            val pos = IntArray(2)
                            view.getLocationOnScreen(pos)
                            return pos[1] + view.baseline
                        }

                        val delta = baseline("기록 확보 완료") - baseline("서울특별시 서초구 사평대로 인근")
                        row.put("evidence_baseline_delta_px", delta)
                        kotlin.math.abs(delta) <= 1
                    }
                    capture("$name-summary")
                    click("상세 기록 펼치기")
                    await { texts().any { it.text.toString() == "상세 기록 접기" } }
                    if (fixture.optBoolean("check_visible_video_action")) onUi {
                        val video = texts().single { it.text.toString() == "영상 보기" }
                        val rect = Rect()
                        check(video.getGlobalVisibleRect(rect) && rect.height() == video.height && rect.width() == video.width) { "Video action clipped: $name" }
                        check(video.isEnabled && video.isClickable)
                        row.put("video_action_fully_visible", true)
                    }
                    capture("$name-detail")
                    click("상세 기록 접기")
                    await { texts().none { it.text.toString() == "상세 기록 접기" } }
                    if (fixture.optBoolean("check_video_playback")) {
                        click("상세 기록 펼치기")
                        val monitor = addMonitor(VideoActivity::class.java.name, null, false)
                        val player = try {
                            click("영상 보기")
                            checkNotNull(waitForMonitorWithTimeout(monitor, 10000))
                        } finally {
                            removeMonitor(monitor)
                        }
                        var position = 0
                        try {
                            await {
                                val video = VideoActivity::class.java.getDeclaredField("video")
                                    .apply { isAccessible = true }
                                    .get(player) as android.widget.VideoView
                                position = video.currentPosition
                                video.isPlaying && video.visibility == View.VISIBLE && position > 700
                            }
                            val downloaded =
                                VideoActivity::class.java.getDeclaredField("downloadedFile")
                                    .apply { isAccessible = true }.get(player) as File
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                                .digest(downloaded.readBytes())
                                .joinToString("") { "%02x".format(it) }
                            check(digest == event.getJSONObject("video").getString("sha256"))
                            row.put("video_sha256", digest).put("playing_position_ms", position)
                            capture("$name-playing")
                            if (fixture.optBoolean("check_video_completion")) {
                                var complete = false
                                val deadline = android.os.SystemClock.elapsedRealtime() + 45000
                                while (!complete && android.os.SystemClock.elapsedRealtime() < deadline) {
                                    onUi {
                                        complete =
                                            VideoActivity::class.java.getDeclaredField("completed")
                                                .apply { isAccessible = true }.getBoolean(player)
                                    }
                                    if (!complete) Thread.sleep(100)
                                }
                                check(complete) { "Playback did not complete: $name" }
                                onUi {
                                    val video = VideoActivity::class.java.getDeclaredField("video")
                                        .apply { isAccessible = true }
                                        .get(player) as android.widget.VideoView
                                    check(!video.isPlaying)
                                    row.put("completed_position_ms", video.currentPosition)
                                        .put("duration_ms", video.duration)
                                }
                                row.put("playback_completed", true)
                                capture("$name-completed")
                            }
                        } finally {
                            onUi { player.finish() }
                        }
                        await {
                            (field("opened").get(main) as? JSONObject)?.optString("id") == event.getString(
                                "id"
                            ) && !field("busy").getBoolean(main)
                        }
                        await { texts().any { it.text.toString() == title } }
                        row.put("returned_same_event", true)
                        capture("$name-returned")
                    }
                    row.put("status", "pass")
                } catch (t: Throwable) {
                    row.put("status", "fail").put("error", t.stackTraceToString())
                    capture("$name-failed")
                }
                rows.put(row)
                File(
                    targetContext.filesDir,
                    "gallery-result.json"
                ).writeText(JSONObject().put("cases", rows).toString(2))
            }
            val failures = (0 until rows.length()).count {
                rows.getJSONObject(it).getString("status") != "pass"
            }
            result.putString("stream", "Gallery: ${rows.length()} scenes, $failures failures\n")
            finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        } catch (t: Throwable) {
            result.putString("stream", t.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
