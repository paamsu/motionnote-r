package ai.teraq.roadguardian

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** 시연용 지도 맥락을 따로 보관한다. 원래 근거와 상세 화면 지도는 그대로 둔다 */
object ContestNavigationSnapshot {
    const val SOURCE = "contest_navigation_map_at_notification"
    private val worker = Executors.newSingleThreadExecutor()
    fun file(context: Context, event: JSONObject) = File(
        context.filesDir,
        "event-navigation/${event.getString("id")}-${event.getString("hash")}.json"
    )

    fun save(
        context: Context,
        event: JSONObject,
        image: Bitmap,
        address: String,
        light: Boolean,
        observed: Double
    ) {
        val captured = System.currentTimeMillis() / 1000.0
        worker.execute {
            try {
                val point = EventLocationPanel.location(event) ?: return@execute
                val target = file(context, event)
                if (target.exists()) return@execute
                val bytes = ByteArrayOutputStream().also {
                    image.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        it
                    )
                }.toByteArray()
                if (bytes.size > 8 * 1024 * 1024) return@execute
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                val record =
                    JSONObject().put("schema", "road-guardian.event-map.v1").put("source", SOURCE)
                        .put("event_id", event.getString("id"))
                        .put("event_hash", event.getString("hash"))
                        .put("event_created", event.opt("created"))
                        .put("notification_observed", observed)
                        .put("snapshot_created", captured).put("lat", point.latitude)
                        .put("lon", point.longitude)
                        .put("address", address)
                        .put("address_source", "contest_navigation_scenario")
                        .put("light_theme", light).put("image_sha256", hash)
                        .put("png", Base64.encodeToString(bytes, Base64.NO_WRAP))
                target.parentFile?.mkdirs()
                val atomic = AtomicFile(target)
                val stream = atomic.startWrite()
                try {
                    stream.write(record.toString().toByteArray())
                    atomic.finishWrite(stream)
                } catch (e: Exception) {
                    atomic.failWrite(stream)
                    throw e
                }
            } catch (_: Exception) {
                // 지도 저장에 실패해도 사건 알림은 띄운다. 이때 지도를 저장했다고 표시하지 않는다
            } finally {
                image.recycle()
            }
        }
    }
}
