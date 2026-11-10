package ai.teraq.roadguardian

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.LinearLayout

/** 휴대폰 응급 연락 화면. 통화는 시스템 전화 앱이 맡는다 */
open class EmergencyHelpActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            finish()
            return
        }
        val ui = PhoneAccountUi(this)
        val root = ui.install()
        root.addView(ui.button("‹ 내 차량으로") { finish() })
        ui.heading(root, "긴급 도움", "구조·구급은 119, 경찰 도움이 필요하면 112로 연락하세요.")
        val status = ui.notice("버튼을 누르면 전화 앱이 열립니다. 통화 시작은 전화 앱에서 확인하세요.")
        val choices = LinearLayout(this).apply {
            orientation =
                if (resources.configuration.screenWidthDp >= 600 && resources.configuration.fontScale < 1.5f) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        for ((number, title) in listOf("119" to "119  구조·구급", "112" to "112  경찰")) {
            val contact = ui.button(title, true) {
                status.text =
                    if (EmergencyContact.open(this, number)) "전화 앱으로 이동했습니다. 연결 여부는 전화 앱에서 확인하세요."
                    else "전화 앱을 열 수 없습니다. 다른 전화로 ${number}에 연락하세요."
            }
            choices.addView(
                contact,
                if (choices.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(
                    0,
                    -2,
                    1f
                ).apply {
                    marginEnd = (12 * resources.displayMetrics.density).toInt()
                } else LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(choices)
        root.addView(status)
        root.addView(ui.text("통화 시 알려 주세요", 22f, true))
        root.addView(ui.text("현재 위치와 진행 방향\n사고·위험 상황과 다친 사람 수\n차량 번호·차종·색상", 18f))
        root.addView(ui.notice("이 화면은 전화 연결을 돕습니다. 기관 자동 접수·위치 및 영상 전송·보험사 접수는 연결되지 않았습니다."))
    }
}
