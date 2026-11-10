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

/** Actual Main programmatic routes for authored weather signal timelines; no camera recognition or direct taps. */
class WeatherTimelineFlowProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        try {
            val client = BackendClient("http://127.0.0.1:9492")
            val manifest = JSONObject(client.request("/api/dev/weather-timeline-fixture"))
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
                "collecting" to "시야 변화 확인 중",
                "sustained" to "전방 시야 저하",
                "washer" to "워셔 사용 구간",
                "recovered" to "시야 저하 신호 해제",
                "gap" to "입력 확인 필요"
            )
            val states = mapOf(
                "collecting" to "collecting",
                "sustained" to "sustained_candidate",
                "washer" to "washer_suppressed",
                "recovered" to "recovered",
                "gap" to "inconclusive"
            )
            val provenance = "합성 신호의 시간 변화 · 영상 인식 결과 아님"
            val rows = JSONArray()
            for (case in listOf("collecting", "sustained", "washer", "recovered", "gap")) {
                val id = ids.getString(case)
                val event = JSONObject(client.request("/api/events/$id"))
                check(event.get("evidence_valid") == true && event.getString("source") == "synthetic")
                val observations = event.getJSONObject("data").getJSONObject("observations")
                val timeline = observations.getJSONObject("weather_timeline")
                val report = observations.getJSONObject("weather_timeline_analysis")
                check(
                    timeline.getString("schema") == "road-guardian.weather-timeline.v1" && timeline.getString(
                        "source"
                    ) == "authored_synthetic"
                )
                check(
                    report.getString("status") == states.getValue(case) && report.getString("source") == "auth_timeline" && report.get(
                        "camera_analysis_performed"
                    ) == false
                )
                val assessment =
                    event.getJSONObject("assessment").getJSONObject("weather_timeline_assessment")
                check(
                    assessment.getString("status") == if (case == "gap") "withheld" else states.getValue(
                        case
                    )
                )
                if (case == "gap") check(event.getString("status") == "input_review_required")
                check(EventBriefState.from(event).title == titles.getValue(case))
                runOnMainSync {
                    stopPoll()
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
                    "weather-timeline-$case-main.png",
                    titles.getValue(case),
                    "상세 기록 펼치기"
                )
                runOnMainSync {
                    labels(activity).single { it.text.toString() == "상세 기록 펼치기" }.performClick()
                }
                await("$case detail") { labels(activity).any { it.text.toString() == provenance && it.height > 0 && !it.isLayoutRequested } }
                runOnMainSync {
                    labels(activity).single { it.text.toString() == provenance }.let {
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
                frame(activity, "weather-timeline-$case-detail.png", provenance)
                val zip = File(targetContext.filesDir, "weather-timeline-$case.zip").apply {
                    writeBytes(client.evidencePackage(id))
                }
                val verified = EvidencePackageVerifier.verify(zip, id)
                ZipFile(zip).use { archive ->
                    val original = JSONObject(
                        archive.getInputStream(archive.getEntry("sensor-original.json")).readBytes()
                            .toString(Charsets.UTF_8)
                    )
                    val storedObservations = original.getJSONObject("observations")
                    val storedTimeline = storedObservations.getJSONObject("weather_timeline")
                    val storedReport = storedObservations.getJSONObject("weather_timeline_analysis")
                    check(
                        storedTimeline.getString("schema") == timeline.getString("schema") && storedTimeline.getString(
                            "source"
                        ) == timeline.getString("source")
                    )
                    val actualSamples = storedTimeline.getJSONArray("samples")
                    val expectedSamples = timeline.getJSONArray("samples")
                    check(actualSamples.length() == expectedSamples.length())
                    for (i in 0 until actualSamples.length()) for (key in listOf(
                        "t",
                        "wiper_high",
                        "visibility_low",
                        "rain_sensor_wet",
                        "washer_active"
                    )) check(
                        actualSamples.getJSONObject(i).get(key) == expectedSamples.getJSONObject(i)
                            .get(key)
                    )
                    check(
                        storedReport.getString("status") == states.getValue(case) && storedReport.get(
                            "as_of"
                        ) == report.get("as_of") && storedReport.get("camera_analysis_performed") == false
                    )
                    val eventReport = JSONObject(
                        archive.getInputStream(archive.getEntry("event-report.json")).readBytes()
                            .toString(Charsets.UTF_8)
                    )
                    check(
                        eventReport.getString("id") == id && eventReport.getString("sensor_sha256") == event.getString(
                            "hash"
                        )
                    )
                    check(
                        eventReport.getJSONObject("assessment")
                            .getJSONObject("weather_timeline_assessment")
                            .getString("status") == assessment.getString("status")
                    )
                }
                rows.put(
                    JSONObject().put("case", case).put("event_id", id)
                        .put("sensor_sha256", event.getString("hash"))
                        .put("timeline_status", states.getValue(case))
                        .put("policy_status", assessment.getString("status"))
                        .put("title", titles.getValue(case))
                        .put("actual_glyph_pixels_verified", true)
                        .put("details_initially_collapsed", true)
                        .put("detail_provenance_verified", true)
                        .put("original_timeline_preserved_in_zip", true).put("zip_verified", true)
                        .put("zip_files", verified.files).put("zip_sha256", sha(zip.readBytes()))
                )
            }
            File(
                targetContext.filesDir,
                "weather-timeline-flow.json"
            ).writeText(
                JSONObject().put("pass", true).put("cases", rows).put(
                    "scope",
                    "Actual Main fresh HTTP routes, programmatic disclosure, visible glyph pixel captures and original timeline/report ZIP verification. Authored synthetic sensor timing only; no camera inference, real sensor input, direct human taps or physical vehicle test."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: five authored weather timeline Main states, collapsed/expanded visible pixels and original ZIP.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            runCatching {
                uiAutomation.takeScreenshot()?.let { b ->
                    File(targetContext.filesDir, "weather-timeline-failure.png").outputStream()
                        .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    b.recycle()
                }
            }
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { main?.finish() }
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
