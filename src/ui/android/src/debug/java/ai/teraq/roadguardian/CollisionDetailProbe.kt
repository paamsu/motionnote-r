package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import org.json.JSONObject

/** Phone renderer fixture check, not login or HTTP navigation. */
class CollisionDetailProbe : Instrumentation() {
    private var night = false
    override fun onCreate(a: Bundle?) {
        super.onCreate(a)
        night = a?.getString("night") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            val event =
                JSONObject(File(targetContext.filesDir, "detail-context-fixture.json").readText())
            require(VideoCollisionAnalysis.present(event))
            val expected = VideoCollisionAnalysis.detail(event)
            screen = startActivitySync(
                Intent(targetContext, AccountDetailPreviewActivity::class.java)
                    .putExtra("context_fixture", true).putExtra("night", night)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            val activity = checkNotNull(screen)
            fun texts(): List<TextView> {
                val out = mutableListOf<TextView>()
                fun walk(v: View) {
                    if (!v.isShown) return
                    if (v is TextView) out.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(activity.window.decorView)
                return out
            }

            fun waitFor(test: () -> Boolean) {
                repeat(100) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(50)
                }
                error("Layout timeout")
            }
            waitFor { activity.hasWindowFocus() }
            if (android.os.Build.VERSION.SDK_INT >= 30) waitFor {
                val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                val appearance =
                    activity.window.insetsController?.systemBarsAppearance ?: return@waitFor false
                (appearance and mask != 0) == !night
            }
            waitFor { texts().any { it.text.toString() == "상세 기록 펼치기" && it.height > 0 } }
            runOnMainSync {
                check(texts().none { it.text.toString() == expected })
                check(texts().none { it.text.toString() == "장소" || it.text.toString() == "주변 차량" })
                check(texts().any { it.text.toString() == EventBriefState.from(event).title })
                fun hasGraphic(v: View): Boolean = v is DriverEventGraphic && v.isShown ||
                        (v is ViewGroup && (0 until v.childCount).any { hasGraphic(v.getChildAt(it)) })
                check(hasGraphic(activity.window.decorView))
            }
            Thread.sleep(300)
            val brief = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.filesDir, "collision-brief.png").outputStream()
                .use { brief.compress(Bitmap.CompressFormat.PNG, 100, it) }
            brief.recycle()
            runOnMainSync { texts().single { it.text.toString() == "상세 기록 펼치기" }.performClick() }
            waitFor { texts().any { it.text.toString() == expected && it.height > 0 && !it.isLayoutRequested } }
            runOnMainSync {
                check(texts().any { it.text.toString() == EventBriefState.from(event).title })
                check(texts().any { it.text.toString() == "합성 영상 6시점 · 실제 충돌 확인 아님" })
                texts().single { it.text.toString() == expected }.let {
                    it.requestRectangleOnScreen(
                        Rect(
                            0,
                            0,
                            it.width,
                            it.height + (160 * activity.resources.displayMetrics.density).toInt()
                        ), true
                    )
                }
            }
            waitFor {
                texts().single { it.text.toString() == expected }.let { v ->
                    val r = Rect()
                    v.getGlobalVisibleRect(r) && r.height() == v.height && r.width() == v.width
                }
            }
            runOnMainSync {
                val v = texts().single { it.text.toString() == expected }
                check((0 until v.layout.lineCount).all { v.layout.getEllipsisCount(it) == 0 })
            }
            Thread.sleep(300)
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.filesDir, "collision-detail.png").outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            File(targetContext.filesDir, "collision-detail-result.json").writeText(
                JSONObject()
                    .put("event_id", event.getString("id")).put("night", night)
                    .put("valid", VideoCollisionAnalysis.checked(event) != null)
                    .put("image_first", true).put("details_initially_hidden", true)
                    .put("visible_text", expected).put("renderer_fixture_only", true).toString(2)
            )
            result.putString("stream", "PASS: phone collision detail and visible uncertainty\n")
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
