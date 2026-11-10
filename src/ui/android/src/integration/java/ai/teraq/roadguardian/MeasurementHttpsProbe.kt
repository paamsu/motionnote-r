package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Actual TLS/receipt/pair verification and production file callback/rendering; not UI taps or real measurements. */
class MeasurementHttpsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            check(BuildConfig.MEASUREMENTS_ENABLED)
            val fixture = IntegrationIdentity.install(targetContext, "measurement_token")
            val id = fixture.getString("measurement_case_event_id")
            val beforeBody = fixture.getJSONObject("measurement_before")
            val afterBody = fixture.getJSONObject("measurement_after")
            val mismatchBody = fixture.getJSONObject("measurement_mismatch")
            val vehicle = beforeBody.getString("vehicle_id")
            val client = AuthenticatedEventsClient(targetContext)
            val session = client.sessionFingerprint()
            val event = client.eventForDisplay(id).value
            val caseHash = event.getString("hash")
            for (body in listOf(beforeBody, afterBody, mismatchBody)) MeasurementProtocol.validate(
                MeasurementProtocol.decode(body.toString().toByteArray()),
                vehicle
            )
            val before = client.saveMeasurementForDisplay(id, vehicle, beforeBody, session).value
            val after = client.saveMeasurementForDisplay(id, vehicle, afterBody, session).value
            val mismatch =
                client.saveMeasurementForDisplay(id, vehicle, mismatchBody, session).value
            check(
                client.saveMeasurementForDisplay(
                    id,
                    vehicle,
                    beforeBody,
                    session
                ).value.summary == before.summary
            )
            val changed = JSONObject(beforeBody.toString())
            changed.getJSONArray("samples").getJSONObject(0).put("voltage_v", 9)
            check((runCatching {
                client.saveMeasurementForDisplay(
                    id,
                    vehicle,
                    changed,
                    session
                )
            }.exceptionOrNull() as? AccountAccessException)?.reason == AccountAccessException.Reason.CONFLICT)
            val compared = client.measurementComparisonForDisplay(id, caseHash, before, after).value
            check(compared.matched)
            check(
                !client.measurementComparisonForDisplay(
                    id,
                    caseHash,
                    before,
                    mismatch
                ).value.matched
            )
            val raw = AuthenticatedTransport().measurementComparison(
                BuildConfig.AUTH_API_URL,
                id,
                before.summary.id,
                after.summary.id,
                fixture.getString("measurement_token")
            )
            val mutations = listOf<(JSONObject) -> Unit>(
                { it.put("event_id", "other") },
                { it.put("case_sha256", "0".repeat(64)) },
                { it.put("before_id", after.summary.id) },
                { it.getJSONObject("comparison").put("repair_confirmed", true) },
                { it.getJSONObject("comparison").put("conditions_verified", true) },
                { it.getJSONObject("comparison").put("before_sha256", "0".repeat(64)) },
                { it.getJSONObject("comparison").put("voltage_unit", "mV") },
                {
                    it.getJSONObject("comparison").getJSONObject("metrics")
                        .put("paired_delta_median_v", 99)
                },
                {
                    it.getJSONObject("comparison").getJSONObject("metrics")
                        .put("before_median_v", 99)
                },
                {
                    it.getJSONObject("comparison").getJSONObject("metrics")
                        .put("before_coverage", .1)
                },
                {
                    it.getJSONObject("comparison").getJSONObject("metrics")
                        .getJSONArray("before_range_v").put(0, 0)
                },
                {
                    val pairs = it.getJSONObject("comparison").getJSONArray("matched_pairs")
                    pairs.put(0, pairs.getJSONArray(1))
                },
                {
                    it.getJSONObject("comparison").getJSONArray("matched_pairs").getJSONArray(0)
                        .put(0, 999)
                },
                { it.getJSONObject("comparison").getJSONObject("tolerances").put("rpm", 10000) },
                { it.getJSONObject("comparison").put("unverified", org.json.JSONArray()) }
            )
            for (mutate in mutations) {
                val body = JSONObject(raw)
                mutate(body)
                check(runCatching {
                    MeasurementProtocol.comparison(
                        body.toString(),
                        id,
                        caseHash,
                        before,
                        after
                    )
                }.isFailure)
            }
            for (bytes in listOf(
                "{\"x\":1,\"x\":2}".toByteArray(),
                "{} trailing".toByteArray(),
                byteArrayOf(-1),
                ByteArray(256 * 1024 + 1)
            )) check(runCatching { MeasurementProtocol.decode(bytes) }.isFailure)
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
                check(labels().contains(value)) { "Missing measurement view: $value" }
            }

            fun snapshot(name: String) {
                waitForIdleSync()
                Thread.sleep(250)
                checkNotNull(uiAutomation.takeScreenshot()).let { image ->
                    File(targetContext.filesDir, name).outputStream()
                        .use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    image.recycle()
                }
            }

            fun open() {
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        MeasurementActivity::class.java
                    ).putExtra("event_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                await("측정 JSON 파일 확인")
            }

            fun compare(first: String, last: String) {
                runOnMainSync {
                    MeasurementActivity::class.java.getDeclaredMethod(
                        "compare",
                        String::class.java,
                        String::class.java
                    ).apply { isAccessible = true }.invoke(screen, first, last)
                }
            }
            await("앞·뒤 측정 기록 비교")
            open()
            check(labels().contains("TEST-PAIR-01"))
            val imported = JSONObject(beforeBody.toString()).put(
                "run_id",
                "00000000-0000-4000-8000-000000000168"
            )
            val file = File(targetContext.cacheDir, "parking-capture/measurement-fixture-v168.json")
            file.parentFile!!.mkdirs()
            file.writeText(imported.toString())
            val uri = androidx.core.content.FileProvider.getUriForFile(
                targetContext,
                targetContext.packageName + ".parkingphotos",
                file
            )
            val prior = client.measurementsForDisplay(id).value.map { it.id }
            runOnMainSync {
                MeasurementActivity::class.java.getDeclaredField("pickerSession")
                    .apply { isAccessible = true }.set(screen, session)
                MeasurementActivity::class.java.getDeclaredMethod(
                    "onActivityResult",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Intent::class.java
                ).apply { isAccessible = true }
                    .invoke(screen, 441, Activity.RESULT_OK, Intent().setData(uri))
            }
            await("이 측정 기록을 보관")
            check(client.measurementsForDisplay(id).value.map { it.id } == prior)
            runOnMainSync {
                MeasurementActivity::class.java.getDeclaredMethod("savePending")
                    .apply { isAccessible = true }.invoke(screen)
            }
            await("앞 측정 기록")
            check(client.measurementsForDisplay(id).value.any { it.id == imported.getString("run_id") })
            runOnMainSync {
                for (spinner in views().filterIsInstance<android.widget.Spinner>()) check(
                    (spinner.selectedView as TextView).typeface == PleosTypography.face()
                )
            }
            compare(before.summary.id, after.summary.id)
            await("대응 전압 차이 +2.200 V")
            val layoutEnd = android.os.SystemClock.elapsedRealtime() + 10000
            while (views().filterIsInstance<MeasurementComparisonGraphic>()
                    .none { it.width > 0 && it.height > 0 } && android.os.SystemClock.elapsedRealtime() < layoutEnd
            ) Thread.sleep(50)
            runOnMainSync {
                val chart = views().filterIsInstance<MeasurementComparisonGraphic>().single()
                val scroll = views().filterIsInstance<android.widget.ScrollView>().first()
                val rect = android.graphics.Rect()
                chart.getDrawingRect(rect)
                scroll.offsetDescendantRectToMyCoords(chart, rect)
                scroll.scrollTo(0, (rect.top - 120).coerceAtLeast(0))
            }
            val expected = mutableMapOf<Int, Int>()
            runOnMainSync {
                val chart = views().filterIsInstance<MeasurementComparisonGraphic>().single()
                val bitmap = Bitmap.createBitmap(chart.width, chart.height, Bitmap.Config.ARGB_8888)
                chart.draw(android.graphics.Canvas(bitmap))
                for (color in listOf(
                    android.graphics.Color.rgb(120, 153, 162),
                    android.graphics.Color.rgb(35, 110, 117)
                )) {
                    var count = 0
                    for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) if (bitmap.getPixel(
                            x,
                            y
                        ) == color
                    ) count++
                    check(count > 100)
                    expected[color] = count
                }
                bitmap.recycle()
            }
            var frame: Bitmap? = null
            val frameEnd = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < frameEnd) {
                val bounds = android.graphics.Rect()
                runOnMainSync {
                    check(
                        views().filterIsInstance<MeasurementComparisonGraphic>().single()
                            .getGlobalVisibleRect(bounds)
                    )
                }
                val candidate = checkNotNull(uiAutomation.takeScreenshot())
                val counts = expected.mapValues { 0 }.toMutableMap()
                for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(candidate.height) step 3) for (x in bounds.left.coerceAtLeast(
                    0
                ) until bounds.right.coerceAtMost(candidate.width) step 3) {
                    val c = candidate.getPixel(x, y)
                    for (color in expected.keys) if (kotlin.math.abs(
                            android.graphics.Color.red(c) - android.graphics.Color.red(
                                color
                            )
                        ) < 4 && kotlin.math.abs(
                            android.graphics.Color.green(c) - android.graphics.Color.green(
                                color
                            )
                        ) < 4 && kotlin.math.abs(
                            android.graphics.Color.blue(c) - android.graphics.Color.blue(
                                color
                            )
                        ) < 4
                    ) counts[color] = counts.getValue(color) + 1
                }
                if (expected.all { counts.getValue(it.key) >= it.value * .9 && counts.getValue(it.key) <= it.value * 1.1 }) {
                    frame = candidate
                    break
                }
                candidate.recycle()
                Thread.sleep(50)
            }
            val image = checkNotNull(frame)
            File(targetContext.filesDir, "measurement-phone-v168.png").outputStream()
                .use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
            compare(before.summary.id, mismatch.summary.id)
            await("지금은 비교를 보류합니다")
            check(views().filterIsInstance<MeasurementComparisonGraphic>().isEmpty())
            snapshot("measurement-held-v198.png")
            IntegrationIdentity.install(targetContext, "other_owner_token")
            await("측정 자료를 지웠습니다")
            check(
                !labels().contains("TEST-PAIR-01") && views().filterIsInstance<MeasurementComparisonGraphic>()
                    .isEmpty()
            )
            IntegrationIdentity.install(targetContext, "read_only_token")
            screen = startActivitySync(
                Intent(
                    targetContext,
                    MeasurementActivity::class.java
                ).putExtra("event_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            await("앞 측정 기록")
            check(!labels().contains("측정 JSON 파일 확인"))
            val reader = AuthenticatedEventsClient(targetContext)
            check((runCatching {
                reader.saveMeasurementForDisplay(
                    id,
                    vehicle,
                    imported,
                    reader.sessionFingerprint()
                )
            }.exceptionOrNull() as? AccountAccessException)?.reason == AccountAccessException.Reason.FORBIDDEN)
            IntegrationIdentity.install(targetContext, "measurement_token")
            screen = startActivitySync(
                Intent(
                    targetContext,
                    MeasurementLargeTextActivity::class.java
                ).putExtra("event_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            await("앞 측정 기록")
            compare(before.summary.id, after.summary.id)
            await("앞 중앙값")
            check(
                screen!!.resources.configuration.fontScale == 1.5f && views().filterIsInstance<MeasurementComparisonGraphic>()
                    .isEmpty()
            )
            snapshot("measurement-large-v198.png")
            File(targetContext.filesDir, "measurement-result-v168.json").writeText(
                JSONObject().put(
                    "event_id",
                    id
                ).put("synthetic_measurements", true).put("actual_vehicle", false)
                    .put("comparison_mutations_rejected", mutations.size)
                    .put("file_preview_without_save", true)
                    .put("file_callback_and_explicit_save_method", true)
                    .put("not_comparable_without_chart", true).put("account_change_clears", true)
                    .put("read_only_no_import", true).put("large_font_text_fallback", true)
                    .put("picker_and_button_taps", false).toString(2)
            )
            result.putString(
                "stream",
                "PASS: Android TLS save/replay/conflict, strict file parsing, 15 comparison mutations, actual detail entry, content-URI preview then explicit save method, both bar pixel coverage, noncomparable suppression, account-change clearing, read-only controls, 1.5 font text fallback. Synthetic measurements/login; no picker or button taps, vehicle or repair claim.\n"
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
