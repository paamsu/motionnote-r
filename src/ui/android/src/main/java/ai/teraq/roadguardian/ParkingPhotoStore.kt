package ai.teraq.roadguardian

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 기기 안 개인 사진 저장소. 확인한 주차 기록은 모두 남긴다 */
class ParkingPhotoStore(
    private val context: Context,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    // AtomicFile을 쓰므로 여러 저장소 인스턴스 사이의 읽기·쓰기 순서는 호출한 쪽이 맞춘다
    private companion object {
        val commitLock = Any()
    }

    data class ReadResult(val record: JSONObject?, val error: String? = null)
    data class HistoryResult(
        val records: List<JSONObject>,
        val unreadable: Int = 0,
        val error: String? = null
    )

    private val directory = File(context.filesDir, "parking-photos").apply { mkdirs() }
    fun importPhoto(uri: Uri): File {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val factor = (maxOf(info.size.width, info.size.height) / 1600f).coerceAtLeast(1f)
            decoder.setTargetSize(
                (info.size.width / factor).toInt(),
                (info.size.height / factor).toInt()
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun digest(file: File): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validate(record: JSONObject, verifyPhotos: Boolean = true): JSONObject {
        val id = record.getString("id")
        require(UUID.fromString(id).toString() == id)
        require(record.getString("source") == "user_confirmed_photo")
        require(ParkingSigns.normalizedFloor(record.getString("floor")).isNotEmpty())
        require(record.getString("landmark").isNotBlank() && record.getString("place").isNotBlank())
        require(record.getDouble("parked_at").let { it.isFinite() && it > 0 })
        val photos = record.getJSONArray("photos")
        require(photos.length() in 1..4)
        val hashes = record.optJSONArray("photo_sha256")
        if (hashes != null) require(hashes.length() == photos.length())
        for (i in 0 until photos.length()) {
            val file = File(photos.getString(i))
            require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator) && file.isFile)
            if (hashes != null && verifyPhotos) require(digest(file) == hashes.getString(i))
        }
        record.put(
            "photo_integrity",
            if (hashes == null) "not_recorded" else if (verifyPhotos) "verified" else "not_checked"
        )
        return record
    }

    /** 메타데이터 목록만 읽는다. 기록을 열 때는 사진 해시를 모두 확인한다. UI 스레드 밖에서 실행한다 */
    fun history(): HistoryResult {
        val files = directory.listFiles() ?: return HistoryResult(
            emptyList(),
            error = "주차 기록 목록을 읽지 못했습니다."
        )
        val records = mutableListOf<JSONObject>()
        var unreadable = 0
        for (file in files) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (file.name == "latest.json" || !file.name.matches(Regex("[0-9a-f-]{36}\\.json"))) continue
            try {
                require(file.length() in 1..1048576)
                val record = validate(JSONObject(file.readText()), false)
                require(file.name == "${record.getString("id")}.json")
                records.add(record)
            } catch (_: Exception) {
                unreadable++
            }
        }
        return HistoryResult(records.sortedWith(compareByDescending<JSONObject> { it.getDouble("parked_at") }.thenByDescending {
            it.getString(
                "id"
            )
        }), unreadable)
    }

    fun latestResult(): ReadResult {
        return try {
            val text = synchronized(commitLock) {
                val base = File(directory, "latest.json")
                if (!base.exists() && !File(directory, "latest.json.bak").exists()) null
                else AtomicFile(base).openRead().bufferedReader().use { it.readText() }
            }
            if (text == null) ReadResult(null) else ReadResult(validate(JSONObject(text)))
        } catch (_: Exception) {
            ReadResult(null, "저장된 주차 기록 또는 사진을 확인하지 못했습니다. 원본을 보존한 상태입니다.")
        }
    }

    fun latest(): JSONObject? = latestResult().record
    fun record(id: String): JSONObject? {
        if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) return null
        return try {
            val saved = validate(JSONObject(File(directory, "$id.json").readText()))
            require(saved.getString("id") == id)
            saved
        } catch (_: Exception) {
            null
        }
    }

    fun comparisonRecord(id: String?): ReadResult {
        if (id == null) return ReadResult(null, "비교할 주차 기록이 없습니다. 먼저 주차 사진을 저장하세요.")
        val saved = record(id)
        return if (saved != null && saved.optString("id") == id) ReadResult(saved)
        else ReadResult(null, "선택한 주차 기록 또는 사진을 확인하지 못했습니다. 다른 주차 기록으로 바꾸지 않았습니다.")
    }

    fun save(floor: String, landmark: String, photos: List<File>, recognized: String): JSONObject {
        val normalized = ParkingSigns.normalizedFloor(floor)
        require(normalized.isNotEmpty() && landmark.isNotBlank() && photos.size in 1..4 && photos.all { it.isFile })
        val record = JSONObject().put("id", UUID.randomUUID().toString()).put("floor", normalized)
            .put("landmark", landmark).put("parked_at", nowMillis() / 1000.0)
            .put("place", "사진으로 기록한 주차 위치").put("source", "user_confirmed_photo")
            .put("photos", JSONArray(photos.map { it.absolutePath }))
            .put("recognized_text", recognized)
            .put("photo_sha256", JSONArray(photos.map { digest(it) }))
        validate(record)
        val bytes = record.toString().toByteArray(Charsets.UTF_8)
        synchronized(commitLock) {
            writeAtomic(File(directory, "${record.getString("id")}.json"), bytes)
            writeAtomic(File(directory, "latest.json"), bytes)
        }
        return record
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}
