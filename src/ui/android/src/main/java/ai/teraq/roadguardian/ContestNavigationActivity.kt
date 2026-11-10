package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.maplibre.android.geometry.LatLng
import org.json.JSONObject
import java.lang.ref.WeakReference

/** 공모전 시연용 배경 내비 화면. 경로 안내와 실제 차량 위치는 쓰지 않는다 */
class ContestNavigationActivity : Activity() {
    private lateinit var map: NativeMapPanel
    private var light = true
    private var visible = false
    private var captureGeneration = 0L
    private var overlay: DriverNavigationOverlay? = null

    companion object {
        private var current = WeakReference<ContestNavigationActivity>(null)

        /** 새 합성 사건이 들어오면 메인 스레드에서 호출된다 */
        fun captureEvent(event: JSONObject) {
            current.get()?.capture(event)
        }

        /** true면 시연 내비가 사건을 처리한 것이다. 시스템 알림을 중복으로 띄우지 않는다 */
        fun showOverlay(event: JSONObject): Boolean =
            current.get()?.showEventOverlay(event) ?: false

        fun showSharedOverlay(feed: JSONObject): Boolean {
            val activity = current.get() ?: return false
            if (!activity.visible || activity.isFinishing || activity.isDestroyed) return false
            return activity.overlay?.showShared(feed) ?: false
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        current = WeakReference(this)
    }

    override fun onPause() {
        visible = false
        captureGeneration++
        overlay?.dismiss()
        if (current.get() === this) current.clear()
        super.onPause()
    }

    override fun onDestroy() {
        captureGeneration++
        overlay?.dispose()
        overlay = null
        if (current.get() === this) current.clear()
        super.onDestroy()
    }

    private fun showEventOverlay(event: JSONObject): Boolean {
        if (!visible || isFinishing || isDestroyed || DriverEventNotification.identity(event) == null) return false
        // 오버레이를 올리기 전에 지도 영역만 캡처한다. 창 전체는 캡처하지 않는다
        capture(event)
        return overlay?.show(event) ?: false
    }

    private fun capture(event: JSONObject) {
        if (!visible || !::map.isInitialized) return
        val point = EventLocationPanel.location(event) ?: return
        // 고정된 시연 배경이라 다른 위치의 사건에는 붙이지 않는다
        if (point.latitude != 37.5 || point.longitude != 127.0) return
        val address = EventLocationPanel.displayAddress(event) ?: return
        val copy = JSONObject(event.toString())
        val observed = System.currentTimeMillis() / 1000.0
        val ticket = ++captureGeneration
        map.captureDisplayedMap { image ->
            if (!visible || isDestroyed || ticket != captureGeneration) {
                image.recycle()
                return@captureDisplayedMap
            }
            ContestNavigationSnapshot.save(
                applicationContext,
                copy,
                image,
                address,
                light,
                observed
            )
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!(BuildConfig.DEBUG || BuildConfig.CONTEST_DEMO) || !BuildConfig.SYNTHETIC_BACKEND) {
            finish()
            return
        }
        light = intent.getBooleanExtra("light", true)
        val ui = PhoneAccountUi(this, light)
        window.statusBarColor = PleosPalette.background(light)
        window.navigationBarColor = PleosPalette.background(light)
        window.decorView.systemUiVisibility = if (light)
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        val root = FrameLayout(this)
        map = NativeMapPanel(
            this, light, null, true, LatLng(37.5, 127.0),
            positionLabel = "사평대로 인근", synthetic = true
        )
        map.useNavigationAnchor()
        root.addView(map, FrameLayout.LayoutParams(-1, -1))
        val guidance = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(12))
            setBackgroundColor(ui.cardColor)
            addView(ui.text("↑  직진", 40f, true))
            addView(ui.text("사평대로", 28f))
        }
        root.addView(guidance, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = ui.dp(24)
            topMargin = ui.dp(24)
        })
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom
            )
            insets
        }
        overlay = DriverNavigationOverlay(root, ui)
        setContentView(root)
    }
}
