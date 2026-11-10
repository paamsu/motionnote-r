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

/** Existing synthetic measurement fixtures, plus explicitly altered invalid states. */
class BriefStateProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            val fixtures = JSONObject(
                targetContext.assets.open("diagnostic-cases-v135.json").bufferedReader().readText()
            )
            val cases = linkedMapOf<String, JSONObject>()
            for (key in listOf("measurement", "accepted", "recovered", "unresolved")) cases[key] =
                fixtures.getJSONObject(key)
            cases["invalid_after"] = JSONObject(
                fixtures.getJSONObject("recovered").toString()
            ).apply { getJSONObject("repair_case").put("stage", "follow_up_invalid") }
            cases["invalid_diagnostic"] =
                JSONObject(fixtures.getJSONObject("measurement").toString()).put(
                    "diagnostic_valid",
                    false
                )
            cases["missing_after"] = JSONObject(
                fixtures.getJSONObject("recovered").toString()
            ).apply { getJSONObject("repair_case").remove("after") }
            cases["contradictory"] = JSONObject(
                fixtures.getJSONObject("unresolved").toString()
            ).apply { getJSONObject("repair_case").put("stage", "observed_signal_recovered") }
            cases["dismissed"] = JSONObject(fixtures.getJSONObject("measurement").toString()).put(
                "status",
                "dismissed"
            )
            val titles = mapOf(
                "measurement" to "저전압 확인",
                "accepted" to "재측정 대기",
                "recovered" to "재측정 전압 회복",
                "unresolved" to "저전압 다시 감지",
                "invalid_after" to "재측정 확인 필요",
                "invalid_diagnostic" to "측정 확인 필요",
                "missing_after" to "측정 확인 필요",
                "contradictory" to "측정 확인 필요",
                "dismissed" to "검토에서 제외"
            )
            val report = JSONArray()
            for ((key, event) in cases) {
                val summary = EventBriefState.from(event)
                check(summary.title == titles[key])
                val low = key in setOf("measurement", "unresolved")
                check(summary.voltageLow == low)
                check((summary.action != null) == (low || key == "recovered"))
                for (mode in listOf("day", "night", "large")) {
                    VisualBriefPreviewActivity.fontScaleOverride = if (mode == "large") 1.4f else 1f
                    screen = startActivitySync(
                        Intent(
                            targetContext,
                            VisualBriefPreviewActivity::class.java
                        ).putExtra("event", event.toString()).putExtra("night", mode == "night")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    waitForIdleSync()
                    Thread.sleep(900)
                    val texts = mutableListOf<String>()
                    runOnMainSync {
                        fun visit(v: View) {
                            if (v is TextView && v.isShown) {
                                texts.add(v.text.toString())
                                v.layout?.let { l ->
                                    for (i in 0 until l.lineCount) {
                                        check(l.getEllipsisCount(i) == 0)
                                        check(l.getLineMax(i) <= v.width - v.compoundPaddingLeft - v.compoundPaddingRight + 2)
                                    }
                                }
                            }
                            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                        }
                        visit(screen!!.window.decorView)
                        check(texts.contains(summary.title))
                        check(texts.size <= 7)
                        if (!low) check(texts.none { it == "서비스센터 안내" })
                    }
                    if (key in setOf("recovered", "unresolved", "invalid_after")) checkNotNull(
                        uiAutomation.takeScreenshot()
                    ).let { b ->
                        File(
                            targetContext.filesDir,
                            "brief-state-$key-$mode-verified-v215.png"
                        ).outputStream()
                            .use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        b.recycle()
                    }
                    report.put(
                        JSONObject().put("case", key).put("mode", mode).put("title", summary.title)
                            .put("low_graphic", summary.voltageLow)
                            .put("visible_texts", JSONArray(texts))
                    )
                    runOnMainSync { screen!!.finish() }
                    screen = null
                }
            }
            File(
                targetContext.filesDir,
                "brief-state-verified-v215.json"
            ).writeText(report.toString(2))
            result.putString(
                "stream",
                "PASS: 9 states x day/night/large; title, action and low-voltage graphic agree; no clipped text or stale booking actions.\n"
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
