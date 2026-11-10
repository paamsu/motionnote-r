package ai.teraq.roadguardian

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.TimeUnit
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** Debug-only real model execution. Does not replace the user's latest parking record. */
class ParkingOcrProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 160f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            canvas.drawText("B2", 120f, 260f, paint)
            canvas.drawText("24", 120f, 520f, paint)
            val text = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                45,
                TimeUnit.SECONDS
            ).text
            check(ParkingSigns.floors(text) == setOf("B2")) { "Unexpected fixture OCR: $text" }
            val scene =
                BitmapFactory.decodeResource(targetContext.resources, R.drawable.parking_scene_v1)
            val sceneText = Tasks.await(
                recognizer.process(InputImage.fromBitmap(scene, 0)),
                45,
                TimeUnit.SECONDS
            ).text
            val scratch = File(
                targetContext.cacheDir,
                "ocr-probe-${System.currentTimeMillis()}"
            ).apply { mkdirs() }
            val isolated = object : ContextWrapper(targetContext) {
                override fun getFilesDir() = scratch
            }
            val photo = File(
                scratch,
                "sign.jpg"
            ).apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
            val store = ParkingPhotoStore(isolated)
            val scenePhoto = File(
                scratch,
                "scene.jpg"
            ).apply { outputStream().use { scene.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
            val thirdPhoto = store.importPhoto(android.net.Uri.fromFile(photo))
            val firstPhoto = store.importPhoto(android.net.Uri.fromFile(photo))
            val secondPhoto = store.importPhoto(android.net.Uri.fromFile(scenePhoto))
            val saved =
                store.save("B2", "24번 기둥", listOf(firstPhoto, secondPhoto, thirdPhoto), text)
            check(store.latest()?.getJSONArray("photos")?.length() == 3)
            check(store.latest()?.getJSONArray("photo_sha256")?.length() == 3)
            check(runCatching {
                store.save(
                    "B2",
                    "24",
                    listOf(firstPhoto, secondPhoto, thirdPhoto, firstPhoto),
                    text
                )
            }.isFailure)
            check(store.latest()?.getString("id") == saved.getString("id"))
            // Age the isolated fixture, then reopen from disk as after a weekend away.
            val threeDaysAgo = System.currentTimeMillis() / 1000.0 - 3 * 86400
            saved.put("parked_at", threeDaysAgo)
            val records = File(scratch, "parking-photos")
            File(records, "${saved.getString("id")}.json").writeText(saved.toString())
            File(records, "latest.json").writeText(saved.toString())
            check(ParkingPhotoStore(isolated).latest()?.getString("id") == saved.getString("id"))
            check(ParkingPhotoStore(isolated).latest()?.getDouble("parked_at") == threeDaysAgo)
            store.save("B3", "25번 기둥", listOf(firstPhoto), "B3")
            check(store.record(saved.getString("id"))?.getString("floor") == "B2")
            check(store.comparisonRecord(saved.getString("id")).record?.getString("floor") == "B2")
            check(store.latest()?.getString("floor") == "B3")
            check(store.comparisonRecord(null).error != null)
            check(store.comparisonRecord(java.util.UUID.randomUUID().toString()).record == null)
            check(store.record("../../latest") == null)
            check(photo.exists())
            firstPhoto.appendBytes(byteArrayOf(1, 2, 3))
            val corrupted = store.latestResult()
            check(corrupted.record == null && corrupted.error != null)
            check(store.record(saved.getString("id")) == null)
            check(store.comparisonRecord(saved.getString("id")).error != null)
            check(photo.exists()) // Corrupt evidence is retained, not deleted.
            bitmap.recycle()
            scene.recycle()
            result.putString(
                "stream",
                "PASS: real Korean ML Kit recognized fixture; three photos and hashes retained; fourth photo rejected without replacing latest; three-day-old parking survives disk reopen; historical B2 comparison stays B2 after B3 save; missing reference never falls back; changed photo blocked without deletion.\nFixture: $text\nAI scene OCR (observation, not ground truth): $sceneText\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            recognizer.close()
        }
    }
}
