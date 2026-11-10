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

/** Real Store fixtures plus explicit corrupted presentations; no user taps or vehicle inference. */
class DiagnosticEvidenceProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            val fixture = JSONObject(
                targetContext.assets.open("driver-followup-v221.json").bufferedReader()
                    .use { it.readText() })
            val cases = linkedMapOf(
                "low" to fixture.getJSONObject("low"),
                "recovered" to fixture.getJSONObject("recovered")
            )

            fun corrupt(name: String, change: (JSONObject) -> Unit) {
                cases[name] =
                    JSONObject(fixture.getJSONObject("recovered").toString()).apply { change(this) }
            }
            corrupt("evidence") { it.put("evidence_valid", false) }
            corrupt("diagnostic") { it.put("diagnostic_valid", false) }
            corrupt("post_integrity") {
                it.getJSONObject("repair").getJSONObject("post_repair")
                    .put("integrity_valid", false)
            }
            corrupt("after_invalid") {
                it.getJSONObject("repair_case").put("stage", "follow_up_invalid")
            }
            corrupt("contradictory") {
                it.getJSONObject("repair_case").getJSONObject("after").put("delta_v", -9)
            }
            corrupt("insufficient") {
                it.getJSONObject("repair_case").getJSONObject("after").put("matched_samples", 19)
            }
            corrupt("post_hash") {
                it.getJSONObject("repair").getJSONObject("post_repair").put("hash", "broken")
            }
            corrupt("dismissed") { it.put("status", "dismissed") }
            corrupt("post_state") {
                it.getJSONObject("repair").getJSONObject("post_repair")
                    .put("status", "follow_up_required")
            }
            cases["initial_verdict"] = JSONObject(
                fixture.getJSONObject("low").toString()
            ).apply {
                getJSONObject("assessment").getJSONObject("diagnostic")
                    .put("verdict", "no_anomaly_in_observed_signals")
            }
            val rows = JSONArray()
            for ((name, event) in cases) for (mode in listOf("day", "night", "large")) {
                val valid = name in setOf("low", "recovered")
                check(DiagnosticEvidenceReview.canOpen(event) == valid) { "validation $name" }
                VisualBriefPreviewActivity.fontScaleOverride = if (mode == "large") 1.5f else 1f
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        VisualBriefPreviewActivity::class.java
                    ).putExtra("event", event.toString()).putExtra("diagnostic_review", true)
                        .putExtra("night", mode == "night").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                waitForIdleSync()
                Thread.sleep(220)
                val texts = mutableListOf<String>()
                var charts = 0
                runOnMainSync {
                    fun visit(v: View) {
                        if (!v.isShown) return
                        if (v is TextView) {
                            texts.add(v.text.toString())
                            v.layout?.let { l ->
                                for (i in 0 until l.lineCount) {
                                    check(l.getEllipsisCount(i) == 0)
                                    check(l.getLineMax(i) <= v.width - v.compoundPaddingLeft - v.compoundPaddingRight + 2)
                                }
                            }
                        }
                        if (v is VoltageComparisonGraphic) charts++
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(screen!!.window.decorView)
                    check(texts.count { it == "최초 측정과 재측정" } == 1)
                    if (valid) {
                        check(texts.contains(if (name == "low") "추가 점검 필요" else "관측 전압 회복 · 추가 확인 필요"))
                        check(texts.contains("재측정 원문과 식별 정보 펼치기"))
                        check(texts.none { it.contains("SHA-256") })
                    } else {
                        check(texts.contains("측정 자료 확인 실패 · 판단 보류"))
                        check(charts == 0)
                        check(texts.none {
                            it.contains(" V") || it.contains("관측 전압 회복") || it.contains(
                                "SHA-256"
                            )
                        })
                    }
                }
                val file = "diagnostic-evidence-$name-$mode-v222.png"
                if (name in setOf("low", "recovered", "after_invalid")) {
                    Thread.sleep(550)
                    checkNotNull(uiAutomation.takeScreenshot()).let { b ->
                        File(targetContext.filesDir, file).outputStream()
                            .use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        b.recycle()
                    }
                }
                rows.put(
                    JSONObject().put("case", name).put("mode", mode).put("valid", valid)
                        .put("charts", charts).put("texts", JSONArray(texts))
                )
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            File(
                targetContext.filesDir,
                "diagnostic-evidence-v222.json"
            ).writeText(rows.toString(2))
            result.putString(
                "stream",
                "PASS: 36 diagnostic evidence presentations; invalid values and recovery styling hidden, raw data collapsed, no text overflow. Component only.\n"
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
