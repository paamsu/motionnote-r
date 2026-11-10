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
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/** Actual Main programmatic routes for stored offline photo analysis; no live model or direct taps. */
class SceneAnalysisFlowProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        try {
            val client = BackendClient("http://127.0.0.1:9490")
            val manifest = JSONObject(client.request("/api/dev/scene-analysis-fixture"))
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
            fun tamperChecks(source: File, eventId: String, case: String): JSONArray {
                val results = JSONArray()
                for (mode in listOf(
                    "wrong_class",
                    "raw_mismatch",
                    "photo_sha",
                    "event_id",
                    "model_input"
                )) {
                    val entries = linkedMapOf<String, ByteArray>()
                    ZipFile(source).use { zip ->
                        val all = zip.entries()
                        while (all.hasMoreElements()) {
                            val e = all.nextElement()
                            entries[e.name] = zip.getInputStream(e).readBytes()
                        }
                    }
                    val report =
                        JSONObject(checkNotNull(entries["event-report.json"]).toString(Charsets.UTF_8))
                    val analysis =
                        JSONObject(checkNotNull(entries["scene-analysis.json"]).toString(Charsets.UTF_8))
                    when (mode) {
                        "wrong_class" -> {
                            val wrong =
                                if (analysis.getString("class") == "pothole") "other" else "pothole"
                            analysis.put("class", wrong).put("status", "candidate")
                            analysis.getJSONObject("result").put("class", wrong)
                                .put("status", "candidate")
                        }

                        "raw_mismatch" -> {
                            val wrong = JSONObject().put("class", "other")
                                .put("reason", "Contradictory injected result.").toString()
                            analysis.put("raw_output", wrong)
                                .put("raw_output_sha256", sha(wrong.toByteArray(Charsets.UTF_8)))
                        }

                        "photo_sha" -> analysis.put("input_photo_sha256", "0".repeat(64))
                        "event_id" -> analysis.put(
                            "event_id",
                            "00000000-0000-4000-8000-000000000000"
                        )

                        "model_input" -> {
                            val wrong = checkNotNull(entries["scene-model-input.png"]).clone()
                            wrong[0] = 0
                            entries["scene-model-input.png"] = wrong
                            analysis.put("model_input_sha256", sha(wrong))
                            analysis.getJSONObject("result").put("model_input_sha256", sha(wrong))
                        }
                    }
                    report.put("scene_analysis", analysis)
                    entries["scene-analysis.json"] = analysis.toString().toByteArray(Charsets.UTF_8)
                    entries["event-report.json"] = report.toString().toByteArray(Charsets.UTF_8)
                    val manifestJson =
                        JSONObject(checkNotNull(entries["manifest.json"]).toString(Charsets.UTF_8))
                    val files = manifestJson.getJSONArray("files")
                    for (n in 0 until files.length()) {
                        val row = files.getJSONObject(n)
                        val bytes = checkNotNull(entries[row.getString("path")])
                        row.put("bytes", bytes.size).put("sha256", sha(bytes))
                    }
                    entries["manifest.json"] = manifestJson.toString().toByteArray(Charsets.UTF_8)
                    val target =
                        File(targetContext.filesDir, "scene-analysis-$case-rejected-$mode.zip")
                    ZipOutputStream(target.outputStream()).use { zip ->
                        for ((name, bytes) in entries) {
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(bytes)
                            zip.closeEntry()
                        }
                    }
                    check(runCatching {
                        EvidencePackageVerifier.verify(
                            target,
                            eventId
                        )
                    }.isFailure) { "Rehashed tamper accepted: $case/$mode" }
                    results.put(
                        JSONObject().put("mode", mode).put("rehashed_manifest", true)
                            .put("rejected", true).put("zip_sha256", sha(target.readBytes()))
                    )
                }
                return results
            }

