package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.json.JSONObject
import java.io.File

class EventPopupProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        start()
    }

    override fun onStart() {
        val out = Bundle()
        var screen: Activity? = null
        try {
            val event = JSONObject(File(targetContext.filesDir, "popup-fixture.json").readText())
            fun capture(name: String) {
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "popup-$name.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            capture("background")
            screen = startActivitySync(
                Intent(
                    targetContext,
                    EventPopupActivity::class.java
                ).putExtra("event", event.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            )
            Thread.sleep(1500)
            capture("visible")
            val a = screen!!
            var issue: Throwable? = null
            runOnMainSync {
                try {
                    val buttons = mutableListOf<Button>()
                    fun walk(v: View) {
                        if (v is Button) buttons.add(v)
                        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                    }
                    walk(a.window.decorView)
                    check(buttons.size == 1 && buttons.single().text.toString() == "확인")
                    check(a.window.attributes.width < targetContext.resources.displayMetrics.widthPixels)
                    check(buttons.single().performClick())
                } catch (t: Throwable) {
                    issue = t
                }
            }
            issue?.let { throw it }
            Thread.sleep(1200)
            capture("returned")
            screen = null
            out.putString(
                "stream",
                "PASS: translucent event popup, single acknowledgement, dismissed to background\n"
            )
            finish(Activity.RESULT_OK, out)
        } catch (t: Throwable) {
            out.putString("stream", "FAIL: ${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, out)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
