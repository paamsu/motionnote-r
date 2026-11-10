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
import org.json.JSONObject
import java.io.File

/** Stored synthetic state rendering only; never dials or dispatches. */
class EmergencyCaseProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val rows = JSONArray()
        var activity: Activity? = null
        val output = File(targetContext.filesDir, "emergency-states-v456").apply { mkdirs() }
        fun views(root: View): List<View> {
            val all = mutableListOf(root)
            if (root is ViewGroup) for (i in 0 until root.childCount) all.addAll(
                views(
                    root.getChildAt(
                        i
                    )
                )
            )
            return all
        }

        fun capture(name: String) {
            waitForIdleSync()
            Thread.sleep(450)
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            File(output, "$name.png").outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            val cases = JSONObject(
                targetContext.assets.open("emergency-cases-v137.json").bufferedReader()
                    .use { it.readText() })
            for (mode in listOf("day", "night", "large")) for (name in listOf(
                "rescue",
                "police",
                "retry",
                "waiting",
                "wrong_recipient",
                "invalid"
            )) {
                EmergencyCasePreviewActivity.font = if (mode == "large") 1.5f else 1f
                activity = startActivitySync(
                    Intent(targetContext, EmergencyCasePreviewActivity::class.java)
                        .putExtra("case", name).putExtra("night", mode == "night")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                waitForIdleSync()
                runOnMainSync {
                    val labels = views(activity!!.window.decorView).filterIsInstance<TextView>()
                    val visible = labels.filter { it.isShown }.map { it.text.toString() }
                    val expected = when (name) {
                        "rescue", "police" -> "접수 완료"
                        "retry" -> "전달 실패 · 재시도 필요"
                        "invalid" -> "자료 확인 실패 · 접수 판단 보류"
                        else -> "접수 확인 대기"
                    }
                    check(expected in visible) { "Missing status $name/$mode: $visible" }
                    val heading = labels.first { it.text.toString() == expected }
                    val bounds = android.graphics.Rect()
                    check(heading.getGlobalVisibleRect(bounds) && bounds.height() == heading.height) { "Clipped status $name/$mode" }
                    check(heading.layout != null && heading.layout.height <= heading.height - heading.compoundPaddingTop - heading.compoundPaddingBottom)
                    check(visible.none { it.startsWith("접수 번호 ") }) { "Receipt must start collapsed" }
                    if (name !in listOf(
                            "rescue",
                            "police"
                        )
                    ) check(visible.none { it == "접수 완료" || it.startsWith("접수 정보") })
                    if (name == "invalid") check(visible.none { it == "내 차량" || it == "기록 위치" })
                    else check(
                        cases.getJSONObject(name).getJSONObject("data").getJSONObject("context")
                            .getJSONObject("own_vehicle").getString("plate") in visible
                    )
                    rows.put(
                        JSONObject().put("case", name).put("mode", mode).put("status", expected)
                            .put("initial_receipt_hidden", true)
                    )
                }
                capture("$name-$mode")
                if (name in listOf("rescue", "police") && mode == "day") {
                    runOnMainSync {
                        val labels = views(activity!!.window.decorView).filterIsInstance<TextView>()
                        labels.single { it.text.toString() == "접수 정보 펼치기" }.performClick()
                        check(labels.any {
                            it.isShown && it.text.toString().startsWith("접수 번호 MOCK-")
                        })
                        labels.single { it.text.toString() == "접수 정보 접기" }.performClick()
                        check(labels.none { it.isShown && it.text.toString().startsWith("접수 번호 ") })
                    }
                }
                runOnMainSync { activity!!.finish() }
                activity = null
            }
            File(output, "result.json").writeText(
                JSONObject().put("passed", true).put("rows", rows).put("physical_vehicle", false)
                    .put("real_dispatch", false).toString(2)
            )
            result.putString(
                "stream",
                "PASS: 18 emergency state/theme renders; two receipt expand/collapse controls; no dispatch.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            File(output, "result.json").writeText(
                JSONObject().put("passed", false).put("rows", rows).put("error", e.toString())
                    .toString(2)
            )
            result.putString("stream", "FAIL: $e\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            EmergencyCasePreviewActivity.font = 1f
        }
    }
}
