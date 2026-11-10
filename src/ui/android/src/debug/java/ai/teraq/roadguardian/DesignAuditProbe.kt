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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Cross-screen bounds evidence. Authored previews only; never activates UI controls. */
class DesignAuditProbe : Instrumentation() {
    private var only: String? = null
    private var tag = "v195"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        only = arguments?.getString("scene")
        tag = arguments?.getString("evidence_tag") ?: "v195"
        require(tag in setOf("v195", "v200", "v201", "v202", "v203"))
        require(only == null || only == "workspace")
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            data class Scene(
                val id: String,
                val type: Class<out Activity>,
                val key: String,
                val value: String
            )

            val scenes = listOf(
                Scene("road", AccountDetailPreviewActivity::class.java, "dossier_fixture", "road"),
                Scene(
                    "crash",
                    AccountDetailPreviewActivity::class.java,
                    "dossier_fixture",
                    "crash"
                ),
                Scene("traffic", TrafficPreviewActivity::class.java, "case", "reckless"),
                Scene("followup", ShowcasePreviewActivity::class.java, "case", "followup"),
                Scene("workspace", ShowcasePreviewActivity::class.java, "case", "workspace")
            )

            data class Anchor(val bounds: Rect, val color: Int, val minimum: Int)

            val rows = JSONArray()
            val failures = JSONArray()
            for (scene in scenes.filter { only == null || it.id == only }) for (mode in if (tag in setOf(
                    "v200",
                    "v201",
                    "v202",
                    "v203"
                )
            ) listOf("day", "night", "large") else listOf("day", "night")) {
                val night = mode == "night"
                val font = if (mode == "large") 1.5f else 1f
                AccountDetailPreviewActivity.previewFontScale = font
                TrafficPreviewActivity.previewFontScale = font
                ShowcasePreviewActivity.previewFontScale = font
                activity = startActivitySync(
                    Intent(targetContext, scene.type).putExtra(scene.key, scene.value)
                        .putExtra("night", night)
                        .putExtra("decoded", scene.id == "traffic")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                waitForIdleSync()
                Thread.sleep(500)
                waitForIdleSync()
                val texts = JSONArray()
                val nodes = JSONArray()
                val anchors = mutableListOf<Anchor>()
                var focused = false
                var appearance = 0
                runOnMainSync {
                    focused = activity!!.hasWindowFocus()
                    appearance =
                        if (android.os.Build.VERSION.SDK_INT >= 30) activity!!.window.insetsController?.systemBarsAppearance
                            ?: 0 else -1
                    fun visit(v: View) {
                        val at = IntArray(2)
                        v.getLocationOnScreen(at)
                        if (v.parent == null || v is android.widget.ScrollView || v is android.widget.LinearLayout) nodes.put(
                            JSONObject().put("type", v.javaClass.simpleName).put("x", at[0])
                                .put("y", at[1]).put("w", v.width).put("h", v.height)
                                .put("scroll_y", v.scrollY).put("padding_top", v.paddingTop)
                        )
                        if (v is TextView && v.isShown && v.text.isNotEmpty()) {
                            val visible = Rect()
                            val intersects = v.getGlobalVisibleRect(visible)
                            val row = JSONObject().put("text", v.text.toString()).put("x", at[0])
                                .put("y", at[1]).put("w", v.width).put("h", v.height)
                                .put("visible", intersects)
                                .put("visible_rect", visible.toShortString())
                                .put("visible_height", visible.height())
                            var overflow = false
                            v.layout?.let { layout ->
                                for (line in 0 until layout.lineCount) if (layout.getLineMax(
                                        line
                                    ) > v.width - v.compoundPaddingLeft - v.compoundPaddingRight + 2 || layout.getEllipsisCount(
                                        line
                                    ) > 0
                                ) overflow = true
                            }
                            row.put("line_overflow", overflow).put("line_count", v.lineCount)
                            texts.put(row)
                            if (intersects && visible.height() == v.height && v.width > 0 && v.height > 0 && anchors.size < 4) {
                                val reference =
                                    Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                                v.draw(android.graphics.Canvas(reference))
                                var ink = 0
                                for (y in 0 until reference.height step 2) for (x in 0 until reference.width step 2) if (reference.getPixel(
                                        x,
                                        y
                                    ) == v.currentTextColor
                                ) ink++
                                reference.recycle()
                                if (ink > 10) anchors.add(
                                    Anchor(
                                        Rect(
                                            at[0],
                                            at[1],
                                            at[0] + v.width,
                                            at[1] + v.height
                                        ), v.currentTextColor, (ink * .85).toInt()
                                    )
                                )
                            }
                        }
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(activity!!.window.decorView)
                }
                if (scene.id == "workspace") for (i in 0 until texts.length()) {
                    val t = texts.getJSONObject(i)
                    if (t.getString("text") == "아이오닉 6" && t.getInt("line_count") != 1) failures.put(
                        JSONObject().put("model_split", t)
                    )
                }
                val first = texts.optJSONObject(0)
                if (first != null) {
                    val rect = first.getString("visible_rect")
                    if (!first.getBoolean("visible") || first.getInt("visible_height") != first.getInt(
                            "h"
                        )
                    ) failures.put(
                        JSONObject().put("scene", scene.id).put("first_heading_hidden", true)
                            .put("rect", rect)
                    )
                }
                check(anchors.size >= 2) { "Not enough rendered text anchors for ${scene.id}" }
                var ready: Bitmap? = null
                var attempts = 0
                val deadline = android.os.SystemClock.elapsedRealtime() + 10000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    val candidate = checkNotNull(uiAutomation.takeScreenshot())
                    attempts++
                    val matched = anchors.all { a ->
                        var ink = 0
                        for (y in a.bounds.top.coerceAtLeast(0) until a.bounds.bottom.coerceAtMost(
                            candidate.height
                        ) step 2)
                            for (x in a.bounds.left.coerceAtLeast(0) until a.bounds.right.coerceAtMost(
                                candidate.width
                            ) step 2) if (candidate.getPixel(x, y) == a.color) ink++
                        ink >= a.minimum
                    }
                    if (matched) {
                        ready = candidate
                        break
                    }
                    candidate.recycle()
                    Thread.sleep(80)
                }
                val image = checkNotNull(ready) { "Native text pixels not ready for ${scene.id}" }
                val name = "design-${scene.id}-$mode-reviewed-$tag.png"
                runOnMainSync {
                    appearance =
                        if (android.os.Build.VERSION.SDK_INT >= 30) activity!!.window.insetsController?.systemBarsAppearance
                            ?: 0 else -1
                }
                File(targetContext.filesDir, name).outputStream()
                    .use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                for (i in 0 until texts.length()) {
                    val t = texts.getJSONObject(i)
                    val horizontal =
                        t.getInt("x") < 0 || t.getInt("x") + t.getInt("w") > image.width
                    if (t.getBoolean("line_overflow") || (t.getBoolean("visible") && horizontal)) failures.put(
                        JSONObject().put("scene", scene.id).put("night", night).put("text", t)
                    )
                }
                rows.put(
                    JSONObject().put("scene", scene.id).put("mode", mode).put("night", night)
                        .put("native_text_anchors", anchors.size).put("capture_attempts", attempts)
                        .put("focused", focused).put("bar_appearance", appearance)
                        .put("screenshot", name).put("screen_w", image.width)
                        .put("screen_h", image.height).put("nodes", nodes).put("texts", texts)
                )
                image.recycle()
                runOnMainSync { activity!!.finish() }
                activity = null
            }
            File(
                targetContext.filesDir,
                "design-audit-reviewed-$tag.json"
            ).writeText(
                JSONObject().put("rows", rows).put("failures", failures).put(
                    "scope",
                    "production components with authored fixtures; no taps, inference or real CCNC proof"
                ).toString(2)
            )
            result.putString(
                "stream",
                "${if (failures.length() == 0) "PASS" else "FAIL"}: ${rows.length()} design states; ${failures.length()} horizontal overflow findings; original screenshots and bounds saved.\n"
            )
            finish(
                if (failures.length() == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                result
            )
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
            AccountDetailPreviewActivity.previewFontScale = 1f
            TrafficPreviewActivity.previewFontScale = 1f
            ShowcasePreviewActivity.previewFontScale = 1f
        }
    }
}
