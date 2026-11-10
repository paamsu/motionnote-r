package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Component evidence only; does not simulate a real moving vehicle or bypass its OEM. */
class DriverBriefProbe : Instrumentation() {
    private var comparison = false
    private val tag get() = if (comparison) "v205" else "v202"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        comparison = arguments?.getString("comparison") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            val rows = JSONArray()
            for (mode in listOf("day", "night", "large")) for (state in listOf(
                "drive",
                "unavailable",
                "parked"
            )) {
                DriverBriefPreviewActivity.font = if (mode == "large") 1.5f else 1f
                activity = startActivitySync(
                    Intent(
                        targetContext,
                        DriverBriefPreviewActivity::class.java
                    ).putExtra("state", state).putExtra("comparison_ready", comparison)
                        .putExtra("night", mode == "night")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                waitForIdleSync()
                val nodes = mutableListOf<View>()
                runOnMainSync {
                    fun visit(v: View) {
                        if (v.isShown) nodes.add(v)
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(activity!!.window.decorView)
                }
                val labels = nodes.filterIsInstance<TextView>()
                check(labels.size == 2)
                check(nodes.none { it.isClickable || it is android.widget.ScrollView || it is android.widget.ImageView })
                val expected = when (state) {
                    "drive" -> if (comparison) "시험 비교 자료 준비" else "주행 기어"
                    "parked" -> "화면 열람 제한"
                    else -> "차량 상태 확인 중"
                }
                check(labels[0].text.toString() == expected)
                val anchors = mutableListOf<Triple<Bitmap, Int, Int>>()
                runOnMainSync {
                    for (v in labels) {
                        check(v.layout.lineCount in 1..2)
                        for (line in 0 until v.layout.lineCount) {
                            check(v.layout.getEllipsisCount(line) == 0)
                            check(v.layout.getLineMax(line) <= v.width - v.compoundPaddingLeft - v.compoundPaddingRight + 2)
                        }
                        val at = IntArray(2)
                        v.getLocationOnScreen(at)
                        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                        v.draw(Canvas(b))
                        anchors.add(Triple(b, at[0], at[1]))
                    }
                }
                var accepted: Bitmap? = null
                val deadline = android.os.SystemClock.elapsedRealtime() + 10000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    runOnMainSync {
                        labels.forEachIndexed { index, v ->
                            val at = IntArray(2)
                            v.getLocationOnScreen(at)
                            var bitmap = anchors[index].first
                            if (bitmap.width != v.width || bitmap.height != v.height) {
                                bitmap.recycle()
                                bitmap =
                                    Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                                v.draw(Canvas(bitmap))
                            }
                            anchors[index] = Triple(bitmap, at[0], at[1])
                        }
                    }
                    val frame = checkNotNull(uiAutomation.takeScreenshot())
                    val ready = anchors.all { (b, left, top) ->
                        var n = 0
                        var matches = 0
                        for (y in 0 until b.height) for (x in 0 until b.width) {
                            val a = b.getPixel(x, y)
                            if (Color.alpha(a) < 245) continue
                            n++
                            if (left + x !in 0 until frame.width || top + y !in 0 until frame.height) continue
                            val c = frame.getPixel(left + x, top + y)
                            if (kotlin.math.abs(Color.red(a) - Color.red(c)) < 12 && kotlin.math.abs(
                                    Color.green(a) - Color.green(c)
                                ) < 12 && kotlin.math.abs(Color.blue(a) - Color.blue(c)) < 12
                            ) matches++
                        }
                        n > 30 && matches >= n * .97
                    }
                    if (ready) {
                        accepted = frame
                        break
                    }
                    frame.recycle()
                    Thread.sleep(80)
                }
                anchors.forEach { it.first.recycle() }
                val frame = checkNotNull(accepted) { "Brief not presented: $mode/$state" }
                val file = "driver-brief-$state-$mode-$tag.png"
                File(targetContext.filesDir, file).outputStream()
                    .use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                frame.recycle()
                rows.put(
                    JSONObject().put("mode", mode).put("state", state).put("text_blocks", 2)
                        .put("controls", 0).put("scrolls", 0).put("native_pixels", true)
                        .put("screenshot", file)
                )
                runOnMainSync { activity!!.finish() }
                activity = null
            }
            File(
                targetContext.filesDir,
                "driver-brief-result-$tag.json"
            ).writeText(
                JSONObject().put("states", rows).put(
                    "scope",
                    "authored component preview; no OEM bypass or real driving approval"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS:9 brief states, two text blocks, zero controls/scroll/images, native text pixels and no overflow. Synthetic component only.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            uiAutomation.takeScreenshot()?.let { b ->
                File(targetContext.filesDir, "driver-brief-failed-$tag.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
            DriverBriefPreviewActivity.font = 1f
        }
    }
}
