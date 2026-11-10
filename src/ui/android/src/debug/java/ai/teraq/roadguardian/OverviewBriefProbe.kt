package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Authored component inputs. No HTTP, physical driving, direct taps or Main viewport claim. */
class OverviewBriefProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun fixture(name: String): JSONObject {
        val kind = when (name) {
            "repeat-low" -> "diagnosis"
            "road-followup", "general-observation" -> "observation"
            "invalid", "dismissed" -> "pothole"
            "owner" -> "owner_record"
            else -> name
        }
        val category = when (kind) {
            "diagnosis" -> "maintenance"
            "crash" -> "incident"
            else -> "road"
        }
        val e = JSONObject().put("id", UUID.randomUUID().toString()).put("hash", "a".repeat(64))
            .put("source", if (name == "owner") "owner_reported" else "synthetic")
            .put("evidence_valid", name != "invalid")
            .put("status", if (name == "dismissed") "dismissed" else "evidence_ready")
            .put(
                "assessment",
                JSONObject().put("kind", kind).put("category", category)
                    .put("diagnostic", JSONObject().put("verdict", "inspection_required"))
            )
            .put("diagnostic_valid", true).put(
                "repair_case",
                JSONObject().put("source", "synthetic").put("stage", "measurement_review")
            )
            .put("insurance_case", JSONObject().put("source", "synthetic").put("status", "ready"))
            .put(
                "case_overview",
                JSONObject().put("schema", "road-guardian.case-overview.v1")
                    .put("category", category).put(
                    "state", when (name) {
                        "owner" -> "owner_recorded"
                        "dismissed" -> "dismissed"
                        else -> "awaiting_followup"
                    }
                )
            )
        if (name == "repeat-low") e.getJSONObject("repair_case").put("stage", "follow_up_required")
            .put(
                "after",
                JSONObject().put("delta_v", -1.8).put("voltage_median_v", 10.7)
                    .put("baseline_v", 12.5).put("matched_samples", 60)
            )
        if (name == "road-followup") e.put("observation_purpose", "road_followup")
        return e
    }

    override fun onStart() {
        val result = Bundle()
        var activity: OverviewBriefPreviewActivity? = null
        try {
            val rows = JSONArray()
            for (name in listOf(
                "pothole",
                "crash",
                "diagnosis",
                "repeat-low",
                "road-followup",
                "invalid",
                "dismissed",
                "owner",
                "general-observation"
            )) for (mode in listOf("day", "night", "large")) {
                val e = fixture(name)
                OverviewBriefPreviewActivity.font = if (mode == "large") 1.5f else 1f
                activity = startActivitySync(
                    Intent(
                        targetContext,
                        OverviewBriefPreviewActivity::class.java
                    ).putExtra("event_json", e.toString()).putExtra("night", mode == "night")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ) as OverviewBriefPreviewActivity
                val a = activity!!
                waitForIdleSync()
                Thread.sleep(250)
                waitForIdleSync()
                val nodes = mutableListOf<View>()
                lateinit var scroll: ScrollView
                val expectedTitle = when (name) {
                    "pothole" -> "포트홀 감지"
                    "crash" -> "사고 감지"
                    "diagnosis" -> "저전압 확인"
                    "repeat-low" -> "저전압 다시 감지"
                    "invalid" -> "자료 확인 실패"
                    "dismissed" -> "검토에서 제외한 기록"
                    "owner" -> "본인 작성 · 사실 확인 전"
                    else -> "이 목록에는 해당 기록이 없습니다."
                }
                val expectedNext = when (name) {
                    "pothole" -> "센서 기록 확보"
                    "crash" -> "보험사 연결 안내를 확인하세요"
                    "diagnosis" -> "서비스센터 안내를 확인하세요"
                    "repeat-low" -> "서비스센터 방문을 추천합니다"
                    "invalid" -> "자료를 다시 확인해야 합니다"
                    "dismissed" -> "제외한 기록입니다"
                    "owner" -> "작성한 내용과 첨부를 확인하세요"
                    else -> null
                }
                val brief = name in setOf("pothole", "crash", "diagnosis", "repeat-low")
                var needsScroll = false
                var titleVisible = false
                var actionVisible = false
                runOnMainSync {
                    fun visit(v: View) {
                        nodes.add(v)
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(a.window.decorView)
                    scroll = nodes.filterIsInstance<ScrollView>().single()
                    scroll.scrollTo(0, 0)
                    val labels = nodes.filterIsInstance<TextView>()
                    val texts = labels.map { it.text.toString() }
                    check(texts.count { it == expectedTitle } == 1) { "Title $name/$mode: $texts" }
                    if (expectedNext != null) check(texts.count { it == expectedNext } == 1) { "Next $name/$mode: $texts" }
                    check(nodes.filterIsInstance<DriverEventGraphic>().size == if (brief) 1 else 0)
                    if (brief) check(texts.none { it.startsWith("기록 차량") || it.contains("차종 공통 이미지") || it == "후속 관측 자료 대기" || it == "재측정 자료 대기" })
                    for (label in labels) {
                        val layout = label.layout ?: continue
                        check(layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom) { "Text height clipped $name/$mode: ${label.text}" }
                        for (i in 0 until layout.lineCount) check(
                            layout.getEllipsisCount(i) == 0 && layout.getLineMax(
                                i
                            ) <= label.width - label.compoundPaddingLeft - label.compoundPaddingRight + 2
                        ) { "Text width clipped $name/$mode: ${label.text}" }
                    }
                    fun full(v: View): Boolean {
                        val r = Rect()
                        return v.getGlobalVisibleRect(r) && r.width() == v.width && r.height() == v.height
                    }
                    titleVisible = full(labels.single { it.text.toString() == expectedTitle })
                    check(titleVisible)
                    val action = labels.firstOrNull {
                        it.contentDescription?.toString()?.startsWith("현재 받은 기록 ") == true
                    }
                    if (brief) check(
                        action?.text.toString() == when (name) {
                            "crash" -> "보험사 연결 안내"
                            "diagnosis", "repeat-low" -> "서비스센터 안내"
                            else -> "상세 기록"
                        }
                    )
                    actionVisible = action?.let { full(it) } ?: true
                    needsScroll = scroll.canScrollVertically(1)
                    check(actionVisible || needsScroll) { "Unreachable bottom action $name/$mode" }
                }
                fun capture(suffix: String): String {
                    val file = "overview-brief-$name-$mode-$suffix-v229.png"
                    val frame = checkNotNull(uiAutomation.takeScreenshot())
                    File(targetContext.filesDir, file).outputStream()
                        .use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    frame.recycle()
                    return file
                }

                val top = capture("top")
                var bottom: String? = null
                if (needsScroll) {
                    runOnMainSync { scroll.scrollTo(0, scroll.getChildAt(0).height) }
                    waitForIdleSync()
                    Thread.sleep(150)
                    runOnMainSync {
                        val action = nodes.filterIsInstance<TextView>().firstOrNull {
                            it.contentDescription?.toString()?.startsWith("현재 받은 기록 ") == true
                        }
                        if (action != null) {
                            val rect = Rect()
                            check(action.getGlobalVisibleRect(rect) && rect.width() == action.width && rect.height() == action.height) { "Bottom remains clipped $name/$mode" }
                        }
                    }
                    bottom = capture("bottom")
                }
                rows.put(
                    JSONObject().put("case", name).put("mode", mode).put("title", expectedTitle)
                        .put("next", expectedNext ?: JSONObject.NULL)
                        .put("graphic_count", if (brief) 1 else 0)
                        .put("title_fully_visible", titleVisible)
                        .put("action_initially_visible", actionVisible)
                        .put("requires_scroll", needsScroll).put("bottom_reachable", true)
                        .put("top_png", top).put("bottom_png", bottom ?: JSONObject.NULL).put(
                        "observation_excluded",
                        name in setOf("road-followup", "general-observation")
                    )
                )
                File(targetContext.filesDir, "overview-brief-v229.json").writeText(
                    JSONObject().put(
                        "scope",
                        "Authored JSON; standalone parked component native screenshots and view bounds. Scroll is method-driven; no direct taps, HTTP, Main viewport or driving verification."
                    ).put("rows", rows).toString(2)
                )
                runOnMainSync { a.finish() }
                activity = null
            }
            result.putString(
                "stream",
                "PASS: 27 authored overview layouts; titles, single next, graphics/fallback, metadata absence and text bounds. Scroll requirement recorded, not hidden. Native PNGs saved; no Main/HTTP/direct-tap claim.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            uiAutomation.takeScreenshot()?.let { b ->
                File(targetContext.filesDir, "overview-brief-failed-v229.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
            OverviewBriefPreviewActivity.font = 1f
        }
    }
}
