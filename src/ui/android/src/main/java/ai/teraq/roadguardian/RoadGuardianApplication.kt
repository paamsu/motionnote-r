package ai.teraq.roadguardian

import android.app.Application

class RoadGuardianApplication : Application() {
    var privateVideoCacheReady = false
        private set

    override fun onCreate() {
        super.onCreate()
        PleosTypography.initialize(this)
        privateVideoCacheReady = runCatching {
            PrivateVideoCache.clearPreviousProcess(cacheDir)
        }.isSuccess
    }
}
