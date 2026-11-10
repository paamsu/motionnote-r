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
class ActionRefreshMainProbe : Instrumentation() {
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
            for (key in listOf(
                "crash_before",
                "diagnosis_accepted_before",
                "diagnosis_recovered_before",
                "crash_second",
                "diagnosis_accepted_second",
                "diagnosis_recovered_second"
            )) {
                val before = event(key)
                check(!DemoActionDraftStore.hasRecord(targetContext, before))
                val transition =
                    if (key.startsWith("crash")) "dispatch" else if (key.contains("accepted")) "accepted" else "recovered"
                if (key == "crash_before") {
                    arm(key, transition)
                    show(before)
                } else {
                    show(before)
                    val panel = readyPanel()
                    arm(key, transition, if (key.endsWith("second")) 2 else 1)
                    runOnMainSync { panel.choose(true) }
                }
                val title = when (transition) {
                    "dispatch" -> "모의 접수 확인"
                    "accepted" -> "재측정 대기"
                    else -> "재측정 전압 회복"
                }
                val next = when (transition) {
                    "dispatch" -> "실제 보험사 접수는 아닙니다"
                    "accepted" -> "이후 측정 자료를 확인해 주세요"
                    else -> "점검 기록을 확인해 주세요"
                }
                await("Updated full state absent: $key") { title in labels() && next in labels() && "다시 확인" !in labels() }
                runOnMainSync {
                    val current = field("opened").get(activity) as JSONObject
                    check(
                        current.getString("id") == before.getString("id") && current.getString("hash") == before.getString(
                            "hash"
                        )
                    )
                    check(
                        EventBriefState.from(current).title == title && nodes().filterIsInstance<DemoActionPanel>()
                            .isEmpty()
                    )
                    check(labels().none {
                        it in setOf(
                            "연결 준비",
                            "예약 준비",
                            "나중에",
                            "보험 연결 준비 완료",
                            "예약 준비 완료"
                        )
                    })
                    if (transition == "recovered") check("재측정 기록 보기" in labels())
                }
                check(
                    !DemoActionDraftStore.hasRecord(
                        targetContext,
                        before
                    )
                ) { "Stale choice wrote a draft: $key" }
                finalFrame(key, title, next)
                rows.put(
                    JSONObject().put("case", key).put("title", title).put("no_stale_draft", true)
                        .put("full_state_pixels", true)
                )
            }
            for (key in listOf("other_id", "other_hash")) {
                val before = event(key)
                show(before)
                val panel = readyPanel()
                arm(key, key)
                runOnMainSync { panel.choose(true) }
                await("Identity mismatch not refused") { "준비 상태 확인 필요" in labels() }
                runOnMainSync {
                    check(field("opened").get(activity) === before)
                    check(EventBriefState.from(before).title in labels())
                }
                check(!DemoActionDraftStore.hasRecord(targetContext, before))
                rows.put(JSONObject().put("case", key).put("rejected", true))
            }
            for (key in listOf("departure", "driving")) {
                val before = event(key)
                show(before)
                val panel = readyPanel()
                arm(key, "dispatch", 1, 1500)
                runOnMainSync { panel.choose(true) }
                Thread.sleep(200)
                runOnMainSync {
                    if (key == "departure") {
                        field("opened").set(activity, null)
                        field("selected").set(activity, "설정")
                    } else driving.setSimulatedMoving(true)
                    method("render").invoke(activity)
                }
                Thread.sleep(2200)
                waitForIdleSync()
                runOnMainSync {
                    if (key == "departure") check(
                        field("opened").get(activity) == null && field("selected").get(
                            activity
                        ) == "설정"
                    )
                    else check(field("opened").get(activity) === before)
                    check(
                        "모의 접수 확인" !in labels() && nodes().filterIsInstance<DemoActionPanel>()
                            .isEmpty()
                    )
                }
                check(!DemoActionDraftStore.hasRecord(targetContext, before))
                rows.put(JSONObject().put("case", key).put("late_callback_discarded", true))
            }
            File(
                targetContext.filesDir,
                "action-refresh-main-v228.json"
            ).writeText(
                JSONObject().put("pass", true).put("cases", rows).put(
                    "scope",
                    "Actual Main with authored live fixture transitions and shared choose methods; native title/next/graphic pixels. No direct taps, external submission or physical driving."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: six state refresh cases, stale choice draft refusal, identity/hash refusal and departure/driving callback discard. No direct taps.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            uiAutomation.takeScreenshot()?.let { image ->
                File(targetContext.filesDir, "action-refresh-failure-v228.png").outputStream()
                    .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { main?.finish() }
        }
    }
}
