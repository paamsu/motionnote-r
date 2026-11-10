package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Real TLS and shared vehicle renderer. Selection/compare methods are invoked, never taps. */
class VehicleMeasurementsHttpsProbe : Instrumentation() {
    private var interactive = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        interactive = arguments?.getString("interactive") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: VehicleMeasurementsPreviewActivity? = null
        try {
            val fixture = IntegrationIdentity.install(targetContext, "measurement_token")
            val id = fixture.getString("measurement_case_event_id")
            val client = AuthenticatedEventsClient(targetContext)
            val vehicle = fixture.getJSONObject("measurement_before").getString("vehicle_id")
            val hash = client.eventForDisplay(id).value.getString("hash")
            val session = client.sessionFingerprint()
            val before = client.saveMeasurementForDisplay(
                id,
                vehicle,
                fixture.getJSONObject("measurement_before"),
                session
            ).value
            val after = client.saveMeasurementForDisplay(
                id,
                vehicle,
                fixture.getJSONObject("measurement_after"),
                session
            ).value
            val mismatch = client.saveMeasurementForDisplay(
                id,
                vehicle,
                fixture.getJSONObject("measurement_mismatch"),
                session
            ).value
            IntegrationIdentity.install(targetContext, "read_only_token")
            fun open(): VehicleMeasurementsPreviewActivity = startActivitySync(
                Intent(targetContext, VehicleMeasurementsPreviewActivity::class.java)
                    .putExtra("event_id", id).putExtra("case_hash", hash).putExtra("night", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ) as VehicleMeasurementsPreviewActivity

            fun views(): List<View> {
                val values = mutableListOf<View>()
                val collect = {
                    fun visit(view: View) {
                        if (view.isShown) values.add(view)
                        if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                            view.getChildAt(
                                i
                            )
                        )
                    }
                    visit(checkNotNull(screen).window.decorView)
                }
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) collect() else runOnMainSync { collect() }
                return values
            }

