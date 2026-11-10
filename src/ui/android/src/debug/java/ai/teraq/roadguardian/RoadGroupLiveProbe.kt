package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Actual backend pages and production Main method routes. Never activates a control. */
class RoadGroupLiveProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            check(BuildConfig.DEBUG && BuildConfig.SYNTHETIC_BACKEND)
            val client = BackendClient()
            val summary = JSONObject(client.request("/api/demo/road-summary"))
            check(
                summary.getInt("input_count") == 16 && summary.getInt("included_count") == 13 && summary.getInt(
                    "pending_count"
                ) == 1 && summary.getInt("excluded_count") == 2
            )
            val group = (0 until summary.getJSONArray("groups")
                .length()).map { summary.getJSONArray("groups").getJSONObject(it) }
                .single { it.getInt("report_count") == 12 }.getString("id")
            val first = JSONObject(client.request("/api/demo/road-summary/groups/$group"))
            val second = JSONObject(
                client.request(
                    "/api/demo/road-summary/groups/$group/10/${
                        first.getString("membership_sha256")
                    }"
                )
            )
            check(
                first.getJSONArray("events").length() == 10 && second.getJSONArray("events")
                    .length() == 2
            )
            val source = first.getJSONArray("events").getJSONObject(0)
            val sourceId = source.getString("id")
            check(source.getJSONObject("video").getBoolean("integrity_valid"))
            val ids = (listOf(first, second)).flatMap { page ->
                (0 until page.getJSONArray("events").length()).map {
                    page.getJSONArray("events").getJSONObject(it).getString("id")
                }
            }
            check(ids.size == 12 && ids.toSet().size == 12)
            fun views(): List<View> {
                val values = mutableListOf<View>()
                runOnMainSync {
                    fun visit(v: View) {
                        if (v.isShown) values.add(v)
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(checkNotNull(screen).window.decorView)
                }
                return values
            }

            fun labels() =
                views().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }

            fun await(text: String) {
                val end = SystemClock.elapsedRealtime() + 15000
                while (!labels().contains(text) && SystemClock.elapsedRealtime() < end) Thread.sleep(
                    80
                )
                check(labels().contains(text)) { "Missing $text; ${labels()}" }
            }

            fun capture(name: String, anchor: String) {
                await(anchor)
                waitForIdleSync()
                val candidates = views().filterIsInstance<TextView>()
                var error: Throwable? = null
                runOnMainSync {
                    try {
                        for (v in candidates) {
                            val layout = v.layout ?: continue
                            for (line in 0 until layout.lineCount) {
                                check(layout.getEllipsisCount(line) == 0)
                                check(layout.getLineMax(line) <= v.width - v.compoundPaddingLeft - v.compoundPaddingRight + 2)
                            }
                        }
                    } catch (t: Throwable) {
                        error = t
                    }
                }
                error?.let { throw it }
                var expected: Bitmap? = null
                val at = IntArray(2)
                val title = candidates.first { it.text.toString().contains(anchor) }
                runOnMainSync {
                    try {
                        check(title.width > 0 && title.height > 0)
                        title.getLocationOnScreen(at)
                        expected =
                            Bitmap.createBitmap(title.width, title.height, Bitmap.Config.ARGB_8888)
                                .apply {
                                    eraseColor(Color.TRANSPARENT)
                                    title.draw(Canvas(this))
                                }
                    } catch (t: Throwable) {
                        error = t
                    }
                }
                error?.let { throw it }
                val glyphs = checkNotNull(expected)
                var accepted: Bitmap? = null
                val end = SystemClock.elapsedRealtime() + 10000
                while (SystemClock.elapsedRealtime() < end) {
                    runOnMainSync { title.getLocationOnScreen(at) }
                    val frame = checkNotNull(uiAutomation.takeScreenshot())
                    var required = 0
                    var matched = 0
                    for (y in 0 until glyphs.height) for (x in 0 until glyphs.width) {
                        val a = glyphs.getPixel(x, y)
                        if (Color.alpha(a) < 245) continue
                        required++
                        val sx = at[0] + x
                        val sy = at[1] + y
                        if (sx !in 0 until frame.width || sy !in 0 until frame.height) continue
                        val b = frame.getPixel(sx, sy)
                        if (kotlin.math.abs(Color.red(a) - Color.red(b)) < 12 && kotlin.math.abs(
                                Color.green(a) - Color.green(b)
                            ) < 12 && kotlin.math.abs(Color.blue(a) - Color.blue(b)) < 12
                        ) matched++
                    }
                    var contrast = true
                    if (!targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) {
                        val night = screen!!.intent.getBooleanExtra("night", false)
                        var clock = 0
                        for (y in 20 until minOf(100, frame.height)) for (x in 20 until minOf(
                            180,
                            frame.width
                        )) {
                            val c = frame.getPixel(x, y)
                            if (if (night) Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(
                                    c
                                ) > 200 else Color.red(c) < 140 && Color.green(c) < 140 && Color.blue(
                                    c
                                ) < 140
                            ) clock++
                        }
                        contrast = clock > 100
                    }
                    if (required > 30 && matched >= required * .97 && contrast) {
                        accepted = frame
                        break
                    }
                    frame.recycle()
                    Thread.sleep(80)
                }
                glyphs.recycle()
                checkNotNull(accepted) { "Anchor not actually presented: $anchor" }.let { frame ->
                    File(targetContext.filesDir, name).outputStream()
                        .use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    frame.recycle()
                }
            }

            val states = JSONArray()
            for (mode in listOf("day", "night", "large")) for (kind in listOf(
                "summary",
                "first",
                "second"
            )) {
                RoadSummaryPreviewActivity.previewFontScale = if (mode == "large") 1.5f else 1f
                RoadSummaryPreviewActivity.liveData = when (kind) {
                    "first" -> first
                    "second" -> second
                    else -> summary
                }
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        RoadSummaryPreviewActivity::class.java
                    ).putExtra("live", true).putExtra("group_sources", kind != "summary")
                        .putExtra("night", mode == "night")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                capture(
                    "road-group-$kind-$mode-v199.png",
                    if (kind == "summary") "도로 관측 모아 보기" else "묶음의 원본 관측"
                )
                check(
                    if (kind == "summary") labels().contains("묶음 1의 원본 보기") else labels().contains(
                        if (kind == "first") "원본 1–10 / 12건" else "원본 11–12 / 12건"
                    )
                )
                states.put(
                    JSONObject().put("mode", mode).put("kind", kind)
                        .put("native_anchor_pixels", true)
                )
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            for (mode in listOf("bad_id", "duplicate_id", "bad_snapshot")) {
                val page = JSONObject(first.toString())
                when (mode) {
                    "bad_id" -> page.getJSONArray("events").getJSONObject(0).put("id", "../escape")
                    "duplicate_id" -> page.getJSONArray("events").getJSONObject(1)
                        .put("id", page.getJSONArray("events").getJSONObject(0).getString("id"))

                    else -> page.put("membership_sha256", "bad")
                }
                RoadSummaryPreviewActivity.previewFontScale = 1f
                RoadSummaryPreviewActivity.liveData = page
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        RoadSummaryPreviewActivity::class.java
                    ).putExtra("live", true).putExtra("group_sources", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                capture("road-group-$mode-v199.png", "원본 목록 확인 실패")
                check(!labels().contains("DEMO-OWN-01"))
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            val car =
                targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)
            if (car) {
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        MainActivity::class.java
                    ).putExtra("review_road_summary", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                capture("road-group-main-summary-v199.png", "도로 관측 모아 보기")
                fun open(offset: Int, snapshot: String?) {
                    val end = SystemClock.elapsedRealtime() + 10000
                    while (SystemClock.elapsedRealtime() < end) {
                        var busy = true
                        runOnMainSync {
                            busy = MainActivity::class.java.getDeclaredField("busy")
                                .apply { isAccessible = true }.getBoolean(screen)
                        }
                        if (!busy) break
                        Thread.sleep(80)
                    }
                    runOnMainSync {
                        MainActivity::class.java.getDeclaredMethod(
                            "openRoadGroup",
                            String::class.java,
                            Int::class.javaPrimitiveType,
                            String::class.java
                        ).apply { isAccessible = true }.invoke(screen, group, offset, snapshot)
                    }
                }
                open(0, null)
                await("원본 1–10 / 12건")
                capture("road-group-main-first-v199.png", "묶음의 원본 관측")
                open(10, first.getString("membership_sha256"))
                await("원본 11–12 / 12건")
                capture("road-group-main-second-v199.png", "묶음의 원본 관측")
                runOnMainSync {
                    MainActivity::class.java.getDeclaredMethod(
                        "openRoadGroupEvent",
                        String::class.java
                    ).apply { isAccessible = true }.invoke(screen, sourceId)
                    check(
                        MainActivity::class.java.getDeclaredField("opened")
                            .apply { isAccessible = true }.get(screen) == null
                    )
                }
                open(0, null)
                await("원본 1–10 / 12건")
                val ready = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < ready) {
                    var busy = true
                    runOnMainSync {
                        busy = MainActivity::class.java.getDeclaredField("busy")
                            .apply { isAccessible = true }.getBoolean(screen)
                    }
                    if (!busy) break
                    Thread.sleep(80)
                }
                runOnMainSync {
                    MainActivity::class.java.getDeclaredMethod(
                        "openRoadGroupEvent",
                        String::class.java
                    ).apply { isAccessible = true }.invoke(screen, sourceId)
                }
                capture("road-group-main-source-v199.png", "도로 근거 정리")
                check(labels().contains("묶음 원본으로 돌아가기"))
                runOnMainSync {
                    MainActivity::class.java.getDeclaredMethod("closeEventDetail")
                        .apply { isAccessible = true }.invoke(screen)
                }
                await("원본 1–10 / 12건")
                capture("road-group-main-returned-v199.png", "묶음의 원본 관측")
                open(10, "0".repeat(64))
                await("원본 목록 확인 실패")
                capture("road-group-main-changed-v199.png", "원본 목록 확인 실패")
                check(!labels().contains("DEMO-OWN-01"))
            }
            File(
                targetContext.filesDir,
                "road-group-live-result-v199.json"
            ).writeText(
                JSONObject().put("summary", summary).put("first", first).put("second", second)
                    .put("states", states).put("invalid_pages_rejected", 3)
                    .put("source_event_id", sourceId).put("actual_main_method_routes", car)
                    .put("ui_taps", false).put("scene_inference", false).toString(2)
            )
            result.putString(
                "stream",
                "PASS: actual backend summary16/13/1/2, anonymous groups, exact12 sources on10+2 pages,9 native display modes,3 invalid-page refusals and current car Main method routes. No control taps, camera inference or agency/repair claim.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            uiAutomation.takeScreenshot()?.let { b ->
                File(targetContext.filesDir, "road-group-failed-v199.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
            RoadSummaryPreviewActivity.previewFontScale = 1f
            RoadSummaryPreviewActivity.liveData = null
        }
    }
}
