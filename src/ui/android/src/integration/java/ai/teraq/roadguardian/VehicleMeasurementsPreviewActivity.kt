package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle

/** Integration-only host. Production MainActivity supplies real parked/foreground/OEM restrictions. */
class VehicleMeasurementsPreviewActivity : Activity() {
    companion object {
        var fontScale = 1f
    }

    var displayAllowed = true
    override fun attachBaseContext(base: android.content.Context) {
        val configuration = android.content.res.Configuration(base.resources.configuration)
            .apply { fontScale = VehicleMeasurementsPreviewActivity.fontScale }
        super.attachBaseContext(base.createConfigurationContext(configuration))
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", true))
        val body = ui.install()
        body.addView(ui.text("합성 측정 · 정차 조건의 통합 렌더 검사", 17f))
        body.addView(
            VehicleMeasurementsPanel(
                this,
                ui,
                AccountEventRoute.checked(intent.getStringExtra("event_id")),
                checkNotNull(intent.getStringExtra("case_hash"))
            ) { displayAllowed && !isFinishing && !isDestroyed })
    }

    override fun onPause() {
        findViewById<android.view.ViewGroup>(android.R.id.content).removeAllViews()
        super.onPause()
    }
}
