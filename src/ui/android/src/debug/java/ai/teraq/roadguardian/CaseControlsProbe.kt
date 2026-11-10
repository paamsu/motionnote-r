package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Actual production detail renderer with synthetic case data; no input injection or taps. */
class CaseControlsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        val cases = JSONArray()
        try {
            val car =
                targetContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
            for ((name, font, night) in listOf(
                Triple("day", 1f, false),
                Triple("night", 1f, true),
                Triple("large", 1.5f, false)
            )) {
                runOnMainSync { AccountDetailPreviewActivity.previewFontScale = font }
                screen = startActivitySync(
                    Intent(targetContext, AccountDetailPreviewActivity::class.java)
                        .putExtra("dossier_fixture", "crash").putExtra("night", night)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                waitForIdleSync()
                Thread.sleep(450)
                waitForIdleSync()
                val buttons = mutableListOf<Button>()
                fun collect(view: View) {
                    if (view is Button && view.isShown) buttons.add(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) collect(
                        view.getChildAt(
                            i
                        )
                    )
                }

                val controls = JSONArray()
                var layoutFailure: Throwable? = null
                runOnMainSync {
                    runCatching {
                        collect(screen!!.window.decorView)
                        check(buttons.size >= 2)
                        for (button in buttons) {
                            val density = button.resources.displayMetrics.density
                            check(button.width > 0 && button.height >= density * (if (car) 76 else 56) - 1)
                            val layout = checkNotNull(button.layout)
                            check(layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                            for (line in 0 until layout.lineCount) check(
                                layout.getEllipsisCount(
                                    line
                                ) == 0
                            )
                            check(
                                button.textSize >= android.util.TypedValue.applyDimension(
                                    android.util.TypedValue.COMPLEX_UNIT_SP,
                                    if (car) 23.9f else 17.9f,
                                    button.resources.displayMetrics
                                )
                            ) { "${button.text}: text size ${button.textSize}px, car=$car font=$font" }
                            check(button.width <= (button.parent as View).width)
                            if (button.text.endsWith("펼치기") && android.os.Build.VERSION.SDK_INT >= 30)
                                check(button.createAccessibilityNodeInfo().stateDescription == "접힘")
                            controls.put(
                                JSONObject().put("label", button.text.toString())
                                    .put("height_dp", button.height / density)
                                    .put("text_px", button.textSize)
                            )
                        }
                    }.onFailure { layoutFailure = it }
                }
                layoutFailure?.let { throw IllegalStateException("Layout validation failed", it) }
                // Show the bottom actions in the physical emulator screenshot without invoking a button.
                runOnMainSync {
                    fun scroll(view: View) {
                        if (view is ScrollView) view.scrollTo(0, view.getChildAt(0).height)
                        else if (view is ViewGroup) for (i in 0 until view.childCount) scroll(
                            view.getChildAt(
                                i
                            )
                        )
                    }
                    scroll(screen!!.window.decorView)
                }
                waitForIdleSync()
                Thread.sleep(250)
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(
                    targetContext.filesDir,
                    "case-controls-${if (car) "car" else "phone"}-$name-v169.png"
                ).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                cases.put(
                    JSONObject().put("mode", name).put("font_scale", font).put("controls", controls)
                )
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            File(
                targetContext.filesDir,
                "case-controls-v169.json"
            ).writeText(
                JSONObject().put("automotive", car).put("synthetic_case", true)
                    .put("button_taps", false).put("cases", cases).toString(2)
            )
            result.putString(
                "stream",
                "PASS: production crash detail controls, day/night and 1.5 font, minimum touch height, unclipped labels and disclosure accessibility. Synthetic renderer; no button taps or driving/OEM validation.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString(
                "stream",
                "FAIL: ${failure.javaClass.simpleName}: ${failure.message}: ${failure.cause?.message}\n"
            )
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                AccountDetailPreviewActivity.previewFontScale = 1f
                screen?.finish()
            }
        }
    }
}
