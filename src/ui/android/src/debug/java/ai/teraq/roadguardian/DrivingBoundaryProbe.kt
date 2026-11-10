package ai.teraq.roadguardian

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Injected app state only: does not claim an OEM gear or physical drive test. */
class DrivingBoundaryProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: MainActivity? = null
        try {
            activity = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val a = activity
            fun field(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            val render =
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
            lateinit var state: DrivingState
            runOnMainSync {
                val timer = field("timer").get(a) as Handler
                for (n in listOf(
                    "feedPoll",
                    "drivingWatch"
                )) timer.removeCallbacks(field(n).get(a) as Runnable)
                (field("adapter").get(a) as PleosAdapter).close()
                state = field("drivingState").get(a) as DrivingState
                state.useSimulation(true)
                state.setSimulatedMoving(true)
            }
            fun nodes(): List<View> {
                val list = mutableListOf<View>()
                fun visit(v: View) {
                    if (!v.isShown) return
                    list.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(a.window.decorView)
                return list
            }

            val input =
                JSONArray(File(targetContext.filesDir, "driving-input-v567.json").readText())
            val rows = JSONArray()
            for (i in 0 until input.length()) {
                val row = input.getJSONObject(i)
                val event = row.getJSONObject("event")
                for (mode in listOf("drive", "unavailable")) {
                    runOnMainSync {
                        field("opened").set(a, event)
                        field("selected").set(a, "기록")
                        state.useSimulation(mode == "drive")
                        state.setSimulatedMoving(true)
                        if (mode == "unavailable") state.observe(null, -1L)
                        render.invoke(a)
                    }
                    waitForIdleSync()
                    Thread.sleep(150)
                    lateinit var texts: List<String>
                    runOnMainSync {
                        val shown = nodes()
                        texts = shown.filterIsInstance<TextView>().map { it.text.toString() }
                        check(shown.none {
                            it.isClickable || it.javaClass.simpleName in setOf(
                                "SyntheticSurroundPanel",
                                "EventEvidencePhotoPanel",
                                "EventLocationPanel"
                            )
                        }) { "Restricted screen exposed controls or evidence" }
                        check(texts.none { it.contains(event.getString("id")) || it.contains("서울특별시") })
                        check((if (mode == "drive") "주행 시연" else "차량 상태 확인 중") in texts)
                    }
                    val name = row.getString("scene") + "-" + mode + ".png"
                    checkNotNull(uiAutomation.takeScreenshot()).let { b ->
                        File(targetContext.filesDir, "v567-" + name).outputStream()
                            .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        b.recycle()
                    }
                    rows.put(
                        JSONObject().put("scene", row.getString("scene")).put("mode", mode)
                            .put("texts", JSONArray(texts)).put("controls", 0)
                            .put("photo_map_views", 0).put("screenshot", name)
                    )
                }
            }
            File(targetContext.filesDir, "driving-result-v567.json").writeText(rows.toString(2))
            result.putString("result", "PASS: ${rows.length()} restricted event states")
        } catch (t: Throwable) {
            result.putString("result", "FAIL: ${t.stackTraceToString()}")
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
            finish(0, result)
        }
    }
}
