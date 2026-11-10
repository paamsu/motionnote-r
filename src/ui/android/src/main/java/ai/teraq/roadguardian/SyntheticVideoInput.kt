package ai.teraq.roadguardian

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal class SyntheticVideoException(val reason: Reason) : java.io.IOException(reason.name) {
    enum class Reason { ORIGINAL_INVALID, MISSING, SOURCE_UNVERIFIED, MEDIA_INVALID, TRANSFER_INVALID }

    fun display() = when (reason) {
        Reason.ORIGINAL_INVALID -> "원본 확인 실패"
        Reason.MISSING -> "보관된 영상 없음"
        Reason.SOURCE_UNVERIFIED -> "영상 출처 확인 필요"
        Reason.MEDIA_INVALID -> "영상 파일 손상·누락"
        Reason.TRANSFER_INVALID -> "받은 영상 확인 실패"
    }
}

/** 개발용 합성 입력. 실제 인증 재생은 별도 규칙을 따른다 */
internal object SyntheticVideoInput {
    fun download(eventId: String, cache: File): File {
        check((BuildConfig.DEBUG || BuildConfig.CONTEST_DEMO) && BuildConfig.SYNTHETIC_BACKEND)
        require(eventId.matches(Regex("[0-9a-fA-F-]{36}")))
        val event = JSONObject(BackendClient().request("/api/events/$eventId"))
        if (!event.optBoolean("evidence_valid")) throw SyntheticVideoException(
            SyntheticVideoException.Reason.ORIGINAL_INVALID
        )
        val video = event.optJSONObject("video") ?: throw SyntheticVideoException(
            SyntheticVideoException.Reason.MISSING
        )
        if (event.optString("source") != "synthetic" || video.optString("source") !in setOf(
                "synthetic_test_pattern",
                "synthetic_scene_fixture"
            ) || video.opt("camera_evidence") != false
        )
            throw SyntheticVideoException(SyntheticVideoException.Reason.SOURCE_UNVERIFIED)
        if (!video.optBoolean("integrity_valid")) throw SyntheticVideoException(
            SyntheticVideoException.Reason.MEDIA_INVALID
        )
        val url =
            BackendEndpoint.resolve(BuildConfig.BACKEND_URL, "/api/events/$eventId/video", true)
        val connection = URL(url).openConnection() as HttpURLConnection
        val file = File.createTempFile("synthetic-video-", ".mp4", cache)
        var accepted = false
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            connection.setRequestProperty("Accept", "video/mp4")
            check(connection.responseCode == 200)
            file.outputStream().use {
                AuthenticatedVideoTransport.copyVerifiedBody(
                    connection,
                    video.getString("sha256"),
                    video.getLong("bytes"),
                    it
                )
            }
            accepted = true
            return file
        } catch (error: Exception) {
            throw SyntheticVideoException(SyntheticVideoException.Reason.TRANSFER_INVALID).apply {
                initCause(
                    error
                )
            }
        } finally {
            connection.disconnect()
            if (!accepted) file.delete()
        }
    }
}
