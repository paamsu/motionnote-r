package ai.teraq.roadguardian

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.util.UUID

/** Seed only input state; comparison and typing use the production screen. */
class ParkingEditPreviewActivity : PhotoParkingActivity() {
    override fun getFilesDir(): File =
        File(cacheDir, "parking-edit-${intent.getStringExtra("fixture_id")}").apply { mkdirs() }

    override fun onCreate(state: Bundle?) {
        if (state != null) {
            super.onCreate(state)
            return
        }
        intent.putExtra("fixture_id", UUID.randomUUID().toString()).putExtra("compare", true)
        val store = ParkingPhotoStore(this)
        val fixture = File(filesDir, "sign.png")
        val bitmap = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 90f
            }
            drawText("B2 / 24", 45f, 230f, paint)
            paint.textSize = 35f
            drawText("SYNTHETIC TEST", 45f, 420f, paint)
        }
        fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val photo = store.importPhoto(Uri.fromFile(fixture))
        val saved = store.save("B2", "24", listOf(photo), "B2 / 24")
        val seeded = Bundle().apply {
            putString("comparison_id", saved.getString("id"))
            putStringArrayList("photos", arrayListOf(photo.absolutePath))
            putString("floor", "B2")
            putString("landmark", "24")
            putString("status", "가상 사진과 표지 입력입니다. 비교한 뒤 층이나 기둥 값을 바꿔 확인하세요.")
        }
        super.onCreate(seeded)
    }
}
