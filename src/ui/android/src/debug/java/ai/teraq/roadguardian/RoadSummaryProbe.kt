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

class RoadSummaryProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            for (mode in listOf("day", "night", "large", "empty", "invalid")) {
                RoadSummaryPreviewActivity.previewFontScale = if (mode == "large") 1.5f else 1f
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        RoadSummaryPreviewActivity::class.java
                    ).putExtra("night", mode == "night").putExtra("empty", mode == "empty")
                        .putExtra("invalid", mode == "invalid")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                waitForIdleSync()
                Thread.sleep(600)
                waitForIdleSync()
                runOnMainSync {
                    val labels = mutableListOf<TextView>()
                    fun visit(v: View) {
                        if (v is TextView && v.isShown) labels.add(v)
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(screen!!.window.decorView)
                    val text = labels.joinToString("\n") { it.text.toString() }
                    if (mode == "invalid") check(text.contains("집계 자료 확인 실패") && !text.contains("99건"))
                    else if (mode == "empty") check(text.contains("묶을 수 있는 관측 없음"))
                    else check(
                        text.contains("4건") && text.contains("3건") && text.contains("확인 대기 1건") && text.contains(
                            "가상 차량 ID 2개"
                        ) && text.contains("기관 제공·보수 확인 아님")
                    )
                    for (label in labels) {
                        val layout = checkNotNull(label.layout)
                        for (line in 0 until layout.lineCount) {
                            check(layout.getEllipsisCount(line) == 0)
                            check(layout.getLineWidth(line) <= label.width - label.paddingLeft - label.paddingRight + 2)
                        }
                    }
                }
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "road-summary-$mode-v186.png").outputStream()
                    .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            result.putString(
                "stream",
                "PASS: road summary day/night/large/empty/inconsistent counts; no text truncation. Synthetic rendering, no taps or agency transmission.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
            RoadSummaryPreviewActivity.previewFontScale = 1f
        }
    }
}
