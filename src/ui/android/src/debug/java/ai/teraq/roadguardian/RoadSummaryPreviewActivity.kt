package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import org.json.JSONObject

class RoadSummaryPreviewActivity : Activity() {
    companion object {
        var previewFontScale = 1f
        var liveData: JSONObject? = null
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
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        val body = ui.install()
        val event = if (intent.getBooleanExtra(
                "live",
                false
            )
        ) JSONObject(checkNotNull(liveData).toString()) else JSONObject(
            assets.open("road-summary-v186.json").bufferedReader().use { it.readText() })
        if (intent.getBooleanExtra("empty", false)) {
            for (key in listOf(
                "input_count",
                "included_count",
                "pending_count",
                "excluded_count",
                "group_count"
            )) event.put(key, 0)
            event.put("groups", org.json.JSONArray())
        }
        if (intent.getBooleanExtra("invalid", false)) event.put("included_count", 99)
        if (intent.getBooleanExtra("group_sources", false)) RoadGroupPanel.render(
            ui,
            body,
            event,
            open = {},
            next = {},
            back = { finish() })
        else RoadSummaryPanel.render(ui, body, event, openGroup = {})
    }
}
