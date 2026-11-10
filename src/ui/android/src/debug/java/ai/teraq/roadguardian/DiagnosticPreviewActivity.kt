package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import org.json.JSONObject

/** Server-generated synthetic diagnostic and workshop states; no real workshop contact. */
class DiagnosticPreviewActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val cases = JSONObject(
            assets.open("diagnostic-cases-v135.json").bufferedReader().use { it.readText() })
        val event = cases.getJSONObject(intent.getStringExtra("case") ?: "recovered")
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        val body = ui.install()
        body.addView(ui.text("합성 시험 · 실제 차량 측정·정비소 접수 아님", 16f))
        DiagnosticCasePanel.render(ui, body, event)
    }
}
