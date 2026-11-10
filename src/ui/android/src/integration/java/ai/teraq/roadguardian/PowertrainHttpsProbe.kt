package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Actual Android TLS and production phone Activity; fixture upstream, no provider login or UI taps. */
class PowertrainHttpsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            check(BuildConfig.POWERTRAIN_ENABLED)
            val fixture = IntegrationIdentity.install(targetContext, "powertrain_token")
            val id = fixture.getString("powertrain_case_event_id")
            val client = AuthenticatedEventsClient(targetContext)
            val event = client.eventForDisplay(id).value
            val vehicle = event.getJSONObject("data").getString("vehicle_id")
            check(client.capabilitiesForDisplay().value.powertrainRead)
            val day = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
            val raw = AuthenticatedTransport().powertrain(
                BuildConfig.AUTH_API_URL,
                id,
                day,
                day,
                fixture.getString("powertrain_token")
            )
            val batch = PowertrainBatch.parse(raw, id, vehicle, day, day)
            check(
                batch.trips.size == 1 && batch.trips[0].signals.map { it.values.size } == listOf(
                    60,
                    50,
                    40
                )
            )
            check(batch.trips[0].signals[0].values[20] == null)
            val mutations = listOf<(JSONObject) -> Unit>(
                { it.put("event_id", "different") },
                { it.put("scope", "shared") },
                { it.put("start_date", "20200101") },
                { it.getJSONObject("batch").put("vehicle_id", "other") },
                { it.getJSONObject("batch").put("comparison_ready", true) },
                { it.getJSONObject("batch").put("repair_confirmed", true) },
                { it.getJSONObject("batch").put("transport", "offline_import") },
                { it.getJSONObject("batch").put("missing_conditions", org.json.JSONArray()) },
                {
                    it.getJSONObject("batch").getJSONArray("trips").getJSONObject(0)
                        .put("sample_hz", 1)
                },
                {
                    it.getJSONObject("batch").getJSONArray("trips").getJSONObject(0)
                        .put("aligned_samples", true)
                },
                {
                    it.getJSONObject("batch").getJSONArray("trips").getJSONObject(0)
                        .getJSONObject("signals").getJSONObject("hv_pack_voltage_v")
                        .getJSONArray("values").put(0, 9999)
                },
                {
                    it.getJSONObject("batch").getJSONArray("trips").getJSONObject(0)
                        .getJSONObject("signals").getJSONObject("engine_rpm")
                        .put("invalid_count", 100)
                }
            )
            for (mutate in mutations) {
                val value = JSONObject(raw)
                mutate(value)
                check(runCatching {
                    PowertrainBatch.parse(
                        value.toString(),
                        id,
                        vehicle,
                        day,
                        day
                    )
                }.isFailure)
            }
            check(runCatching { PowertrainBatch.checkedDates("20260230", day) }.isFailure)
            screen = startActivitySync(
                Intent(
                    targetContext,
                    AccountEventsActivity::class.java
                ).putExtra(
                    "notification_event_id",
                    id
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            fun views(): List<View> {
                val list = mutableListOf<View>()
                val collect = {
                    fun read(view: View) {
                        list.add(view)
                        if (view is ViewGroup) for (i in 0 until view.childCount) read(
                            view.getChildAt(
                                i
                            )
                        )
                    }
                    read(screen!!.window.decorView)
                }
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) collect() else runOnMainSync { collect() }
                return list
            }

            fun labels() =
                views().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }

            fun await(value: String) {
                val end = android.os.SystemClock.elapsedRealtime() + 20000
                while (!labels().contains(value) && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(
                    50
                )
                check(labels().contains(value)) { "Missing production view: $value" }
            }
            await("정비 기록의 Pleos 자료 조회")
            screen = startActivitySync(
                Intent(
                    targetContext,
                    PowertrainActivity::class.java
                ).putExtra("event_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            await("선택한 기간 조회")
            check(labels().contains("TEST-CLOUD-01"))
            runOnMainSync {
                views().filterIsInstance<android.widget.EditText>().forEach { it.setText(day) }
                // Invoke the actual request method without synthesizing a UI click.
                PowertrainActivity::class.java.getDeclaredMethod("query")
                    .apply { isAccessible = true }.invoke(screen)
            }
            await("270.00 ~ 281.80 V")
            check(
                labels().contains("엔진 회전수") && labels().contains("엔진 오일 온도") && labels().contains(
                    "정비 전후 비교를 보류"
                )
            )
            check(views().filterIsInstance<PowertrainSignalChart>().size == 3)
            val layoutDeadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (views().filterIsInstance<PowertrainSignalChart>()
                    .any { it.width == 0 || it.height == 0 } && android.os.SystemClock.elapsedRealtime() < layoutDeadline
            ) Thread.sleep(50)
            check(
                views().filterIsInstance<PowertrainSignalChart>()
                    .all { it.width > 0 && it.height > 0 })
            // Move the production ScrollView to the first chart after layout; not a gesture.
            runOnMainSync {
                val chart = views().filterIsInstance<PowertrainSignalChart>().first()
                val scroll = views().filterIsInstance<android.widget.ScrollView>().first()
                val rect = android.graphics.Rect()
                chart.getDrawingRect(rect)
                scroll.offsetDescendantRectToMyCoords(chart, rect)
                scroll.scrollTo(0, (rect.top - 80).coerceAtLeast(0))
            }
            waitForIdleSync()
            var captured: android.graphics.Bitmap? = null
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val bounds = android.graphics.Rect()
                runOnMainSync {
                    check(
                        views().filterIsInstance<PowertrainSignalChart>().first()
                            .getGlobalVisibleRect(bounds)
                    )
                }
                val candidate = checkNotNull(uiAutomation.takeScreenshot())
                var ink = 0
                for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(candidate.height) step 2)
                    for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(candidate.width) step 2) {
                        val c = candidate.getPixel(x, y)
                        if (kotlin.math.abs(android.graphics.Color.red(c) - 35) < 4 && kotlin.math.abs(
                                android.graphics.Color.green(c) - 110
                            ) < 4 && kotlin.math.abs(android.graphics.Color.blue(c) - 117) < 4
                        ) ink++
                    }
                if (ink > 60) {
                    captured = candidate
                    break
                }
                candidate.recycle()
                Thread.sleep(50)
            }
            val frame = checkNotNull(captured) { "Chart pixels did not reach the frame" }
            File(targetContext.filesDir, "powertrain-phone-v165.png").outputStream()
                .use { check(frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            frame.recycle()
            IntegrationIdentity.install(targetContext, "other_owner_token")
            await("자료를 지웠습니다")
            check(views().filterIsInstance<PowertrainSignalChart>().isEmpty())
            check(!labels().contains("270.00 ~ 281.80 V"))
            IntegrationIdentity.install(targetContext, "powertrain_token")
            screen = startActivitySync(
                Intent(
                    targetContext,
                    PowertrainLargeTextActivity::class.java
                ).putExtra("event_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            await("선택한 기간 조회")
            runOnMainSync {
                views().filterIsInstance<android.widget.EditText>().forEach { it.setText(day) }
                PowertrainActivity::class.java.getDeclaredMethod("query")
                    .apply { isAccessible = true }.invoke(screen)
            }
            await("270.00 ~ 281.80 V")
            check(views().filterIsInstance<PowertrainSignalChart>().isEmpty())
            check(screen!!.resources.configuration.fontScale == 1.5f)
            File(
                targetContext.filesDir,
                "powertrain-result-v165.json"
            ).writeText(
                JSONObject().put("event_id", id).put("synthetic_upstream", true)
                    .put("provider_called", false).put("mutations_rejected", mutations.size)
                    .put("production_activity", true).put("account_change_clears", true)
                    .put("large_font_text_fallback", true).put("ui_taps", false).toString(2)
            )
            result.putString(
                "stream",
                "PASS: Android HTTPS, case/scope/date/vehicle binding, 12 malformed responses rejected, independent arrays, actual detail entry, production Activity request, three charts and first-chart pixels, account-change removal, 1.5 font text fallback. Synthetic upstream/login; no taps or vendor call.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            LoginStateStore(targetContext).write(net.openid.appauth.AuthState())
            runOnMainSync { screen?.finish() }
        }
    }
}
