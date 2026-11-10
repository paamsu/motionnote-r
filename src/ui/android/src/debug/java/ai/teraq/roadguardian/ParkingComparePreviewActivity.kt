package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import java.io.File

/** Isolated sign fixtures, never saved as user parking records. */
open class ParkingComparePreviewActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = PhoneAccountUi(this)
        val body = ui.install()
        ui.heading(body, "가상 사진 비교 시험", "실제 주차 사진이 아닙니다.")
        val directory = File(cacheDir, "comparison-preview").apply { mkdirs() }
        fun fixture(name: String, sign: String, color: Int): File {
            val file = File(directory, name)
            val bitmap = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(color)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textSize = 95f
            }
            canvas.drawText(sign, 45f, 250f, paint)
            paint.textSize = 38f
            canvas.drawText("SYNTHETIC TEST", 45f, 400f, paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return file
        }

        val saved = listOf(
            fixture("saved1.png", "B2 / 24", Color.rgb(32, 72, 94)),
            fixture("saved2.png", "B2 / EXIT", Color.rgb(36, 94, 66))
        )
        val current = listOf(
            fixture("current1.png", "B3 / 31", Color.rgb(105, 63, 35)),
            fixture("current2.png", "B3 / LIFT", Color.rgb(102, 45, 65))
        )
        body.addView(ui.button("두 사진 함께 크게 보기", true) {
            ParkingPhotoGallery.compare(
                this,
                saved,
                current
            )
        })
    }
}
