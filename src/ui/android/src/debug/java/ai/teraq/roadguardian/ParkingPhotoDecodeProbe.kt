package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.widget.FrameLayout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ParkingPhotoDecodePreviewActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(FrameLayout(this))
    }
}

class ParkingPhotoDecodeProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var activity: Activity? = null
        try {
            val source =
                File.createTempFile("parking-decode-probe-", ".png", targetContext.cacheDir)
            Bitmap.createBitmap(3200, 800, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
                source.outputStream().use { check(compress(Bitmap.CompressFormat.PNG, 100, it)) }
                recycle()
            }
            val obsolete =
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            activity = startActivitySync(
                Intent(
                    targetContext,
                    ParkingPhotoDecodePreviewActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            lateinit var picture: ParkingPhotoView
            lateinit var root: FrameLayout
            runOnMainSync {
                root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    .getChildAt(0) as FrameLayout
                picture = object : ParkingPhotoView(activity, 800) {
                    override fun decode(file: File, edge: Int): Bitmap {
                        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                        if (file.name == "blocked-fixture") {
                            entered.countDown()
                            while (release.count > 0) try {
                                release.await(2, TimeUnit.SECONDS)
                            } catch (_: InterruptedException) {
                            }
                            return obsolete
                        }
                        return super.decode(file, edge)
                    }
                }
                root.addView(picture, FrameLayout.LayoutParams(800, 400))
                picture.load(File("blocked-fixture"))
            }
            check(entered.await(3, TimeUnit.SECONDS))
            runOnMainSync { picture.load(source) }
            fun awaitCondition(condition: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 4000
                while (!condition()) {
                    check(SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(20)
                }
            }
            awaitCondition {
                var ready = false
                runOnMainSync {
                    val bitmap = (picture.drawable as? BitmapDrawable)?.bitmap
                    ready =
                        bitmap != null && bitmap.width == 800 && bitmap.height == 200 && bitmap.getPixel(
                            0,
                            0
                        ) == Color.BLUE
                }
                ready
            }
            release.countDown()
            awaitCondition { obsolete.isRecycled }
            runOnMainSync {
                check((picture.drawable as BitmapDrawable).bitmap.getPixel(0, 0) == Color.BLUE)
                root.removeView(picture)
                check(picture.drawable == null)
            }
            result.putString(
                "stream",
                "PASS: real 3200x800 PNG decoded to 800x200 off main thread; newer blue photo remains after blocked old red result; stale bitmap recycled; detached view clears drawable. Synthetic cache fixture only.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            release.countDown()
            activity?.let { runOnMainSync { it.finish() } }
        }
    }
}
