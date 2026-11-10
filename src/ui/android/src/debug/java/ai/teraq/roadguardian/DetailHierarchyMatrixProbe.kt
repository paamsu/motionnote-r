package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Actual Main, injected authored records and parked state. Not HTTP or direct touch evidence. */
class DetailHierarchyMatrixProbe : Instrumentation() {
    private var fullBackend = false
    private var waitMap = false
    private var systemMode: String? = null
    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        fullBackend = args?.getString("fullBackend") == "true"
        waitMap = args?.getString("waitMap") == "true"
        systemMode = args?.getString("systemMode")
        start()
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        val result = Bundle()
        var activity: MainActivity? = null
        var config: Configuration? = null
        try {
            val crash = JSONObject(
                targetContext.assets.open("crash-cases-v133.json").bufferedReader()
                    .use { it.readText() })
            val diagnosis = JSONObject(
                targetContext.assets.open("diagnostic-cases-v135.json").bufferedReader()
                    .use { it.readText() })
            val cases = linkedMapOf(
                "crash" to crash.getJSONObject("ready"),
                "low" to diagnosis.getJSONObject("measurement"),
                "repeat-low" to diagnosis.getJSONObject("unresolved"),
                "recovered" to diagnosis.getJSONObject("recovered")
            )
            if (fullBackend) {
                val fresh =
                    JSONObject(File(targetContext.filesDir, "detail-backend-cases.json").readText())
                cases.clear()
                for (key in fresh.keys().asSequence().toList().sorted()) cases[key] =
                    fresh.getJSONObject(key)
            }
            val main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            activity = main
            config = Configuration(main.resources.configuration)
            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

            fun texts(): List<TextView> {
                val nodes = mutableListOf<TextView>()
                fun walk(v: View) {
                    if (!v.isShown) return
                    if (v is TextView) nodes.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(main.window.decorView)
                return nodes
            }

            fun waitFor(test: () -> Boolean) {
                repeat(160) {
                    var done = false
                    runOnMainSync { done = test() }
                    if (done) return
                    Thread.sleep(50)
                }
                error("UI timeout")
            }

            fun fullyVisible(view: View): Boolean {
                val rect = Rect()
                return view.getGlobalVisibleRect(rect) && rect.width() == view.width && rect.height() == view.height
            }

            fun focus(label: String) {
                runOnMainSync {
                    val v = texts().single { it.text.toString() == label }
                    v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), true)
                }
                waitFor {
                    texts().single { it.text.toString() == label }
                        .let { fullyVisible(it) && !it.isLayoutRequested }
                }
            }

            fun click(label: String) {
                focus(label)
                runOnMainSync {
                    check(texts().single { it.text.toString() == label }.performClick())
                }
                waitForIdleSync()
            }

