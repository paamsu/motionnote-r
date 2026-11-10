package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle

/** Integration-only read renderer. It neither captures SDK data nor bypasses a production Activity. */
class ObservationPanelPreviewActivity : Activity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val id = AccountEventRoute.checked(intent.getStringExtra("event_id"))
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        val body = ui.install()
        ui.heading(body, "추가 관측 렌더 검사", "합성 계정의 실제 HTTPS 조회 · 차량 조작 검사가 아닙니다.")
        body.addView(SdkObservationsPanel(this, ui, id, true, { !isFinishing && !isDestroyed }))
    }

    override fun onPause() {
        (findViewById<android.view.ViewGroup>(android.R.id.content)).removeAllViews()
        super.onPause()
    }
}
