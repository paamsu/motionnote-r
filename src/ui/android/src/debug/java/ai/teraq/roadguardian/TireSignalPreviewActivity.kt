package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout

/** Actual installed SDK connection preview. No synthetic pressure input or fallback. */
class TireSignalPreviewActivity : Activity() {
    private lateinit var adapter: PleosAdapter
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        adapter = PleosAdapter(this, {}, { _, _ -> })
        adapter.start()
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        val body = ui.install()
        body.addView(ui.text("실제 SDK 연결 시험 · 에뮬레이터 차량 서비스", 16f))
        body.addView(ui.text("타이어 신호 연결", 28f, true))
        body.addView(
            TireSignalGraphic(this, ui.light) { adapter.tireSnapshot() },
            LinearLayout.LayoutParams(-1, ui.dp(240))
        )
        body.addView(ui.text("값 수신은 안전 판정이 아닙니다. 단위·차종별 기준 확인 전에는 압력 수치를 안내하지 않습니다.", 17f))
    }

    override fun onResume() {
        super.onResume()
        adapter.startTireMonitoring()
    }

    override fun onPause() {
        adapter.stopTireMonitoring()
        super.onPause()
    }

    override fun onDestroy() {
        adapter.close()
        super.onDestroy()
    }
}