            fun capture(name: String) {
                Thread.sleep(250)
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "detail-matrix-$name.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            waitFor { !field("busy").getBoolean(main) && (field("uxMonitor").get(main) as? VehicleUxMonitor)?.restricted == false }
            val report = JSONArray()

            for ((name, event) in cases) for (mode in systemMode?.let { listOf(it) }
                ?: listOf("day", "night", "large")) {
                runOnMainSync {
                    val timer = field("timer").get(main) as Handler
                    for (n in listOf(
                        "feedPoll",
                        "drivingWatch"
                    )) timer.removeCallbacks(field(n).get(main) as Runnable)
                    if (systemMode == null) {
                        val adjusted = Configuration(config)
                        adjusted.fontScale = if (mode == "large") 1.5f else 1f
                        main.resources.updateConfiguration(adjusted, main.resources.displayMetrics)
                    } else check(kotlin.math.abs(main.resources.configuration.fontScale - (if (mode == "large") 1.5f else 1f)) < 0.01f) { "System font scale was not applied" }
                    (field("drivingState").get(main) as DrivingState).apply {
                        useSimulation(true)
                        setSimulatedMoving(false)
                    }
                    field("selected").set(main, "기록")
                    field("opened").set(main, event)
                    field("light").setBoolean(main, mode != "night")
                    MainActivity::class.java.getDeclaredMethod("render")
                        .apply { isAccessible = true }.invoke(main)
                }
                val title = EventBriefState.from(event).title
                if (name.startsWith("crash-")) runOnMainSync {
                    check(texts().any { it.text.toString() == "보험사 다시 연결" } == name.endsWith("-retry"))
                }
                if (event.has("expected_brief_title")) {
                    check(title == event.getString("expected_brief_title")) { "Independent expected title mismatch: $name / $title" }
                    if (event.optJSONObject("assessment")?.optString("kind") == "pothole") {
                        check(DriverBriefPanel.verifiedBrief(event)?.second?.title == title)
                        val invalid = JSONObject(event.toString())
                        invalid.getJSONObject("video_road_analysis")
                            .put("raw_output_sha256", "0".repeat(64))
                        check(EventBriefState.from(invalid).title == "영상 기록 확인 필요")
                        check(DriverBriefPanel.verifiedBrief(invalid) == null)
                    }
                }
                if (fullBackend) runOnMainSync { check(!field("requestFailed").getBoolean(main)) { "Initial backend refresh failed" } }
                waitFor { texts().any { it.text.toString() == "상세 기록 펼치기" && it.height > 0 } }
                if (name in setOf("crash", "low")) {
                    val ready = if (name == "crash") "보험사를 연결할까요?" else "근처 서비스센터를 예약할까요?"
                    waitFor { texts().any { it.text.toString() == ready && it.height > 0 } }
                }
                if (fullBackend) waitFor { texts().any { it.text.toString() == "서울특별시 서초구 사평대로 인근" } }
                if (waitMap) waitFor {
                    fun hasReadyMap(v: View): Boolean {
                        if (v is EventLocationPanel) {
                            return (0 until v.childCount).any { hasReadyMap(v.getChildAt(it)) }
                        }
                        if (v is android.widget.ImageView && v.contentDescription?.toString()
                                ?.startsWith("사건 좌표") == true
                        ) return true
                        if (v is NativeMapPanel) return false
                        return v is ViewGroup && (0 until v.childCount).any {
                            hasReadyMap(
                                v.getChildAt(
                                    it
                                )
                            )
                        }
                    }

                    fun loading(v: View): Boolean =
                        v is NativeMapPanel || (v is ViewGroup && (0 until v.childCount).any {
                            loading(v.getChildAt(it))
                        })
                    hasReadyMap(main.window.decorView) && !loading(main.window.decorView)
                }
                focus(title)
                capture("$name-$mode-brief")
                val typography = JSONObject()
                runOnMainSync {
                    val heading = texts().single { it.text.toString() == title }
                    val px = heading.textSize
                    typography.put("font_scale", main.resources.configuration.fontScale)
                        .put("title_px", px)
                        .put("scaled_density", main.resources.displayMetrics.scaledDensity)
                    if (fullBackend) {
                        val address = texts().single { it.text.toString() == "서울특별시 서초구 사평대로 인근" }
                        val expected = android.util.TypedValue.applyDimension(
                            android.util.TypedValue.COMPLEX_UNIT_SP,
                            22f,
                            main.resources.displayMetrics
                        )
                        typography.put("address_px", address.textSize)
                            .put("address_expected_px", expected)
                        check(kotlin.math.abs(address.textSize - expected) < 0.1f) { "Address ignores OS font scaling" }
                        check((0 until address.layout.lineCount).all {
                            address.layout.getEllipsisCount(
                                it
                            ) == 0
                        }) { "Address truncated" }
                    }
                }
                val help = event.optJSONObject("assessment")?.optString("kind") == "manual_help"
                if (help && name.endsWith("-completed")) runOnMainSync {
                    check(texts().any { it.text.toString() == "접수 완료" })
                    check(texts().none { it.text.toString() == "같은 요청 다시 전달" })
                }
                if (help && name.endsWith("-retry")) runOnMainSync {
                    check(texts().any { it.text.toString() == "전달 실패 · 다시 시도해 주세요" })
                    check(texts().any { it.text.toString() == "같은 요청 다시 전달" })
                    check(texts().none { it.text.toString() == "접수 완료" })
                }
                val expand = if (help) "좌표 확인 펼치기" else "원본 데이터 펼치기"
                val collapse = if (help) "좌표 확인 접기" else "원본 데이터 접기"
                val privateText =
                    if (help) event.getJSONObject("data").getJSONObject("location").let {
                        String.format(
                            java.util.Locale.KOREAN,
                            "%.5f, %.5f",
                            it.getDouble("lat"),
                            it.getDouble("lon")
                        )
                    } else "합성 시연 · 실제 차량 측정 자료 아님"
                click("상세 기록 펼치기")
                waitFor { texts().any { it.text.toString() == expand && it.height > 0 } }
                runOnMainSync { check(texts().none { it.text.toString() == privateText }) }
                if (help && fullBackend) runOnMainSync {
                    val address = checkNotNull(EventLocationPanel.storedAddress(event))
                    check(texts().count { it.text.toString() == address } == 2) { "Map and emergency detail addresses differ" }
                    check(texts().none { it.text.toString() == "가상 시험 구간" })
                    for (label in listOf("", "null", "x".repeat(161))) {
                        val copy = JSONObject(event.toString())
                        copy.getJSONObject("data").getJSONObject("location")
                            .put("address_label", label)
                        check(EventLocationPanel.storedAddress(copy) == null)
                    }
                    val unknown = JSONObject(event.toString())
                    unknown.getJSONObject("data").getJSONObject("location")
                        .put("address_source", "unknown")
                    check(EventLocationPanel.storedAddress(unknown) == null)
                    val invalid = JSONObject(event.toString())
                    invalid.put("evidence_valid", false)
                    check(EventLocationPanel.storedAddress(invalid) == null)
                }
                focus(expand)
                capture("$name-$mode-actions")
                click(expand)
                waitFor { texts().any { it.text.toString() == privateText } }
                click(collapse)
                waitFor { texts().none { it.text.toString() == privateText } }
                focus(expand)
                runOnMainSync {
                    for (label in if (help) listOf(expand, "주행 화면으로") else listOf(
                        expand,
                        "기록 묶음 보관"
                    )) {
                        val v = texts().single { it.text.toString() == label }
                        check((0 until v.layout.lineCount).all { v.layout.getEllipsisCount(it) == 0 })
                    }
                }
                capture("$name-$mode-refolded")
                if (name == "crash-retry" && mode == "large") {
                    val originalDispatch =
                        event.getJSONArray("dispatches").getJSONObject(0).getString("id")
                    click("보험사 다시 연결")
                    waitFor { !field("busy").getBoolean(main) && texts().any { it.text.toString() == "보험 접수 완료" } }
                    runOnMainSync {
                        val current = field("opened").get(main) as JSONObject
                        check(current.getString("id") == event.getString("id"))
                        val deliveries = current.getJSONArray("dispatches")
                        check(deliveries.length() == 1)
                        val delivery = deliveries.getJSONObject(0)
                        check(delivery.getString("id") == originalDispatch)
                        check(
                            delivery.getString("status") == "acknowledged" && delivery.getString("receipt")
                                .startsWith("MOCK-")
                        )
                        check(texts().none { it.text.toString() == "보험사 다시 연결" })
                    }
                    capture("$name-$mode-retried")
                }
                if (help && name.endsWith("-retry") && mode == "large") {
                    val originalDispatch =
                        event.getJSONArray("dispatches").getJSONObject(0).getString("id")
                    click("같은 요청 다시 전달")
                    waitFor { !field("busy").getBoolean(main) && texts().any { it.text.toString() == "접수 완료" } }
                    runOnMainSync {
                        val current = field("opened").get(main) as JSONObject
                        check(current.getString("id") == event.getString("id"))
                        val delivery = current.getJSONArray("dispatches").getJSONObject(0)
                        check(delivery.getString("id") == originalDispatch)
                        check(
                            delivery.getString("status") == "acknowledged" && delivery.getString("receipt")
                                .startsWith("MOCK-")
                        )
                        check(texts().none { it.text.toString() == "같은 요청 다시 전달" })
                    }
                    capture("$name-$mode-retried")
                }
                report.put(
                    JSONObject().put("case", name).put("mode", mode).put("title", title)
                        .put("detail_source_type", if (help) "coordinates" else "raw_event")
                        .put("raw_initially_hidden", true).put("raw_expand_collapse", true)
                        .put("typography", typography)
                )
                File(
                    targetContext.filesDir,
                    "detail-matrix-result.json"
                ).writeText(JSONObject().put("cases", report).toString(2))
            }
            File(
                targetContext.filesDir,
                "detail-matrix-result.json"
            ).writeText(
                JSONObject().put("cases", report).put(
                    "scope",
                    "Actual Main with authored fixtures and simulated park; programmatic clicks; action readiness uses isolated HTTP fixture; not full scenario or external action validation"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: ${report.length()} Main detail combinations; source initially hidden and expandable/collapsible\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                activity?.let { a ->
                    config?.let { a.resources.updateConfiguration(it, a.resources.displayMetrics) }
                    a.finish()
                }
            }
        }
    }
}
