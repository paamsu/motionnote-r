package ai.teraq.roadguardian

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BackendClient(private val baseUrl: String = BuildConfig.BACKEND_URL) {
    private fun endpoint(path: String): URL = URL(
        BackendEndpoint.resolve(
            baseUrl,
            path,
            (BuildConfig.DEBUG || BuildConfig.CONTEST_DEMO) && BuildConfig.SYNTHETIC_BACKEND
        )
    )

    fun evidencePackage(eventId: String): ByteArray {
        require(AccountEventRoute.checked(eventId) == eventId)
        val connection =
            endpoint("/api/events/$eventId/export").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4000
            connection.readTimeout = 15000
            if (connection.responseCode == 400) throw AccountAccessException(AccountAccessException.Reason.INTEGRITY)
            check(connection.responseCode == 200 && connection.contentType?.startsWith("application/zip") == true) { "증거 묶음을 받지 못했습니다" }
            val output = java.io.ByteArrayOutputStream()
            val started = System.nanoTime()
            check(connection.contentLengthLong <= AuthenticatedExportTransport.MAX_BYTES)
            connection.inputStream.use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() - started > 30_000_000_000L) throw java.io.InterruptedIOException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    check(output.size() + n <= AuthenticatedExportTransport.MAX_BYTES) { "증거 묶음이 저장 한도를 넘었습니다" }
                    output.write(buffer, 0, n)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    fun scenePhoto(eventId: String): ByteArray {
        require(AccountEventRoute.checked(eventId) == eventId)
        val connection =
            endpoint("/api/events/$eventId/scene-photo").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            require(
                connection.responseCode == 200 && connection.contentType?.substringBefore(";") in setOf(
                    "image/png",
                    "image/jpeg"
                )
            )
            require(connection.contentLengthLong <= 8 * 1024 * 1024)
            val output = java.io.ByteArrayOutputStream()
            val started = System.nanoTime()
            connection.inputStream.use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() - started > 15_000_000_000L) throw java.io.InterruptedIOException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 8 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    fun surround(eventId: String): ByteArray = surroundImage(eventId, null)
    fun surroundView(eventId: String, direction: String): ByteArray {
        require(direction in SyntheticSurroundViews.directions)
        return surroundImage(eventId, direction)
    }

    private fun surroundImage(eventId: String, direction: String?): ByteArray {
        require(AccountEventRoute.checked(eventId) == eventId)
        val route =
            if (direction == null) "/api/events/$eventId/surround" else "/api/events/$eventId/surround-views/$direction"
        val connection = endpoint(route).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            require(connection.responseCode == 200 && connection.contentType?.substringBefore(";") == "image/png")
            val output = java.io.ByteArrayOutputStream()
            val started = System.nanoTime()
            connection.inputStream.use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() - started > 15_000_000_000L) throw java.io.InterruptedIOException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 8 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    fun request(path: String, body: JSONObject? = null): String {
        val connection = endpoint(path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(
                        body.toString().toByteArray(Charsets.UTF_8)
                    )
                }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("요청을 처리하지 못했습니다 ($code): $text")
            return text
        } finally {
            connection.disconnect()
        }
    }
}
