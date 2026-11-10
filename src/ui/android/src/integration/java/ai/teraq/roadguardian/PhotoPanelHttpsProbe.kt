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
import android.widget.ImageView
import android.widget.TextView
import java.io.File

/** Real authenticated shared photo renderer, read-only controls and host/account invalidation. No taps. */
class PhotoPanelHttpsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: PhotoPanelPreviewActivity? = null
        try {
            val fixture = IntegrationIdentity.install(targetContext, "photo_token")
            val id = fixture.getString("photo_case_event_id")
            val car =
                targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)

            fun open(): PhotoPanelPreviewActivity = startActivitySync(
                Intent(targetContext, PhotoPanelPreviewActivity::class.java).putExtra(
                    "event_id",
                    id
                )
                    .putExtra("night", car)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ) as PhotoPanelPreviewActivity

            fun views(): List<View> {
                val values = mutableListOf<View>()
                runOnMainSync {
                    fun read(view: View) {
                        if (view.isShown) values.add(view)
                        if (view is ViewGroup) for (i in 0 until view.childCount) read(
                            view.getChildAt(
                                i
                            )
                        )
                    }
                    read(screen!!.window.decorView)
                }
                return values
            }

            fun labels() =
                views().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }

            fun await(value: String) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 20000
                while (!labels().contains(value) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(
                    50
                )
                check(labels().contains(value)) { "Missing photo renderer: $value; ${labels()}" }
            }
            screen = open()
            await("그림으로 만든 전송 시험")
            check(
                labels().contains("보관한 사진 1/4") && !labels().contains("사진 선택 후 확인") && !labels().contains(
                    "이 사진을 기록에 보관"
                )
            )
            val image = views().filterIsInstance<ImageView>().first { it.drawable != null }
            val caption = views().filterIsInstance<TextView>()
                .first { it.text.toString().startsWith("그림으로 만든 전송 시험") }

            data class Target(
                val bounds: Rect,
                val color: Int,
                val minimum: Int,
                val tolerant: Boolean
            )

            val targets = mutableListOf<Target>()
            runOnMainSync {
                fun add(view: View, color: Int, tolerant: Boolean) {
                    val bitmap =
                        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    fun matching(c: Int) =
                        if (tolerant) kotlin.math.abs(Color.red(c) - 30) < 4 && kotlin.math.abs(
                            Color.green(c) - 105
                        ) < 4 && kotlin.math.abs(Color.blue(c) - 120) < 4 else c == color

                    var expected = 0
                    for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) if (matching(
                            bitmap.getPixel(x, y)
                        )
                    ) expected++
                    bitmap.recycle()
                    check(expected >= 10)
                    val bounds = Rect()
                    check(view.getGlobalVisibleRect(bounds))
                    check(bounds.width() >= view.width && bounds.height() >= view.height)
                    val screenPosition = IntArray(2)
                    val windowPosition = IntArray(2)
                    view.getLocationOnScreen(screenPosition)
                    view.getLocationInWindow(windowPosition)
                    bounds.offset(
                        screenPosition[0] - windowPosition[0],
                        screenPosition[1] - windowPosition[1]
                    )
                    targets.add(Target(bounds, color, (expected * .9).toInt(), tolerant))
                }
                add(image, 0, true)
                add(caption, caption.currentTextColor, false)
            }
            var ready: Bitmap? = null
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val frame = checkNotNull(uiAutomation.takeScreenshot())
                val matched = targets.all { target ->
                    var count = 0
                    for (y in target.bounds.top.coerceAtLeast(0) until target.bounds.bottom.coerceAtMost(
                        frame.height
                    ) step 3)
                        for (x in target.bounds.left.coerceAtLeast(0) until target.bounds.right.coerceAtMost(
                            frame.width
                        ) step 3) {
                            val c = frame.getPixel(x, y)
                            if (if (target.tolerant) kotlin.math.abs(Color.red(c) - 30) < 4 && kotlin.math.abs(
                                    Color.green(c) - 105
                                ) < 4 && kotlin.math.abs(Color.blue(c) - 120) < 4 else c == target.color
                            ) count++
                        }
                    count >= target.minimum
                }
                if (matched) {
                    ready = frame
                    break
                }
                frame.recycle()
                Thread.sleep(50)
            }
            val frame = checkNotNull(ready)
            File(targetContext.filesDir, "photo-panel-v161.png").outputStream()
                .use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            frame.recycle()
            runOnMainSync { screen!!.displayAllowed = false }
            await("사진을 지웠습니다")
            check(views().filterIsInstance<ImageView>().none { it.drawable != null })
            runOnMainSync { screen!!.finish() }
            screen = open()
            await("그림으로 만든 전송 시험")
            IntegrationIdentity.install(targetContext, "other_owner_token")
            await("사진을 지웠습니다")
            check(
                views().filterIsInstance<ImageView>()
                    .none { it.drawable != null } && !labels().contains("그림으로 만든 전송 시험"))
            result.putString(
                "stream",
                "PASS: private photo HTTPS shared renderer, read-only controls, actual image and caption frame coverage, host-condition and account-change image removal; automotive=$car. Test drawing/login; no MainActivity card selection, actual gear/UX change or taps.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
            LoginStateStore(targetContext).write(net.openid.appauth.AuthState())
        }
    }
}
