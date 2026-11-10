package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Synthetic insurance state/component matrix, not insurer or human driving validation. */
class CrashBriefProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            val fixtures = JSONObject(
                targetContext.assets.open("crash-cases-v133.json").bufferedReader().readText()
            )
            val cases = linkedMapOf<String, JSONObject>()
            for (key in listOf("ready", "expired", "retry", "acknowledged")) cases[key] =
                fixtures.getJSONObject(key)
            for ((key, status) in listOf(
                "waiting" to "awaiting_receipt",
                "payload" to "payload_invalid",
                "unknown" to "not_configured"
            )) cases[key] = JSONObject(
                fixtures.getJSONObject("ready").toString()
            ).apply { getJSONObject("insurance_case").put("status", status) }
            cases["missing_receipt"] =
                JSONObject(fixtures.getJSONObject("acknowledged").toString()).apply {
                    getJSONObject("insurance_case").put("receipt", JSONObject.NULL)
                }
            for ((key, value) in listOf(
                "numeric_receipt" to 123,
                "boolean_receipt" to true
            )) cases[key] = JSONObject(fixtures.getJSONObject("acknowledged").toString()).apply {
                getJSONObject("insurance_case").put("receipt", value)
            }
            cases["foreign_source"] =
                JSONObject(fixtures.getJSONObject("acknowledged").toString()).apply {
                    getJSONObject("insurance_case").put("source", "unknown")
                }
            cases["dismissed"] =
                JSONObject(fixtures.getJSONObject("ready").toString()).put("status", "dismissed")
            cases["invalid"] =
                JSONObject(fixtures.getJSONObject("ready").toString()).put("evidence_valid", false)
            val titles = mapOf(
                "ready" to "사고 감지",
                "expired" to "차량·보험 확인 필요",
                "retry" to "보험 연결 실패",
                "acknowledged" to "보험 접수 완료",
                "waiting" to "보험 접수 확인 중",
                "payload" to "접수 자료 확인 실패",
                "unknown" to "보험 연결 상태 미확인",
                "missing_receipt" to "보험 연결 상태 미확인",
                "numeric_receipt" to "보험 연결 상태 미확인",
                "boolean_receipt" to "보험 연결 상태 미확인",
                "foreign_source" to "보험 연결 상태 미확인",
                "dismissed" to "검토에서 제외",
                "invalid" to "기록 확인 필요"
            )
            val report = JSONArray()
            for ((key, event) in cases) for (mode in listOf("day", "night", "large")) {
                val summary = EventBriefState.from(event)
                check(summary.title == titles[key])
                check((summary.action != null) == (key == "ready"))
                VisualBriefPreviewActivity.fontScaleOverride = if (mode == "large") 1.4f else 1f
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        VisualBriefPreviewActivity::class.java
                    ).putExtra("event", event.toString()).putExtra("night", mode == "night")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                waitForIdleSync()
                Thread.sleep(700)
                val labels = mutableListOf<String>()
                runOnMainSync {
                    fun visit(v: View) {
                        if (v is TextView && v.isShown) {
                            labels.add(v.text.toString())
                            v.layout?.let { l ->
                                for (i in 0 until l.lineCount) check(
                                    l.getEllipsisCount(
                                        i
                                    ) == 0
                                )
                            }
                        }
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(screen!!.window.decorView)
                    check(labels.contains(summary.title))
                    check(labels.size <= 7)
                    check(labels.none { it.contains("연결할까요") })
                    if (key != "ready") check(labels.none { it.contains("연결 안내 펼치기") })
                }
                if (key in listOf(
                        "ready",
                        "acknowledged",
                        "invalid"
                    )
                ) checkNotNull(uiAutomation.takeScreenshot()).let { b ->
                    File(targetContext.filesDir, "crash-$key-$mode-v219.png").outputStream()
                        .use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    b.recycle()
                }
                report.put(
                    JSONObject().put("case", key).put("mode", mode).put("labels", JSONArray(labels))
                )
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            File(targetContext.filesDir, "crash-brief-v219.json").writeText(report.toString(2))
            result.putString(
                "stream",
                "PASS: 13 insurance states x day/night/large; current summary and no stale connect prompt.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            VisualBriefPreviewActivity.fontScaleOverride = 1f
            runOnMainSync { screen?.finish() }
        }
    }
}
