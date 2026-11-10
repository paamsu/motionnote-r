package ai.teraq.roadguardian

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject

/** 앱 안 시연 내비 위에만 올리는 안내. OEM이나 다른 내비 창은 건드리지 않는다 */
internal class DriverNavigationOverlay(
    private val root: FrameLayout,
    private val ui: PhoneAccountUi
) {
    private val main = Handler(Looper.getMainLooper())
    private var card: LinearLayout? = null
    private val shown = mutableSetOf<String>()
    private var disposed = false
    private val timeout = Runnable { dismiss() }
    private val boundsListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resize() }

    init {
        root.addOnLayoutChangeListener(boundsListener)
    }

    private fun resize() {
        val current = card ?: return
        val width = (root.width - root.paddingLeft - root.paddingRight - ui.dp(48)).coerceAtLeast(0)
        if (width == 0) return
        val target = minOf(ui.dp(800), width)
        val params = current.layoutParams as FrameLayout.LayoutParams
        if (params.width != target) {
            params.width = target
            current.layoutParams = params
        }
    }

    fun show(event: JSONObject): Boolean {
        if (disposed || !root.isAttachedToWindow) return false
        val key = DriverEventNotification.identity(event) ?: return false
        val brief = DriverBriefPanel.verifiedBrief(event) ?: return false
        if (key in shown) return true
        dismiss()
        // 작성된 목격 정보는 차 밖의 사람을 가리킨다. 운전자 상태와는 관계없다
        val witness = WitnessCasePanel.eligible(event)
        val autoDone =
            WitnessCasePanel.automatic(event) && DriverEventNoticeState.helpDeliveryState(event) == "acknowledged"
        val driverAuto = EventBriefState.authoredDriverEmergency(event)
        val driverMedia = EventBriefState.driverEmergencyMediaValid(event)
        val driverDone =
            driverAuto && driverMedia && DriverEventNoticeState.helpDeliveryState(event) == "acknowledged"
        val wantedAuto = WantedAutoNotice.received(event)
        val trafficAuto = TrafficZoneAutoNotice.received(event)
        val title =
            if (trafficAuto) TrafficZoneAutoNotice.summary(event).title else if (wantedAuto) WantedAutoNotice.summary(
                event
            ).title else if (driverDone) "운전자 119 신고 완료" else if (autoDone) "주변 사람 119 신고 완료" else if (witness) "주변 사람 도움 요청" else brief.second.title
        val helpNumber =
            event.optJSONObject("assessment")?.optString("recipient")?.removeSuffix("-mock")
        val status =
            if (trafficAuto) TrafficZoneAutoNotice.summary(event).next else if (wantedAuto) WantedAutoNotice.summary(
                event
            ).next else if (driverDone || autoDone) "위치·현장 기록 확보" else if (driverAuto && !driverMedia) "현장 자료 확인 필요" else when (brief.second.title) {
                "사고 감지" -> "기록 확보 완료 · 정차 후 보험사 연결"
                "포트홀 감지" -> "센서 기록 확보 완료"
                "저전압 확인" -> "기록 보관 · 정차 후 센터 안내"
                "저전압 다시 감지" -> "반복 기록 보관 · 센터 방문 추천"
                "재측정 전압 회복" -> "이전 이상 기록 보관"
                "전압 이상 없음" -> "측정 기록 보관"
                "주차 중 충격 기록" -> "충격 기록 보관 · 정차 후 확인"
                "응답 시간 초과", "112 도움 요청", "119 도움 요청" -> when (DriverEventNoticeState.helpDeliveryState(
                    event
                )) {
                    "acknowledged" -> "$helpNumber 접수 완료 · 기록 보관"
                    "retry" -> "$helpNumber 전달 재시도 필요 · 기록 보관"
                    else -> if (witness) "정차 후 $helpNumber 요청 · 기록 보관" else "$helpNumber 접수 전 · 기록 보관"
                }

                "전방 시야 저하" -> EventBriefState.rainAdvice(event)
                else -> brief.second.next
            }
        return present(key, brief.first, title, status, brief.second.voltageLow)
    }

    fun showShared(feed: JSONObject): Boolean {
        if (disposed || !root.isAttachedToWindow || card != null) return false
        val notice =
            SharedRoadNotice.checked(feed, System.currentTimeMillis() / 1000.0) ?: return false
        if (notice.key in shown) return true
        val remaining = ((notice.expiresAt - System.currentTimeMillis() / 1000.0) * 1000).toLong()
            .coerceAtMost(15_000L)
        if (remaining <= 0) return false
        return present(notice.key, "pothole", notice.title, notice.status, false, remaining)
    }

    private fun present(
        key: String,
        graphic: String,
        title: String,
        status: String,
        voltageLow: Boolean,
        lifetimeMs: Long = 15_000L
    ): Boolean {
        val container = LinearLayout(root.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(8))
            val surface = PleosPalette.panel(ui.light)
            background = GradientDrawable().apply {
                setColor(
                    Color.argb(
                        217,
                        Color.red(surface),
                        Color.green(surface),
                        Color.blue(surface)
                    )
                )
                cornerRadius = ui.dp(24).toFloat()
                setStroke(ui.dp(1), PleosPalette.line(ui.light))
            }
            elevation = ui.dp(8).toFloat()
            // 카드 안의 탭만 받고 나머지 지도는 가리지 않는다
            isClickable = true
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val row = LinearLayout(root.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(
            DriverEventGraphic(
                root.context,
                graphic,
                ui.light,
                voltageLow
            ).apply { if (key.startsWith("shared:")) contentDescription = "전방 포트홀 주의 그래픽" },
            LinearLayout.LayoutParams(ui.dp(136), ui.dp(136)).apply { marginEnd = ui.dp(24) })
        val words = LinearLayout(root.context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(ui.text(title, 34f, true))
        words.addView(ui.text(status, 24f))
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(row)
        container.addView(ui.textButton("알림 닫기") { dismiss() })
        root.addView(
            container,
            FrameLayout.LayoutParams(
                minOf(
                    ui.dp(800),
                    (root.width - root.paddingLeft - root.paddingRight - ui.dp(48)).coerceAtLeast(
                        ui.dp(
                            240
                        )
                    )
                ), -2, Gravity.CENTER
            )
        )
        card = container
        resize()
        shown.add(key)
        main.postDelayed(timeout, lifetimeMs)
        return true
    }

    fun dismiss() {
        main.removeCallbacks(timeout)
        card?.let { root.removeView(it) }
        card = null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        dismiss()
        root.removeOnLayoutChangeListener(boundsListener)
        shown.clear()
    }
}
