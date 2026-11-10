package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Full-image OCR observations only. No plate localization or real watchlist integration. */
class TrafficTextProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val response = Bundle()
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            val folder = File(targetContext.filesDir, "traffic-ocr-input")
            val manifest = JSONObject(File(folder, "inputs.json").readText())
            val inputs = manifest.getJSONArray("inputs")
            val rows = JSONArray()
            require(inputs.length() in 1..12)
            fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
                .joinToString("") { "%02x".format(it) }
            for (i in 0 until inputs.length()) {
                val input = inputs.getJSONObject(i)
                val name = input.getString("id")
                require(name.matches(Regex("[a-f0-9]{32}")))
                val file = File(folder, "$name.png")
                val bytes = file.readBytes()
                require(bytes.size in 1..16 * 1024 * 1024 && sha(bytes) == input.getString("sha256"))
                val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                require(bitmap.width <= 4096 && bitmap.height <= 4096)
                val start = android.os.SystemClock.elapsedRealtime()
                val result = try {
                    Tasks.await(
                        recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                        45,
                        TimeUnit.SECONDS
                    )
                } finally {
                    bitmap.recycle()
                }
                val lines = JSONArray()
                for (block in result.textBlocks) for (line in block.lines) {
                    val b = line.boundingBox
                    lines.put(
                        JSONObject().put("text", line.text).put(
                            "bounding_box",
                            if (b == null) JSONObject.NULL else JSONArray(
                                listOf(
                                    b.left,
                                    b.top,
                                    b.right,
                                    b.bottom
                                )
                            )
                        )
                    )
                }
                rows.put(
                    JSONObject().put("id", name).put("input_sha256", sha(bytes))
                        .put("text", result.text).put("lines", lines)
                        .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - start)
                )
            }
            File(
                targetContext.filesDir,
                "traffic-ocr-result.json"
            ).writeText(
                JSONObject().put("schema", "road-guardian.traffic-text-audit.v1")
                    .put("source", "synthetic_full_image_mlkit_ocr").put("camera_evidence", false)
                    .put("plate_localization_performed", false).put("watchlist_accessed", false)
                    .put("rows", rows).toString(2)
            )
            response.putString(
                "stream",
                "PASS: full-image OCR executed; classification and spatial review pending.\n"
            )
            finish(Activity.RESULT_OK, response)
        } catch (e: Exception) {
            response.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, response)
        } finally {
            recognizer.close()
        }
    }
}
