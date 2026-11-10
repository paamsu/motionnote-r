package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

/** Actual HTTP responses rendered by the production comparison component. No control taps. */
class RoadFollowupLiveProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val output = Bundle()
        var screen: Activity? = null
        try {
            val client = BackendClient("http://127.0.0.1:9463")
            val ids = JSONObject(client.request("/api/dev/road-native-fixture"))
            val rows = JSONArray()
            for (kind in listOf("waiting", "clear", "repeat", "invalid")) for (mode in listOf(
                "day",
                "night",
                "large"
            )) {
                val event = JSONObject(client.request("/api/events/${ids.getString(kind)}"))
                val case = event.getJSONObject("road_followup_case")
                val expected = mapOf(
                    "waiting" to "awaiting_observation",
                    "clear" to "no_repeat_signal",
                    "repeat" to "repeat_signal",
                    "invalid" to "comparison_invalid"
                ).getValue(kind)
                check(case.getString("stage") == expected)
                ShowcasePreviewActivity.previewFontScale = if (mode == "large") 1.5f else 1f
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        ShowcasePreviewActivity::class.java
                    ).putExtra("night", mode == "night")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                runOnMainSync {
                    val ui = PhoneAccountUi(screen!!, mode != "night")
                    val body = ui.install()
                    RoadFollowupPanel.render(ui, body, event)
                }
                waitForIdleSync()
                Thread.sleep(500)
                waitForIdleSync()
                val views = mutableListOf<View>()
                val labels = mutableListOf<String>()
                runOnMainSync {
                    fun visit(v: View) {
                        if (v.isShown) {
                            views.add(v)
                            if (v is TextView) {
                                labels.add(v.text.toString())
                                val l = v.layout
                                if (l != null) for (i in 0 until l.lineCount) {
                                    check(l.getEllipsisCount(i) == 0)
                                    check(l.getLineMax(i) <= v.width - v.compoundPaddingLeft - v.compoundPaddingRight + 2)
                                }
                            }
                        }
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(screen!!.window.decorView)
                }
                val charts = views.filterIsInstance<PairedObservationGraphic>()
                val anchor = when (kind) {
                    "waiting" -> "이후 자료 없음 · 비교 수치와 보수 여부는 미확인"
                    "invalid" -> "원본 또는 비교 조건 확인 실패 · 이전 결과로 대신하지 않습니다"
                    "clear" -> "이번 관측 · 같은 기준 충격 없음"
                    else -> "충격 신호 재관측"
                }
                check(labels.contains(anchor))
                if (kind in listOf("clear", "repeat")) {
                    val before = event.getJSONObject("data").getJSONArray("samples")
                    var peak = 0.0
                    for (i in 0 until before.length()) peak = maxOf(
                        peak,
                        kotlin.math.abs(before.getJSONObject(i).getDouble("vertical_g"))
                    )
                    check(peak == case.getJSONObject("before").getDouble("peak_g"))
                    val after =
                        JSONObject(client.request("/api/events/${case.getString("followup_id")}"))
                    val samples = after.getJSONObject("data").getJSONArray("samples")
                    var second = 0.0
                    for (i in 0 until samples.length()) second = maxOf(
                        second,
                        kotlin.math.abs(samples.getJSONObject(i).getDouble("vertical_g"))
                    )
                    check(second == case.getJSONObject("after").getDouble("peak_g"))
                    if (mode == "large") check(charts.isEmpty() && labels.any {
                        it == "이후 관측  ${
                            String.format(
                                Locale.KOREAN,
                                "%.3f g",
                                second
                            )
                        }"
                    })
                    else check(
                        charts.size == 1 && charts.single().contentDescription.contains(
                            String.format(Locale.KOREAN, "%.3f", second)
                        )
                    )
                } else check(charts.isEmpty() && !case.has("after"))
                val title =
                    views.filterIsInstance<TextView>().first { it.text.toString() == anchor }
                val bounds = android.graphics.Rect()
                runOnMainSync { check(title.getGlobalVisibleRect(bounds) && bounds.height() == title.height) }
                val image = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "road-live-$kind-$mode-v209.png").outputStream()
                    .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                rows.put(
                    JSONObject().put("case", kind).put("mode", mode)
                        .put("event_id", event.getString("id")).put("stage", expected)
                        .put("texts", JSONArray(labels))
                )
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            File(targetContext.filesDir, "road-live-v209.json").writeText(
                JSONObject().put(
                    "states",
                    rows
                ).put("source", "actual local HTTP with separately authored sensor originals").put(
                    "scope",
                    "production comparison component in preview Activity; no Main navigation, UI taps, media playback or image inference"
                ).toString(2)
            )
            output.putString(
                "stream",
                "PASS: 12 live HTTP comparison display states; raw sensor peaks independently checked; invalid/waiting hide values; native labels and bounds checked.\n"
            )
            finish(Activity.RESULT_OK, output)
        } catch (t: Throwable) {
            output.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, output)
        } finally {
            runOnMainSync { screen?.finish() }
            ShowcasePreviewActivity.previewFontScale = 1f
        }
    }
}
