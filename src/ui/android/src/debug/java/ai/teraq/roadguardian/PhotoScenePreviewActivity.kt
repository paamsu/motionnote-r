package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import java.io.File

/** AI-created scenes in isolated cache, never user parking history. */
class PhotoScenePreviewActivity : Activity() {
    var comparison: android.app.Dialog? = null
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val ui = PhoneAccountUi(this)
        ui.install()
        fun asset(name: String) =
            File.createTempFile("photo-scene-verified-", ".png", cacheDir).apply {
                assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
                check(length() > 100000)
            }
        comparison = ParkingPhotoGallery.comparisonDialog(
            this,
            listOf(asset("garage-b2-v187.png")),
            listOf(asset("garage-b3-v187.png")),
            intArrayOf(0, 0),
            "AI 합성 시연 · 실제 촬영 사진 아님"
        ).also { it.show() }
    }

    override fun onDestroy() {
        comparison?.dismiss()
        super.onDestroy()
    }
}
