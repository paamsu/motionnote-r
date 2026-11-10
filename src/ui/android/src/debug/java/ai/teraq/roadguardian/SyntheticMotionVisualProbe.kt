package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File

/** Renders decoded synthetic-video evidence, no simulated taps or real-road claims. */
class SyntheticMotionVisualProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            for (mode in listOf("day", "night", "large")) {
                TrafficPreviewActivity.previewFontScale = if (mode == "large") 1.5f else 1f
                screen = startActivitySync(
                    Intent(targetContext, TrafficPreviewActivity::class.java).putExtra(
                        "case",
                        "reckless"
                    )
                        .putExtra("decoded", true).putExtra("night", mode == "night")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                waitForIdleSync()
                Thread.sleep(600)
                waitForIdleSync()
                var failure: Exception? = null
                runOnMainSync {
                    try {
                        val labels = mutableListOf<TextView>()
                        fun visit(v: View) {
                            if (v.isShown && v is TextView) labels.add(v)
                            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                        }
                        visit(screen!!.window.decorView)
                        val text = labels.joinToString("\n") { it.text.toString() }
                        check(
                            text.contains("4회") && text.contains("차선 이동 · 합성 영상") && text.contains(
                                "상대 횡방향 위치 · 합성 영상 계산"
                            )
                        )
                        check(!text.contains("DEMO") && !text.contains("null"))
                        for (label in labels) {
                            check(!label.paint.isFakeBoldText)
                            val layout = checkNotNull(label.layout)
                            for (line in 0 until layout.lineCount) {
                                check(layout.getEllipsisCount(line) == 0)
                                check(layout.getLineWidth(line) <= label.width - label.paddingLeft - label.paddingRight + 2)
                            }
                        }
                    } catch (e: Exception) {
                        failure = e
                    }
                }
                failure?.let { throw it }
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "synthetic-motion-$mode-v192.png").outputStream()
                    .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            result.putString(
                "stream",
                "PASS: 3 decoded synthetic motion render states; no clipping/fake bold/fictional identity. No taps or real-road recognition.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
            TrafficPreviewActivity.previewFontScale = 1f
        }
    }
}
