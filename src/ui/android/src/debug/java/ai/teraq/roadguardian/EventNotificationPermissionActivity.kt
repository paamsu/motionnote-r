package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle

class EventNotificationPermissionActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = PhoneAccountUi(this)
        val body = ui.install()
        body.addView(ui.text("이벤트 알림 권한", 30f, true))
        body.addView(ui.text("현재 음악·내비 화면 위에 알림을 표시합니다."))
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            348
        ) else finish()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, p, results)
        finish()
    }
}
