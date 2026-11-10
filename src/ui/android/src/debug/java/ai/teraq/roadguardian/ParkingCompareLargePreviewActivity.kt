package ai.teraq.roadguardian

/** Local 2x font configuration; does not change the device accessibility setting. */
class ParkingCompareLargePreviewActivity : ParkingComparePreviewActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        val configuration =
            android.content.res.Configuration(base.resources.configuration).apply { fontScale = 2f }
        super.attachBaseContext(base.createConfigurationContext(configuration))
    }
}
