package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Main UI/state and shared action methods. No synthetic tap, external call or booking. */
class VisualDemoActionProbe : Instrumentation() {
    private var captureHoldMs = 0L
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        captureHoldMs =
            arguments?.getString("capture_hold_ms")?.toLongOrNull()?.coerceIn(0L, 3000L) ?: 0L
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: MainActivity? = null
        try {
            val client = BackendClient("http://127.0.0.1:9470")
            val events = JSONArray(client.request("/api/events"))
            val rows = (0 until events.length()).map { events.getJSONObject(it) }
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

            fun textNodes(): List<TextView> {
                val out = mutableListOf<TextView>()
                fun visit(v: View) {
                    if (!v.isShown) return
                    if (v is TextView) out.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(a.window.decorView)
                return out
            }

            data class PixelAnchor(val bitmap: Bitmap, val left: Int, val top: Int, val ink: Int)

            fun frame(screen: Activity, name: String, vararg expected: String) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val anchors = mutableListOf<PixelAnchor>()
                    runOnMainSync {
                        for (text in expected) textNodes().firstOrNull { it.text.toString() == text && it.layout?.text?.toString() == text && !it.isLayoutRequested && it.width > 0 && it.height > 0 }
                            ?.let { view ->
                                val at = IntArray(2)
                                view.getLocationOnScreen(at)
                                val bitmap = Bitmap.createBitmap(
                                    view.width,
                                    view.height,
                                    Bitmap.Config.ARGB_8888
                                ).also { view.draw(Canvas(it)) }
                                anchors.add(
                                    PixelAnchor(
                                        bitmap,
                                        at[0],
                                        at[1],
                                        view.currentTextColor
                                    )
                                )
                            }
                    }
                    if (anchors.size != expected.size) {
                        anchors.forEach { it.bitmap.recycle() }
                        Thread.sleep(60)
                        continue
                    }
                    val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                    val presented = try {
                        anchors.all { (mask, left, top, ink) ->
                            var pixels = 0
                            var matched = 0
                            for (y in 0 until mask.height) for (x in 0 until mask.width) {
                                val value = mask.getPixel(x, y)
                                if (Color.alpha(value) < 245 || kotlin.math.abs(
                                        Color.red(value) - Color.red(
                                            ink
                                        )
                                    ) > 24 || kotlin.math.abs(Color.green(value) - Color.green(ink)) > 24 || kotlin.math.abs(
                                        Color.blue(value) - Color.blue(ink)
                                    ) > 24
                                ) continue
                                pixels++
                                if (left + x !in 0 until screenshot.width || top + y !in 0 until screenshot.height) continue
                                val actual = screenshot.getPixel(left + x, top + y)
                                if (kotlin.math.abs(Color.red(value) - Color.red(actual)) < 12 && kotlin.math.abs(
                                        Color.green(value) - Color.green(actual)
                                    ) < 12 && kotlin.math.abs(Color.blue(value) - Color.blue(actual)) < 12
                                ) matched++
                            }
                            pixels > 30 && matched >= pixels * .97
                        }
                    } finally {
                        anchors.forEach { it.bitmap.recycle() }
                    }
                    if (presented && SystemClock.elapsedRealtime() <= deadline) {
                        File(targetContext.filesDir, name).outputStream()
                            .use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        screenshot.recycle()
                        if (captureHoldMs > 0) Thread.sleep(captureHoldMs)
                        return
                    }
                    screenshot.recycle()
                    Thread.sleep(60)
                }
                error("Native expected pixels absent: $name / ${expected.toList()}")
            }

