package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import java.io.File

/** Synthetic rendering only. Does not click controls or authenticate. */
class CaseWorkspaceProbe : Instrumentation() {
    private var maintenance = false
    private var font = 1f
    private var night = false
    private var category: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        maintenance = arguments?.getString("maintenance") == "true"
        font = arguments?.getString("font")?.toFloatOrNull() ?: 1f
        night = arguments?.getString("night") == "true"
        category = arguments?.getString("category")
            ?.takeIf { it in setOf("all", "incident", "road", "maintenance") }
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            AccountListPreviewActivity.previewFontScale = font
            val activity = startActivitySync(
                Intent(
                    targetContext,
                    AccountListPreviewActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            screen = activity
            val events = JSONArray(
                targetContext.assets.open("case-workspace-v144.json").bufferedReader()
                    .use { it.readText() })
            val labels = mutableListOf<String>()
            val automotive =
                targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)
            var panel: View? = null
            fun collect(view: View) {
                if (view is TextView) labels.add(view.text.toString()) else view.contentDescription?.let {
                    labels.add(
                        it.toString()
                    )
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
            runOnMainSync {
                val ui = PhoneAccountUi(activity, !night)
                val body = ui.install()
                panel = body
                body.addView(ui.text("사건 근거 · 합성 시험", 26f, true))
                if (maintenance) MaintenanceRecordList.render(
                    ui,
                    body,
                    events
                ) { error("Probe must not activate controls") }
                else CaseWorkspace.render(
                    ui,
                    body,
                    events,
                    category
                ) { error("Probe must not activate controls") }
                collect(body)
            }
            waitForIdleSync()
            val text = labels.joinToString("\n")
            val expectedLabels = if (automotive && maintenance) listOf(
                "현재 받은 정비 기록 1건",
                "정비 전후 비교",
                "재측정 비교"
            ) else if (automotive && category == "incident") listOf(
                "전체 3",
                "충돌 1",
                "현장 영상",
                "미취득",
                "접촉·책임",
                "미확정"
            ) else if (automotive) listOf(
                "전체 3",
                "도로 1",
                "정비 1",
                "충돌 1",
                "재측정 비교",
                "현재 목록의 다른 기록"
            ) else listOf("전체 3", "도로 1", "정비 1", "충돌 1", "재측정 비교", "현장 영상", "미취득")
            if (automotive && maintenance) check(
                !text.contains("도로 근거 검토") && !text.contains("충돌 근거 정리") && !text.contains(
                    "전체 3"
                )
            )
            for (expected in expectedLabels) check(text.contains(expected)) { "Missing $expected" }
            for (excluded in listOf(
                "응답 시간 초과",
                "주차 중 충격 감지",
                "전방 시야 저하",
                "시험 목록 번호 일치"
            )) check(!text.contains(excluded)) { "Legacy service displayed" }
            if (font >= 1.3f && category != "incident") check(text.contains("재측정 · 비교 있음")) { "Large font text alternative missing" }
            // Allow the activity transition and newly installed surface to reach the compositor.
            Thread.sleep(600)
            waitForIdleSync()
            runOnMainSync {
                fun measure(view: View) {
                    if (view.isShown && view is TextView && view.text.isNotBlank()) check(view.width - view.paddingLeft - view.paddingRight > 0 && view.height > 0) { "Text has no visible bounds" }
                    if (view.isShown && view.isClickable) check(view.width >= 48 * view.resources.displayMetrics.density && view.height >= 48 * view.resources.displayMetrics.density) { "Touch target too small" }
                    if (view is ViewGroup) for (index in 0 until view.childCount) measure(
                        view.getChildAt(
                            index
                        )
                    )
                }
                measure(checkNotNull(panel))
            }
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.cacheDir, "case-workspace-v144.png").outputStream()
                .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            result.putString(
                "stream",
                "PASS: candidate categories, current-page counts, legacy exclusion, evidence states, positive text bounds and 48dp touch targets, font=$font night=$night category=$category. Render only; no taps/login/network tested.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            AccountListPreviewActivity.previewFontScale = 1f
            runOnMainSync { screen?.finish() }
        }
    }
}
