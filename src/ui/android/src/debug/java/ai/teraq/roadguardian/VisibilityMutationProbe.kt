package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

class VisibilityMutationProbe : Instrumentation() {
    private var suite = "visibility"
    override fun onCreate(a: Bundle?) {
        super.onCreate(a)
        suite = a?.getString("suite") ?: "visibility"
        require(suite in setOf("visibility", "collision"))
        start()
    }

    override fun onStart() {
        val response = Bundle()
        try {
            val dir = File(targetContext.filesDir, "$suite-mutations")
            val manifest = JSONObject(File(dir, "manifest.json").readText())
            val cases = manifest.getJSONArray("cases")
            val results = JSONArray()
            var passed = 0
            for (i in 0 until cases.length()) {
                val c = cases.getJSONObject(i)
                val accepted = runCatching {
                    EvidencePackageVerifier.verify(
                        File(dir, c.getString("file")),
                        manifest.getString("event_id")
                    )
                }.isSuccess
                val match = accepted == c.getBoolean("expected_accept")
                if (match) passed++
                results.put(
                    JSONObject().put("name", c.getString("name")).put("accepted", accepted)
                        .put("expected_accept", c.getBoolean("expected_accept")).put("pass", match)
                )
            }
            File(targetContext.filesDir, "$suite-mutations-result.json").writeText(
                JSONObject().put(
                    "passed",
                    passed
                ).put("total", cases.length()).put("cases", results).toString(2)
            )
            response.putString("stream", "Visibility mutation cases: $passed/${cases.length()}\n")
            finish(
                if (passed == cases.length()) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                response
            )
        } catch (e: Throwable) {
            response.putString("stream", e.stackTraceToString())
            finish(Activity.RESULT_CANCELED, response)
        }
    }
}
