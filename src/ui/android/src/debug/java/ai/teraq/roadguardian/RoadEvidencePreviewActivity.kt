package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import org.json.JSONObject

/** Server-generated synthetic review fixtures; no agency submission. */
class RoadEvidencePreviewActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val cases = JSONObject(
            assets.open("road-evidence-v140.json").bufferedReader().use { it.readText() })
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        val body = ui.install()
        RoadEvidencePanel.render(
            ui,
            body,
            cases.getJSONObject(intent.getStringExtra("case") ?: "waiting")
        )
    }
}
