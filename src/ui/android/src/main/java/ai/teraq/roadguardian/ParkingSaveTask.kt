package ai.teraq.roadguardian

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.FutureTask

/** Activity가 다시 만들어져도 저장을 이어 간다. Activity는 붙잡지 않는다 */
object ParkingSaveTask {
    fun create(
        context: Context,
        floor: String,
        landmark: String,
        photos: List<File>,
        recognized: String
    ): FutureTask<String?> {
        val directory = context.filesDir
        val storage = object : ContextWrapper(context.applicationContext) {
            override fun getFilesDir() = directory
        }
        val selected = photos.toList()
        return FutureTask {
            try {
                ParkingPhotoStore(storage).save(floor, landmark, selected, recognized)
                null
            } catch (_: Exception) {
                "저장하지 못했습니다. 저장 공간을 확인하세요."
            }
        }
    }
}
