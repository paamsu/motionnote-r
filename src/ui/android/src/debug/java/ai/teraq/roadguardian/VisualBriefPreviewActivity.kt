package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import org.json.JSONObject

class VisualBriefPreviewActivity : Activity() {
    companion object {
        var fontScaleOverride = 1f
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(
            base.createConfigurationContext(
                android.content.res.Configuration(
                    base.resources.configuration
                ).apply { fontScale = fontScaleOverride })
        )
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        val body = ui.install()
        if (intent.getBooleanExtra("diagnostic_review", false)) DiagnosticEvidenceReview.render(
            ui,
            body,
            JSONObject(intent.getStringExtra("event")!!)
        ) else VehicleEventDetail.render(
            this,
            body,
            JSONObject(intent.getStringExtra("event")!!),
            ui.light,
            client = if (intent.getBooleanExtra(
                    "surround_test",
                    false
                )
            ) BackendClient("http://127.0.0.1:9470") else null,
            allowed = { !isFinishing && !isDestroyed })
    }
}
