package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/** Actual Main result callback and HTTP/export path. No document picker taps or Toast pixel claim. */
class DemoPhotoExportProbe : Instrumentation() {
    private var fixtureDir: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        fixtureDir = arguments?.getString("fixture_dir")
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: MainActivity? = null
        val rows = JSONArray()
        try {
            val fixture = File(fixtureDir ?: targetContext.filesDir.absolutePath, "clear.zip")
            val payload = fixture.readBytes()
            val id = ZipFile(fixture).use { zip ->
                JSONObject(
                    zip.getInputStream(
                        checkNotNull(
                            zip.getEntry("manifest.json")
                        )
                    ).bufferedReader().use { it.readText() }).getString("event_id")
            }
            EvidencePackageVerifier.verify(fixture, id)
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

            val callback = MainActivity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java
            ).apply { isAccessible = true }

            fun await(message: String, test: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 18000
                while (SystemClock.elapsedRealtime() < deadline) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(40)
                }
                error(message)
            }

            fun stopPolling() {
                val timer = field("timer").get(activity) as Handler
                for (name in listOf(
                    "feedPoll",
                    "drivingWatch"
                )) timer.removeCallbacks(field(name).get(activity) as Runnable)
            }
            await("Main idle and actual OEM permission unavailable") {
                !field("busy").getBoolean(activity) && !field("comparisonLoading").getBoolean(
                    activity
                ) &&
                        (field("uxMonitor").get(activity) as? VehicleUxMonitor)?.restricted == false
            }
            lateinit var driving: DrivingState
            val preferences = activity.getSharedPreferences("export_result", Activity.MODE_PRIVATE)
            runOnMainSync {
                stopPolling();
                (field("adapter").get(activity) as PleosAdapter).close()
                driving = field("drivingState").get(activity) as DrivingState
                driving.useSimulation(true)
                driving.setSimulatedMoving(false)
            }
            for (kind in listOf("valid", "invalid", "http400")) for (transition in listOf(
                "parked",
                "moving",
                "background"
            )) {
                val name = "$kind-$transition"
                val output = File(targetContext.filesDir, "demo-export-$name-v223.zip")
                val sentinel = "unchanged private probe output: $name".toByteArray()
                output.writeBytes(sentinel)
                runOnMainSync {
                    stopPolling()
                    driving.setSimulatedMoving(false)
                    field("foreground").setBoolean(activity, true)
                    check(preferences.edit().remove("pending").commit())
                    field("pendingExportId").set(activity, id)
                }
                val arrived = CountDownLatch(1)
                val release = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 10000
                    val worker = Thread {
                        try {
                            server.accept().use { socket ->
                                socket.soTimeout = 10000
                                val reader = socket.getInputStream().bufferedReader()
                                check(reader.readLine() == "GET /api/events/$id/export HTTP/1.1")
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                }
                                arrived.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                                val bytes =
                                    if (kind == "valid") payload else "authored invalid package".toByteArray()
                                val status = if (kind == "http400") "400 Bad Request" else "200 OK"
                                socket.getOutputStream().apply {
                                    write("HTTP/1.1 $status\r\nContent-Type: application/zip\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
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
                            callback.invoke(
                                activity,
                                81,
                                Activity.RESULT_OK,
                                Intent().setData(Uri.fromFile(output))
                            )
                            check(field("pendingExportId").get(activity) == null)
                        }
                        check(
                            arrived.await(
                                10,
                                TimeUnit.SECONDS
                            )
                        ) { "Missing export request: $name" }
                        failure.get()?.let { throw it }
                        runOnMainSync {
                            if (transition == "moving") driving.setSimulatedMoving(true)
                            if (transition == "background") field("foreground").setBoolean(
                                activity,
                                false
                            )
                        }
                        release.countDown()
                        worker.join(11000)
                        check(!worker.isAlive)
                        failure.get()?.let { throw it }
                        val drained = CountDownLatch(1)
                        (field("executor").get(activity) as java.util.concurrent.ExecutorService).execute { drained.countDown() }
                        check(drained.await(15, TimeUnit.SECONDS))
                        waitForIdleSync()
                    } finally {
                        release.countDown()
                        if (worker.isAlive) {
                            server.close()
                            worker.join(1000)
                        }
                    }
                }
                check(
                    output.readBytes().contentEquals(if (kind == "valid") payload else sentinel)
                ) { "Output mismatch: $name" }
                val expected =
                    if (kind == "valid") "합성 자료 저장 완료 · 외부 제출 없음" else "자료 확인 실패로 저장하지 않았습니다"
                runOnMainSync {
                    val pending = preferences.getString("pending", null)
                    if (transition == "parked") check(pending == null) { "Foreground parked result not consumed: $name" }
                    else {
                        check(pending == expected) { "Wrong delayed result: $name, $pending" }
                        method("showPendingExportResult").invoke(activity)
                        check(
                            preferences.getString(
                                "pending",
                                null
                            ) == expected
                        ) { "Result consumed while restricted: $name" }
                        driving.setSimulatedMoving(false)
                        field("foreground").setBoolean(activity, true)
                        method("showPendingExportResult").invoke(activity)
                        check(!preferences.contains("pending")) { "Result not consumed after parked foreground: $name" }
                    }
                }
                rows.put(
                    JSONObject().put("case", name).put("output_bytes", output.length())
                        .put("output_matches", true)
                        .put("sentinel_preserved", kind != "valid")
                        .put("pending_checked", transition != "parked")
                        .put("result_consumed_after_return", true)
                )
            }
            // Inspect owned Toast cancellation, not rendered Toast pixels.
            runOnMainSync {
                stopPolling()
                driving.setSimulatedMoving(false)
                field("foreground").setBoolean(activity, true)
                val report = MainActivity::class.java.getDeclaredMethod(
                    "reportExportResult",
                    String::class.java
                ).apply { isAccessible = true }
                report.invoke(activity, "합성 자료 저장 완료 · 외부 제출 없음")
                check(field("exportResultToast").get(activity) != null)
                driving.setSimulatedMoving(true)
                method("showPendingExportResult").invoke(activity)
                check(field("exportResultToast").get(activity) == null) { "Existing Toast reference retained on D" }
                driving.setSimulatedMoving(false)
                report.invoke(activity, "합성 자료 저장 완료 · 외부 제출 없음")
                check(field("exportResultToast").get(activity) != null)
                callActivityOnPause(activity)
                check(
                    !field("foreground").getBoolean(activity) && field("exportResultToast").get(
                        activity
                    ) == null
                ) { "Existing Toast reference retained on pause" }
            }
            rows.put(
                JSONObject().put("case", "existing-toast-cancel-on-D-and-pause")
                    .put("reference_cleared", true).put("toast_pixels_verified", false)
            )
            File(targetContext.filesDir, "demo-photo-export-v223.json").writeText(
                JSONObject().put("pass", true).put("event_id", id)
                    .put("method_probe", true).put("direct_picker_taps", false)
                    .put("toast_pixels_verified", false).put("simulated_gear", true)
                    .put("cases", rows).toString(2)
            )
            result.putString(
                "stream",
                "PASS: ${rows.length()} actual Main callback/HTTP cases; exact valid bytes, invalid/400 output preservation, deferred results checked. No picker taps or Toast pixel claim.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString(
                "stream",
                "FAIL: ${failure.javaClass.simpleName}: ${failure.message}; completed=${rows.length()}\n${failure.stackTraceToString()}\n"
            )
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            screen?.let { runOnMainSync { it.finish() } }
        }
    }
}
