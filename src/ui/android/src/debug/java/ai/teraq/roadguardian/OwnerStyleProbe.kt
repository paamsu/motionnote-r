package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import java.io.File
import org.json.JSONObject

/** Actual phone entry view; no clicks, account changes or monitoring changes. */
class OwnerStyleProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: OwnerActivity? = null
        try {
            screen = startActivitySync(
                Intent(
                    targetContext,
                    OwnerActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ) as OwnerActivity
            waitForIdleSync()
            Thread.sleep(400)
            var buttons = 0
            var switches = 0
            runOnMainSync {
                fun visit(v: View) {
                    if (v is TextView && v.isShown) {
                        val layout = v.layout
                        if (layout != null) for (i in 0 until layout.lineCount) {
                            check(layout.getEllipsisCount(i) == 0)
                            check(layout.getLineMax(i) <= v.width - v.compoundPaddingLeft - v.compoundPaddingRight + 2)
                        }
                        if (v is Button || v is Switch) {
                            check(v.typeface == PleosTypography.face(false))
                            check(v.currentTextColor == PleosPalette.ink(true))
                        }
                        if (v is Button) buttons++
                        if (v is Switch) switches++
                    }
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(screen!!.window.decorView)
                check(buttons >= 3 && switches == 1)
            }
            val frame = checkNotNull(uiAutomation.takeScreenshot())
            check(
                frame.getPixel(frame.width / 2, frame.height * 9 / 10) == PleosPalette.background(
                    true
                )
            )
            File(targetContext.filesDir, "owner-style-v211.png").outputStream()
                .use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            frame.recycle()
            File(
                targetContext.filesDir,
                "owner-style-v211.json"
            ).writeText(
                JSONObject().put("buttons", buttons).put("switches", switches)
                    .put("control_font", "Asta Regular")
                    .put("control_text", "PleosPalette.ink(true)")
                    .put("background_pixel", "#FFFFFF").put("text_bounds_checked", true).put(
                    "scope",
                    "actual default phone home; no direct taps or large-font/night checks"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: actual phone home neutral text/background, shared Asta buttons/switch, text bounds. No control taps.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
