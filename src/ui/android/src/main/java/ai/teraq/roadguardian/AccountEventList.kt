package ai.teraq.roadguardian

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 목록 그리기만 맡는다. 인증, 페이지 넘김, 상세 요청은 호출하는 쪽이 처리한다 */
object AccountEventList {
    fun render(
        ui: PhoneAccountUi,
        body: LinearLayout,
        events: JSONArray,
        uiScale: Float = 1f,
        open: (String) -> Unit
    ) {
        fun dp(value: Int) = ui.dp(kotlin.math.round(value * uiScale).toInt())
        if (events.length() == 0) {
            body.addView(ui.notice("이 목록에는 표시할 수 있는 사건이 없습니다."))
            return
        }
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val id = event.optString("id")
            val timestamp = event.optDouble("created", Double.NaN)
            val date = if (timestamp.isFinite() && timestamp > 0)
                SimpleDateFormat(
                    "yyyy년 M월 d일  HH:mm",
                    Locale.KOREAN
                ).format(Date((timestamp * 1000).toLong()))
            else "발생 시각 확인 필요"
            val title =
                event.optJSONObject("assessment")?.optString("title")?.takeIf { it.isNotBlank() }
                    ?: "사건 기록"
            val source = when {
                event.optString("source") == "owner_reported" -> "본인 작성 · 사실 확인 전"
                event.optString("source").contains("synthetic", true) -> "합성 시험 자료"
                else -> "서버 수신 기록"
            }
            val card = LinearLayout(body.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(18))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
                val shape = GradientDrawable().apply {
                    setColor(ui.cardColor)
                    cornerRadius = dp(14).toFloat()
                }
                background =
                    RippleDrawable(ColorStateList.valueOf(Color.rgb(221, 232, 246)), shape, null)
                isFocusable = true
                isClickable = true
                contentDescription = "$date, $title, $source, 상세 보기"
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfo
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.Button"
                    }
                }
                setOnClickListener { open(id) }
            }
            card.addView(ui.text(date, 15f * uiScale).apply { setTextColor(ui.muted) })
            card.addView(ui.text(title, 21f * uiScale, true).apply {
                textLocale = Locale.KOREAN
                breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    lineBreakWordStyle =
                        android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
            })
            card.addView(ui.text("$source · ${id.take(8)}\n상세 보기 ›", 16f * uiScale).apply {
                setTextColor(ui.muted);
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
            })
            for (child in 0 until card.childCount) card.getChildAt(child).importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
            body.addView(card)
        }
    }
}
