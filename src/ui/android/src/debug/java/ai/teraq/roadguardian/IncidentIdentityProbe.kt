package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** First-screen identity and uncertainty checks; no UI control activation. */
class IncidentIdentityProbe : Instrumentation() {
    private var font = 1f
    private var night = false
    private var variant = "normal"
    private var contextCheck = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        contextCheck = arguments?.getString("context_check") == "true"
        font = arguments?.getString("font")?.toFloatOrNull() ?: 1f
        night = arguments?.getString("night") == "true"
        variant = arguments?.getString("variant") ?: "normal"
        start()
    }

    override fun onStart() {
        if (contextCheck) {
            runContextCheck()
            return
        }
        val result = Bundle()
        var screen: Activity? = null
        try {
            AccountDetailPreviewActivity.previewFontScale = font
            screen = startActivitySync(
                Intent(
                    targetContext,
                    AccountDetailPreviewActivity::class.java
                ).putExtra("dossier_fixture", "crash")
                    .putExtra("night", night)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            if (variant != "normal") runOnMainSync {
                val event = JSONObject(
                    targetContext.assets.open("dossier-crash-v149.json").bufferedReader()
                        .use { it.readText() })
                val context = event.getJSONObject("data").getJSONObject("context")
                if (variant == "missing") context.put("actors", JSONArray())
                    .put("own_vehicle", JSONObject())
                if (variant == "long") {
                    context.getJSONObject("own_vehicle").put("plate", "시험 차량번호 확인 필요")
                        .put("model", "긴 차종 이름이 전달된 경우의 표시 확인")
                    context.getJSONObject("impact").put("area", "충격 부위 미확인 · 추가 원본 대조 필요")
                    context.getJSONArray("actors").put(
                        JSONObject(
                            context.getJSONArray("actors").getJSONObject(0).toString()
                        ).put("plate", "DEMO-SECOND-02")
                    )
                }
                if (variant == "invalid") event.put("evidence_valid", false)
                val ui = PhoneAccountUi(screen!!, !night)
                val body = ui.install()
                if (targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) VehicleEventDetail.render(
                    screen!!,
                    body,
                    event,
                    !night
                )
                else AccountEventDetail.render(ui, body, event)
            }
            waitForIdleSync()
            Thread.sleep(600)
            val texts = mutableListOf<TextView>()
            val content = mutableListOf<String>()
            val captureTargets = mutableListOf<Rect>()
            var uiFailure: Exception? = null
            runOnMainSync {
                try {
                    fun screenBounds(text: TextView): Rect? {
                        val bounds = Rect()
                        if (!text.getGlobalVisibleRect(bounds)) return null
                        val screenPosition = IntArray(2)
                        val windowPosition = IntArray(2)
                        text.getLocationOnScreen(screenPosition)
                        text.getLocationInWindow(windowPosition)
                        bounds.offset(
                            screenPosition[0] - windowPosition[0],
                            screenPosition[1] - windowPosition[1]
                        )
                        return bounds
                    }

                    fun collect(view: View) {
                        if (view is TextView && view.isShown) {
                            texts.add(view)
                            content.add(view.text.toString())
                        }
                        if (view is ViewGroup) for (i in 0 until view.childCount) collect(
                            view.getChildAt(
                                i
                            )
                        )
                    }
                    collect(screen!!.window.decorView)
                    for (text in texts) {
                        check(text.width - text.paddingLeft - text.paddingRight > 0) { "No content width: text=${text.text}, width=${text.width}, padding=${text.paddingLeft + text.paddingRight}, parent=${text.parent.javaClass.simpleName}" }
                        text.layout?.let { layout ->
                            for (i in 0 until layout.lineCount) check(
                                layout.getEllipsisCount(i) == 0
                            )
                        }
                    }
                    val labels = content.joinToString("\n")
                    when (variant) {
                        "invalid" -> check(
                            labels.contains("원본 확인 실패") && !labels.contains("DEMO-OWN-01") && !labels.contains(
                                "DEMO 7352"
                            )
                        )

                        "missing" -> check(labels.contains("주변 차량 정보 없음") && labels.contains("주변에 차량이 없었다는 뜻은 아닙니다"))
                        "long" -> check(
                            labels.contains("주변 차량 2대 중 첫 기록") && labels.contains("나머지 1대") && labels.contains(
                                "추가 원본 대조 필요"
                            )
                        )

                        else -> {
                            for (label in listOf(
                                "DEMO-OWN-01",
                                "아이오닉 6",
                                "DEMO 7352",
                                "쏘렌토",
                                "접촉 관계 미확정",
                                "가상 차량",
                                "영상 인식 미실행"
                            )) check(labels.contains(label)) { "Missing $label; labels=$labels" }
                            // Default type must show both identities without scrolling; large type intentionally scrolls.
                            if (font < 1.3f) for (label in listOf(
                                "충돌 근거 정리",
                                "DEMO-OWN-01",
                                "DEMO 7352"
                            )) {
                                val text = texts.first { it.text.toString() == label }
                                val visible = Rect()
                                check(text.getGlobalVisibleRect(visible))
                                check(visible.height() >= text.height && visible.width() >= text.width)
                                captureTargets.add(checkNotNull(screenBounds(text)))
                            }
                        }
                    }
                    check(!labels.contains("발생 시각"))
                    if (captureTargets.isEmpty()) texts.take(3)
                        .forEach { screenBounds(it)?.let { bounds -> captureTargets.add(bounds) } }
                } catch (failure: Exception) {
                    uiFailure = failure
                }
            }
            uiFailure?.let { failure ->
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(
                        targetContext.filesDir,
                        "incident-identity-${variant}-${font}-${night}-failure-v171.png"
                    ).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                throw failure
            }
            // View layout can finish before SurfaceFlinger presents it. Require text pixels in each target.
            var rendered: Bitmap? = null
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val candidate = checkNotNull(uiAutomation.takeScreenshot())
                val visible = captureTargets.isNotEmpty() && captureTargets.all { bounds ->
                    val colors = mutableSetOf<Int>()
                    val left = bounds.left.coerceIn(0, candidate.width)
                    val right = bounds.right.coerceIn(left, candidate.width)
                    val top = bounds.top.coerceIn(0, candidate.height)
                    val bottom = bounds.bottom.coerceIn(top, candidate.height)
                    for (y in top until bottom step ((bottom - top) / 40).coerceAtLeast(1))
                        for (x in left until right step ((right - left) / 160).coerceAtLeast(1)) colors.add(
                            candidate.getPixel(x, y)
                        )
                    colors.size > 4
                }
                if (visible) {
                    rendered = candidate
                    break
                }
                candidate.recycle()
                Thread.sleep(50)
            }
            val bitmap =
                checkNotNull(rendered) { "Text layout was ready but screen pixels were not presented; targets=$captureTargets" }
            File(
                targetContext.filesDir,
                "incident-identity-${variant}-${font}-${night}-v171.png"
            ).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            result.putString(
                "stream",
                "PASS: visible vehicle identities, uncertainty, wrapping text and invalid-original hiding; variant=$variant font=$font night=$night. Renderer only; no SDK/network/taps.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            AccountDetailPreviewActivity.previewFontScale = 1f
            runOnMainSync { screen?.finish() }
        }
    }

    private fun runContextCheck() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            val event =
                JSONObject(File(targetContext.filesDir, "detail-context-fixture.json").readText())
            val impact = event.optJSONObject("assessment")?.optString("kind") in setOf(
                "crash",
                "parking",
                "pothole"
            ) || event.optJSONObject("data")?.optJSONObject("context")
                ?.optJSONObject("impact") != null
            screen = startActivitySync(
                Intent(
                    targetContext,
                    AccountDetailPreviewActivity::class.java
                ).putExtra("context_fixture", true).putExtra("night", night)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            val activity = screen!!
            fun texts(): List<TextView> {
                val out = mutableListOf<TextView>()
                fun walk(v: View) {
                    if (!v.isShown) return
                    if (v is TextView) out.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(activity.window.decorView)
                return out
            }

            fun waitFor(test: () -> Boolean) {
                repeat(100) {
                    var ready = false
                    runOnMainSync { ready = test() }
                    if (ready) return
                    Thread.sleep(50)
                }
                error("Layout timeout")
            }
            waitFor { texts().isNotEmpty() }
            runOnMainSync {
                texts().firstOrNull { it.text.toString() == "상세 기록 펼치기" }?.performClick()
            }
            val source = "합성 시연 · 실제 차량 측정 자료 아님"
            waitFor { texts().any { it.text.toString() == source && it.height > 0 } }
            fun capture(label: String, name: String) {
                waitFor {
                    texts().first { it.text.toString() == label }
                        .let { it.width > 0 && it.height > 0 && !it.isLayoutRequested }
                }
                runOnMainSync {
                    texts().first { it.text.toString() == label }.let {
                        it.requestRectangleOnScreen(
                            Rect(
                                0,
                                0,
                                it.width,
                                it.height + (160 * activity.resources.displayMetrics.density).toInt()
                            ), true
                        )
                    }
                }
                Thread.sleep(300)
                uiAutomation.takeScreenshot()?.let { b ->
                    File(targetContext.filesDir, "detail-context-attempt.png").outputStream()
                        .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    b.recycle()
                }
                runOnMainSync {
                    texts().first { it.text.toString() == label }.let { v ->
                        val r = Rect()
                        v.getGlobalVisibleRect(r)
                        File(
                            targetContext.filesDir,
                            "detail-context-bounds.txt"
                        ).writeText("$label width=${v.width} height=${v.height} visible=$r")
                    }
                }
                waitFor {
                    texts().first { it.text.toString() == label }.let { v ->
                        val r = Rect()
                        v.getGlobalVisibleRect(r) && r.height() == v.height && r.width() == v.width && !v.isLayoutRequested
                    }
                }
                Thread.sleep(300)
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, name).outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            capture(source, "detail-context-source.png")
            runOnMainSync {
                texts().firstOrNull { it.text.toString() == "자료와 확인 사항 펼치기" }?.performClick()
            }
            waitFor { if (impact) texts().any { it.text.toString() == "충격 위치" } else true }
            runOnMainSync {
                check(texts().none { it.text.toString().contains("차량·충격 위치") })
                check(texts().any { it.text.toString() == "충격 위치" } == impact)
            }
            if (impact) capture("충격 위치", "detail-context-impact.png")
            File(
                targetContext.filesDir,
                "detail-context-result.json"
            ).writeText(
                JSONObject().put("impact_expected", impact).put("impact_label_matches", true)
                    .put("night", night).put(
                    "renderer",
                    if (targetContext.packageManager.hasSystemFeature("android.hardware.type.automotive")) "vehicle" else "account"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: source context and conditional impact field. Renderer fixture only.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
