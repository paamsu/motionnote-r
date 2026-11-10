package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import org.json.JSONObject

/** Synthetic component preview only; actual contest Main retains SDK/OEM display restrictions. */
class TrafficPreviewActivity : Activity() {
    companion object {
        var previewFontScale = 1f
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(
            base.createConfigurationContext(
                android.content.res.Configuration(
                    base.resources.configuration
                ).apply { fontScale = previewFontScale })
        )
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val kind = intent.getStringExtra("case") ?: "violation"
        require(kind in setOf("violation", "wanted", "reckless"))
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        val body = ui.install()
        val event = JSONObject(
            assets.open(
                if (kind == "reckless" && intent.getBooleanExtra(
                        "decoded",
                        false
                    )
                ) "traffic-reckless-v192.json" else "traffic-$kind-${if (kind == "reckless") "v184" else "v183"}.json"
            ).bufferedReader().use { it.readText() })
        if (intent.getBooleanExtra("invalid", false)) event.put("evidence_valid", false)
        if (intent.getBooleanExtra("unproved", false)) event.getJSONObject("traffic_review")
            .remove("recognition_performed")
        TrafficCasePanel.render(ui, body, event)
    }
}
