package ai.teraq.roadguardian

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/** Phone-only visual fixture. Does not relax automotive UX restrictions. */
class VehicleDetailPreviewActivity : Activity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            finish()
            return
        }
        val light = intent.getBooleanExtra("day", false)
        val background = Color.parseColor(if (light) "#FFFFFF" else "#202328")
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility =
            if (light) android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        val dp = resources.displayMetrics.density
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((28 * dp).toInt(), (20 * dp).toInt(), (28 * dp).toInt(), (24 * dp).toInt())
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(background)
            addView(body)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
                insets
            }
        })
        val event = JSONObject(
            assets.open("account-detail-fixture.json").bufferedReader().use { it.readText() })
        VehicleEventDetail.render(this, body, event, light)
    }
}
