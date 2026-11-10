package ai.teraq.roadguardian

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** 고른 사진을 메모리 안에서 JPEG로 바꾼다. 원본 파일과 메타데이터는 기기에 남긴다 */
object CasePhotoDraft {
    fun fromUri(context: Context, uri: Uri): ByteArray {
        val output = ByteArrayOutputStream()
        checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 12 * 1024 * 1024)
                output.write(buffer, 0, count)
            }
        }
        return fromBytes(output.toByteArray())
    }

    fun fromBytes(original: ByteArray): ByteArray {
        require(original.size in 1..12 * 1024 * 1024)
        val source = ImageDecoder.createSource(ByteBuffer.wrap(original))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            require(info.size.width > 0 && info.size.height > 0 && info.size.width.toLong() * info.size.height <= 40_000_000)
            val factor = (maxOf(info.size.width, info.size.height) / 1280f).coerceAtLeast(1f)
            decoder.setTargetSize(
                (info.size.width / factor).toInt().coerceAtLeast(1),
                (info.size.height / factor).toInt().coerceAtLeast(1)
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
            require(output.size() in 1..CasePhoto.MAX_BYTES)
            return output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
