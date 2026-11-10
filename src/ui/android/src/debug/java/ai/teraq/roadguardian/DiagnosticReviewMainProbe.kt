package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual Main methods with authored loopback HTTP responses; no taps or physical driving. */
class DiagnosticReviewMainProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: MainActivity? = null
        try {
            screen = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val activity = screen
            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

            fun method(name: String) =
                MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }

            val render = method("render")
            val open = MainActivity::class.java.getDeclaredMethod(
                "onOpenDiagnosticReview",
                JSONObject::class.java
            ).apply { isAccessible = true }

            fun await(message: String, test: () -> Boolean) {
                val end = SystemClock.elapsedRealtime() + 18000
                while (SystemClock.elapsedRealtime() < end) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(40)
                }
                error(message)
            }

            fun nodes(): List<View> {
                val out = mutableListOf<View>()
                fun visit(v: View) {
                    if (!v.isShown) return
                    out.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(activity.window.decorView)
                return out
            }

            fun labels() = nodes().filterIsInstance<TextView>().map { it.text.toString() }
            fun verifiedScreenshot(name: String, expected: String): JSONObject {
                val started = SystemClock.elapsedRealtime()
                val deadline = started + 5000
                var pixels = 0
                var matched = 0
                while (SystemClock.elapsedRealtime() < deadline) {
                    var anchor: Bitmap? = null
                    val at = IntArray(2)
                    runOnMainSync {
                        val text = nodes().filterIsInstance<TextView>()
                            .firstOrNull { it.text.toString() == expected && it.width > 0 && it.height > 0 }
                        if (text != null) {
                            text.getLocationOnScreen(at)
                            anchor = Bitmap.createBitmap(
                                text.width,
                                text.height,
                                Bitmap.Config.ARGB_8888
                            ).also { text.draw(Canvas(it)) }
                        }
                    }
                    val mask = anchor
                    if (mask == null) {
                        Thread.sleep(60)
                        continue
                    }
                    val frame = checkNotNull(uiAutomation.takeScreenshot())
                    pixels = 0
                    matched = 0
                    for (y in 0 until mask.height) for (x in 0 until mask.width) {
                        val pixel = mask.getPixel(x, y)
                        if (Color.alpha(pixel) < 245) continue
                        pixels++
                        if (at[0] + x !in 0 until frame.width || at[1] + y !in 0 until frame.height) continue
                        val shown = frame.getPixel(at[0] + x, at[1] + y)
                        if (kotlin.math.abs(Color.red(pixel) - Color.red(shown)) < 12 && kotlin.math.abs(
                                Color.green(pixel) - Color.green(shown)
                            ) < 12 && kotlin.math.abs(Color.blue(pixel) - Color.blue(shown)) < 12
                        ) matched++
                    }
                    mask.recycle()
                    if (pixels > 30 && matched >= pixels * .97 && SystemClock.elapsedRealtime() <= deadline) {
                        val filename = "diagnostic-review-$name-verified-v222.png"
                        File(targetContext.filesDir, filename).outputStream()
                            .use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        frame.recycle()
                        return JSONObject().put("file", filename).put("anchor", expected)
                            .put("opaque_pixels", pixels).put("matched_pixels", matched)
                            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                    }
                    frame.recycle()
                    Thread.sleep(60)
                }
                error("Native text not presented within five seconds: $name, matched $matched/$pixels")
            }

            fun stopPolling() {
                val timer = field("timer").get(activity) as Handler
                for (name in listOf(
                    "feedPoll",
                    "drivingWatch"
                )) timer.removeCallbacks(field(name).get(activity) as Runnable)
            }
            await("Main not idle or actual OEM display unavailable") {
                !field("busy").getBoolean(
                    activity
                ) && !field("comparisonLoading").getBoolean(activity) && (field("uxMonitor").get(
                    activity
                ) as? VehicleUxMonitor)?.restricted == false
            }
            lateinit var driving: DrivingState
            runOnMainSync {
                stopPolling();
                (field("adapter").get(activity) as PleosAdapter).close()
                driving = field("drivingState").get(activity) as DrivingState
                driving.useSimulation(true)
                driving.setSimulatedMoving(false)
            }
            val fixtures = JSONObject(
                targetContext.assets.open("driver-followup-v221.json").bufferedReader()
                    .use { it.readText() })
            val low = JSONObject(fixtures.getJSONObject("low").toString())
            val recovered = JSONObject(fixtures.getJSONObject("recovered").toString()).put(
                "id",
                low.getString("id")
            )
            check(recovered.getString("hash") == low.getString("hash"))
            val invalid = JSONObject(low.toString()).apply {
                getJSONObject("repair_case").put(
                    "stage",
                    "follow_up_invalid"
                )
            }
            val rows = JSONArray()
            fun reset() {
                runOnMainSync {
                    stopPolling()
                    method("clearDiagnosticReview").invoke(activity)
                    driving.setSimulatedMoving(false)
                    field("selected").set(activity, "기록")
                    field("opened").set(activity, JSONObject(low.toString()))
                    render.invoke(activity)
                }
            }

            fun request(
                response: JSONObject,
                action: () -> Unit,
                transition: (() -> Unit)? = null,
                code: Int = 200
            ) {
                val failure = AtomicReference<Throwable?>()
                val arrived = CountDownLatch(1)
                val release = CountDownLatch(1)
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 9000
                    val worker = Thread {
                        try {
                            server.accept().use { socket ->
                                socket.soTimeout = 6000
                                val input = socket.getInputStream().bufferedReader()
                                check(input.readLine() == "GET /api/events/${low.getString("id")} HTTP/1.1")
                                while (true) {
                                    val line = input.readLine() ?: break
                                    if (line.isEmpty()) break
                                }
                                arrived.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                                val bytes = response.toString().toByteArray()
                                socket.getOutputStream().apply {
                                    write("HTTP/1.1 $code ${if (code == 200) "OK" else "Service Unavailable"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    write(bytes)
                                    flush()
                                }
                            }
                        } catch (t: Throwable) {
                            failure.set(t)
                            arrived.countDown()
                        }
                    }.apply { start() }
                    try {
                        runOnMainSync {
                            field("client").set(
                                activity,
                                BackendClient("http://127.0.0.1:${server.localPort}")
                            )
                            action()
                        }
                        check(arrived.await(10, TimeUnit.SECONDS)) { "Expected review GET missing" }
                        failure.get()?.let { throw it }
                        if (transition != null) runOnMainSync { transition() }
                        release.countDown()
                        worker.join(11000)
                        check(!worker.isAlive)
                        failure.get()?.let { throw it }
                        // A barrier on Main's serial executor ensures its preceding response posted first.
                        val drained = CountDownLatch(1)
                        (field("executor").get(activity) as java.util.concurrent.ExecutorService).execute { drained.countDown() }
                        check(drained.await(10, TimeUnit.SECONDS))
                        waitForIdleSync()
                    } finally {
                        release.countDown()
                        if (worker.isAlive) {
                            server.close()
                            worker.join(1000)
                        }
                    }
                }
            }
            for ((name, event, expected) in listOf(
                Triple("repeat", low, "추가 점검 필요"),
                Triple("recovered", recovered, "관측 전압 회복 · 추가 확인 필요"),
                Triple("invalid", invalid, "측정 자료 확인 실패 · 판단 보류")
            )) {
                reset()
                runOnMainSync { check(labels().contains("재측정 기록 보기")) { "Initial repeat brief lacks review action" } }
                request(event, { open.invoke(activity, low) })
                runOnMainSync {
                    android.util.Log.i("DiagnosticReviewProbe", "$name labels: ${labels()}")
                    check(!field("diagnosticReviewLoading").getBoolean(activity))
                    check(!field("diagnosticReviewFailed").getBoolean(activity))
                    check(
                        (field("diagnosticReview").get(activity) as? JSONObject)?.getJSONObject("repair_case")
                            ?.getString("stage") == event.getJSONObject("repair_case")
                            .getString("stage")
                    )
                    check(expected in labels()) { "$name: missing current state: ${labels()}" }
                    if (name == "invalid") check(nodes().none { it.javaClass.simpleName == "VoltageComparisonGraphic" }) { "Invalid follow-up still has a voltage chart" }
                    rows.put(JSONObject().put("case", name).put("labels", JSONArray(labels())))
                }
                rows.put(
                    JSONObject().put("case", "$name-native-frame")
                        .put("screenshot", verifiedScreenshot(name, expected))
                )
                request(event, { method("returnFromDiagnosticReview").invoke(activity) })
                runOnMainSync {
                    check(field("diagnosticReviewParent").get(activity) == null)
                    check(
                        (field("opened").get(activity) as? JSONObject)?.optString("id") == low.getString(
                            "id"
                        )
                    )
                }
            }
            reset()
            runOnMainSync {
                field("selected").set(activity, "차량")
                field("opened").set(activity, null)
                field("events").set(activity, JSONArray().put(low))
                render.invoke(activity)
                check("재측정 기록 보기" in labels())
            }
            request(low, { open.invoke(activity, low) })
            runOnMainSync {
                check(field("selected").get(activity) == "차량")
                check("추가 점검 필요" in labels())
            }
            rows.put(
                JSONObject().put("case", "vehicle-tab-review")
                    .put("screenshot", verifiedScreenshot("vehicle", "추가 점검 필요"))
            )
            request(
                JSONObject().put("error", "authored unavailable"),
                { method("returnFromDiagnosticReview").invoke(activity) },
                code = 503
            )
            runOnMainSync {
                check(
                    field("diagnosticReview").get(activity) == null && field("diagnosticReviewFailed").getBoolean(
                        activity
                    )
                )
                check(nodes().none { it.javaClass.simpleName == "VoltageComparisonGraphic" })
                rows.put(
                    JSONObject().put("case", "return-http503").put("labels", JSONArray(labels()))
                        .put("old_values_hidden", true)
                )
            }
            request(recovered, { method("returnFromDiagnosticReview").invoke(activity) })
            runOnMainSync {
                check(field("diagnosticReviewParent").get(activity) == null)
                check(field("selected").get(activity) == "차량")
                check(
                    (field("opened").get(activity) as? JSONObject)?.optJSONObject("repair_case")
                        ?.optString("stage") == "observed_signal_recovered"
                )
            }
            rows.put(
                JSONObject().put("case", "return-http503-retry-recovered").put("fresh_result", true)
            )
            for ((name, bad) in listOf(
                "wrong-id" to JSONObject(low.toString()).put(
                    "id",
                    UUID.randomUUID().toString()
                ), "wrong-hash" to JSONObject(low.toString()).put("hash", "0".repeat(64))
            )) {
                reset()
                request(bad, { open.invoke(activity, low) })
                runOnMainSync {
                    check(
                        field("diagnosticReview").get(activity) == null && field("diagnosticReviewFailed").getBoolean(
                            activity
                        )
                    )
                    check(nodes().none { it.javaClass.simpleName == "VoltageComparisonGraphic" })
                    rows.put(JSONObject().put("case", name).put("rejected", true))
                }
            }
            for (name in listOf("drive", "pause", "other-event")) {
                reset()
                request(low, { open.invoke(activity, low) }, {
                    when (name) {
                        "drive" -> {
                            driving.setSimulatedMoving(true)
                            render.invoke(activity)
                        }

                        "pause" -> callActivityOnPause(activity)
                        else -> {
                            field("opened").set(
                                activity,
                                JSONObject(low.toString()).put("id", UUID.randomUUID().toString())
                            )
                            render.invoke(activity)
                        }
                    }
                })
                runOnMainSync {
                    check(field("diagnosticReview").get(activity) == null) { "Late $name response accepted" }
                    check(labels().none { it == "최초 측정과 재측정" })
                    rows.put(JSONObject().put("case", "late-$name").put("discarded", true))
                }
                if (name == "pause") runOnMainSync {
                    callActivityOnResume(activity)
                    stopPolling()
                }
            }
            File(targetContext.filesDir, "diagnostic-review-main-verified-v222.json").writeText(
                JSONObject().put(
                    "scope",
                    "Actual Main methods with authored loopback HTTP; native text pixels at 97 percent within five seconds; debug simulated gear, actual OEM permission. No taps or real driving."
                ).put("parent_id", low.getString("id")).put("rows", rows).toString(2)
            )
            result.putString(
                "stream",
                "PASS: Main review current repeat/recovery/invalid, parent return, ID/hash rejection and late D/pause/other-event responses.\n"
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
