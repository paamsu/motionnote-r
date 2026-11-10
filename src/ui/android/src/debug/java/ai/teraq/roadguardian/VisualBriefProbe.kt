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

class VisualBriefProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            val events = JSONArray(BackendClient().request("/api/events"))
            val all = (0 until events.length()).map { events.getJSONObject(it) }
            val report = JSONArray()
            for (kind in listOf("pothole", "crash", "diagnosis")) {
                val event = all.first {
                    it.optJSONObject("assessment")
                        ?.optString("kind") == kind && it.optBoolean("evidence_valid")
                }
                for (night in listOf(false, true)) {
                    screen = startActivitySync(
                        Intent(
                            targetContext,
                            VisualBriefPreviewActivity::class.java
                        ).putExtra("event", event.toString()).putExtra("night", night)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    waitForIdleSync()
                    Thread.sleep(300)
                    val texts = mutableListOf<String>()
                    var photos = 0
                    runOnMainSync {
                        fun visit(v: View) {
                            if (v.isShown) {
                                if (v is TextView) {
                                    texts.add(v.text.toString())
                                    check(!v.text.toString().contains("SHA-256"))
                                    v.layout?.let { l ->
                                        for (i in 0 until l.lineCount) check(
                                            l.getEllipsisCount(
                                                i
                                            ) == 0
                                        )
                                    }
                                }
                                if (v.contentDescription?.toString()
                                        ?.contains("합성 예시 사진") == true
                                ) photos++
                            }
                            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                        }
                        visit(screen!!.window.decorView)
                        check(texts.size <= 10)
                        if (kind == "pothole") check(photos == 4)
                    }
                    val name = "visual-brief-$kind-${if (night) "night" else "day"}-v214.png"
                    checkNotNull(uiAutomation.takeScreenshot()).let { b ->
                        File(targetContext.filesDir, name).outputStream()
                            .use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        b.recycle()
                    }
                    report.put(
                        JSONObject().put("kind", kind).put("night", night)
                            .put("visible_texts", JSONArray(texts))
                            .put("directional_example_photos", photos)
                    )
                    runOnMainSync { screen!!.finish() }
                    screen = null
                }
            }
            File(targetContext.filesDir, "visual-brief-v214.json").writeText(report.toString(2))
            result.putString(
                "stream",
                "PASS: three event kinds day/night; <=10 visible texts, no raw hashes, four road example photos. Component display, not driving or detection validation.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
