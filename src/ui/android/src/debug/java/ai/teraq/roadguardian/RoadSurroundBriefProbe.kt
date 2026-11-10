package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RoadSurroundBriefPreviewActivity : Activity() {
    companion object {
        var font = 1f
        var endpoint = "http://127.0.0.1:9463"
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(
            base.createConfigurationContext(
                android.content.res.Configuration(
                    base.resources.configuration
                ).apply { fontScale = font })
        )
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        VehicleEventDetail.render(
            this, ui.install(), JSONObject(intent.getStringExtra("event")!!), ui.light,
            client = BackendClient(endpoint), allowed = { !isFinishing && !isDestroyed })
    }
}

/** Same stored road records through actual Main and authored previews. No taps or camera claim. */
class RoadSurroundBriefProbe : Instrumentation() {
    private var separate = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        separate = arguments?.getString("suite") == "surround_views"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        var preview: Activity? = null
        try {
            val endpoint = if (separate) "http://127.0.0.1:9464" else "http://127.0.0.1:9463"
            RoadSurroundBriefPreviewActivity.endpoint = endpoint
            val client = BackendClient(endpoint)
            val ids =
                if (separate) JSONObject(client.request("/api/dev/surround-views-fixture")).getJSONObject(
                    "ids"
                ) else JSONObject(client.request("/api/dev/road-surround-fixture"))
            val savedKey = if (separate) "pothole" else "clear"
            if (separate) {
                val legacy = JSONObject().put(
                    "surround",
                    JSONObject().put("schema", "road-guardian.synthetic-surround.v1")
                )
                check(SyntheticSurroundViews.metadata(legacy) == null)
                check(
                    SyntheticSurroundViews.metadata(
                        JSONObject(legacy.toString()).put(
                            "surround_views",
                            JSONObject.NULL
                        )
                    ) == null
                )
                for (wrong in listOf<Any>(JSONArray(), true, "invalid", 1)) {
                    check(runCatching {
                        SyntheticSurroundViews.metadata(
                            JSONObject(legacy.toString()).put(
                                "surround_views",
                                wrong
                            )
                        )
                    }.isFailure)
                }
            }
            val rows = JSONArray()
            fun nodes(activity: Activity): List<View> {
                val result = mutableListOf<View>()
                fun visit(view: View) {
                    if (!view.isShown) return
                    result.add(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                        view.getChildAt(
                            i
                        )
                    )
                }
                visit(activity.window.decorView)
                return result
            }

            fun await(message: String, test: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 18000
                while (SystemClock.elapsedRealtime() < deadline) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(50)
                }
                error(message)
            }

