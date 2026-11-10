package ai.teraq.roadguardian

import android.content.Context
import android.content.res.Configuration

/** Test-only font context; does not change the device's settings. */
class MeasurementLargeTextActivity : MeasurementActivity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            fontScale = 1.5f
        }))
    }
}
