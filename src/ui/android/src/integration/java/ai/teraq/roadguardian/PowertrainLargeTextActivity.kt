package ai.teraq.roadguardian

import android.content.Context
import android.content.res.Configuration

/** Test-only font context. No device font setting is changed. */
class PowertrainLargeTextActivity : PowertrainActivity() {
    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration).apply { fontScale = 1.5f }
        super.attachBaseContext(base.createConfigurationContext(config))
    }
}
