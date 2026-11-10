package ai.teraq.roadguardian

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng

/** Server-generated road fixtures at a frozen time; no live vehicle or route assertion. */
class RoadWarningPreviewActivity : Activity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderPreview()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        renderPreview()
    }

    private fun renderPreview() {
        val light = !intent.getBooleanExtra("night", false)
        val cases =
            JSONObject(assets.open("road-cases-v134.json").bufferedReader().use { it.readText() })
        val feed = cases.getJSONObject(intent.getStringExtra("kind") ?: "pothole")
        val warning = feed.getJSONArray("warnings").getJSONObject(0)
        val position = warning.getJSONObject("location")
        val receiver = feed.getJSONObject("vehicle")
        val density = resources.displayMetrics.density
        window.statusBarColor = Color.parseColor(if (light) "#EEF1F4" else "#141619")
        window.navigationBarColor = window.statusBarColor
        window.decorView.systemUiVisibility =
            if (light) android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        fun dp(n: Int) = (n * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor(if (light) "#EEF1F4" else "#141619"))
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
                insets
            }
        }
        val map = FrameLayout(this)
        map.addView(
            NativeMapPanel(
                this, light, LatLng(position.getDouble("lat"), position.getDouble("lon")), false,
                LatLng(receiver.getDouble("lat"), receiver.getDouble("lon"))
            ), FrameLayout.LayoutParams(-1, -1)
        )
        map.addView(TextView(this).apply {
            text = "합성 주행 미리보기 · 시각 고정"
            textSize = 16f
            setTextColor(if (light) Color.BLACK else Color.WHITE)
            setBackgroundColor(Color.parseColor(if (light) "#FFFFFF" else "#202328"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }, FrameLayout.LayoutParams(-2, -2))
        root.addView(map, LinearLayout.LayoutParams(0, -1, 1f))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        RoadWarningPanel.render(this, panel, warning, light, feed.getDouble("generated_at"))
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(panel)
        }, LinearLayout.LayoutParams(dp(380), -1))
        setContentView(root)
    }
}
