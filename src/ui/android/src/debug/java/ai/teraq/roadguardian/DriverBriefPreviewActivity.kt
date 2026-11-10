package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** Isolated visual fixture. Never changes a vehicle gear, OEM restriction or Activity approval. */
class DriverBriefPreviewActivity : Activity() {
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

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        val host = FrameLayout(this).apply { setBackgroundColor(PleosPalette.background(ui.light)) }
        setContentView(host)
        DriverBriefPanel.render(
            ui,
            host,
            intent.getStringExtra("state") ?: "unavailable",
            intent.getBooleanExtra("comparison_ready", false),
            intent.getStringExtra("event_json")?.let { org.json.JSONObject(it) })
    }
}
