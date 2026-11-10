package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Main fetches the event through its normal demo HTTP route; programmatic UI interaction. */
class CollisionVideoHttpProbe : Instrumentation() {
    private var eventId = ""
    override fun onCreate(a: Bundle?) {
        super.onCreate(a)
        eventId = checkNotNull(a?.getString("event_id"))
        require(AccountEventRoute.checked(eventId) == eventId)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        var player: Activity? = null
        try {
            val client = BackendClient()
            val event = JSONObject(client.request("/api/events/$eventId"))
            check(
                event.getJSONObject("video").getString("input_kind") == "authored_collision_scene"
            )
            val expected = EventBriefState.from(event).title
            check(expected == "차량 영상 기록")
            check(event.getJSONObject("assessment").getString("kind") == "observation")
            check(VideoCollisionAnalysis.checked(event) != null)
            for (flag in listOf("physical_contact_confirmed", "camera_evidence")) {
                val altered = EvidenceJson.copy(event)
                altered.getJSONObject("video_collision_analysis").put(flag, true)
                check(VideoCollisionAnalysis.checked(altered) == null)
                check(EventBriefState.from(altered).title == "영상 기록 확인 필요")
            }
            val archive = File(targetContext.filesDir, "collision-video-http-evidence.zip")
            archive.writeBytes(client.evidencePackage(eventId))
            val verified = EvidencePackageVerifier.verify(archive, eventId)
            File(
                targetContext.filesDir,
                "collision-video-http-event.json"
            ).writeText(EvidenceJson.encode(event))
            main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).putExtra("review_event_id", eventId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val activity = main
            fun field(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            fun texts(): List<TextView> {
                val out = mutableListOf<TextView>()
                fun walk(v: View) {
                    if (!v.isShown) return
                    if (v is TextView) out.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(activity.window.decorView)
                return out
            }

            fun waitFor(test: () -> Boolean) {
                repeat(200) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(50)
                }
                error("UI timeout")
            }

            fun capture(name: String) {
                Thread.sleep(300)
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, name).outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            waitFor {
                (field("opened").get(activity) as? JSONObject)?.optString("id") == eventId && !field(
                    "busy"
                ).getBoolean(activity) && (field("uxMonitor").get(activity) as? VehicleUxMonitor)?.restricted == false
            }
            for (light in listOf(true, false)) {
                runOnMainSync {
                    val handler = field("timer").get(activity) as Handler
                    for (n in listOf(
                        "feedPoll",
                        "drivingWatch"
                    )) handler.removeCallbacks(field(n).get(activity) as Runnable)
                    (field("drivingState").get(activity) as DrivingState).apply {
                        useSimulation(true)
                        setSimulatedMoving(false)
                    }
                    check((field("opened").get(activity) as JSONObject).getString("id") == eventId)
                    field("light").setBoolean(activity, light)
                    MainActivity::class.java.getDeclaredMethod("render")
                        .apply { isAccessible = true }.invoke(activity)
                }
                waitFor { texts().any { it.text.toString() == "상세 기록 펼치기" } }
                capture("collision-video-http-${if (light) "day" else "night"}-main.png")
                runOnMainSync {
                    val title = texts().single { it.text.toString() == expected }
                    check((0 until title.layout.lineCount).all { title.layout.getEllipsisCount(it) == 0 })
                    check(texts().none { it.text.toString() in setOf("포트홀 감지", "방지턱 후보 확인") })
                }
            }
            runOnMainSync { texts().single { it.text.toString() == "상세 기록 펼치기" }.performClick() }
            waitFor { texts().any { it.text.toString() == "합성 입력 영상" } }
            if (VideoCollisionAnalysis.present(event)) {
                check(VideoCollisionAnalysis.checked(event) != null)
                waitFor { texts().any { it.text.toString() == VideoCollisionAnalysis.detail(event) } }
                runOnMainSync {
                    texts().last {
                        it.text.toString() == VideoCollisionAnalysis.detail(
                            event
                        )
                    }.let {
                        it.requestRectangleOnScreen(
                            android.graphics.Rect(
                                0,
                                0,
                                it.width,
                                it.height + 160
                            ), true
                        )
                    }
                }
                capture("collision-video-http-analysis.png")
                val altered = EvidenceJson.copy(event)
                altered.getJSONObject("video_collision_analysis").put("motion", "separating")
                if (event.getJSONObject("video_collision_analysis")
                        .getString("motion") != "separating"
                ) check(VideoCollisionAnalysis.checked(altered) == null)
            }
            val playback = JSONObject()
            if (event.getJSONObject("video")
                    .optString("input_kind") == "authored_collision_scene"
            ) {
                val monitor = addMonitor(VideoActivity::class.java.name, null, false)
                try {
                    runOnMainSync {
                        texts().single { it.text.toString() == "합성 입력 영상" }.performClick()
                    }
                    player = checkNotNull(waitForMonitorWithTimeout(monitor, 10000))
                } finally {
                    removeMonitor(monitor)
                }
                val screen = checkNotNull(player)
                var position = 0
                var duration = 0
                waitFor {
                    val video = VideoActivity::class.java.getDeclaredField("video")
                        .apply { isAccessible = true }.get(screen) as android.widget.VideoView
                    position = video.currentPosition
                    duration = video.duration
                    video.isPlaying && video.visibility == View.VISIBLE && position > 700
                }
                val downloaded = VideoActivity::class.java.getDeclaredField("downloadedFile")
                    .apply { isAccessible = true }.get(screen) as File
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(downloaded.readBytes()).joinToString("") { "%02x".format(it) }
                check(digest == event.getJSONObject("video").getString("sha256"))
                val extractor = android.media.MediaExtractor()
                val times = mutableListOf<Long>()
                try {
                    extractor.setDataSource(downloaded.absolutePath)
                    val track = (0 until extractor.trackCount).single {
                        extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)
                            ?.startsWith("video/") == true
                    }
                    extractor.selectTrack(track)
                    do {
                        val at = extractor.sampleTime
                        if (at < 0) break
                        times.add(at)
                    } while (extractor.advance())
                } finally {
                    extractor.release()
                }
                capture("collision-video-http-playing.png")
                runOnMainSync { screen.finish() }
                player = null
                waitFor {
                    (field("opened").get(activity) as? JSONObject)?.optString("id") == eventId && !field(
                        "busy"
                    ).getBoolean(activity)
                }
                playback.put("position_ms", position).put("duration_ms", duration)
                    .put("source_sha256", digest).put("pts_us", org.json.JSONArray(times.sorted()))
                    .put("returned_same_event", true)
            }
            File(targetContext.filesDir, "collision-video-http-native-result.json").writeText(
                JSONObject().put("zip_files", verified.files).put("zip_verified", true)
                    .put("playback", playback).put(
                    "scope",
                    "Actual demo HTTP event fetch and ZIP download; Main review_event_id route; programmatic disclosure and theme/simulation configuration, no opened-event injection, human taps or physical vehicle."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: native collision analysis, archive, Main day/night and playback verified.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                player?.finish()
                main?.finish()
            }
        }
    }
}
