package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import org.json.JSONObject

/** Debug-only renderer fixture; not an authenticated API test. */
class AccountDetailPreviewActivity : Activity() {
    companion object {
        var previewFontScale = 1f
    }

    override fun attachBaseContext(base: android.content.Context) {
        val configuration = android.content.res.Configuration(base.resources.configuration)
            .apply { fontScale = previewFontScale.coerceIn(1f, 2f) }
        super.attachBaseContext(base.createConfigurationContext(configuration))
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val light = !intent.getBooleanExtra("night", false)
        val ui = PhoneAccountUi(this, light)
        val body = ui.install()
        val dossier =
            intent.getStringExtra("dossier_fixture")?.takeIf { it in setOf("crash", "road") }
        val asset =
            if (dossier == null) "account-detail-fixture.json" else "dossier-$dossier-v149.json"
        val contextFixture = intent.getBooleanExtra("context_fixture", false)
        val event = JSONObject(
            if (contextFixture) java.io.File(filesDir, "detail-context-fixture.json")
                .readText() else assets.open(asset).bufferedReader().use { it.readText() })
        if (dossier != null && intent.getBooleanExtra(
                "invalid_original",
                false
            )
        ) event.put("evidence_valid", false)
        if ((dossier != null || contextFixture) && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE))
            VehicleEventDetail.render(this, body, event, light)
        else AccountEventDetail.render(ui, body, event)
        body.addView(ui.button("미리보기 닫기") { finish() })
    }
}
