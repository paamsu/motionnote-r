package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Current production components with explicit local synthetic fixtures; no account or dispatch. */
class ShowcasePreviewActivity : Activity() {
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

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        val body = ui.install()
        body.addView(ui.text("합성 시험 자료 · 실제 기관 접수·도로 보수 확인 아님", 16f))
        if (intent.getStringExtra("case") == "workspace") {
            body.addView(ui.text("사건 근거", 30f, true))
            val events = JSONArray(
                assets.open("case-workspace-v144.json").bufferedReader().use { it.readText() })
            VehicleCaseWorkspace.render(ui, body, events, null, null) {}
        } else {
            val event = JSONObject(
                assets.open("showcase-road-followup-v174.json").bufferedReader()
                    .use { it.readText() })
            if (intent.getStringExtra("case") == "waiting") event.getJSONObject("road_followup_case")
                .put("stage", "awaiting_observation")
            RoadFollowupPanel.render(ui, body, event)
        }
    }
}