            fun labels() =
                views().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }

            fun await(text: String) {
                val end = android.os.SystemClock.elapsedRealtime() + 15000
                while (!labels().contains(text) && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(
                    50
                )
                check(labels().contains(text)) { "Missing vehicle measurement state: $text; font=${screen?.resources?.configuration?.fontScale}; ${labels()}" }
            }

            fun panel() = views().filterIsInstance<VehicleMeasurementsPanel>().single()
            fun compare(second: MeasurementProtocol.Summary) {
                runOnMainSync {
                    val target = panel()
                    for ((key, value) in listOf(
                        "before" to before.summary,
                        "after" to second
                    )) VehicleMeasurementsPanel::class.java.getDeclaredField(key)
                        .apply { isAccessible = true }.set(target, value)
                    VehicleMeasurementsPanel::class.java.getDeclaredMethod("compare")
                        .apply { isAccessible = true }.invoke(target)
                }
            }
            screen = open()
            await("앞 기록 선택")
            check(
                !labels().contains("측정 JSON 파일 확인") && !labels().contains("보관할 측정") && !labels().contains(
                    "묶음 저장"
                )
            )
            runOnMainSync {
                VehicleMeasurementsPanel::class.java.getDeclaredMethod(
                    "choose",
                    Boolean::class.javaPrimitiveType
                ).apply { isAccessible = true }.invoke(panel(), true)
            }
            await("기록 1 ·")
            check(
                labels().contains("보조 12V") && labels().contains("합성 측정 시험") && labels().contains(
                    "표본 40개"
                )
            )
            check(!labels().contains(before.summary.id.take(8)))
            checkNotNull(uiAutomation.takeScreenshot()).let { image ->
                File(targetContext.filesDir, "vehicle-record-selection-v182.png").outputStream()
                    .use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                image.recycle()
            }
            compare(after.summary)
            await("대응 전압 차이 +2.200 V")
            val chart = views().filterIsInstance<MeasurementComparisonGraphic>().single()
            val layoutEnd = android.os.SystemClock.elapsedRealtime() + 5000
            while ((chart.width == 0 || chart.height == 0) && android.os.SystemClock.elapsedRealtime() < layoutEnd) Thread.sleep(
                50
            )
            val expected = mutableMapOf<Int, Int>()
            val bounds = Rect()
            var drawing: Result<Unit>? = null
            runOnMainSync {
                drawing = runCatching {
                    val bitmap =
                        Bitmap.createBitmap(chart.width, chart.height, Bitmap.Config.ARGB_8888)
                    chart.draw(Canvas(bitmap))
                    for (color in listOf(Color.rgb(130, 154, 162), Color.rgb(103, 208, 204))) {
                        var n = 0
                        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) if (bitmap.getPixel(
                                x,
                                y
                            ) == color
                        ) n++
                        check(n > 100)
                        expected[color] = n
                    }
                    bitmap.recycle()
                    check(chart.getGlobalVisibleRect(bounds))
                    check(bounds.height() >= chart.height && bounds.width() >= chart.width)
                    val screenAt = IntArray(2)
                    val windowAt = IntArray(2)
                    chart.getLocationOnScreen(screenAt)
                    chart.getLocationInWindow(windowAt)
                    bounds.offset(screenAt[0] - windowAt[0], screenAt[1] - windowAt[1])
                }
            }
            checkNotNull(drawing).getOrThrow()
            var image: Bitmap? = null
            val end = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < end) {
                val candidate = checkNotNull(uiAutomation.takeScreenshot())
                val counts = expected.mapValues { 0 }.toMutableMap()
                for (y in bounds.top until minOf(
                    bounds.bottom,
                    candidate.height
                ) step 3) for (x in bounds.left until minOf(bounds.right, candidate.width) step 3) {
                    val color = candidate.getPixel(x, y)
                    for (key in counts.keys) if (kotlin.math.abs(Color.red(color) - Color.red(key)) < 4 && kotlin.math.abs(
                            Color.green(color) - Color.green(key)
                        ) < 4 && kotlin.math.abs(Color.blue(color) - Color.blue(key)) < 4
                    ) counts[key] = counts.getValue(key) + 1
                }
                if (expected.all { counts.getValue(it.key) >= it.value * .9 && counts.getValue(it.key) <= it.value * 1.1 }) {
                    image = candidate
                    break
                }
                candidate.recycle()
                Thread.sleep(50)
            }
            val bitmap = checkNotNull(image) { "Both bars not presented" }
            File(targetContext.filesDir, "vehicle-measurements-v173.png").outputStream()
                .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            compare(mismatch.summary)
            await("지금은 비교를 보류합니다")
            check(
                views().filterIsInstance<MeasurementComparisonGraphic>()
                    .isEmpty() && !labels().contains("대응 전압 차이")
            )
            runOnMainSync { screen!!.displayAllowed = false }
            await("측정 자료를 지웠습니다")
            check(!labels().contains("TEST-PAIR-01") && !labels().contains(before.summary.id.take(8)))
            screen = open()
            await("앞 기록 선택")
            compare(after.summary)
            await("대응 전압 차이")
            IntegrationIdentity.install(targetContext, "other_owner_token")
            await("측정 자료를 지웠습니다")
            check(!labels().contains("TEST-PAIR-01"))
            IntegrationIdentity.install(targetContext, "read_only_token")
            runOnMainSync { VehicleMeasurementsPreviewActivity.fontScale = 1.5f }
            screen = open()
            await("앞 기록 선택")
            compare(after.summary)
            await("앞 중앙값")
            check(
                screen!!.resources.configuration.fontScale == 1.5f && views().filterIsInstance<MeasurementComparisonGraphic>()
                    .isEmpty()
            )
            File(targetContext.filesDir, "vehicle-measurements-v173.json").writeText(
                JSONObject().put("synthetic_measurements", true).put("actual_tls", true)
                    .put("read_only", true).put("both_bars_presented", true)
                    .put("no_chart_when_not_comparable", true).put("host_change_clears", true)
                    .put("account_change_clears", true).put("large_font_text_fallback", true)
                    .put("picker_or_button_taps", false).put("main_real_gear_navigation", false)
                    .toString(2)
            )
            if (interactive) {
                runOnMainSync { VehicleMeasurementsPreviewActivity.fontScale = 1f }
                screen = open()
                await("앞 기록 선택")
                Thread.sleep(90000) // Observation session only; the tool does not inject input.
            }
            result.putString(
                "stream",
                "PASS: actual TLS read-only vehicle measurement renderer, selection labels, both bar coverage, noncomparable suppression, host/account clearing and 1.5 font fallback. Synthetic login/measurements; method calls, no taps or Main real-gear navigation.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            LoginStateStore(targetContext).write(net.openid.appauth.AuthState())
            runOnMainSync {
                VehicleMeasurementsPreviewActivity.fontScale = 1f
                screen?.finish()
            }
        }
    }
}