            val titles = mapOf(
                "pothole" to "포트홀 후보 확인",
                "bump" to "방지턱 후보 확인",
                "unclear" to "사진 판독 보류",
                "conflict" to "노면 확인 필요"
            )
            val rows = JSONArray()
            for (case in listOf("pothole", "bump", "unclear", "conflict")) {
                val id = ids.getString(case)
                val event = JSONObject(client.request("/api/events/$id"))
                val analysis = checkNotNull(ScenePhotoAnalysis.checked(event))
                val expectedClass = when (case) {
                    "pothole" -> "pothole"
                    "unclear" -> "unclear"
                    else -> "raised_bump"
                }
                check(analysis.getString("class") == expectedClass)
                val photo = event.getJSONObject("video").getJSONObject("input_photo")
                val mutated = JSONObject(event.toString())
                mutated.getJSONObject("scene_analysis")
                    .put("class", if (expectedClass == "pothole") "other" else "pothole")
                check(ScenePhotoAnalysis.checked(mutated) == null)
                val emergencyPriority = JSONObject(event.toString()).apply {
                    getJSONObject("assessment").put(
                        "kind",
                        "observation"
                    ).put(
                        "emergency_assessment",
                        JSONObject().put("source", "authored_timeline_evaluation")
                            .put("status", "waiting")
                    )
                }
                check(EventBriefState.from(emergencyPriority).title == "운전자 응답 확인 중")
                val voltagePriority = JSONObject(event.toString()).apply {
                    getJSONObject("assessment").put("kind", "observation")
                    put("observation_purpose", "voltage_review")
                    getJSONObject("data").put("scenario", "healthy")
                    put("diagnostic_valid", false)
                }
                check(EventBriefState.from(voltagePriority).title == "측정 확인 필요")
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
                check(!labelsSafe(activity).contains("합성 사진의 사전 분석 · 센서 입력과 별도 보관"))
                if (case == "pothole") {
                    await("four photos loaded") { labels(activity).any { it.text.toString() == "합성 사진 4개 보관" } }
                    frame(
                        activity,
                        "scene-analysis-$case-main.png",
                        titles.getValue(case),
                        "합성 사진 · 사전 판독",
                        "합성 사진 4개 보관",
                        "전방",
                        "후방",
                        "왼쪽",
                        "오른쪽"
                    )
                } else frame(
                    activity,
                    "scene-analysis-$case-main.png",
                    titles.getValue(case),
                    "합성 사진 · 사전 판독",
                    "상세 기록 펼치기"
                )
                runOnMainSync {
                    labels(activity).single { it.text.toString() == "상세 기록 펼치기" }.performClick()
                }
                val detail = when (expectedClass) {
                    "pothole" -> "사진 판독: 포트홀 후보"
                    "raised_bump" -> "사진 판독: 방지턱 후보"
                    else -> "사진 판독: 판독 보류"
                }
                val provenance = "합성 사진의 사전 분석 · 센서 입력과 별도 보관"
                await("$case detail") { labels(activity).any { it.text.toString() == detail && it.height > 0 && !it.isLayoutRequested } }
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
                frame(activity, "scene-analysis-$case-detail.png", detail, provenance)
                val zip = File(targetContext.filesDir, "scene-analysis-$case.zip").apply {
                    writeBytes(client.evidencePackage(id))
                }
                val verified = EvidencePackageVerifier.verify(zip, id)
                ZipFile(zip).use { archive ->
                    val report = JSONObject(
                        archive.getInputStream(archive.getEntry("event-report.json")).readBytes()
                            .toString(Charsets.UTF_8)
                    )
                    check(report.getString("id") == id)
                    val pp = report.getJSONObject("video").getJSONObject("input_photo")
                    check(
                        sha(
                            archive.getInputStream(archive.getEntry(pp.getString("path")))
                                .readBytes()
                        ) == photo.getString("sha256")
                    )
                    check(report.has("scene_analysis"))
                }
                val rejected = tamperChecks(zip, id, case)
                rows.put(
                    JSONObject().put("ui_mutated_class_refused", true)
                        .put("emergency_priority_preserved", true)
                        .put("voltage_priority_preserved", true).put("tamper_controls", rejected)
                        .put("case", case).put("event_id", id)
                        .put("sensor_sha256", event.getString("hash"))
                        .put("photo_sha256", photo.getString("sha256")).put("class", expectedClass)
                        .put("title", titles.getValue(case))
                        .put("actual_glyph_pixels_verified", true)
                        .put("details_initially_collapsed", true)
                        .put("detail_provenance_verified", true).put("zip_verified", true)
                        .put("zip_files", verified.files).put("zip_sha256", sha(zip.readBytes()))
                )
            }
            File(
                targetContext.filesDir,
                "scene-analysis-flow.json"
            ).writeText(
                JSONObject().put("pass", true).put("cases", rows).put(
                    "scope",
                    "Actual Main fresh HTTP routes, programmatic disclosure, glyph pixel capture, immutable stored offline local photo inference and ZIP. No app real-time model invocation, video inference, direct human taps, or physical vehicle test."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: four stored photo analysis Main states, visible glyph pixels, collapsed details and verified ZIP.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            runCatching {
                uiAutomation.takeScreenshot()?.let { b ->
                    File(targetContext.filesDir, "scene-analysis-failure.png").outputStream()
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
