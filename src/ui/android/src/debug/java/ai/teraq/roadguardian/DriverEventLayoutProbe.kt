package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Authored component layout evidence, not real driving or direct control interaction. */
class DriverEventLayoutProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            val rows = JSONArray()
            for (kind in listOf(
                "pothole",
                "crash",
                "diagnosis",
                "repeat-low",
                "recovered",
                "healthy"
            )) for (mode in listOf("day", "night", "large")) {
                val event =
                    JSONObject().put("id", UUID.randomUUID().toString()).put("hash", "a".repeat(64))
                        .put("source", "synthetic").put("evidence_valid", true)
                        .put("status", "evidence_ready")
                        .put(
                            "assessment",
                            JSONObject().put(
                                "kind",
                                if (kind in setOf(
                                        "repeat-low",
                                        "recovered",
                                        "healthy"
                                    )
                                ) "diagnosis" else kind
                            ).put("diagnostic", JSONObject().put("verdict", "inspection_required"))
                        )
                        .put("diagnostic_valid", true).put(
                            "repair_case",
                            JSONObject().put("source", "synthetic")
                                .put("stage", "measurement_review")
                        )
                        .put(
                            "insurance_case",
                            JSONObject().put("source", "synthetic").put("status", "ready")
                        )
                if (kind in setOf("repeat-low", "recovered")) event.getJSONObject("repair_case")
                    .put(
                        "stage",
                        if (kind == "repeat-low") "follow_up_required" else "observed_signal_recovered"
                    )
                    .put(
                        "after",
                        JSONObject().put("delta_v", if (kind == "repeat-low") -1.8 else -0.1)
                            .put("voltage_median_v", if (kind == "repeat-low") 10.7 else 12.4)
                            .put("baseline_v", 12.5).put("matched_samples", 60)
                    )
                if (kind == "healthy") event.getJSONObject("assessment").getJSONObject("diagnostic")
                    .put("verdict", "no_anomaly_in_observed_signals")
                val expected = when (kind) {
                    "pothole" -> "포트홀 감지"
                    "crash" -> "사고 감지"
                    "repeat-low" -> "저전압 다시 감지"
                    "recovered" -> "재측정 전압 회복"
                    "healthy" -> "전압 이상 없음"
                    else -> "저전압 확인"
                }
                val message = when (kind) {
                    "pothole" -> "센서 기록 보관"
                    "crash" -> "정차 후 보험 안내"
                    "diagnosis" -> "정차 후 센터 안내"
                    "repeat-low" -> "서비스센터 방문 추천"
                    else -> "측정 구간의 결과"
                }
                val graphicDescription = when (kind) {
                    "pothole" -> "도로 충격 기록 그래픽"
                    "crash" -> "사고 기록 그래픽"
                    else -> "전압 측정 그래픽"
                }
                DriverBriefPreviewActivity.font = if (mode == "large") 1.5f else 1f
                activity = startActivitySync(
                    Intent(targetContext, DriverBriefPreviewActivity::class.java)
                        .putExtra("state", "drive").putExtra("event_json", event.toString())
                        .putExtra("night", mode == "night")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                waitForIdleSync()
                val nodes = mutableListOf<View>()
                runOnMainSync {
                    fun visit(view: View) {
                        if (view.isShown) nodes.add(view)
                        if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                            view.getChildAt(
                                i
                            )
                        )
                    }
                    visit(activity!!.window.decorView)
                }
                val labels = nodes.filterIsInstance<TextView>()
                val graphics = nodes.filterIsInstance<DriverEventGraphic>()
                check(labels.size == 2 && labels[0].text.toString() == expected && labels[1].text.toString() == message)
                check(graphics.size == 1 && graphics.single().contentDescription.toString() == graphicDescription)
                check(nodes.none { it.isClickable || it is android.widget.Button || it is android.widget.ScrollView || it is android.widget.HorizontalScrollView })
                val targets: List<View> = listOf(labels[0], graphics.single(), labels[1])
                var accepted: Bitmap? = null
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    val anchors = mutableListOf<Triple<Bitmap, Int, Int>>()
                    runOnMainSync {
                        var previousBottom = Int.MIN_VALUE
                        for (view in targets) {
                            check(view.width > 0 && view.height > 0)
                            val visible = Rect()
                            check(view.getGlobalVisibleRect(visible) && visible.width() == view.width && visible.height() == view.height) { "Clipped bounds $kind/$mode" }
                            val at = IntArray(2)
                            view.getLocationOnScreen(at)
                            check(at[1] >= previousBottom) { "Overlapping event blocks $kind/$mode" }
                            previousBottom = at[1] + view.height
                            if (view is TextView) {
                                val layout = checkNotNull(view.layout)
                                check(layout.lineCount in 1..2)
                                check(layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                                for (line in 0 until layout.lineCount) {
                                    check(layout.getEllipsisCount(line) == 0)
                                    check(layout.getLineMax(line) <= view.width - view.compoundPaddingLeft - view.compoundPaddingRight + 2)
                                }
                            }
                            val bitmap = Bitmap.createBitmap(
                                view.width,
                                view.height,
                                Bitmap.Config.ARGB_8888
                            )
                            view.draw(Canvas(bitmap))
                            anchors.add(Triple(bitmap, at[0], at[1]))
                        }
                    }
                    val frame = checkNotNull(uiAutomation.takeScreenshot())
                    val presented = try {
                        anchors.all { (bitmap, left, top) ->
                            var opaque = 0
                            var matches = 0
                            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                                val expectedPixel = bitmap.getPixel(x, y)
                                if (Color.alpha(expectedPixel) < 245) continue
                                opaque++
                                if (left + x !in 0 until frame.width || top + y !in 0 until frame.height) continue
                                val actual = frame.getPixel(left + x, top + y)
                                if (kotlin.math.abs(Color.red(expectedPixel) - Color.red(actual)) < 12 &&
                                    kotlin.math.abs(Color.green(expectedPixel) - Color.green(actual)) < 12 &&
                                    kotlin.math.abs(Color.blue(expectedPixel) - Color.blue(actual)) < 12
                                ) matches++
                            }
                            opaque > 30 && matches >= opaque * .97
                        }
                    } finally {
                        anchors.forEach { it.first.recycle() }
                    }
                    if (presented && android.os.SystemClock.elapsedRealtime() <= deadline) {
                        accepted = frame
                        break
                    }
                    frame.recycle()
                    Thread.sleep(80)
                }
                val frame =
                    checkNotNull(accepted) { "Native event pixels not presented $kind/$mode" }
                val name = "driver-event-$kind-$mode-v224.png"
                File(targetContext.filesDir, name).outputStream()
                    .use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                frame.recycle()
                rows.put(
                    JSONObject().put("kind", kind).put("mode", mode).put("title", expected)
                        .put("message", message).put("graphic", graphicDescription)
                        .put("text_blocks", 2).put("controls", 0).put("scrolls", 0)
                        .put("fully_visible", true).put("native_pixels", true)
                        .put("screenshot", name)
                )
                runOnMainSync { activity!!.finish() }
                activity = null
            }
            File(targetContext.filesDir, "driver-event-layout-v224.json").writeText(
                JSONObject().put("states", rows)
                    .put(
                        "scope",
                        "authored synthetic event component in drive display state; no actual driving, OEM override or direct taps"
                    ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: 18 event layouts, exact titles/graphics, two text blocks, zero controls/scrolls, complete visible height, no text overflow, native text and graphic pixels. Authored preview only.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            uiAutomation.takeScreenshot()?.let { frame ->
                File(targetContext.filesDir, "driver-event-layout-failed-v224.png").outputStream()
                    .use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                frame.recycle()
            }
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
            DriverBriefPreviewActivity.font = 1f
        }
    }
}
