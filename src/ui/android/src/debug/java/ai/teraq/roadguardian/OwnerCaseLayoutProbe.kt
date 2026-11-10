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
import org.json.JSONObject
import java.io.File

/** Shared production renderer with authored test text. Reads layout only; never clicks or types. */
class OwnerCaseLayoutProbe : Instrumentation() {
    private data class FrameTarget(val bounds: Rect, val color: Int, val minimum: Int = 10)

    private var font = 1f
    private var night = false
    private var variant = "normal"
    private var evidenceTag = "v179"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        font = arguments?.getString("font")?.toFloatOrNull() ?: 1f
        night = arguments?.getString("night") == "true"
        variant = arguments?.getString("variant") ?: "normal"
        evidenceTag = arguments?.getString("evidence_tag") ?: "v179"
        require(evidenceTag in setOf("v179", "v193", "v194", "v195"))
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            AccountDetailPreviewActivity.previewFontScale = font
            screen = startActivitySync(
                Intent(targetContext, AccountDetailPreviewActivity::class.java).putExtra(
                    "night",
                    night
                )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            val event = JSONObject(
                targetContext.assets.open("owner-layout-v159.json").bufferedReader()
                    .use { it.readText() })
            if (variant == "invalid") event.put("evidence_valid", false)
            if (variant == "missing") event.getJSONObject("data").getJSONObject("context")
                .put("own_vehicle", JSONObject())
            if (variant == "long") event.getJSONObject("data").getJSONObject("context")
                .getJSONObject("own_vehicle")
                .put("plate", "시험 차량번호 · 입력 내용 확인 전")
                .put("model", "긴 차량명이 전달되는 경우에도 차종을 줄이지 않고 보여주는 표시 시험")
            runOnMainSync {
                val ui = PhoneAccountUi(screen!!, !night)
                val body = ui.install()
                if (targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) VehicleEventDetail.render(
                    screen!!,
                    body,
                    event,
                    !night
                )
                else AccountEventDetail.render(ui, body, event)
            }
            waitForIdleSync()
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            var texts = emptyList<TextView>()
            var outlines = 0
            var flows = 0
            var graphic: CaseFlowGraphic? = null
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val found = mutableListOf<TextView>()
                runOnMainSync {
                    outlines = 0
                    flows = 0
                    fun read(view: View) {
                        if (view is TextView && view.isShown) found.add(view)
                        if (view is VehicleIdentityOutline && view.isShown) outlines++
                        if (view is CaseFlowGraphic && view.isShown) {
                            flows++
                            graphic = view
                        }
                        if (view is ViewGroup) for (index in 0 until view.childCount) read(
                            view.getChildAt(
                                index
                            )
                        )
                    }
                    read(screen!!.window.decorView)
                }
                texts = found
                if (texts.any {
                        it.text.toString()
                            .contains(if (variant == "invalid") "기록 표시를 보류" else "보관 상태")
                    } || texts.any { it.text.toString().contains("기록 표시를 보류") }) break
                Thread.sleep(50)
            }
            var uiFailure: Exception? = null
            val targets = mutableListOf<FrameTarget>()
            runOnMainSync {
                try {
                    val labels = texts.joinToString("\n") { it.text.toString() }
                    if (variant == "invalid") check(
                        labels.contains("기록 표시를 보류") && !labels.contains(
                            "TEST-OWNER-01"
                        ) && outlines == 0 && flows == 0
                    )
                    else {
                        check(labels.contains("보관 상태") && labels.contains("자동 사고·정비 판정 미실시"))
                        check(!labels.contains("화면 배치를 확인하는") && !labels.contains("읽은 작성 내용"))
                        check(labels.contains("작성 내용과 출처 펼치기"))
                        check(!labels.contains("작성 시각 ·") && !labels.contains("서버 보관 ·"))
                        val hidden = mutableListOf<String>()
                        fun readAll(v: View) {
                            if (v is TextView) hidden.add(v.text.toString())
                            if (v is ViewGroup) for (i in 0 until v.childCount) readAll(
                                v.getChildAt(
                                    i
                                )
                            )
                        }
                        readAll(screen!!.window.decorView)
                        check(hidden.any { it.startsWith("작성 시각 ·") } && hidden.any {
                            it.startsWith(
                                "서버 보관 ·"
                            )
                        })
                        if (variant == "normal") check(
                            labels.contains("TEST-OWNER-01") && labels.contains(
                                "작성 차종 시험"
                            )
                        )
                        if (variant == "missing") check(labels.contains("미기재"))
                        if (variant == "long") check(labels.contains("줄이지 않고 보여주는 표시 시험"))
                        check(outlines == if (font >= 1.3f) 0 else 1)
                        check(flows == if (font >= 1.3f) 0 else 1)
                    }
                    for (text in texts) {
                        check(text.width - text.paddingLeft - text.paddingRight > 0)
                        val layout = checkNotNull(text.layout)
                        for (line in 0 until layout.lineCount) {
                            check(layout.getEllipsisCount(line) == 0)
                            val available = text.width - text.paddingLeft - text.paddingRight
                            // Android Layout.getLineWidth includes trailing spaces; getLineMax excludes them.
                            // Keep the same 2px tolerance for visible glyphs and account for any whitespace overflow.
                            val trailing = text.text.subSequence(
                                layout.getLineVisibleEnd(line),
                                layout.getLineEnd(line)
                            ).toString()
                            check(trailing.all { it.isWhitespace() })
                            check(layout.getLineMax(line) <= available + 2) {
                                "visible overflow text=${text.text} line=$line max=${
                                    layout.getLineMax(
                                        line
                                    )
                                } available=$available"
                            }
                            check(
                                layout.getLineWidth(line) <= layout.getLineMax(line) + text.paint.measureText(
                                    trailing
                                ) + 2
                            )
                        }
                    }
                    val expected =
                        if (variant == "invalid") listOf("기록 표시를 보류") else if (variant == "normal" && font < 1.3f)
                            listOf(
                                "정비 자료 보관 시험",
                                "TEST-OWNER-01",
                                "보관 상태"
                            ) else if (variant == "long") listOf(
                            "정비 자료 보관 시험",
                            "시험 차량번호 · 입력 내용 확인 전",
                            "줄이지 않고 보여주는 표시 시험"
                        ) else listOf("정비 자료 보관 시험")
                    for (value in expected) {
                        val target = texts.first { it.text.toString().contains(value) }
                        val bounds = Rect()
                        check(target.getGlobalVisibleRect(bounds))
                        check(bounds.width() >= target.width && bounds.height() >= target.height)
                        val screenPosition = IntArray(2)
                        val windowPosition = IntArray(2)
                        target.getLocationOnScreen(screenPosition)
                        target.getLocationInWindow(windowPosition)
                        bounds.offset(
                            screenPosition[0] - windowPosition[0],
                            screenPosition[1] - windowPosition[1]
                        )
                        targets.add(FrameTarget(bounds, target.currentTextColor))
                    }
                    graphic?.let { view ->
                        // The OEM map can cover the trailing edge during task transitions. Verify the
                        // last step's full text area against a software render, rather than only the title.
                        val reference =
                            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        view.draw(android.graphics.Canvas(reference))
                        val region =
                            Rect(view.width * 2 / 3, view.height / 2, view.width, view.height)
                        val ink =
                            android.graphics.Color.parseColor(if (night) "#F2F6FA" else "#132432")
                        var expected = 0
                        for (y in region.top until region.bottom step 2) for (x in region.left until region.right step 2) if (reference.getPixel(
                                x,
                                y
                            ) == ink
                        ) expected++
                        reference.recycle()
                        check(expected >= 10)
                        val position = IntArray(2)
                        view.getLocationOnScreen(position)
                        region.offset(position[0], position[1])
                        targets.add(
                            FrameTarget(
                                region,
                                ink,
                                (expected * .9).toInt().coerceAtLeast(10)
                            )
                        )
                    }
                } catch (failure: Exception) {
                    uiFailure = failure
                }
            }
            uiFailure?.let { throw it }
            var ready: Bitmap? = null
            val frameDeadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < frameDeadline) {
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                val visible = targets.all { (bounds, color, minimum) ->
                    var matching = 0
                    for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(bitmap.height) step 2)
                        for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(
                            bitmap.width
                        ) step 2) if (bitmap.getPixel(x, y) == color) matching++
                    matching >= minimum
                }
                if (visible) {
                    ready = bitmap
                    break
                }
                bitmap.recycle()
                Thread.sleep(50)
            }
            val bitmap = checkNotNull(ready)
            val prefix = "owner-layout-$variant-${if (night) "night" else "day"}-$font-$evidenceTag"
            File(targetContext.filesDir, "$prefix.png").outputStream()
                .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            result.putString(
                "stream",
                "PASS: authored fixture $variant font=$font night=$night; production detail renderer, wrapping/no ellipsis, neutral outline, native large type, collapsed prose, actual text-color pixels. No provider login, SDK or UI control activation.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(targetContext.filesDir, "owner-layout-failed-v193.png").outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
            AccountDetailPreviewActivity.previewFontScale = 1f
        }
    }
}
