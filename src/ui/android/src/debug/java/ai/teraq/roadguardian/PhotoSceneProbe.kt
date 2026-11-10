package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.io.File

class PhotoSceneProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var screen: PhotoScenePreviewActivity? = null
        val result = Bundle()
        try {
            screen = startActivitySync(
                Intent(
                    targetContext,
                    PhotoScenePreviewActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ) as PhotoScenePreviewActivity
            waitForIdleSync()
            fun ready(): Boolean {
                var ok = false
                runOnMainSync {
                    val pictures = mutableListOf<ParkingPhotoView>()
                    fun visit(v: View) {
                        if (v is ParkingPhotoView) pictures.add(v)
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(screen!!.comparison!!.window!!.decorView)
                    ok = pictures.size == 2 && pictures.all {
                        val b = (it.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        b != null && b.width >= 1000 && b.height >= 600
                    }
                }
                return ok
            }

            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (!ready() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            check(ready()) { "Both real photo bitmaps must be decoded, not error placeholders" }
            waitForIdleSync()
            Thread.sleep(500)
            runOnMainSync {
                val labels = mutableListOf<TextView>()
                val images = mutableListOf<ImageView>()
                fun visit(v: View) {
                    if (v.isShown) {
                        if (v is TextView) labels.add(v)
                        if (v is ImageView && v.drawable != null) images.add(v)
                    }
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(screen!!.comparison!!.window!!.decorView)
                check(labels.any { it.text.contains("AI 합성 시연") })
                check(images.size == 2)
                for (image in images) {
                    val bounds = android.graphics.Rect()
                    check(image.getGlobalVisibleRect(bounds))
                    check(bounds.width() == image.width && bounds.height() == image.height)
                }
                for (label in labels) {
                    val layout = checkNotNull(label.layout)
                    for (i in 0 until layout.lineCount) check(layout.getEllipsisCount(i) == 0)
                }
            }
            val frame = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.filesDir, "photo-scene-pair-v187.png").outputStream()
                .use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            frame.recycle()
            result.putString(
                "stream",
                "PASS: current comparison dialog shows two AI scene fixtures, explicit synthetic notice and fully visible images; method-render test, no taps or camera.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
