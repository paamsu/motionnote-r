package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Runs the real foreground expiry timer using a short synthetic display lease. */
class AccountDisplayExpiryProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun field(name: String) =
        AccountEventsActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun text(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { text(v.getChildAt(it)) }
        else -> emptyList()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            val screen = startActivitySync(
                Intent(
                    targetContext,
                    AccountEventsActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            activity = screen
            waitForIdleSync()
            val marker = "SYNTHETIC_PRIVATE_RECORD"
            runOnMainSync {
                val generation = field("generation")
                generation.setLong(screen, generation.getLong(screen) + 1)
                val body = field("body").get(screen) as LinearLayout
                body.removeAllViews()
                body.addView(TextView(screen).apply { text = marker })
                field("displayLease").set(
                    screen,
                    AccountDisplayLease(
                        System.currentTimeMillis() + 32000,
                        System.currentTimeMillis(),
                        SystemClock.elapsedRealtime()
                    )
                )
                check(marker in text(screen.window.decorView))
            }
            Thread.sleep(3500)
            waitForIdleSync()
            runOnMainSync {
                val visible = text(screen.window.decorView)
                check(marker !in visible) { "Expired private text remained" }
                check(visible.any { it.contains("로그인 시간이 만료되어") })
                check(field("displayLease").get(screen) == null)
            }
            result.putString(
                "stream",
                "PASS: foreground timer removed synthetic private record after two-second lease and displayed reconnect guidance. No real login/network tested.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
