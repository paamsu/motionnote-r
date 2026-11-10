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
import org.json.JSONObject
import java.io.File

/** Pixel and suppression checks only. No taps, real sensors or repair claims. */
class RoadComparisonVisualProbe : Instrumentation() {
    private var tag = "v176"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        tag = arguments?.getString("tag") ?: "v176"
        require(tag in setOf("v176", "v195", "v200"))
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            val fixture =
                targetContext.assets.open("showcase-road-followup-v174.json").bufferedReader()
                    .use { it.readText() }

            fun views(): List<View> {
                val list = mutableListOf<View>()
                runOnMainSync {
                    fun visit(v: View) {
                        if (v.isShown) list.add(v)
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(checkNotNull(screen).window.decorView)
                }
                return list
            }
            for (mode in listOf(
                "night",
                "day",
                "large",
                "invalid",
                "missing",
                "waiting",
                "waiting-large",
                "waiting-invalid"
            )) {
                runOnMainSync {
                    ShowcasePreviewActivity.previewFontScale =
                        if (mode in setOf("large", "waiting-large")) 1.5f else 1f
                }
                screen = startActivitySync(
                    Intent(targetContext, ShowcasePreviewActivity::class.java).putExtra(
                        "case",
                        "followup"
                    ).putExtra("night", mode != "day")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                val event = JSONObject(fixture)
                if (mode == "invalid") event.put("evidence_valid", false)
                if (mode == "missing") event.getJSONObject("road_followup_case")
                    .getJSONObject("after").remove("peak_g")
                if (mode.startsWith("waiting")) event.getJSONObject("road_followup_case")
                    .put("stage", "awaiting_observation")
                if (mode == "waiting-invalid") event.put("evidence_valid", false)
                runOnMainSync {
                    val ui = PhoneAccountUi(screen!!, mode == "day")
                    val body = ui.install()
                    body.addView(ui.text("합성 시험 · 실제 보수·도로 안전 확인 아님", 16f))
                    RoadFollowupPanel.render(ui, body, event)
                }
                waitForIdleSync()
                val end = SystemClock.elapsedRealtime() + 5000
                while (views().any { it is PairedObservationGraphic && (it.width == 0 || it.height == 0) } && SystemClock.elapsedRealtime() < end) Thread.sleep(
                    40
                )
                Thread.sleep(500)
                waitForIdleSync() // The width listener can schedule a second layout after the first nonzero size.
                val labels =
                    views().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
                val graphics = views().filterIsInstance<PairedObservationGraphic>()
                if (mode.startsWith("waiting")) {
                    check(graphics.isEmpty() && !labels.contains("2.437") && !labels.contains("0.070"))
                    if (mode == "waiting-invalid") check(
                        labels.contains("원본 또는 비교 조건 확인 실패") && !labels.contains(
                            "같은 위치"
                        )
                    )
                    else {
                        check(labels.contains("같은 위치·방향·측정 조건") && labels.contains("비교 수치와 보수 여부는 미확인"))
                        val image = checkNotNull(uiAutomation.takeScreenshot())
                        File(targetContext.filesDir, "road-$mode-$tag.png").outputStream()
                            .use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        image.recycle()
                    }
                } else if (mode == "invalid" || mode == "missing") {
                    check(graphics.isEmpty() && !labels.contains("2.437") && !labels.contains("0.070"))
                } else if (mode == "large") {
                    check(
                        graphics.isEmpty() && labels.contains("최초 관측  2.437 g") && labels.contains(
                            "이후 관측  0.070 g"
                        )
                    )
                } else {
                    val chart = graphics.single()
                    val expected = mutableMapOf<Int, Int>()
                    val cores = mutableMapOf<Int, MutableList<Pair<Int, Int>>>()
                    val bounds = Rect()
                    var drawing: Result<Unit>? = null
                    runOnMainSync {
                        drawing = runCatching {
                            val bitmap = Bitmap.createBitmap(
                                chart.width,
                                chart.height,
                                Bitmap.Config.ARGB_8888
                            )
                            chart.draw(Canvas(bitmap))
                            val colors = if (mode == "day") listOf(
                                Color.rgb(102, 102, 102),
                                Color.rgb(36, 107, 209)
                            ) else listOf(Color.rgb(189, 189, 189), Color.rgb(142, 184, 255))
                            for (color in colors) {
                                var n = 0
                                val points = mutableListOf<Pair<Int, Int>>()
                                for (y in 1 until bitmap.height - 1) for (x in 1 until bitmap.width - 1) if (bitmap.getPixel(
                                        x,
                                        y
                                    ) == color
                                ) {
                                    n++
                                    if (bitmap.getPixel(x - 1, y) == color && bitmap.getPixel(
                                            x + 1,
                                            y
                                        ) == color && bitmap.getPixel(
                                            x,
                                            y - 1
                                        ) == color && bitmap.getPixel(x, y + 1) == color
                                    ) points.add(x to y)
                                }
                                check(n > 10 && points.size > 10)
                                expected[color] = n
                                cores[color] = points
                            }
                            bitmap.recycle()
                            check(chart.getGlobalVisibleRect(bounds))
                            check(bounds.height() == chart.height && bounds.width() == chart.width)
                            val a = IntArray(2)
                            val b = IntArray(2)
                            chart.getLocationOnScreen(a)
                            chart.getLocationInWindow(b)
                            bounds.offset(a[0] - b[0], a[1] - b[1])
                        }
                    }
                    checkNotNull(drawing).getOrThrow()
                    var lastCounts = emptyMap<Int, Int>()
                    var accepted: Bitmap? = null
                    val frameEnd = SystemClock.elapsedRealtime() + 10000
                    while (SystemClock.elapsedRealtime() < frameEnd) {
                        val image = checkNotNull(uiAutomation.takeScreenshot())
                        val counts = expected.mapValues { 0 }.toMutableMap()
                        for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(image.height)) for (x in bounds.left.coerceAtLeast(
                            0
                        ) until bounds.right.coerceAtMost(image.width)) {
                            val c = image.getPixel(x, y)
                            for (key in counts.keys) if (kotlin.math.abs(
                                    Color.red(c) - Color.red(
                                        key
                                    )
                                ) < 4 && kotlin.math.abs(Color.green(c) - Color.green(key)) < 4 && kotlin.math.abs(
                                    Color.blue(c) - Color.blue(key)
                                ) < 4
                            ) counts[key] = counts.getValue(key) + 1
                        }
                        lastCounts = counts.toMap()
                        val corePresented = cores.all { (color, points) ->
                            val matched = points.count { (x, y) ->
                                val sx = bounds.left + x
                                val sy = bounds.top + y
                                if (sx !in 0 until image.width || sy !in 0 until image.height) false else {
                                    val actual = image.getPixel(sx, sy)
                                    kotlin.math.abs(Color.red(actual) - Color.red(color)) < 4 && kotlin.math.abs(
                                        Color.green(actual) - Color.green(color)
                                    ) < 4 && kotlin.math.abs(Color.blue(actual) - Color.blue(color)) < 4
                                }
                            }
                            matched >= points.size * .97
                        }
                        if (corePresented) {
                            accepted = image
                            break
                        }
                        image.recycle()
                        Thread.sleep(40)
                    }
                    if (accepted == null) {
                        val failed = checkNotNull(uiAutomation.takeScreenshot())
                        File(
                            targetContext.filesDir,
                            "road-comparison-failed-$mode-$tag.png"
                        ).outputStream().use { failed.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        failed.recycle()
                    }
                    val image =
                        checkNotNull(accepted) { "Both road bars not fully presented: $mode expected=$expected actual=$lastCounts bounds=$bounds" }
                    File(targetContext.filesDir, "road-comparison-$mode-$tag.png").outputStream()
                        .use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    image.recycle()
                    check(chart.contentDescription.contains("중력 제거 수직 신호 최댓값"))
                }
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            result.putString(
                "stream",
                "PASS: road comparison day/night bars, large font and invalid/missing suppression; awaiting observation shown without old metrics, large font fallback and invalid original refusal. Synthetic renderer only; no taps, real road or repair claim.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                ShowcasePreviewActivity.previewFontScale = 1f
                screen?.finish()
            }
        }
    }
}
