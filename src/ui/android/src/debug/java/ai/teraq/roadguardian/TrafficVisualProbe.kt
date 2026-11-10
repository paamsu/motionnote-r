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

class TrafficVisualProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            for (kind in listOf("violation", "wanted", "reckless")) for (mode in listOf(
                "day",
                "night",
                "large",
                "invalid",
                "unproved"
            )) {
                TrafficPreviewActivity.previewFontScale = if (mode == "large") 1.5f else 1f
                screen = startActivitySync(
                    Intent(targetContext, TrafficPreviewActivity::class.java).putExtra("case", kind)
                        .putExtra("night", mode == "night").putExtra("invalid", mode == "invalid")
                        .putExtra("unproved", mode == "unproved")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                waitForIdleSync()
                Thread.sleep(500)
                waitForIdleSync()
                var failure: Exception? = null
                runOnMainSync {
                    try {
                        val labels = mutableListOf<TextView>()
                        val graphics = mutableListOf<VehicleIdentityOutline>()
                        fun visit(v: View) {
                            if (v.isShown) {
                                if (v is TextView) labels.add(v)
                                if (v is VehicleIdentityOutline) graphics.add(v)
                            }
                            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                        }
                        visit(screen!!.window.decorView)
                        val text = labels.joinToString("\n") { it.text.toString() }
                        if (mode in setOf(
                                "invalid",
                                "unproved"
                            )
                        ) check(text.contains("표시를 보류") && !text.contains("DEMO") && graphics.isEmpty())
                        else {
                            check(
                                text.contains("위반·수배 판정 아님") && text.contains("실제 기관 전송 없음") && text.contains(
                                    when (kind) {
                                        "violation" -> "180초"
                                        "reckless" -> "4회"
                                        else -> "시험 번호 일치"
                                    }
                                )
                            )
                            check(text.contains("DEMO"))
                            check(graphics.size == if (mode == "large") 0 else 1)
                        }
                        for (label in labels) {
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
                File(targetContext.filesDir, "traffic-$kind-$mode-v184.png").outputStream()
                    .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            result.putString(
                "stream",
                "PASS: 15 synthetic traffic component states, day/night/large/invalid/unproved; no line truncation, no identity on invalid evidence, no verdict or external submission claims. Render only, no taps or real recognition.\n"
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
