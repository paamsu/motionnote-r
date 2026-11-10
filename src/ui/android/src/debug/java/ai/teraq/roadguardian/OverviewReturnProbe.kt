package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Main render/detail-return methods with real local demo records. Does not tap controls. */
class OverviewReturnProbe : Instrumentation() {
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
            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

            fun method(name: String) =
                MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }

            val render = method("render")
            val back = method("closeEventDetail")
            var ready = false
            repeat(100) {
                if (!ready) {
                    runOnMainSync {
                        ready =
                            !(field("busy").getBoolean(a)) && (field("events").get(a) as JSONArray).length() > 1
                    }
                    if (!ready) Thread.sleep(100)
                }
            }
            check(ready) { "Local demo list did not load" }
            lateinit var records: JSONArray
            lateinit var chosen: JSONObject
            var category = ""
            runOnMainSync {
                records = field("events").get(a) as JSONArray
                val eligible = (0 until records.length()).map { records.getJSONObject(it) }
                    .filter { EvidenceScope.category(it) != null }
                val group =
                    eligible.groupBy { EvidenceScope.category(it)!! }.values.first { it.size >= 2 }
                chosen = group[1]
                category = EvidenceScope.category(chosen)!!
                val selection = field("overviewSelection").get(a) as VehicleOverviewSelection
                selection.setCategory(category)
                selection.select(chosen.getString("id"))
                render.invoke(a)
            }
            val detail =
                JSONObject(BackendClient().request("/api/events/${chosen.getString("id")}"))
            val rows = JSONArray()
            fun inspect(label: String, expected: Int) {
                runOnMainSync {
                    val selection = field("overviewSelection").get(a) as VehicleOverviewSelection
                    check(selection.category == category && selection.eventId == chosen.getString("id")) { "Lost selection: $label" }
                    val texts = mutableListOf<String>()
                    val descriptions = mutableListOf<String>()
                    fun visit(v: View) {
                        if (v is TextView) texts.add(v.text.toString())
                        v.contentDescription?.let { descriptions.add(it.toString()) }
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(a.window.decorView)
                    check("상세 기록" in texts && descriptions.any { it.startsWith("현재 받은 기록 $expected,") }) { "Position not rendered: $label $texts $descriptions" }
                    rows.put(
                        JSONObject().put("case", label).put("event_id", selection.eventId)
                            .put("category", selection.category).put("position", expected)
                    )
                }
            }
            inspect("initial second record", 2)
            runOnMainSync {
                field("opened").set(a, detail)
                render.invoke(a)
                back.invoke(a)
            }
            inspect("detail return", 2)
            runOnMainSync {
                field("light").setBoolean(a, false)
                render.invoke(a)
            }
            inspect("night rerender", 2)
            runOnMainSync {
                val reordered = JSONArray()
                reordered.put(chosen)
                for (i in 0 until records.length()) if (records.getJSONObject(i)
                        .getString("id") != chosen.getString("id")
                ) reordered.put(records.getJSONObject(i))
                field("events").set(a, reordered)
                render.invoke(a)
            }
            inspect("reordered response", 1)
            File(
                targetContext.filesDir,
                "overview-return-v204.json"
            ).writeText(
                JSONObject().put("rows", rows).put(
                    "scope",
                    "Main methods and supplied local demo records; no direct taps or actual driving"
                ).toString(2)
            )
            waitForIdleSync()
            Thread.sleep(300)
            waitForIdleSync()
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(targetContext.filesDir, "overview-return-v204.png").outputStream()
                    .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            result.putString(
                "stream",
                "PASS: 4 Main selection states; same record/category across detail return, night and reorder.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
