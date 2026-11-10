package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout

/** Real SDK overview preview. No synthetic vehicle signal injection. */
class VehicleSignalsPreviewActivity : Activity() {
    private lateinit var adapter: PleosAdapter
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        adapter = PleosAdapter(this, {}, { _, _ -> })
        adapter.start()
        val ui = PhoneAccountUi(this, !intent.getBooleanExtra("night", false))
        val body = ui.install()
        body.addView(ui.text("차량 신호", 32f, true))
        body.addView(
            VehicleSignalsPanel(this, ui.light) { adapter.diagnosticSnapshot() },
            LinearLayout.LayoutParams(-1, -2)
        )
        body.addView(ui.text("공식 Pleos 에뮬레이터 · 실제 SDK 응답을 표시하는 개발 화면", 16f))
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
