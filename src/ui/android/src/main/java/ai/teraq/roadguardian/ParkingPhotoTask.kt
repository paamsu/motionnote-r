package ai.teraq.roadguardian

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** 카메라를 연 화면을 붙잡지 않고 사진을 불러와 인식한다 */
object ParkingPhotoTask {
    data class Result(
        val photos: List<File>,
        val recognized: String,
        val failed: Boolean,
        val imported: Boolean,
        val append: Boolean
    )

    fun create(
        context: Context,
        uris: List<Uri>,
        previous: List<File>,
        append: Boolean
    ): FutureTask<Result> {
        val directory = context.filesDir
        val storage = object : ContextWrapper(context.applicationContext) {
            override fun getFilesDir() = directory
        }
        val selected = uris.toList()
        val existing = previous.toList()
        return FutureTask {
            val imported = mutableListOf<File>()
            val texts = mutableListOf<String>()
            var failure = false
            val store = ParkingPhotoStore(storage)
            for (uri in selected) try {
                imported.add(store.importPhoto(uri))
            } catch (_: Exception) {
                failure = true
            }
            val combined = if (append) existing + imported else imported
            val recognizer =
                TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            try {
                for (file in combined) try {
                    texts.add(
                        Tasks.await(
                            recognizer.process(
                                InputImage.fromFilePath(
                                    storage,
                                    Uri.fromFile(file)
                                )
                            ), 30, TimeUnit.SECONDS
                        ).text
                    )
                } catch (_: Exception) {
                    failure = true
                }
            } finally {
                recognizer.close()
            }
            Result(combined, texts.joinToString("\n\n"), failure, imported.isNotEmpty(), append)
        }
    }
}
