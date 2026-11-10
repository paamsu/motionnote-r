package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import org.json.JSONObject

/** Synthetic read-only preview; does not dial or dispatch. */
class EmergencyCasePreviewActivity : Activity() {
    companion object {
        var font = 1f
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(
            base.createConfigurationContext(
                android.content.res.Configuration(
                    base.resources.configuration
                ).apply { fontScale = font })
        )
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val cases = JSONObject(
            assets.open("emergency-cases-v137.json").bufferedReader().use { it.readText() })
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        val body = ui.install()
        body.addView(ui.text("합성 긴급 요청 · 실제 기관 전송 없음", 16f))
        EmergencyCasePanel.render(
            ui,
            body,
            cases.getJSONObject(intent.getStringExtra("case") ?: "rescue")
        )
    }
}
