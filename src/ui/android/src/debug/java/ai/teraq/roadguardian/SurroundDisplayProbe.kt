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

/** Main with isolated local persisted synthetic atlas; no direct taps. */
class SurroundDisplayProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: MainActivity? = null
        try {
            val client = BackendClient("http://127.0.0.1:9470")
            val events = JSONArray(client.request("/api/events"))
            val records = (0 until events.length()).map { events.getJSONObject(it) }
            val attached = records.first {
                it.optJSONObject("surround")?.optBoolean("integrity_valid") == true
            }
            val missing = records.first { it.optJSONObject("surround") == null }
            val corrupt = records.first {
                it.optJSONObject("surround")?.optBoolean("integrity_valid") == false
            }
            screen = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val a = screen
            fun field(n: String) =
                MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }

            fun method(n: String) =
                MainActivity::class.java.getDeclaredMethod(n).apply { isAccessible = true }

            fun labels(): List<String> {
                val out = mutableListOf<String>()
                fun visit(v: View) {
                    if (v is TextView && v.isShown) out.add(v.text.toString())
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(a.window.decorView)
                return out
            }

            fun await(checker: () -> Boolean) {
                repeat(200) {
                    var done = false
                    runOnMainSync { done = checker() }
                    if (done) return
                    Thread.sleep(50)
                }
                var debug = emptyList<String>()
                runOnMainSync { debug = labels() }
                error("Main state timeout: $debug")
            }
            await { !field("busy").getBoolean(a) && (field("uxMonitor").get(a) as? VehicleUxMonitor)?.restricted == false }
            runOnMainSync {
                (field("timer").get(a) as android.os.Handler).removeCallbacks(
                    field("feedPoll").get(
                        a
                    ) as Runnable
                )
                field("client").set(a, client)
                field("selected").set(a, "기록")
            }
            val report = JSONArray()
            for (mode in listOf("day", "night", "large")) {
                VisualBriefPreviewActivity.fontScaleOverride = if (mode == "large") 1.4f else 1f
                val preview = startActivitySync(
                    Intent(
                        targetContext,
                        VisualBriefPreviewActivity::class.java
                    ).putExtra("event", attached.toString()).putExtra("night", mode == "night")
                        .putExtra("surround_test", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                var loaded = false
                repeat(200) {
                    if (!loaded) {
                        runOnMainSync {
                            fun visit(v: View) {
                                if (v is TextView && v.isShown && v.text.toString() == "센서·합성 이미지 보관 완료") loaded =
                                    true
                                if (v is ViewGroup) for (i in 0 until v.childCount) visit(
                                    v.getChildAt(
                                        i
                                    )
                                )
                            }
                            visit(preview.window.decorView)
                        }
                        if (!loaded) Thread.sleep(50)
                    }
                }
                check(loaded)
                runOnMainSync {
                    var panel: SyntheticSurroundPanel? = null
                    fun find(v: View) {
                        if (v is SyntheticSurroundPanel) panel = v
                        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))
                    }
                    find(preview.window.decorView)
                    val child = requireNotNull(panel)
                    val parent = child.parent as ViewGroup
                    val index = parent.indexOfChild(child)
                    val params = child.layoutParams
                    parent.removeView(child)
                    parent.addView(child, index, params)
                    fun checked(v: View) {
                        if (v is TextView && v.isShown) check(v.text.toString() != "센서·합성 이미지 보관 완료")
                        if (v is ViewGroup) for (i in 0 until v.childCount) checked(v.getChildAt(i))
                    }
                    checked(child)
                }
                loaded = false
                repeat(200) {
                    if (!loaded) {
                        runOnMainSync {
                            fun visit(v: View) {
                                if (v is TextView && v.isShown && v.text.toString() == "센서·합성 이미지 보관 완료") loaded =
                                    true
                                if (v is ViewGroup) for (i in 0 until v.childCount) visit(
                                    v.getChildAt(
                                        i
                                    )
                                )
                            }
                            visit(preview.window.decorView)
                        }
                        if (!loaded) Thread.sleep(50)
                    }
                }
                check(loaded)
                waitForIdleSync()
                Thread.sleep(900)
                runOnMainSync {
                    var photos = 0
                    fun visit(v: View) {
                        if (v.isShown) {
                            if (v.contentDescription?.toString()
                                    ?.endsWith("합성 시야") == true
                            ) photos++
                            if (v is TextView && v.text.toString() in listOf(
                                    "센서·합성 이미지 보관 완료",
                                    "상세 기록 펼치기"
                                )
                            ) {
                                val rect = android.graphics.Rect()
                                check(v.getGlobalVisibleRect(rect) && rect.height() == v.height) { "clipped $mode ${v.text}" }
                                v.layout?.let { l ->
                                    for (i in 0 until l.lineCount) check(
                                        l.getEllipsisCount(
                                            i
                                        ) == 0
                                    )
                                }
                            }
                        }
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(preview.window.decorView)
                    check(photos == 4)
                }
                checkNotNull(uiAutomation.takeScreenshot()).let { b ->
                    File(targetContext.filesDir, "surround-$mode-v224.png").outputStream()
                        .use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    b.recycle()
                }
                report.put(
                    JSONObject().put("mode", mode).put("photos", 4)
                        .put("status_and_details_fully_visible", true)
                )
                runOnMainSync { preview.finish() }
            }
            VisualBriefPreviewActivity.fontScaleOverride = 1f
            await { field("foreground").getBoolean(a) }
            runOnMainSync {
                field("client").set(a, client)
                field("opened").set(a, attached)
                method("render").invoke(a)
            }
            await { "센서·합성 이미지 보관 완료" in labels() }
            runOnMainSync {
                (field("drivingState").get(a) as DrivingState).useSimulation(true);
                (field("drivingState").get(a) as DrivingState).setSimulatedMoving(true)
                method("render").invoke(a)
            }
            Thread.sleep(1000)
            runOnMainSync { check(labels().none { it.contains("사진") || it.contains("상세 기록") }) }
            runOnMainSync {
                (field("drivingState").get(a) as DrivingState).setSimulatedMoving(false)
                method("render").invoke(a)
            }
            await { "센서·합성 이미지 보관 완료" in labels() }
            // Switch away while an image transfer may still be pending; old success must not replace the new event.
            runOnMainSync {
                field("opened").set(a, attached)
                method("render").invoke(a)
                field("opened").set(a, missing)
                method("render").invoke(a)
            }
            Thread.sleep(1500)
            runOnMainSync { check("센서 기록 보관 · 사진 미첨부" in labels() && "센서·합성 이미지 보관 완료" !in labels()) }
            runOnMainSync {
                field("opened").set(a, attached)
                method("render").invoke(a)
                callActivityOnPause(a)
            }
            Thread.sleep(1500)
            runOnMainSync {
                check("센서·합성 이미지 보관 완료" !in labels())
                callActivityOnResume(a)
            }
            await { "센서·합성 이미지 보관 완료" in labels() }
            val zip = File(targetContext.filesDir, "surround-display-export-v224.zip")
            zip.writeBytes(client.evidencePackage(attached.getString("id")))
            val verified = EvidencePackageVerifier.verify(zip, attached.getString("id"))
            check(verified.files >= 5)
            File(
                targetContext.filesDir,
                "surround-display-v224.json"
            ).writeText(
                JSONObject().put("states", report).put("switch_discards_previous", true)
                    .put("pause_discards_previous", true).put("archive_verified", true).put(
                    "scope",
                    "synthetic persisted atlas and Main methods; no direct taps, camera acquisition or road inference"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: day/night/large component, Main restricted/parked, event switch, pause/resume and archive.\n"
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
