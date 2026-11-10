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
class PlateHttpEvidenceProbe : Instrumentation() {
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
            check(PlateAnalysis.checked(event) != null)
            if (event.getJSONObject("video")
                    .optString("input_kind") == "authored_moving_plate_scene" && event.getJSONObject(
                    "plate_analysis"
                ).getJSONObject("agreement").getJSONArray("rows").let { a ->
                    (0 until a.length()).any {
                        a.getJSONObject(it).getString("status") == "inconclusive"
                    }
                }
            ) check(PlateAnalysis.detail(event).contains("판독 보류"))
            File(targetContext.filesDir, "plate-http-event.json").writeText(
                EvidenceJson.encode(
                    event
                )
            )
            val archive = File(targetContext.filesDir, "plate-http-evidence.zip")
            archive.writeBytes(client.evidencePackage(eventId))
            val verified = EvidencePackageVerifier.verify(archive, event.getString("id"))
            val bad = JSONObject(event.toString())
            bad.getJSONObject("plate_analysis").put("input_photo_sha256", "0".repeat(64))
            check(PlateAnalysis.checked(bad) == null)
            val badProfile = JSONObject(event.toString())
            badProfile.getJSONObject("plate_analysis").getJSONObject("bundle")
                .put("agreement_profile", "unknown")
            check(PlateAnalysis.checked(badProfile) == null)
            val badSchema = JSONObject(event.toString())
            badSchema.getJSONObject("plate_analysis").getJSONObject("agreement")
                .put("schema", "road-guardian.plate-text-agreement.v999")
            check(PlateAnalysis.checked(badSchema) == null)
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
                capture("plate-http-${if (light) "day" else "night"}-main.png")
                runOnMainSync {
                    texts().single { it.text.toString() == "상세 기록 펼치기" }.performClick()
                }
                waitFor { texts().any { it.text.toString() == PlateAnalysis.detail(event) && it.height > 0 } }
                runOnMainSync {
                    texts().last { it.text.toString() == PlateAnalysis.detail(event) }.let {
                        it.requestRectangleOnScreen(
                            android.graphics.Rect(
                                0,
                                0,
                                it.width,
                                it.height + 120
                            ), true
                        )
                    }
                }
                capture("plate-http-${if (light) "day" else "night"}-detail.png")
                if (PlateVideoAnalysis.present(event)) {
                    check(PlateVideoAnalysis.checked(event) != null)
                    waitFor { texts().any { it.text.toString() == PlateVideoAnalysis.detail(event) } }
                    runOnMainSync {
                        texts().last {
                            it.text.toString() == PlateVideoAnalysis.detail(
                                event
                            )
                        }.let {
                            it.requestRectangleOnScreen(
                                android.graphics.Rect(
                                    0,
                                    0,
                                    it.width,
                                    it.height + 100
                                ), true
                            )
                        }
                    }
                    capture("plate-http-${if (light) "day" else "night"}-timeline.png")
                }
            }
            val mutationResults = org.json.JSONArray()
            if (PlateVideoAnalysis.present(event)) {
                val directory = File(targetContext.filesDir, "plate-video-mutations-v307")
                for (file in checkNotNull(directory.listFiles()).filter { it.extension == "zip" }
                    .sortedBy { it.name }) {
                    val accepted =
                        runCatching { EvidencePackageVerifier.verify(file, eventId) }.isSuccess
                    check(accepted == (file.name == "valid.zip")) { "Unexpected mutation result: ${file.name} accepted=$accepted" }
                    mutationResults.put(
                        JSONObject().put("file", file.name).put("accepted", accepted)
                    )
                }
                check(mutationResults.length() == 7)
            }
            val playback = JSONObject()
            if (event.getJSONObject("video")
                    .optString("input_kind") == "authored_moving_plate_scene"
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
                capture("plate-http-playing.png")
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
            File(
                targetContext.filesDir,
                "plate-http-native-result.json"
            ).writeText(
                JSONObject().put("mutation_results", mutationResults)
                    .put("zip_files", verified.files).put("zip_verified", true)
                    .put("playback", playback).put("photo_rebinding_rejected", true).put(
                    "scope",
                    "Actual demo HTTP event fetch and ZIP download; Main review_event_id route; programmatic disclosure and theme/simulation configuration, no opened-event injection, human taps or physical vehicle."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: native archive verification, photo rebinding rejection, Main day/night captures.\n"
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
