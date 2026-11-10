package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Actual Main and live authored state transitions; shared choose methods, never direct taps. */
class ActionRefreshContinuationProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        val rows = JSONArray()
        try {
            val client = BackendClient("http://127.0.0.1:9470")
            val ids = JSONObject(client.request("/api/dev/action-refresh-fixture"))
            main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val activity = main
            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

            fun method(name: String) =
                MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }

            fun nodes(): List<View> {
                val all = mutableListOf<View>()
                fun visit(view: View) {
                    if (!view.isShown) return
                    all.add(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                        view.getChildAt(
                            i
                        )
                    )
                }
                visit(activity.window.decorView)
                return all
            }

            fun labels() = nodes().filterIsInstance<TextView>().map { it.text.toString() }
            fun await(message: String, test: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 18000
                while (SystemClock.elapsedRealtime() < deadline) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(40)
                }
                var shown = emptyList<String>()
                runOnMainSync { shown = labels() }
                error("$message: $shown")
            }

            fun stopPoll() {
                val timer = field("timer").get(activity) as Handler
                for (name in listOf(
                    "feedPoll",
                    "drivingWatch"
                )) timer.removeCallbacks(field(name).get(activity) as Runnable)
            }
            await("Main/OEM unavailable") {
                !field("busy").getBoolean(activity) && !field("comparisonLoading").getBoolean(
                    activity
                ) && (field("uxMonitor").get(activity) as? VehicleUxMonitor)?.restricted == false
            }
            lateinit var driving: DrivingState
            runOnMainSync {
                stopPoll()
                field("client").set(activity, client)
                field("light").setBoolean(activity, true)
                driving = field("drivingState").get(activity) as DrivingState
                driving.useSimulation(true)
                driving.setSimulatedMoving(false)
            }
            fun event(key: String) = JSONObject(client.request("/api/events/${ids.getString(key)}"))
            fun arm(key: String, transition: String, get: Int = 1, delay: Int = 0) {
                client.request(
                    "/api/dev/action-refresh-arm",
                    JSONObject().put("key", key).put("transition", transition).put("on_get", get)
                        .put("delay_ms", delay)
                )
            }

            fun show(event: JSONObject) {
                runOnMainSync {
                    stopPoll()
                    driving.setSimulatedMoving(false)
                    field("foreground").setBoolean(activity, true)
                    field("selected").set(activity, "기록")
                    field("opened").set(activity, event)
                    method("render").invoke(activity)
                }
            }

            fun readyPanel(): DemoActionPanel {
                await("Action ready missing") { "나중에" in labels() && nodes().filterIsInstance<DemoActionPanel>().size == 1 }
                lateinit var panel: DemoActionPanel
                runOnMainSync { panel = nodes().filterIsInstance<DemoActionPanel>().single() }
                return panel
            }

            fun finalFrame(name: String, title: String, next: String) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val masks = mutableListOf<Triple<Bitmap, Int, Int>>()
                    val textFlags = mutableListOf<Boolean>()
                    var full = true
                    runOnMainSync {
                        val views = nodes()
                        val targets = mutableListOf<View>()
                        for (label in listOf(
                            title,
                            next
                        )) targets.add(
                            views.filterIsInstance<TextView>()
                                .first { it.text.toString() == label })
                        targets.add(views.filterIsInstance<DriverEventGraphic>().single())
                        for (view in targets) {
                            val rect = Rect()
                            val visible =
                                view.getGlobalVisibleRect(rect) && rect.width() == view.width && rect.height() == view.height && view.width > 0 && view.height > 0
                            full = full && visible
                            val at = IntArray(2)
                            view.getLocationOnScreen(at)
                            if (view.width > 0 && view.height > 0) {
                                masks.add(
                                    Triple(
                                        Bitmap.createBitmap(
                                            view.width,
                                            view.height,
                                            Bitmap.Config.ARGB_8888
                                        ).also { view.draw(Canvas(it)) }, at[0], at[1]
                                    )
                                )
                                textFlags.add(view is TextView)
                            }
                        }
                    }
                    if (!full) {
                        masks.forEach { it.first.recycle() }
                        Thread.sleep(60)
                        continue
                    }
                    val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                    val match = try {
                        masks.mapIndexed { index, (mask, left, top) ->
                            var total = 0
                            var matched = 0
                            for (y in 0 until mask.height) for (x in 0 until mask.width) {
                                val pixel = mask.getPixel(x, y)
                                val alpha = Color.alpha(pixel)
                                if (alpha < (if (textFlags[index]) 32 else 245)) continue
                                total++
                                if (left + x !in 0 until screenshot.width || top + y !in 0 until screenshot.height) continue
                                fun expected(c: Int) =
                                    if (textFlags[index]) (c * alpha + 255 * (255 - alpha) + 127) / 255 else c

                                val actual = screenshot.getPixel(left + x, top + y)
                                if (kotlin.math.abs(expected(Color.red(pixel)) - Color.red(actual)) < 12 && kotlin.math.abs(
                                        expected(Color.green(pixel)) - Color.green(actual)
                                    ) < 12 && kotlin.math.abs(
                                        expected(Color.blue(pixel)) - Color.blue(
                                            actual
                                        )
                                    ) < 12
                                ) matched++
                            }
                            total > 30 && matched >= total * .97
                        }.all { it }
                    } finally {
                        masks.forEach { it.first.recycle() }
                    }
                    if (match && SystemClock.elapsedRealtime() <= deadline) {
                        File(targetContext.filesDir, "action-refresh-$name-v228.png").outputStream()
                            .use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        screenshot.recycle()
                        return
                    }
                    screenshot.recycle()
                    Thread.sleep(60)
                }
                error("Final state pixels missing: $name")
            }
            for ((key, saved) in listOf(
                "normal_crash" to "보험 연결 준비 완료",
                "normal_service" to "예약 준비 완료"
            )) {
                val original = event(key)
                check(!DemoActionDraftStore.hasRecord(targetContext, original))
                show(original)
                val panel = readyPanel()
                runOnMainSync { panel.choose(false) }
                await("Deferred state absent") { "나중에 확인" in labels() }
                check(
                    DemoActionDraftStore.read(targetContext, original)
                        ?.getString("state") == "deferred"
                )
                runOnMainSync { nodes().filterIsInstance<DemoActionPanel>().single().choose(true) }
                await("Prepared state absent") { saved in labels() && "외부 전송 없음" in labels() }
                check(
                    DemoActionDraftStore.read(targetContext, original)
                        ?.getString("state") == "prepared"
                )
                runOnMainSync { method("closeEventDetail").invoke(activity) }
                show(original)
                await("Reopened prepared state absent") { saved in labels() && "외부 전송 없음" in labels() }
                runOnMainSync {
                    check(labels().none {
                        it in setOf(
                            "연결 준비",
                            "예약 준비",
                            "나중에",
                            "다시 확인"
                        )
                    })
                }
                check(
                    DemoActionDraftStore.read(targetContext, original)
                        ?.getString("state") == "prepared"
                )
                finalFrame(key, EventBriefState.from(original).title, saved)
                rows.put(
                    JSONObject().put("case", key).put("deferred_then_prepared", true)
                        .put("reopened_prepared", true).put("native_title_state_graphic", true)
                )
            }
            val before = event("vehicle_accepted")
            check(!DemoActionDraftStore.hasRecord(targetContext, before))
            runOnMainSync {
                stopPoll()
                field("selected").set(activity, "차량")
                field("opened").set(activity, null)
                field("events").set(activity, JSONArray().put(before))
                method("render").invoke(activity)
            }
            val vehiclePanel = readyPanel()
            arm("vehicle_accepted", "accepted")
            runOnMainSync { vehiclePanel.choose(true) }
            await("Vehicle latest state absent") { "재측정 대기" in labels() && "이후 측정 자료를 확인해 주세요" in labels() }
            runOnMainSync {
                check(field("selected").get(activity) == "차량" && field("opened").get(activity) == null)
                val updated = (field("events").get(activity) as JSONArray).getJSONObject(0)
                check(
                    updated !== before && updated.getString("id") == before.getString("id") && updated.getString(
                        "hash"
                    ) == before.getString("hash")
                )
                check(EventBriefState.from(updated).title == "재측정 대기")
                check(
                    nodes().filterIsInstance<DemoActionPanel>()
                        .isEmpty() && labels().none { it in setOf("다시 확인", "예약 준비", "예약 준비 완료") })
            }
            check(!DemoActionDraftStore.hasRecord(targetContext, before))
            finalFrame("vehicle-accepted", "재측정 대기", "이후 측정 자료를 확인해 주세요")
            rows.put(
                JSONObject().put("case", "vehicle_accepted").put("same_tab_updated", true)
                    .put("opened_stays_null", true).put("no_stale_draft", true)
                    .put("native_title_state_graphic", true)
            )
            File(targetContext.filesDir, "action-refresh-continuation-v228.json").writeText(
                JSONObject().put("pass", true).put("cases", rows).put(
                    "scope",
                    "Actual Main with authored live fixture transitions and shared choose methods; native title/next/graphic pixels. No direct taps, external submission or physical driving."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: normal crash/service defer-prepare-reopen and vehicle-tab same-event callback refresh. No direct taps.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            uiAutomation.takeScreenshot()?.let { image ->
                File(
                    targetContext.filesDir,
                    "action-refresh-continuation-failure-v228.png"
                ).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { main?.finish() }
        }
    }
}
