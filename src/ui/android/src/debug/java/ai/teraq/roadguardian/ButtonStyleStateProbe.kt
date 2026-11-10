package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService

/** Component-state fixture only. Never starts MainActivity lifecycle or invokes its actions. */
class ButtonStyleStateFixtureActivity : Activity() {
    lateinit var body: LinearLayout
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(BuildConfig.DEBUG && packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(body)
    }
}

class ButtonStyleStateProbe : Instrumentation() {
    private var night = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        night = arguments?.getString("mode") == "night"
        start()
    }

    override fun onStart() {
        val output = Bundle()
        val rows = JSONArray()
        var screen: ButtonStyleStateFixtureActivity? = null
        var detached: MainActivity? = null
        var callbackCount = 0
        val mode = if (night) "night" else "day"
        val report = JSONObject().put(
            "scope",
            "actual Android component-state fixture, not MainActivity screen or driving-flow validation"
        )
            .put("mode", mode).put("main_lifecycle_started", false).put("actions_invoked", false)
        val directory = File(targetContext.filesDir, "button-style-v647-$mode")
        fun onUi(action: () -> Unit) {
            var error: Throwable? = null
            runOnMainSync {
                try {
                    action()
                } catch (t: Throwable) {
                    error = t
                }
            }
            error?.let { throw it }
        }
        try {
            check(BuildConfig.DEBUG) { "Debug build required" }
            check(targetContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) { "Automotive device required" }
            check(directory.isDirectory || directory.mkdirs())
            val host = startActivitySync(
                Intent(
                    targetContext,
                    ButtonStyleStateFixtureActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as ButtonStyleStateFixtureActivity
            screen = host
            waitForIdleSync()
            val method = MainActivity::class.java.getDeclaredMethod(
                "button",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                kotlin.jvm.functions.Function0::class.java
            ).apply { isAccessible = true }
            val lightField =
                MainActivity::class.java.getDeclaredField("light").apply { isAccessible = true }
            onUi {
                // Public Instrumentation API attaches a context/window, without calling onCreate/onResume.
                val info = ActivityInfo().apply {
                    applicationInfo = host.applicationInfo
                    theme = host.applicationInfo.theme
                }

                @Suppress("DEPRECATION")
                val controller = newActivity(
                    MainActivity::class.java,
                    host,
                    null,
                    host.application,
                    Intent(host, MainActivity::class.java),
                    info,
                    "button fixture",
                    null,
                    "button-fixture",
                    null
                ) as MainActivity
                detached = controller
                lightField.setBoolean(controller, !night)
                host.window.statusBarColor = PleosPalette.background(!night)
                host.window.navigationBarColor = PleosPalette.background(!night)
            }
            val ui = PhoneAccountUi(host, !night)
            val noAction: () -> Unit = { callbackCount++ }
            val states = listOf("default", "pressed", "focused", "disabled")
            val roles = listOf("primary", "basic", "neutralText", "compact")
            for (role in roles) for (state in states) {
                val buttons = mutableListOf<Pair<String, Button>>()
                onUi {
                    host.body.removeAllViews()
                    host.body.setBackgroundColor(PleosPalette.background(!night))
                    host.body.addView(ui.text("버튼 상태 fixture · $role · $state", 20f, true))
                    host.body.addView(ui.text("실제 헬퍼 · 콜백 실행 안 함", 16f))
                    val pair = role == "primary" || role == "basic"
                    val controls = LinearLayout(host).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    host.body.addView(controls, LinearLayout.LayoutParams(-1, 0, 1f))
                    val sources = if (pair) listOf(
                        "MainActivity",
                        "PhoneAccountUi"
                    ) else listOf("PhoneAccountUi")
                    for (source in sources) {
                        val column = LinearLayout(host).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        controls.addView(
                            column,
                            LinearLayout.LayoutParams(0, -1, 1f)
                                .apply { setMargins(ui.dp(8), 0, ui.dp(8), 0) })
                        column.addView(ui.text(source, 16f))
                        val label =
                            if (role == "compact") "새로 확인" else if (role == "neutralText") "상세 기록" else "기록 확인"
                        val control = when {
                            source == "MainActivity" -> method.invoke(
                                detached,
                                label,
                                role == "primary",
                                noAction
                            ) as Button

                            role == "neutralText" -> ui.neutralTextButton(label, noAction)
                            role == "compact" -> ui.compactButton(label, noAction)
                            else -> ui.button(label, role == "primary", noAction)
                        }
                        // Real helper content and drawable untouched; only fixture layout/state applied.
                        column.addView(
                            control,
                            LinearLayout.LayoutParams(if (role == "compact") ui.dp(142) else -1, -2)
                        )
                        buttons.add(source to control)
                    }
                    host.body.requestFocus()
                    buttons.forEach { (_, b) ->
                        b.isEnabled = state != "disabled"
                        b.isPressed = state == "pressed"
                    }
                    if (state == "focused") buttons.first().second.apply {
                        isFocusableInTouchMode = true
                        check(requestFocus())
                    }
                }
                waitForIdleSync()
                Thread.sleep(250)
                fun capture(suffix: String) {
                    val record = JSONObject().put("role", role).put("state", state)
                        .put("focus_target", suffix)
                    val metrics = JSONArray()
                    var fits = true
                    onUi {
                        for ((source, b) in buttons) {
                            val visible = Rect()
                            val shown = b.getGlobalVisibleRect(visible)
                            check(b.isEnabled == (state != "disabled"))
                            check(b.isPressed == (state == "pressed"))
                            if (state == "focused") check(b.isFocused == (source == suffix))
                            check(b.background is android.graphics.drawable.RippleDrawable)
                            val textLayout = b.layout
                            val lines = JSONArray()
                            var textFits = textLayout != null
                            if (textLayout != null) {
                                for (i in 0 until textLayout.lineCount) {
                                    val width = textLayout.getLineMax(i)
                                    val lineFits =
                                        textLayout.getEllipsisCount(i) == 0 && width <= b.width - b.compoundPaddingLeft - b.compoundPaddingRight + 2
                                    lines.put(
                                        JSONObject().put("width_px", width).put("fits", lineFits)
                                    )
                                    textFits = textFits && lineFits
                                }
                                textFits =
                                    textFits && textLayout.height <= b.height - b.compoundPaddingTop - b.compoundPaddingBottom + 2
                            }
                            val viewFits =
                                shown && visible.width() == b.width && visible.height() == b.height
                            fits = fits && textFits && viewFits
                            metrics.put(
                                JSONObject().put("source", source).put("width_px", b.width)
                                    .put("height_px", b.height)
                                    .put("text_size_px", b.textSize).put("lines", lines)
                                    .put("text_fits", textFits).put("view_fits", viewFits)
                                    .put("enabled", b.isEnabled).put("pressed", b.isPressed)
                                    .put("focused", b.isFocused)
                                    .put("text_color", b.currentTextColor)
                                    .put("background_class", b.background.javaClass.name)
                            )
                        }
                        check(callbackCount == 0) { "A callback unexpectedly executed" }
                    }
                    val file = File(directory, "$role-$state-$suffix.png")
                    checkNotNull(uiAutomation.takeScreenshot()).let { bitmap ->
                        file.outputStream()
                            .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        bitmap.recycle()
                    }
                    rows.put(
                        record.put("controls", metrics).put("fits", fits)
                            .put("screenshot", file.absolutePath)
                    )
                }
                capture(if (state == "focused") buttons.first().first else "both")
                // Only one real View can own focus; capture the other helper separately.
                if (state == "focused" && buttons.size == 2) {
                    onUi {
                        buttons[1].second.apply {
                            isFocusableInTouchMode = true
                            check(requestFocus())
                        }
                    }
                    waitForIdleSync()
                    Thread.sleep(250)
                    capture(buttons[1].first)
                }
            }
            val allFit = (0 until rows.length()).all { rows.getJSONObject(it).getBoolean("fits") }
            report.put(
                "status",
                if (allFit) "CAPTURED_AWAITING_VISUAL_REVIEW" else "LAYOUT_FIT_FAILURE"
            )
                .put("all_controls_fit", allFit).put("callback_count", callbackCount)
                .put("font_scale", targetContext.resources.configuration.fontScale)
            output.putString(
                "stream",
                "${report.getString("status")}: 16 role/state combinations, 18 PNGs; actual helpers, no callbacks, no MainActivity lifecycle. Human image review required.\n"
            )
            output.putInt(
                "result_code",
                if (allFit) Activity.RESULT_OK else Activity.RESULT_CANCELED
            )
        } catch (t: Throwable) {
            report.put("status", "ERROR").put("error", t.stackTraceToString())
            output.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            output.putInt("result_code", Activity.RESULT_CANCELED)
        } finally {
            report.put("captures", rows).put("callback_count", callbackCount)
            if (directory.isDirectory) File(directory, "result.json").writeText(report.toString(2))
            onUi {
                detached?.let {
                    (MainActivity::class.java.getDeclaredField("executor")
                        .apply { isAccessible = true }.get(it) as ExecutorService).shutdownNow()
                }
                screen?.finish()
            }
        }
        finish(output.getInt("result_code", Activity.RESULT_CANCELED), output)
    }
}
