package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** One live fixture through Main/source/playback/return and package verification. No control taps. */
class RoadMediaFlowProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        var player: Activity? = null
        try {
            val client = BackendClient()
            val ids = JSONObject(client.request("/api/dev/road-native-fixture"))
            val reports = JSONArray()
            main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val a = main
            fun field(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            val render =
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
            val openSource =
                MainActivity::class.java.getDeclaredMethod("openRoadSource", JSONObject::class.java)
                    .apply { isAccessible = true }
            val openVideo = MainActivity::class.java.getDeclaredMethod(
                "openComparisonVideo",
                String::class.java,
                String::class.java
            ).apply { isAccessible = true }

            fun await(test: () -> Boolean) {
                val end = SystemClock.elapsedRealtime() + 16000
                while (SystemClock.elapsedRealtime() < end) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(50)
                }
                error("road media flow timeout")
            }

            fun labels(activity: Activity): List<String> {
                val out = mutableListOf<String>()
                fun visit(v: View) {
                    if (v is TextView && v.isShown) out.add(v.text.toString())
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(activity.window.decorView)
                return out
            }

            fun textViews(screen: Activity): List<TextView> {
                val out = mutableListOf<TextView>()
                fun visit(view: View) {
                    if (!view.isShown) return
                    if (view is TextView) out.add(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                        view.getChildAt(
                            i
                        )
                    )
                }
                visit(screen.window.decorView)
                return out
            }

            fun frame(
                screen: Activity,
                name: String,
                vararg expected: String,
                graphic: Boolean = false
            ) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                var diagnostics = JSONArray()
                var pixelDiagnostics = JSONArray()
                while (SystemClock.elapsedRealtime() < deadline) {
                    val anchors = mutableListOf<Triple<Bitmap, Int, Int>>()
                    var fullyVisible = true
                    diagnostics = JSONArray()
                    pixelDiagnostics = JSONArray()
                    fun bounds(view: View, label: String): Boolean {
                        val rect = Rect()
                        val visible = view.getGlobalVisibleRect(rect)
                        val at = IntArray(2)
                        view.getLocationOnScreen(at)
                        val full =
                            visible && view.width > 0 && view.height > 0 && rect.width() == view.width && rect.height() == view.height
                        diagnostics.put(
                            JSONObject().put("anchor", label).put("x", at[0]).put("y", at[1])
                                .put("width", view.width).put("height", view.height)
                                .put("visible_rect", rect.toShortString())
                                .put("fully_visible", full)
                        )
                        return full
                    }
                    runOnMainSync {
                        for (text in expected) textViews(screen).firstOrNull { it.text.toString() == text && it.width > 0 && it.height > 0 }
                            ?.let { view ->
                                val full = bounds(view, text)
                                fullyVisible = fullyVisible && full
                                val at = IntArray(2)
                                view.getLocationOnScreen(at)
                                val bitmap = Bitmap.createBitmap(
                                    view.width,
                                    view.height,
                                    Bitmap.Config.ARGB_8888
                                ).also { view.draw(Canvas(it)) }
                                anchors.add(Triple(bitmap, at[0], at[1]))
                            }
                        if (graphic) {
                            val graphs = mutableListOf<View>()
                            fun visit(view: View) {
                                if (!view.isShown) return
                                if (view is PairedObservationGraphic) graphs.add(view)
                                if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                                    view.getChildAt(
                                        i
                                    )
                                )
                            }
                            visit(screen.window.decorView)
                            check(graphs.size == 1) { "Expected one visible comparison graph" }
                            val view = graphs.single()
                            val full = bounds(view, "PairedObservationGraphic")
                            fullyVisible = fullyVisible && full
                            if (view.width > 0 && view.height > 0) {
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
                    }
                    if (!fullyVisible || anchors.size != expected.size + (if (graphic) 1 else 0)) {
                        anchors.forEach { it.first.recycle() }
                        Thread.sleep(60)
                        continue
                    }
                    val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                    val presented = try {
                        anchors.map { (mask, left, top) ->
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
                            pixelDiagnostics.put(
                                JSONObject().put("x", left).put("y", top).put("pixels", pixels)
                                    .put("matched", matched)
                            )
                            pixels > 30 && matched >= pixels * .97
                        }.all { it }
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
                val failure = JSONObject().put("requested_frame", name)
                    .put("expected", JSONArray(expected.toList())).put("anchors", diagnostics)
                    .put("pixel_matches", pixelDiagnostics)
                uiAutomation.takeScreenshot()?.let { screenshot ->
                    failure.put("screenshot_width", screenshot.width)
                        .put("screenshot_height", screenshot.height)
                    File(targetContext.filesDir, "road-flow-failure-v226.png").outputStream()
                        .use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                }
                File(
                    targetContext.filesDir,
                    "road-flow-failure-v226.json"
                ).writeText(failure.toString(2))
                android.util.Log.e("RoadMediaFlowProbe", failure.toString())
                error("Native expected pixels absent: $failure")
            }

            fun stopPoll() {
                val timer = field("timer").get(a) as android.os.Handler
                for (name in listOf(
                    "feedPoll",
                    "drivingWatch"
                )) timer.removeCallbacks(field(name).get(a) as Runnable)
            }

            fun fresh(id: String) {
                runOnMainSync {
                    stopPoll()
                    field("client").set(a, client)
                    (field("drivingState").get(a) as DrivingState).apply {
                        useSimulation(true)
                        setSimulatedMoving(false)
                    }
                    field("selected").set(a, "기록")
                    field("light").setBoolean(a, true)
                    MainActivity::class.java.getDeclaredMethod("clearRoadSource")
                        .apply { isAccessible = true }.invoke(a)
                    field("opened").set(a, null)
                    field("detailReturnId").set(a, id)
                    MainActivity::class.java.getDeclaredMethod("refreshReturnedDetail")
                        .apply { isAccessible = true }.invoke(a)
                }
                await {
                    field("detailReturnId").get(a) == null && (field("opened").get(a) as? JSONObject)?.optString(
                        "id"
                    ) == id
                }
            }

            fun inspect(case: String, phase: String) {
                val toggle = when (case) {
                    "repeat" -> "충격 재관측 · 비교 보기"
                    "clear" -> "같은 기준 충격 없음 · 비교 보기"
                    "invalid" -> "비교 확인 필요 · 기록 보기"
                    else -> "상세 기록 펼치기"
                }
                runOnMainSync {
                    check("포트홀 감지" in labels(a) && toggle in labels(a))
                    var parent: android.view.ViewParent? =
                        textViews(a).first { it.text.toString() == "포트홀 감지" }.parent
                    while (parent != null) {
                        if (parent is android.widget.ScrollView) parent.scrollTo(0, 0)
                        parent = parent.parent
                    }
                }
                frame(a, "road-flow-$case-$phase-brief-v226.png", "포트홀 감지", toggle)
                // Programmatic disclosure listener invocation is not a physical tap.
                runOnMainSync {
                    check(textViews(a).single { it.text.toString() == toggle }.performClick())
                }
                waitForIdleSync()
                val summary = when (case) {
                    "repeat" -> "충격 신호 재관측"
                    "clear" -> "이번 관측 · 같은 기준 충격 없음"
                    "invalid" -> "원본 또는 비교 조건 확인 실패 · 이전 결과로 대신하지 않습니다"
                    else -> "이후 자료 없음 · 보수 확인 전"
                }
                runOnMainSync {
                    val view = textViews(a).single { it.text.toString() == summary }
                    check(labels(a).none { it == "도로 보수 완료" || it == "안전 확인 완료" })
                    // Valid comparisons must fit using the production disclosure scroll only.
                    // No probe scroll can conceal an unusable comparison viewport.
                    if (case !in setOf("repeat", "clear")) view.requestRectangleOnScreen(
                        Rect(
                            0,
                            0,
                            view.width,
                            view.height
                        ), true
                    )
                }
                if (case in setOf("repeat", "clear")) frame(
                    a,
                    "road-flow-$case-$phase-comparison-v226.png",
                    summary,
                    "합성 비교 · 도로 보수·안전 미확인",
                    graphic = true
                )
                else frame(a, "road-flow-$case-$phase-comparison-v226.png", summary)
            }

            fun sha(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw)
                .joinToString("") { "%02x".format(it) }

            fun archive(
                case: String,
                parent: JSONObject,
                following: JSONObject? = null
            ): JSONObject {
                val id = parent.getString("id")
                val file = File(targetContext.filesDir, "road-flow-$case-v226.zip").apply {
                    writeBytes(client.evidencePackage(id))
                }
                EvidencePackageVerifier.verify(file, id)
                ZipFile(file).use { zip ->
                    fun bytes(name: String) =
                        zip.getInputStream(checkNotNull(zip.getEntry(name))).readBytes()

                    val report = JSONObject(bytes("event-report.json").toString(Charsets.UTF_8))
                    check(sha(bytes("sensor-original.json")) == parent.getString("hash"))
                    check(
                        sha(bytes("test-pattern-video.mp4")) == parent.getJSONObject("video")
                            .getString("sha256")
                    )
                    fun photo(event: JSONObject, video: JSONObject, prefix: String) {
                        val descriptor = video.getJSONObject("input_photo")
                        val original = event.getJSONObject("video").getJSONObject("input_photo")
                        val path = descriptor.getString("path")
                        check(path in setOf("$prefix.png", "$prefix.jpg"))
                        check(
                            descriptor.getString("event_id") == event.getString("id") && descriptor.getString(
                                "sensor_sha256"
                            ) == event.getString("hash")
                        )
                        check(
                            descriptor.getString("sha256") == original.getString("sha256") && sha(
                                bytes(path)
                            ) == original.getString("sha256")
                        )
                        check(
                            descriptor.getLong("bytes") == bytes(path).size.toLong() && descriptor.getLong(
                                "bytes"
                            ) == original.getLong("bytes")
                        )
                        for (key in listOf(
                            "mime",
                            "width",
                            "height",
                            "source",
                            "camera_evidence",
                            "integrity_valid"
                        )) check(descriptor.get(key) == original.get(key))
                    }
                    photo(parent, report.getJSONObject("video"), "input-photo")
                    if (following != null) {
                        check(sha(bytes("follow-up-original.json")) == following.getString("hash"))
                        check(
                            sha(bytes("follow-up-video.mp4")) == following.getJSONObject("video")
                                .getString("sha256")
                        )
                        val source = report.getJSONObject("road_followup_source")
                        check(
                            source.getString("event_id") == following.getString("id") && source.getString(
                                "sensor_sha256"
                            ) == following.getString("hash")
                        )
                        photo(following, source.getJSONObject("video"), "follow-up-input-photo")
                    } else {
                        for (name in listOf(
                            "follow-up-original.json",
                            "follow-up-video.mp4",
                            "follow-up-input-photo.png",
                            "follow-up-input-photo.jpg"
                        )) check(zip.getEntry(name) == null)
                        check(!report.has("road_followup_source") || report.isNull("road_followup_source"))
                    }
                }
                return JSONObject().put("zip", file.name).put("original_photo_verified", true)
                    .put("followup_photo_verified", following != null)
                    .put("no_followup_files_or_descriptor", following == null)
            }
            await {
                !field("busy").getBoolean(a) && !field("comparisonLoading").getBoolean(a) && (field(
                    "uxMonitor"
                ).get(a) as? VehicleUxMonitor)?.restricted == false
            }
            val clearParent = JSONObject(client.request("/api/events/${ids.getString("clear")}"))
            val linkedId = clearParent.getJSONObject("road_followup_case").getString("followup_id")
            for ((name, id, title, next) in listOf(
                listOf("linked-observation", linkedId, "도로 후속 관측", "최초 기록과 비교해 주세요"),
                listOf(
                    "unlinked-healthy",
                    ids.getString("unlinked_healthy"),
                    "전압 이상 없음",
                    "측정 구간에서 확인한 결과입니다"
                )
            )) {
                fresh(id)
                runOnMainSync {
                    val event = field("opened").get(a) as JSONObject
                    check(event.getJSONObject("assessment").getString("kind") == "observation")
                    check(event.getString("observation_purpose") == if (name == "linked-observation") "road_followup" else "voltage_review")
                    check(title in labels(a) && next in labels(a))
                    if (name == "linked-observation") check("전압 이상 없음" !in labels(a))
                }
                frame(a, "road-flow-$name-v226.png", title, next)
                reports.put(
                    JSONObject().put("case", name).put("event_id", id).put("title", title)
                        .put("purpose_verified", true)
                )
            }
            for (case in listOf("waiting", "repeat", "clear", "invalid")) {
                val id = ids.getString(case)
                fresh(id)
                lateinit var parent: JSONObject
                runOnMainSync {
                    parent = JSONObject((field("opened").get(a) as JSONObject).toString())
                }
                inspect(case, "initial")
                if (case in setOf("waiting", "invalid")) {
                    reports.put(
                        JSONObject().put("case", case).put("parent_id", id)
                            .put("archive", archive(case, parent))
                    )
                    continue
                }
                val followup = JSONObject(
                    client.request(
                        "/api/events/${
                            parent.getJSONObject("road_followup_case").getString("followup_id")
                        }"
                    )
                )
                val verified = archive(case, parent, followup)
                runOnMainSync { openSource.invoke(a, parent) }
                await { field("roadSource").get(a) != null && !field("busy").getBoolean(a) }
                frame(
                    a,
                    "road-flow-$case-source-v226.png",
                    "이후 관측 원본",
                    "합성 센서 입력 · 사진에서 추정한 값이 아닙니다"
                )
                val plays = JSONArray()
                for ((phase, event) in listOf("after" to followup, "before" to parent)) {
                    val monitor = addMonitor(VideoActivity::class.java.name, null, false)
                    try {
                        runOnMainSync { openVideo.invoke(a, event.getString("id"), id) }
                        player = checkNotNull(waitForMonitorWithTimeout(monitor, 10000))
                    } finally {
                        removeMonitor(monitor)
                    }
                    val screen = checkNotNull(player)
                    var position = 0
                    await {
                        val video = VideoActivity::class.java.getDeclaredField("video")
                            .apply { isAccessible = true }.get(screen) as VideoView
                        position = video.currentPosition
                        video.isPlaying && video.visibility == View.VISIBLE && position > 1200
                    }
                    frame(screen, "road-flow-$case-$phase-playing-v226.png", "재생 중 · 입력 파일 확인됨")
                    plays.put(
                        JSONObject().put("event_id", event.getString("id")).put("phase", phase)
                            .put("position_ms", position)
                            .put("clip_sha256", event.getJSONObject("video").getString("sha256"))
                    )
                    if (case == "clear" && phase == "before") client.request(
                        "/api/dev/road-native-corrupt-clear",
                        JSONObject()
                    )
                    runOnMainSync { screen.finish() }
                    player = null
                    await {
                        field("detailReturnId").get(a) == null && (field("opened").get(a) as? JSONObject)?.optString(
                            "id"
                        ) == id
                    }
                    runOnMainSync { stopPoll() }
                    if (case == "clear" && phase == "before") {
                        lateinit var invalid: JSONObject
                        runOnMainSync {
                            invalid = JSONObject((field("opened").get(a) as JSONObject).toString())
                            check(
                                invalid.getJSONObject("road_followup_case")
                                    .getString("stage") == "comparison_invalid"
                            )
                        }
                        inspect("invalid", "after-video")
                        archive("invalid-after-video", invalid)
                    } else inspect(case, "$phase-return")
                }
                reports.put(
                    JSONObject().put("case", case).put("parent_id", id)
                        .put("followup_id", followup.getString("id")).put("archive", verified)
                        .put("plays", plays).put("same_parent_reloaded_on_return", true)
                )
            }
            File(
                targetContext.filesDir,
                "road-media-flow-v226.json"
            ).writeText(
                JSONObject().put("rows", reports)
                    .put("invalid_followup_refreshed_on_video_return", true)
                    .put("invalid_package_excludes_followup", true)
                    .put("native_title_and_summary_pixels", true)
                    .put("comparison_graph_and_safety_fully_visible", true)
                    .put("valid_comparison_uses_production_disclosure_scroll_only", true).put(
                    "scope",
                    "actual Main fresh GET/method routes and programmatic disclosure callbacks, VideoActivity SDK-permitted playback, native text pixels, live HTTP and both reference photo ZIP verification; authored sensor timelines/reference photos; no camera inference, direct taps or document-picker write"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: waiting/repeat/clear/invalid actual Main GET, native title and comparison text, source/playback/return, both reference photo/sensor/video ZIP bindings, invalid followup photo and descriptor removed.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                player?.finish()
                main?.finish()
            }
        }
    }
}
