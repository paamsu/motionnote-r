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
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Live authored sensor fixtures; actual Main methods and playback, not taps or camera inference. */
class DiagnosticMediaFlowProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        var player: Activity? = null
        try {
            val client = BackendClient("http://127.0.0.1:9464")
            val ids = JSONObject(client.request("/api/dev/diagnostic-media-fixture"))
            val rows = JSONArray()
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

            fun frame(screen: Activity, name: String, vararg expected: String) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val anchors = mutableListOf<Triple<Bitmap, Int, Int>>()
                    runOnMainSync {
                        for (text in expected) labels(screen).firstOrNull { it.text.toString() == text && it.width > 0 && it.height > 0 }
                            ?.let { view ->
                                val at = IntArray(2)
                                view.getLocationOnScreen(at)
                                val bitmap = Bitmap.createBitmap(
                                    view.width,
                                    view.height,
                                    Bitmap.Config.ARGB_8888
                                ).also { view.draw(Canvas(it)) }
                                anchors.add(Triple(bitmap, at[0], at[1]))
                            }
                    }
                    if (anchors.size != expected.size) {
                        anchors.forEach { it.first.recycle() }
                        Thread.sleep(60)
                        continue
                    }
                    val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                    val presented = try {
                        anchors.all { (mask, left, top) ->
                            var pixels = 0
                            var matched = 0
                            for (y in 0 until mask.height) for (x in 0 until mask.width) {
                                val value = mask.getPixel(x, y)
                                if (Color.alpha(value) < 245) continue
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
                        anchors.forEach { it.first.recycle() }
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

            fun readyDetail(case: String, title: String, phase: String) {
                if (case == "diagnosis") {
                    await("diagnosis ready action $phase") {
                        val shown = labels(activity).map { it.text.toString() }
                        title in shown && "센터 예약을 준비할까요?" in shown && "예약 준비" in shown && "준비 상태 확인 중" !in shown
                    }
                    frame(
                        activity,
                        "diagnostic-media-$case-$phase-v225-final.png",
                        title,
                        "센터 예약을 준비할까요?",
                        "예약 준비"
                    )
                } else frame(activity, "diagnostic-media-$case-$phase-v225-final.png", title)
            }

            fun stopPoll() {
                val handler = field("timer").get(activity) as Handler
                for (name in listOf(
                    "feedPoll",
                    "drivingWatch"
                )) handler.removeCallbacks(field(name).get(activity) as Runnable)
            }
            await("idle/OEM allowance") {
                !field("busy").getBoolean(activity) && !field("comparisonLoading").getBoolean(
                    activity
                ) && (field("uxMonitor").get(activity) as? VehicleUxMonitor)?.restricted == false
            }
            runOnMainSync {
                stopPoll()
                field("client").set(activity, client)
                (field("drivingState").get(activity) as DrivingState).apply {
                    useSimulation(true)
                    setSimulatedMoving(false)
                }
                field("selected").set(activity, "기록")
                field("light").setBoolean(activity, true)
            }
            val openVideo = MainActivity::class.java.getDeclaredMethod(
                "openComparisonVideo",
                String::class.java,
                String::class.java
            ).apply { isAccessible = true }

            fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            for (case in listOf(
                "diagnosis",
                "healthy",
                "invalid",
                "missing_voltage",
                "missing_baseline"
            )) {
                val id = ids.getString(case)
                // Exercise Main's own fresh GET and renderer, not a prefilled opened field.
                runOnMainSync {
                    stopPoll()
                    field("opened").set(activity, null)
                    field("detailReturnId").set(activity, id)
                    method("refreshReturnedDetail").invoke(activity)
                }
                await("fresh $case detail") {
                    field("detailReturnId").get(activity) == null && (field(
                        "opened"
                    ).get(activity) as? JSONObject)?.optString("id") == id
                }
                lateinit var event: JSONObject
                val title = when (case) {
                    "diagnosis" -> "저전압 확인"
                    "healthy" -> "전압 이상 없음"
                    "invalid" -> "기록 확인 필요"
                    else -> "측정 확인 필요"
                }
                runOnMainSync {
                    event = JSONObject((field("opened").get(activity) as JSONObject).toString())
                    check(title in labels(activity).map { it.text.toString() })
                    check(event.getString("id") == id)
                    check(
                        event.getJSONObject("assessment").getString("kind") == if (case in setOf(
                                "healthy",
                                "missing_voltage",
                                "missing_baseline"
                            )
                        ) "observation" else "diagnosis"
                    )
                    if (case == "invalid") check(event.opt("evidence_valid") != true)
                    else check(event.opt("evidence_valid") == true && event.opt("diagnostic_valid") == true)
                }
                readyDetail(case, title, "detail")
                if (case in setOf("missing_voltage", "missing_baseline")) {
                    runOnMainSync {
                        val shown = labels(activity).map { it.text.toString() }
                        check("전압 이상 없음" !in shown && "저전압 확인" !in shown && "재측정 전압 회복" !in shown)
                        check(
                            event.getJSONObject("assessment").getJSONObject("diagnostic")
                                .getString("verdict") == "inconclusive"
                        )
                    }
                    rows.put(
                        JSONObject().put("case", case).put("event_id", id).put("title", title)
                            .put("normal_or_low_claim_hidden", true)
                    )
                    continue
                }
                if (case == "invalid") {
                    val error = runCatching { client.evidencePackage(id) }.exceptionOrNull()
                    check(error is AccountAccessException && error.reason == AccountAccessException.Reason.INTEGRITY)
                    rows.put(
                        JSONObject().put("case", case).put("event_id", id)
                            .put("invalid_hidden", true).put("export_rejected", true)
                    )
                    continue
                }
                // VideoActivity uses its production default endpoint; device reverse must point
                // that endpoint at this same fixture. Never substitute a different clip.
                val playbackEvent = JSONObject(BackendClient().request("/api/events/$id"))
                check(playbackEvent.getString("hash") == event.getString("hash"))
                check(
                    playbackEvent.getJSONObject("video").getString("sha256") == event.getJSONObject(
                        "video"
                    ).getString("sha256")
                )
                val zip = File(
                    targetContext.filesDir,
                    "diagnostic-media-$case-v225-final.zip"
                ).apply { writeBytes(client.evidencePackage(id)) }
                EvidencePackageVerifier.verify(zip, id)
                ZipFile(zip).use { archive ->
                    check(
                        sha(
                            archive.getInputStream(checkNotNull(archive.getEntry("sensor-original.json")))
                                .readBytes()
                        ) == event.getString("hash")
                    )
                    check(
                        sha(
                            archive.getInputStream(checkNotNull(archive.getEntry("test-pattern-video.mp4")))
                                .readBytes()
                        ) == event.getJSONObject("video").getString("sha256")
                    )
                }
                val monitor = addMonitor(VideoActivity::class.java.name, null, false)
                try {
                    runOnMainSync { openVideo.invoke(activity, id, id) }
                    player = checkNotNull(waitForMonitorWithTimeout(monitor, 10000))
                } finally {
                    removeMonitor(monitor)
                }
                val playing = checkNotNull(player)
                var position = 0
                await("$case actual playback") {
                    val video = VideoActivity::class.java.getDeclaredField("video")
                        .apply { isAccessible = true }.get(playing) as VideoView
                    position = video.currentPosition
                    video.isPlaying && video.visibility == View.VISIBLE && position > 1200
                }
                frame(playing, "diagnostic-media-$case-playing-v225-final.png", "재생 중 · 입력 파일 확인됨")
                runOnMainSync { playing.finish() }
                player = null
                await("$case same-event return") {
                    field("detailReturnId").get(activity) == null && (field(
                        "opened"
                    ).get(activity) as? JSONObject)?.optString("id") == id
                }
                runOnMainSync {
                    stopPoll()
                    check(title in labels(activity).map { it.text.toString() })
                }
                readyDetail(case, title, "return")
                rows.put(
                    JSONObject().put("case", case).put("event_id", id).put("title", title)
                        .put("position_ms", position)
                        .put("sensor_sha256", event.getString("hash"))
                        .put("clip_sha256", event.getJSONObject("video").getString("sha256"))
                        .put("same_event_return", true).put("zip_verified", true)
                        .put("ready_action_pixels_verified", case == "diagnosis")
                )
            }
            File(targetContext.filesDir, "diagnostic-media-flow-v225-final.json").writeText(
                JSONObject().put("pass", true).put("cases", rows)
                    .put(
                        "scope",
                        "Authored nonvisual sensor visualization; Main GET/method routes, actual video playback and ZIP checks. No camera inference, direct taps, document picker write or physical driving."
                    ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: diagnostic/healthy fresh Main GET, native title pixels, same-event playback and return, verified ZIP; invalid sensor refused, missing voltage/baseline inconclusive. No direct taps or camera inference.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                player?.finish()
                main?.finish()
            }
        }
    }
}
