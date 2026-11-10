package ai.teraq.roadguardian

import org.json.JSONObject

/** 서버에는 변환한 개인 사진만 저장한다. 차량 카메라 원본은 저장하지 않는다 */
data class CasePhoto(
    val id: String,
    val eventId: String,
    val caption: String,
    val width: Int,
    val height: Int,
    val bytes: Int,
    val sha256: String,
    val uploadSha256: String,
    val receivedAt: Double,
    val captureContext: PhotoCaptureContext? = null
) {
    companion object {
        const val MAX_BYTES = 1024 * 1024
        fun hash(value: ByteArray) =
            java.security.MessageDigest.getInstance("SHA-256").digest(value)
                .joinToString("") { "%02x".format(it) }

        fun parse(value: JSONObject, eventId: String, photoId: String? = null): CasePhoto {
            try {
                require(
                    value.getString("schema") == "road-guardian.case-photo.v1" && value.getString(
                        "event_id"
                    ) == eventId
                )
                val id = value.getString("id")
                require(
                    java.util.UUID.fromString(id)
                        .toString() == id && (photoId == null || photoId == id)
                )
                require(value.getString("scope") == "current_subject_only" && value.getString("provenance") == "owner_supplied_not_attested")
                require(
                    value.get("sensor_original") == false && value.get("metadata_removed") == true && value.getString(
                        "transformation"
                    ) == "server_jpeg_reencode"
                )
                fun integer(key: String, range: IntRange): Int {
                    val n = value.get(key)
                    require(n is Int && n in range)
                    return n
                }

                val width = integer("width", 1..1600)
                val height = integer("height", 1..1600)
                val bytes = integer("bytes", 1..MAX_BYTES)
                val caption = value.get("caption")
                require(caption is String && caption.length <= 400)
                val sha = value.getString("sha256")
                val uploaded = value.getString("upload_sha256")
                require(sha.matches(Regex("[0-9a-f]{64}")) && uploaded.matches(Regex("[0-9a-f]{64}")))
                val received = value.get("received_at")
                require(
                    received is Number && received.toDouble().isFinite() && received.toDouble() > 0
                )
                val context = if (value.has("capture_context")) PhotoCaptureContext.parse(
                    value.getJSONObject("capture_context")
                ) else null
                return CasePhoto(
                    id,
                    eventId,
                    caption,
                    width,
                    height,
                    bytes,
                    sha,
                    uploaded,
                    received.toDouble(),
                    context
                )
            } catch (_: Exception) {
                throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
            }
        }

        fun list(value: JSONObject, eventId: String): List<CasePhoto> {
            try {
                require(
                    value.getString("schema") == "road-guardian.case-photos.v1" && value.getString(
                        "event_id"
                    ) == eventId && value.getString("scope") == "current_subject_only"
                )
                val photos = value.getJSONArray("photos")
                require(photos.length() <= 4)
                return (0 until photos.length()).map { parse(photos.getJSONObject(it), eventId) }
                    .also { require(it.map { photo -> photo.id }.distinct().size == it.size) }
            } catch (_: Exception) {
                throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
            }
        }
    }

    fun content(value: JSONObject): ByteArray {
        try {
            require(parse(value, eventId, id) == this)
            val encoded = value.get("image_base64")
            require(encoded is String && encoded.length <= 4 * ((MAX_BYTES + 2) / 3))
            val raw = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            return verifiedBytes(raw)
        } catch (_: Exception) {
            throw AccountAccessException(AccountAccessException.Reason.INTEGRITY)
        }
    }

    fun verifiedBytes(raw: ByteArray): ByteArray {
        try {
            require(raw.size == bytes && hash(raw) == sha256)
            val options =
                android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
            require(options.outMimeType == "image/jpeg" && options.outWidth == width && options.outHeight == height)
            val decoded = android.graphics.BitmapFactory.decodeByteArray(
                raw,
                0,
                raw.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
            require(decoded != null)
            decoded.recycle()
            return raw
        } catch (_: Exception) {
            throw AccountAccessException(AccountAccessException.Reason.INTEGRITY)
        }
    }
}
