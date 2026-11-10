package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import org.json.JSONObject

/** Real server-generated synthetic case states, rendered without contacting an insurer. */
class CrashCasePreviewActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val cases =
            JSONObject(assets.open("crash-cases-v133.json").bufferedReader().use { it.readText() })
        val key = intent.getStringExtra("case") ?: "acknowledged"
        val event = cases.getJSONObject(key)
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        val body = ui.install()
        // This preview focuses on the new panel; production details also show vehicle context.
        body.addView(ui.text("합성 사고 사례 · 실제 보험사 전송 없음", 16f))
        CrashCasePanel.render(ui, body, event)
    }
}
