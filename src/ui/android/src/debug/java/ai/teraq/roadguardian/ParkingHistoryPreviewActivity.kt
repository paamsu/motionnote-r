package ai.teraq.roadguardian

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.util.UUID

/** Isolated history fixtures. Owner photos and latest record are never modified. */
class ParkingHistoryPreviewActivity : ParkingHistoryActivity() {
    override fun getFilesDir(): File =
        File(cacheDir, "history-preview-${intent.getStringExtra("fixture_id")}").apply { mkdirs() }

    override fun onCreate(state: Bundle?) {
        if (!intent.hasExtra("fixture_id")) intent.putExtra(
            "fixture_id",
            UUID.randomUUID().toString()
        )
        val store = ParkingPhotoStore(this)
        if (store.latest() == null) {
            val file = File(filesDir, "fixture.png")
            val bitmap = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.rgb(32, 72, 94))
                drawText("SYNTHETIC PHOTO", 40f, 240f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 65f
                })
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val photo = store.importPhoto(Uri.fromFile(file))
            val now = System.currentTimeMillis()
            for (index in 22 downTo 0) ParkingPhotoStore(this) { now - index * 86400000L }
                .save(
                    "B${index % 3 + 1}",
                    "시험 ${index + 1}",
                    listOf(photo),
                    "SYNTHETIC HISTORY FIXTURE"
                )
        }
        super.onCreate(state)
    }

    override fun recordIntent(id: String): Intent = super.recordIntent(id)
        .setClass(this, ParkingHistoryDetailPreviewActivity::class.java)
        .putExtra("fixture_id", intent.getStringExtra("fixture_id"))
}

class ParkingHistoryDetailPreviewActivity : ParkingActivity() {
    override fun getFilesDir(): File =
        File(cacheDir, "history-preview-${intent.getStringExtra("fixture_id")}")
}
