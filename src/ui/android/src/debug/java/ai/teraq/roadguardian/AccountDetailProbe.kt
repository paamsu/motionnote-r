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

/** Render and inspect synthetic detail; does not activate controls or authenticate. */
class AccountDetailProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val activity = startActivitySync(
                Intent(
                    targetContext,
                    AccountDetailPreviewActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            waitForIdleSync()
            Thread.sleep(1000)
            val labels = mutableListOf<String>()
            fun collect(view: View) {
                if (view is TextView) labels.add(view.text.toString())
                if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
            runOnMainSync { collect(activity.window.decorView) }
            val text = labels.joinToString("\n")
            for (expected in listOf(
                "DEMO 4821",
                "쏘나타",
                "은색",
                "우측 후방",
                "접촉 및 책임 차량 미확정",
                "영상 인식 미실행"
            )) check(text.contains(expected))
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.cacheDir, "account-detail-v64.png").outputStream()
                .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            runOnMainSync { activity.finish() }
            result.putString(
                "stream",
                "PASS: synthetic plate/model/color/impact/uncertainty labels rendered. CAPTURED initial viewport; no scrolling, button interaction or authenticated API tested.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
