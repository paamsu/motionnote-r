package ai.teraq.roadguardian

import java.io.File
import java.util.UUID

/** Exercise the production empty screen without changing the owner's saved photos. */
class ParkingEmptyPreviewActivity : ParkingActivity() {
    private val fixtureId = UUID.randomUUID().toString()
    override fun getFilesDir(): File =
        File(super.getCacheDir(), "parking-empty-$fixtureId").apply { mkdirs() }
}
