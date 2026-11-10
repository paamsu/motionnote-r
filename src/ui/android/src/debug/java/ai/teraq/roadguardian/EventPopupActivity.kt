package ai.teraq.roadguardian

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import org.json.JSONObject

/** Translucent event window: the current app remains behind it; no screenshot imitation. */
class EventPopupActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val event = runCatching { JSONObject(intent.getStringExtra("event") ?: "") }.getOrNull()
        val brief = DriverBriefPanel.verifiedBrief(event)
        if (brief == null) {
            finish()
            return
        }
        val light =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        val ui = PhoneAccountUi(this, light)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ui.dp(48), ui.dp(40), ui.dp(48), ui.dp(28))
            background = GradientDrawable().apply {
                setColor(PleosPalette.background(light))
                cornerRadius = ui.dp(40).toFloat()
            }
        }
        body.addView(
            DriverEventGraphic(this, brief.first, light, brief.second.voltageLow),
            LinearLayout.LayoutParams(-1, ui.dp(128))
        )
        body.addView(ui.text(brief.second.title, 40f, true).apply { gravity = Gravity.CENTER })
        val message = when (brief.second.title) {
            "포트홀 감지" -> "기록 확보 완료"
            "사고 감지" -> "보험사 연결 안내 준비"
            "저전압 확인" -> "근처 서비스센터 안내 준비"
            "저전압 다시 감지" -> "서비스센터 방문을 추천합니다"
            "전방 시야 저하" -> "속도를 줄이고 거리를 확보하세요"
            else -> brief.second.next
        }
        body.addView(ui.text(message, 28f).apply { gravity = Gravity.CENTER })
        // Read-only acknowledgement preserves driving restrictions; detail actions stay parked.
        body.addView(ui.button("확인", true) { finish() })
        setContentView(body)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(.25f)
        val width = minOf(ui.dp(960), resources.displayMetrics.widthPixels - ui.dp(96))
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)
        setFinishOnTouchOutside(true)
    }

    companion object {
        fun show(activity: Activity, event: JSONObject) {
            if (DriverBriefPanel.verifiedBrief(event) != null)
                activity.startActivity(
                    Intent(
                        activity,
                        EventPopupActivity::class.java
                    ).putExtra("event", event.toString())
                )
        }
    }
}