            fun capture(activity: Activity, name: String, kind: String, event: JSONObject) {
                val withPhotos = kind == "saved" || (separate && kind == "missing")
                val partialStatus = if (separate && kind == "missing") {
                    val meta = event.getJSONObject("surround_views")
                    val absent = meta.getJSONArray("missing_directions")
                    val names =
                        mapOf("front" to "전방", "rear" to "후방", "left" to "왼쪽", "right" to "오른쪽")
                    "사진 ${
                        meta.getJSONArray("views").length()
                    }개 확보 · " + (0 until absent.length()).joinToString("·") {
                        names.getValue(
                            absent.getString(
                                it
                            )
                        )
                    } + " 사진 없음"
                } else ""
                val status = when (kind) {
                    "saved" -> if (separate) "기록 확보 완료" else "기록 확보 완료"
                    "missing" -> if (separate) partialStatus else "센서 기록 보관 · 사진 미첨부"
                    else -> "센서 기록 보관 · 사진 확인 실패"
                }
                val toggle =
                    if (kind == "saved" && !separate) "같은 기준 충격 없음 · 비교 보기" else "상세 기록 펼치기"
                await("Surround status unavailable: $name") {
                    nodes(activity).filterIsInstance<TextView>()
                        .any { it.text.toString() == status }
                }
                val expected = mutableListOf("포트홀 감지", status, toggle)
                if (withPhotos) expected.addAll(
                    listOf(
                        "전방",
                        "후방",
                        "왼쪽",
                        "오른쪽",
                        if (separate) "주변 사진" else "주변 사진"
                    )
                )
                val deadline = SystemClock.elapsedRealtime() + 5000
                var diagnostics = JSONArray()
                var pixelStats = JSONArray()
                var masksSaved = false
                while (SystemClock.elapsedRealtime() < deadline) {
                    val anchors = mutableListOf<Triple<Bitmap, Int, Int>>()
                    var complete = true
                    diagnostics = JSONArray()
                    runOnMainSync {
                        val shown = nodes(activity)
                        val texts = shown.filterIsInstance<TextView>()
                        val targets =
                            expected.map { value -> checkNotNull(texts.firstOrNull { it.text.toString() == value }) { "Missing $value" } }
                                .toMutableList<View>()
                        val photos = shown.filter {
                            it.contentDescription?.toString() in listOf(
                                "전방 합성 시야",
                                "후방 합성 시야",
                                "왼쪽 합성 시야",
                                "오른쪽 합성 시야"
                            )
                        }
                        val graphics = shown.filterIsInstance<DriverEventGraphic>()
                        if (withPhotos) {
                            check(photos.size == 4 && graphics.isEmpty())
                            targets.addAll(photos)
                        } else {
                            check(photos.isEmpty() && graphics.size == 1)
                            check(graphics.single().contentDescription.toString() == "도로 충격 기록 그래픽")
                            targets.add(graphics.single())
                            check(texts.none {
                                it.text.toString() in setOf(
                                    "기록 확보 완료",
                                    "기록 확보 완료"
                                )
                            })
                        }
                        for (view in targets) {
                            val visible = Rect()
                            val full =
                                view.getGlobalVisibleRect(visible) && visible.width() == view.width && visible.height() == view.height && view.width > 0 && view.height > 0
                            complete = complete && full
                            val at = IntArray(2)
                            view.getLocationOnScreen(at)
                            diagnostics.put(
                                JSONObject().put(
                                    "name",
                                    if (view is TextView) view.text.toString() else view.contentDescription.toString()
                                ).put("x", at[0]).put("y", at[1]).put("width", view.width)
                                    .put("height", view.height)
                                    .put("visible", visible.toShortString()).put("full", full)
                                    .put("text_view", view is TextView)
                            )
                            if (view.width > 0 && view.height > 0) anchors.add(
                                Triple(
                                    Bitmap.createBitmap(
                                        view.width,
                                        view.height,
                                        Bitmap.Config.ARGB_8888
                                    ).also { view.draw(Canvas(it)) }, at[0], at[1]
                                )
                            )
                        }
                    }
                    if (!complete) {
                        anchors.forEach { it.first.recycle() }
                        Thread.sleep(60)
                        continue
                    }
                    val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                    pixelStats = JSONArray()
                    val background = PleosPalette.background(!name.endsWith("-night"))
                    val matches = try {
                        anchors.mapIndexed { index, (bitmap, left, top) ->
                            var total = 0
                            var good = 0
                            var minimum = 255
                            var maximum = 0
                            var sum = 0L
                            val textAnchor =
                                diagnostics.getJSONObject(index).getBoolean("text_view")
                            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                                val value = bitmap.getPixel(x, y)
                                val alpha = Color.alpha(value)
                                // TextView.draw preserves glyph edge alpha. Compose it over the
                                // actual neutral surface, just as the screen compositor does.
                                if (alpha < (if (textAnchor) 32 else 245)) continue
                                total++
                                fun composed(channel: Int, base: Int) =
                                    (channel * alpha + base * (255 - alpha) + 127) / 255

                                val expectedRed = if (textAnchor) composed(
                                    Color.red(value),
                                    Color.red(background)
                                ) else Color.red(value)
                                val expectedGreen = if (textAnchor) composed(
                                    Color.green(value),
                                    Color.green(background)
                                ) else Color.green(value)
                                val expectedBlue = if (textAnchor) composed(
                                    Color.blue(value),
                                    Color.blue(background)
                                ) else Color.blue(value)
                                if (left + x !in 0 until screenshot.width || top + y !in 0 until screenshot.height) continue
                                val pixel = screenshot.getPixel(left + x, top + y)
                                val dr = kotlin.math.abs(expectedRed - Color.red(pixel))
                                val dg = kotlin.math.abs(expectedGreen - Color.green(pixel))
                                val db = kotlin.math.abs(expectedBlue - Color.blue(pixel))
                                minimum = minOf(minimum, dr, dg, db)
                                maximum = maxOf(maximum, dr, dg, db)
                                sum += (dr + dg + db).toLong()
                                if (dr < 12 && dg < 12 && db < 12) good++
                            }
                            pixelStats.put(
                                JSONObject().put(
                                    "anchor",
                                    diagnostics.getJSONObject(index).getString("name")
                                ).put("total", total).put("matched", good)
                                    .put("min_channel_error", minimum)
                                    .put("max_channel_error", maximum).put(
                                    "mean_channel_error",
                                    if (total > 0) sum.toDouble() / (total * 3) else -1.0
                                )
                            )
                            if (!masksSaved && !(total > 30 && good >= total * .97)) File(
                                targetContext.filesDir,
                                "road-surround-mask-$index-v227.png"
                            ).outputStream()
                                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            total > 30 && good >= total * .97
                        }.all { it }
                    } finally {
                        anchors.forEach { it.first.recycle() }
                    }
                    if (!masksSaved && !matches) {
                        File(
                            targetContext.filesDir,
                            "road-surround-diagnostic-frame-v227.png"
                        ).outputStream()
                            .use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        File(
                            targetContext.filesDir,
                            "road-surround-first-pixels-v227.json"
                        ).writeText(
                            JSONObject().put("case", name).put("bounds", diagnostics)
                                .put("pixels", pixelStats).toString(2)
                        )
                    }
                    masksSaved = true
                    if (matches && SystemClock.elapsedRealtime() <= deadline) {
                        File(targetContext.filesDir, "road-surround-$name-v227.png").outputStream()
                            .use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        screenshot.recycle()
                        rows.put(
                            JSONObject().put("case", name).put("event_id", event.getString("id"))
                                .put("status", status).put("photo_views", if (withPhotos) 4 else 0)
                                .put("fallback_graphic", !withPhotos)
                                .put("same_frame_complete", true).put("native_pixels", true)
                                .put("text_alpha_composited_over_palette_background", true)
                                .put("text_minimum_alpha", 32).put("nontext_minimum_alpha", 245)
                                .put("minimum_samples_exclusive", 30)
                                .put("required_match_fraction", 0.97)
                        )
                        return
                    }
                    screenshot.recycle()
                    Thread.sleep(60)
                }
                uiAutomation.takeScreenshot()?.let { image ->
                    File(targetContext.filesDir, "road-surround-failure-v227.png").outputStream()
                        .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
                val failure = JSONObject().put("case", name).put("bounds", diagnostics)
                    .put("pixels", pixelStats)
                File(
                    targetContext.filesDir,
                    "road-surround-failure-v227.json"
                ).writeText(failure.toString(2))
                error("Surround frame incomplete: $failure")
            }

            val events = listOf(
                "saved" to savedKey,
                "missing" to (if (separate) "partial" else "missing"),
                "corrupt" to "corrupt"
            ).associate { (kind, key) ->
                kind to JSONObject(
                    client.request(
                        "/api/events/${
                            ids.getString(
                                key
                            )
                        }"
                    )
                )
            }
            if (separate) {
                val original = events.getValue("saved")
                val metadata = original.getJSONObject("surround_views")
                SyntheticSurroundViews.validate(
                    metadata,
                    original.getString("id"),
                    original.getString("hash"),
                    true
                )
                for (key in listOf("event_id", "sensor_sha256", "source")) {
                    val changed = JSONObject(metadata.toString()).put(key, "different")
                    check(runCatching {
                        SyntheticSurroundViews.validate(
                            changed,
                            original.getString("id"),
                            original.getString("hash"),
                            true
                        )
                    }.isFailure)
                }
                val duplicate = JSONObject(metadata.toString())
                val duplicateRows = duplicate.getJSONArray("views")
                duplicateRows.getJSONObject(1)
                    .put("sha256", duplicateRows.getJSONObject(0).getString("sha256"))
                check(runCatching {
                    SyntheticSurroundViews.validate(
                        duplicate,
                        original.getString("id"),
                        original.getString("hash"),
                        true
                    )
                }.isFailure)
            }
            main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val activity = main
            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            await("Main/OEM not ready") {
                !field("busy").getBoolean(activity) && !field("comparisonLoading").getBoolean(
                    activity
                ) && (field("uxMonitor").get(activity) as? VehicleUxMonitor)?.restricted == false
            }
            runOnMainSync {
                val timer = field("timer").get(activity) as Handler
                for (name in listOf(
                    "feedPoll",
                    "drivingWatch"
                )) timer.removeCallbacks(field(name).get(activity) as Runnable)
                field("client").set(activity, client)
                field("selected").set(activity, "기록")
                field("light").setBoolean(activity, true)
                (field("drivingState").get(activity) as DrivingState).apply {
                    useSimulation(true)
                    setSimulatedMoving(false)
                }
                field("opened").set(activity, null)
                field("detailReturnId").set(activity, ids.getString(savedKey))
                MainActivity::class.java.getDeclaredMethod("refreshReturnedDetail")
                    .apply { isAccessible = true }.invoke(activity)
            }
            await("Main fresh event missing") {
                field("detailReturnId").get(activity) == null && (field(
                    "opened"
                ).get(activity) as? JSONObject)?.optString("id") == ids.getString(savedKey)
            }
            capture(activity, "main-saved", "saved", events.getValue("saved"))
            runOnMainSync { activity.finish() }
            main = null
            for ((kind, event) in events) for (mode in listOf("day", "night", "large")) {
                RoadSurroundBriefPreviewActivity.font = if (mode == "large") 1.5f else 1f
                preview = startActivitySync(
                    Intent(
                        targetContext,
                        RoadSurroundBriefPreviewActivity::class.java
                    ).putExtra("event", event.toString()).putExtra("night", mode == "night")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                capture(preview!!, "$kind-$mode", kind, event)
                runOnMainSync { preview!!.finish() }
                preview = null
            }
            File(
                targetContext.filesDir,
                if (separate) "surround-views-brief.json" else "road-surround-brief-v227.json"
            ).writeText(
                JSONObject().put("pass", true).put("cases", rows)
                    .put("separate_synthetic_photos", separate)
                    .put("strict_metadata_negative_controls", separate).put(
                    "scope",
                    "Same stored synthetic road records: actual Main GET plus day/night/large component previews. Verified authored image view pixels and fallback graph. No direct taps, camera capture or physical driving."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: actual Main saved surround brief and nine saved/missing/corrupt previews; all requested labels/photos/fallback/comparison control pixels fully visible together.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            uiAutomation.takeScreenshot()?.let { image ->
                File(targetContext.filesDir, "road-surround-failure-v227.png").outputStream()
                    .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            File(
                targetContext.filesDir,
                "road-surround-failure-v227.txt"
            ).writeText(failure.stackTraceToString())
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                preview?.finish()
                main?.finish()
            }
            RoadSurroundBriefPreviewActivity.font = 1f
        }
    }
}
