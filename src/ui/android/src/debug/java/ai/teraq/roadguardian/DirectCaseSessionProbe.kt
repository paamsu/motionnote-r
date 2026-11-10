package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keeps a synthetic renderer open for CUA input. Observes changes; never activates controls. */
class DirectCaseSessionProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            screen = startActivitySync(
                Intent(targetContext, AccountDetailPreviewActivity::class.java)
                    .putExtra("dossier_fixture", "crash").putExtra("night", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            val observed = JSONArray()
            var previous = ""
            val started = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - started < 90000 && !screen.isFinishing) {
                val labels = mutableListOf<String>()
                runOnMainSync {
                    fun visit(view: View) {
                        if (view is TextView && view.isShown) labels.add(view.text.toString())
                        if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                            view.getChildAt(
                                i
                            )
                        )
                    }
                    visit(checkNotNull(screen).window.decorView)
                }
                val current = labels.joinToString("\n")
                if (current != previous) {
                    observed.put(
                        JSONObject().put(
                            "elapsed_ms",
                            SystemClock.elapsedRealtime() - started
                        ).put("visible_text", current)
                    )
                    previous = current
                }
                Thread.sleep(200)
            }
            File(targetContext.filesDir, "direct-case-observations-v172.json").writeText(
                JSONObject()
                    .put("synthetic_renderer", true).put("instrumentation_injected_input", false)
                    .put("observations", observed).toString(2)
            )
            result.putString(
                "stream",
                "PASS: observed real renderer session without instrumentation input. CUA actions and video require separate review.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", "FAIL: ${failure.javaClass.simpleName}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
