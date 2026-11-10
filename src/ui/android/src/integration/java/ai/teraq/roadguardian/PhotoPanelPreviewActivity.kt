package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle

/** Integration-only shared renderer. The production MainActivity supplies actual gear/UX restrictions. */
class PhotoPanelPreviewActivity : Activity() {
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

    var displayAllowed = true
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        val body = ui.install()
        if (intent.getBooleanExtra(
                "photoreal_demo",
                false
            )
        ) body.addView(ui.text("AI 합성 시연 · 실제 촬영 자료 아님", 16f))
        else ui.heading(body, "첨부 사진 렌더 검사", "시험 계정의 HTTPS 조회 · 차량 카드 선택이나 SDK 조작 검사가 아닙니다.")
        body.addView(
            CasePhotosPanel(
                this,
                ui,
                AccountEventRoute.checked(intent.getStringExtra("event_id"))
            ) { displayAllowed && !isFinishing && !isDestroyed })
    }

    override fun onPause() {
        findViewById<android.view.ViewGroup>(android.R.id.content).removeAllViews()
        super.onPause()
    }
}
