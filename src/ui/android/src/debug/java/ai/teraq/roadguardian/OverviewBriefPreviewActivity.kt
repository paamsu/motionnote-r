package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/** Authored standalone parked overview; does not reproduce Main's surrounding chrome. */
class OverviewBriefPreviewActivity : Activity() {
    companion object {
        var font = 1f
    }

    lateinit var body: LinearLayout
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
        body = ui.install()
        VehicleOverviewPanel.render(
            ui,
            body,
            JSONArray().put(JSONObject(intent.getStringExtra("event_json")!!)),
            null,
            null
        ) { }
    }
}
