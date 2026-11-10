package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class MapResolutionPreviewActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = PhoneAccountUi(this)
        val root = ui.install()
        val event = JSONObject(intent.getStringExtra("event")!!)
        root.addView(
            EventLocationPanel(this, ui, event) { true },
            android.widget.LinearLayout.LayoutParams(1100, 520)
        )
    }
}

class MapResolutionProbe : Instrumentation() {
    override fun onCreate(a: Bundle?) {
        super.onCreate(a)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            val id = "map-resolution-" + java.util.UUID.randomUUID()
            val hash = "1".repeat(64)
            val event = JSONObject().put("id", id).put("hash", hash).put("source", "synthetic")
                .put("evidence_valid", true)
                .put(
                    "data",
                    JSONObject().put(
                        "location",
                        JSONObject().put("lat", 37.499).put("lon", 127.003)
                    )
                )
            val image = Bitmap.createBitmap(220, 100, Bitmap.Config.ARGB_8888)
            image.eraseColor(android.graphics.Color.LTGRAY)
            val png =
                ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    .toByteArray()
            image.recycle()
            val record = JSONObject().put("event_id", id).put("event_hash", hash).put("lat", 37.499)
                .put("lon", 127.003)
                .put("address", "서울특별시 서초구").put(
                    "image_sha256",
                    MessageDigest.getInstance("SHA-256").digest(png)
                        .joinToString("") { "%02x".format(it) })
                .put("png", Base64.encodeToString(png, Base64.NO_WRAP))
            val file = File(targetContext.filesDir, "event-map/$id-$hash.json")
            file.parentFile?.mkdirs()
            file.writeText(record.toString())
            val original = file.readBytes()
            activity = startActivitySync(
                Intent(
                    targetContext,
                    MapResolutionPreviewActivity::class.java
                ).putExtra("event", event.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            fun nodes(): List<View> {
                val out = mutableListOf<View>()
                fun walk(v: View) {
                    out.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(activity!!.window.decorView)
                return out
            }

            var ready = false
            repeat(900) {
                if (!ready) {
                    runOnMainSync {
                        ready = nodes().any { it.contentDescription == "사건 좌표 지도 · 현재 렌더링" }
                    }
                    if (!ready) Thread.sleep(50)
                }
            }
            check(ready) { "Map did not refresh at display resolution" }
            check(original.contentEquals(file.readBytes())) { "Saved evidence changed" }
            var width = 0
            var height = 0
            runOnMainSync {
                val v = nodes().filterIsInstance<ImageView>()
                    .single { it.contentDescription == "사건 좌표 지도 · 현재 렌더링" }
                val b = (v.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                width = b.width
                height = b.height
                check(width >= v.width && height >= v.height)
            }
            val shot = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.filesDir, "map-resolution.png").outputStream()
                .use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
            File(
                targetContext.filesDir,
                "map-resolution-result.json"
            ).writeText(
                JSONObject().put("pass", true).put("saved_bytes_unchanged", true)
                    .put("display_width", width).put("display_height", height)
                    .put("seed_width", 220).put("seed_height", 100).put(
                    "scope",
                    "Isolated native map panel with synthetic low-resolution stored image; not event-time OEM navigation capture"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: native display resolution map and immutable stored image\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
