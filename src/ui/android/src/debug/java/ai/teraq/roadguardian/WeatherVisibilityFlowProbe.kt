package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Programmatic native video provenance and archive validation; no physical vehicle test. */
class WeatherVisibilityFlowProbe : Instrumentation() {
    private var freshDriving = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        freshDriving = arguments?.getString("fresh_driving") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        var player: Activity? = null
        try {
            val client = BackendClient("http://127.0.0.1:9494")
            val manifest = JSONObject(client.request("/api/dev/weather-visibility-fixture"))
            val ids = manifest.getJSONObject("ids")
            main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val activity = main
            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

            fun method(name: String) =
                MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }

            fun await(label: String, test: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 18000
                while (SystemClock.elapsedRealtime() < deadline) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(50)
                }
                error("Timeout: $label")
            }

            fun labels(screen: Activity): List<TextView> {
                val nodes = mutableListOf<TextView>()
                fun visit(view: View) {
                    if (!view.isShown) return
                    if (view is TextView) nodes.add(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                        view.getChildAt(
                            i
                        )
                    )
                }
                visit(screen.window.decorView)
                return nodes
            }

            data class PixelAnchor(val bitmap: Bitmap, val left: Int, val top: Int, val ink: Int)

            fun frame(screen: Activity, name: String, vararg expected: String) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val anchors = mutableListOf<PixelAnchor>()
                    runOnMainSync {
                        for (text in expected) labels(screen).firstOrNull { it.text.toString() == text && it.layout?.text?.toString() == text && !it.isLayoutRequested && it.width > 0 && it.height > 0 }
                            ?.let { view ->
                                val at = IntArray(2)
                                view.getLocationOnScreen(at)
                                val bitmap = Bitmap.createBitmap(
                                    view.width,
                                    view.height,
                                    Bitmap.Config.ARGB_8888
                                ).also { view.draw(Canvas(it)) }
                                anchors.add(
                                    PixelAnchor(
                                        bitmap,
                                        at[0],
                                        at[1],
                                        view.currentTextColor
                                    )
                                )
                            }
                    }
                    if (anchors.size != expected.size) {
                        anchors.forEach { it.bitmap.recycle() }
                        Thread.sleep(60)
                        continue
                    }
                    val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                    val presented = try {
                        anchors.all { (mask, left, top, ink) ->
                            var pixels = 0
                            var matched = 0
                            for (y in 0 until mask.height) for (x in 0 until mask.width) {
                                val value = mask.getPixel(x, y)
                                if (Color.alpha(value) < 245 || kotlin.math.abs(
                                        Color.red(value) - Color.red(
                                            ink
                                        )
                                    ) > 24 || kotlin.math.abs(Color.green(value) - Color.green(ink)) > 24 || kotlin.math.abs(
                                        Color.blue(value) - Color.blue(ink)
                                    ) > 24
                                ) continue
                                pixels++
                                if (left + x !in 0 until screenshot.width || top + y !in 0 until screenshot.height) continue
                                val actual = screenshot.getPixel(left + x, top + y)
                                if (kotlin.math.abs(Color.red(value) - Color.red(actual)) < 12 && kotlin.math.abs(
                                        Color.green(value) - Color.green(actual)
                                    ) < 12 && kotlin.math.abs(Color.blue(value) - Color.blue(actual)) < 12
                                ) matched++
                            }
                            pixels > 30 && matched >= pixels * .97
                        }
                    } finally {
                        anchors.forEach { it.bitmap.recycle() }
                    }
                    if (presented && SystemClock.elapsedRealtime() <= deadline) {
                        File(targetContext.filesDir, name).outputStream()
                            .use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        screenshot.recycle()
                        return
                    }
                    screenshot.recycle()
                    Thread.sleep(60)
                }
                error("Native expected pixels absent: $name / ${expected.toList()}")
            }

            fun stopPoll() {
                val handler = field("timer").get(activity) as Handler
                for (name in listOf(
                    "feedPoll",
                    "drivingWatch"
                )) handler.removeCallbacks(field(name).get(activity) as Runnable)
            }

            fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            await("idle/OEM allowance") {
                !field("busy").getBoolean(activity) && !field("comparisonLoading").getBoolean(
                    activity
                ) && (field("uxMonitor").get(activity) as? VehicleUxMonitor)?.restricted == false
            }
            runOnMainSync {
                stopPoll()
                field("client").set(activity, client);
                (field("drivingState").get(activity) as DrivingState).apply {
                    useSimulation(true)
                    setSimulatedMoving(false)
                }
                field("selected").set(activity, "기록")
                field("light").setBoolean(activity, true)
            }
            val titles = mapOf(
                "accumulate" to "전방 시야 저하",
                "wipe" to "워셔 사용 구간",
                "illumination" to "시야 변화 확인 중"
            )
            val provenance = "합성 영상 판독 · 센서 판단과 별도"
            val rows = JSONArray()
            for (case in listOf("accumulate", "wipe", "illumination")) {
                if (freshDriving) {
                    runOnMainSync {
                        stopPoll()
                        field("driverEventNotice").set(activity, DriverEventNoticeState())
                        method("pollComparisons").invoke(activity)
                    }
                    await("baseline poll") { !field("comparisonLoading").getBoolean(activity) }
                }
                val event = if (freshDriving) JSONObject(
                    client.request(
                        "/api/dev/weather-media-create",
                        JSONObject().put("case", case)
                            .put("request_key", java.util.UUID.randomUUID().toString())
                    )
                ) else JSONObject(client.request("/api/events/" + ids.getString(case)))
                val id = event.getString("id")
                check(event.get("evidence_valid") == true && event.getString("source") == "synthetic")
                val report =
                    checkNotNull(VideoVisibilityAnalysis.checked(event)) { "Invalid video analysis: $case" }
                check(EventBriefState.from(event).title == titles.getValue(case))
                for (light in listOf(true, false)) {
                    val theme = if (light) "day" else "night"
                    if (freshDriving) {
                        runOnMainSync {
                            stopPoll()
                            field("light").setBoolean(activity, light)
                            field("opened").set(activity, null);
                            (field("drivingState").get(activity) as DrivingState).setSimulatedMoving(
                                true
                            )
                            method("render").invoke(activity)
                            method("pollComparisons").invoke(activity)
                        }
                        await("new media event poll") {
                            !field("comparisonLoading").getBoolean(
                                activity
                            )
                        }
                        runOnMainSync {
                            val current =
                                (field("driverEventNotice").get(activity) as DriverEventNoticeState).current(
                                    SystemClock.elapsedRealtime()
                                )
                            check(current?.getString("id") == id) { "Notice event mismatch" }
                            check(
                                VideoVisibilityAnalysis.checked(checkNotNull(current))
                                    ?.getString("input_video_sha256") == report.getString("input_video_sha256")
                            )
                            check(labels(activity).none { it.text.toString() == "상세 기록 펼치기" || it.text.toString() == "영상 보기" })
                        }
                        frame(
                            activity,
                            "weather-visibility-$case-$theme-driving.png",
                            titles.getValue(case)
                        )
                        runOnMainSync {
                            (field("drivingState").get(activity) as DrivingState).setSimulatedMoving(
                                false
                            )
                        }
                    }
                    runOnMainSync {
                        stopPoll()
                        field("light").setBoolean(activity, light)
                        field("opened").set(activity, null)
                        field("detailReturnId").set(activity, id)
                        method("refreshReturnedDetail").invoke(activity)
                    }
                    await("$case fresh Main") {
                        field("detailReturnId").get(activity) == null && (field(
                            "opened"
                        ).get(activity) as? JSONObject)?.optString("id") == id && labels(activity).any {
                            it.text.toString() == titles.getValue(
                                case
                            )
                        }
                    }
                    check(!labelsSafe(activity).contains(provenance))
                    frame(
                        activity,
                        "weather-visibility-$case-$theme-main.png",
                        titles.getValue(case),
                        "상세 기록 펼치기"
                    )
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "상세 기록 펼치기" }.performClick()
                    }
                    await("$case original toggle") { labels(activity).any { it.text.toString() == "원본 데이터 펼치기" } }
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "원본 데이터 펼치기" }
                            .performClick()
                    }
                    await("$case detail") { labels(activity).any { it.text.toString() == provenance && it.height > 0 && !it.isLayoutRequested } }
                    runOnMainSync {
                        check(labels(activity).any { it.text.toString() == "합성 시연 · 실제 차량 측정 자료 아님" })
                        check(labels(activity).none {
                            it.text.toString() == "충격 위치" || it.text.toString().contains("차량·충격 위치")
                        })
                    }
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "합성 시연 · 실제 차량 측정 자료 아님" }
                            .let {
                                it.requestRectangleOnScreen(
                                    android.graphics.Rect(
                                        0,
                                        0,
                                        it.width,
                                        it.height
                                    ), true
                                )
                            }
                    }
                    frame(
                        activity,
                        "weather-visibility-$case-$theme-source.png",
                        "합성 시연 · 실제 차량 측정 자료 아님"
                    )
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == provenance }.let {
                            it.requestRectangleOnScreen(
                                android.graphics.Rect(
                                    0,
                                    0,
                                    it.width,
                                    it.height + 190
                                ), true
                            )
                        }
                    }
                    frame(
                        activity,
                        "weather-visibility-$case-$theme-detail.png",
                        provenance,
                        VideoVisibilityAnalysis.detail(event)
                    )
                    val limitation = "조명·부분 와이퍼·시간 순서 오판 가능 · 실제 시야 확인 아님"
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == limitation }.let {
                            it.requestRectangleOnScreen(
                                android.graphics.Rect(
                                    0,
                                    0,
                                    it.width,
                                    it.height + 70
                                ), true
                            )
                        }
                    }
                    frame(
                        activity,
                        "weather-visibility-$case-$theme-limits.png",
                        limitation,
                        "영상과 센서는 독립 작성 자료 · 시간 동기화 미검증"
                    )
                    runOnMainSync {
                        check(labels(activity).none {
                            it.text.toString() == report.getString(
                                "reason"
                            )
                        })
                    }
                }
                val monitor = addMonitor(VideoActivity::class.java.name, null, false)
                try {
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "영상 보기" }.performClick()
                    }
                    player = checkNotNull(waitForMonitorWithTimeout(monitor, 10000))
                } finally {
                    removeMonitor(monitor)
                }
                val screen = checkNotNull(player)
                var position = 0
                var duration = 0
                await("$case real playback") {
                    val video = VideoActivity::class.java.getDeclaredField("video")
                        .apply { isAccessible = true }.get(screen) as android.widget.VideoView
                    position = video.currentPosition
                    duration = video.duration
                    video.isPlaying && video.visibility == View.VISIBLE && position > 1200
                }
                val downloaded = VideoActivity::class.java.getDeclaredField("downloadedFile")
                    .apply { isAccessible = true }.get(screen) as File
                check(sha(downloaded.readBytes()) == report.getString("input_video_sha256"))
                val pts = report.getJSONObject("result").getJSONObject("video_frames")
                    .getJSONArray("all_frame_pts_seconds")
                val sourceTimes = (0 until pts.length()).map {
                    kotlin.math.round(pts.getDouble(it) * 1_000_000).toLong()
                }
                val extractor = android.media.MediaExtractor()
                val actualTimes = mutableListOf<Long>()
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
                        actualTimes.add(at)
                    } while (extractor.advance())
                } finally {
                    extractor.release()
                }
                // MP4 samples can be stored in decode order; compare their presentation times.
                check(actualTimes.sorted() == sourceTimes) { "$case Android sample PTS differ from original video" }
                val frameStepMs =
                    ((sourceTimes.last() - sourceTimes[sourceTimes.lastIndex - 1]) / 1000).toInt()
                val sourceDurationMs =
                    ((sourceTimes.last() - sourceTimes.first()) / 1000).toInt() + frameStepMs
                check(sourceDurationMs == 6000 && duration in sourceDurationMs - frameStepMs..sourceDurationMs + frameStepMs) { "$case player duration=$duration source=$sourceDurationMs frameStep=$frameStepMs" }
                frame(screen, "weather-visibility-$case-playing.png", "재생 중")
                await("$case playback completed") {
                    VideoActivity::class.java.getDeclaredField("completed")
                        .apply { isAccessible = true }
                        .getBoolean(screen) && labels(screen).any { it.text.toString() == "재생 완료" }
                }
                frame(screen, "weather-visibility-$case-completed.png", "사건 영상", "재생 완료")
                runOnMainSync {
                    labels(screen).single { it.text.toString() == "기록으로 돌아가기" }.performClick()
                }
                player = null
                await("$case return to same record") {
                    field("detailReturnId").get(activity) == null && (field(
                        "opened"
                    ).get(activity) as? JSONObject)?.optString("id") == id && labels(activity).any {
                        it.text.toString() == titles.getValue(
                            case
                        )
                    }
                }
                runOnMainSync { stopPoll() }
                val zip = File(targetContext.filesDir, "weather-visibility-$case.zip").apply {
                    writeBytes(client.evidencePackage(id))
                }
                val verified = EvidencePackageVerifier.verify(zip, id)
                ZipFile(zip).use { archive ->
                    val readme = archive.getInputStream(archive.getEntry("README.txt")).readBytes()
                        .toString(Charsets.UTF_8)
                    check(readme.contains("may miss partially visible wipers") && readme.contains("Not observed does not prove absence"))
                    val stored = JSONObject(
                        archive.getInputStream(archive.getEntry("video-visibility-analysis.json"))
                            .readBytes().toString(Charsets.UTF_8)
                    )
                    check(stored.getString("raw_output") == report.getString("raw_output"))
                    val video = archive.getInputStream(archive.getEntry("test-pattern-video.mp4"))
                        .readBytes()
                    check(sha(video) == report.getString("input_video_sha256"))
                    for (i in 0..5) {
                        val f = stored.getJSONArray("frames").getJSONObject(i)
                        check(
                            sha(
                                archive.getInputStream(archive.getEntry(f.getString("path")))
                                    .readBytes()
                            ) == f.getString("sha256")
                        )
                    }
                }
                rows.put(
                    JSONObject().put("case", case).put("event_id", id)
                        .put("sensor_sha256", event.getString("hash"))
                        .put("title", titles.getValue(case))
                        .put("video_observation", report.getString("visibility_change"))
                        .put("fresh_http_driving_notice_same_event", freshDriving)
                        .put("actual_glyph_pixels_verified", true).put("day_and_night", true)
                        .put("details_initially_collapsed", true)
                        .put("detail_provenance_verified", true)
                        .put("original_video_and_six_frames_in_zip", true)
                        .put("playback_position_ms", position).put("playback_duration_ms", duration)
                        .put("source_duration_ms", sourceDurationMs)
                        .put("source_frame_step_ms", frameStepMs)
                        .put("android_sample_pts_match", true)
                        .put("download_matches_original", true).put("returned_same_record", true)
                        .put("zip_verified", true).put("zip_files", verified.files)
                        .put("zip_sha256", sha(zip.readBytes()))
                )
            }
            File(targetContext.filesDir, "weather-visibility-flow.json").writeText(
                JSONObject().put(
                    "pass",
                    true
                ).put("cases", rows).put(
                    "scope",
                    "Actual Main fresh HTTP routes, programmatic disclosure, day/night visible glyph captures and original video/six model frames ZIP verification. Synthetic video observations separate from independently authored sensor timeline. No physical vehicle test or human taps."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: three video observations, six Main/details captures and original ZIP.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            runCatching {
                uiAutomation.takeScreenshot()?.let { b ->
                    File(targetContext.filesDir, "weather-visibility-failure.png").outputStream()
                        .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    b.recycle()
                }
            }
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                player?.finish()
                main?.finish()
            }
        }
    }

    private fun labelsSafe(activity: Activity): List<String> {
        val values = mutableListOf<String>()
        runOnMainSync {
            fun walk(v: View) {
                if (!v.isShown) return
                if (v is TextView) values.add(v.text.toString())
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(activity.window.decorView)
        }
        return values
    }
}