            fun panel(): DemoActionPanel {
                var found: DemoActionPanel? = null
                fun visit(v: View) {
                    if (v is DemoActionPanel) found = v
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(a.window.decorView)
                return requireNotNull(found)
            }

            fun await(test: () -> Boolean) {
                repeat(250) {
                    var done = false
                    runOnMainSync { done = test() }
                    if (done) return
                    Thread.sleep(40)
                }
                var l = emptyList<String>()
                runOnMainSync { l = labels() }
                error("timeout: $l")
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
            for (kind in listOf("crash", "diagnosis")) {
                val event = rows.first { it.getJSONObject("assessment").getString("kind") == kind }
                runOnMainSync {
                    field("opened").set(a, event)
                    method("render").invoke(a)
                }
                await { labels().contains("나중에") }
                runOnMainSync {
                    check(labels().contains(if (kind == "crash") "보험사 연결" else "서비스센터 예약"))
                    check(labels().contains(if (kind == "crash") "보험사를 연결할까요?" else "근처 서비스센터를 예약할까요?"))
                }
                frame(
                    a,
                    "action-$kind-ready-v324.png",
                    if (kind == "crash") "보험사를 연결할까요?" else "근처 서비스센터를 예약할까요?",
                    "나중에",
                    if (kind == "crash") "보험사 연결" else "서비스센터 예약"
                )
                runOnMainSync { panel().choose(false) }
                await { labels().contains("나중에 확인") }
                check(
                    DemoActionDraftStore.read(targetContext, event)
                        ?.getString("state") == "deferred"
                )
                frame(
                    a,
                    "action-$kind-deferred-v324.png",
                    "나중에 확인",
                    if (kind == "crash") "보험사 연결" else "서비스센터 예약"
                )
                runOnMainSync { panel().choose(true) }
                val saved = if (kind == "crash") "보험 접수 완료" else "예약 완료"
                await { labels().contains(saved) }
                check(
                    DemoActionDraftStore.read(targetContext, event)
                        ?.getString("state") == "prepared"
                )
                frame(a, "action-$kind-prepared-v324.png", saved)
                runOnMainSync {
                    method("closeEventDetail").invoke(a)
                    field("opened").set(a, event)
                    method("render").invoke(a)
                }
                await { labels().contains(saved) }
                frame(a, "action-$kind-v224.png", saved)
                runOnMainSync {
                    report.put(JSONObject().put("kind", kind).put("labels", JSONArray(labels())))
                    val p = panel();
                    (field("drivingState").get(a) as DrivingState).useSimulation(true);
                    (field("drivingState").get(a) as DrivingState).setSimulatedMoving(true)
                    method("render").invoke(a)
                    p.choose(false)
                }
                check(
                    DemoActionDraftStore.read(targetContext, event)
                        ?.getString("state") == "prepared"
                )
                runOnMainSync {
                    check(labels().none { it == "보험사 연결" || it == "서비스센터 예약" || it == "나중에" });
                    (field("drivingState").get(a) as DrivingState).setSimulatedMoving(false)
                    method("render").invoke(a)
                }
            }
            val damaged = rows.last { it.getJSONObject("assessment").getString("kind") == "crash" }
            DemoActionDraftStore.prepare(targetContext, damaged)
            val original = File(
                targetContext.noBackupFilesDir,
                "demo-action-${damaged.getString("id")}-${DemoActionDraftStore.fingerprint(damaged)}.json"
            )
            original.copyTo(
                File(
                    targetContext.filesDir,
                    "action-before-corrupt-${damaged.getString("id")}.json"
                ), true
            )
            original.writeText("corrupt test draft")
            runOnMainSync {
                field("opened").set(a, damaged)
                method("render").invoke(a)
            }
            await { labels().contains("준비 상태 확인 필요") }
            frame(a, "action-corrupt-v324.png", "준비 상태 확인 필요")
            runOnMainSync {
                check(labels().none { it == "나중에" || it == "보험사 연결" })
                callActivityOnPause(a)
                check(labels().none { it.contains("준비") })
                callActivityOnResume(a)
            }
            File(targetContext.filesDir, "action-main-v224.json").writeText(report.toString(2))
            result.putString(
                "stream",
                "PASS: crash/service defer then prepare, persisted reopen, restricted stale action rejected, corrupt draft and pause cleared. Main methods, no direct taps.\n"
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
