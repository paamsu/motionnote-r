package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray

/** Read-only synthetic development-server connectivity; never sends credentials. */
class BackendConnectionProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val events = JSONArray(BackendClient().request("/api/events"))
            result.putString(
                "stream",
                "PASS: development server returned ${events.length()} synthetic records\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
