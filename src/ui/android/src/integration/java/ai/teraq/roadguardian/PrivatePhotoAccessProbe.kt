package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Synthetic HTTPS attachments and real shared renderer; method calls, not touch input. */
class PrivatePhotoAccessProbe : Instrumentation() {
    private var screen: PhotoPanelPreviewActivity? = null
    private val rows = JSONArray()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun control(body: JSONObject? = null): JSONObject {
        val c =
            URL("https://localhost:9443/api/dev/private-photo-control").openConnection() as HttpsURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 5000
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(c.responseCode == 200)
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun views(): List<View> {
        val out = mutableListOf<View>()
        runOnMainSync {
            fun visit(v: View) {
                out.add(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
            }
            screen?.let { visit(it.window.decorView) }
        }
        return out
    }

    private fun labels() = views().filterIsInstance<TextView>().filter { it.isShown }
        .joinToString("\n") { it.text.toString() }

    private fun until(description: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 18000
        while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(60)
        check(condition()) { "$description: ${labels()}" }
    }

    private fun await(text: String) = until("Missing $text") { labels().contains(text) }
    private fun open(id: String) {
        runOnMainSync { screen?.finish() }
        screen = startActivitySync(
            Intent(targetContext, PhotoPanelPreviewActivity::class.java).putExtra(
                "event_id",
                id
            ).putExtra("photoreal_demo", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as PhotoPanelPreviewActivity
    }

    private fun save(name: String) {
        if (name == "failure") {
            val frame = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.filesDir, "private-photo-$name-v229-final.png").outputStream()
                .use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            frame.recycle()
            return
        }
        val phrase = when (name) {
            "normal-metadata" -> "서버 보관"
            "account-cleared", "lease-cleared", "host-cleared" -> "사진을 지웠습니다"
            "unsupported" -> "이 서비스는 첨부 사진 조회를 지원하지 않습니다."
            "late-response" -> "계정 정보가 변경됐습니다."
            else -> "이 계정에는 사진 조회 권한이 없습니다."
        }
        await(phrase)
        waitForIdleSync()
        val end = SystemClock.elapsedRealtime() + 5000
        var matchedRatio = 0.0
        while (SystemClock.elapsedRealtime() < end) {
            data class Anchor(val bitmap: Bitmap, val x: Int, val y: Int)

            val anchors = mutableListOf<Anchor>()
            var ready = true
            val texts = views().filterIsInstance<TextView>()
                .filter { it.isShown && it.text.toString().contains(phrase) }
            runOnMainSync {
                for (v in texts) {
                    val visible = Rect()
                    val position = IntArray(2)
                    v.getLocationOnScreen(position)
                    if (v.width <= 0 || v.height <= 0 || !v.getGlobalVisibleRect(visible) || visible.width() != v.width || visible.height() != v.height) {
                        ready = false
                        continue
                    }
                    val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                    v.draw(Canvas(bitmap))
                    anchors.add(Anchor(bitmap, position[0], position[1]))
                }
            }
            val frame = checkNotNull(uiAutomation.takeScreenshot())
            var total = 0
            var matched = 0
            if (ready && anchors.isNotEmpty()) for (a in anchors) for (y in 0 until a.bitmap.height) for (x in 0 until a.bitmap.width) {
                val c = a.bitmap.getPixel(x, y)
                val alpha = Color.alpha(c)
                if (alpha < 32) continue
                total++
                val sx = a.x + x
                val sy = a.y + y
                if (sx !in 0 until frame.width || sy !in 0 until frame.height) continue
                val actual = frame.getPixel(sx, sy)
                fun channel(value: Int) = (value * alpha + 255 * (255 - alpha) + 127) / 255
                if (kotlin.math.abs(channel(Color.red(c)) - Color.red(actual)) < 12 && kotlin.math.abs(
                        channel(Color.green(c)) - Color.green(actual)
                    ) < 12 && kotlin.math.abs(channel(Color.blue(c)) - Color.blue(actual)) < 12
                ) matched++
            }
            anchors.forEach { it.bitmap.recycle() }
            matchedRatio = if (total > 0) matched.toDouble() / total else 0.0
            if (total > 30 && matchedRatio >= .97) {
                File(targetContext.filesDir, "private-photo-$name-v229-final.png").outputStream()
                    .use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                frame.recycle()
                return
            }
            frame.recycle()
            Thread.sleep(60)
        }
        error("Native state text absent: $name ratio=$matchedRatio")
    }

    private fun absent() {
        check(views().filterIsInstance<ImageView>().none { it.drawable != null })
        check(views().none { it is CasePhotoComparison })
        val all = views().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        check(!all.contains("합성 참고 사진") && !all.contains("서버 보관"))
    }

    private fun loaded() {
        await("합성 참고 사진 2")
        waitForIdleSync()
        until("Two attached images") {
            views().filterIsInstance<ImageView>()
                .count { it.drawable is BitmapDrawable && it.width > 0 } == 2
        }
    }

    private fun expandMetadata() {
        val b = views().filterIsInstance<TextView>().first { it.text.toString() == "사진 보관 정보 펼치기" }
        runOnMainSync { check(b.performClick()) }
        await("서버 보관")
    }

    private fun imagePixels(): JSONArray {
        val errors = JSONArray()
        val images = views().filterIsInstance<ImageView>().filter { it.drawable is BitmapDrawable }
        check(images.size == 2)
        for ((index, v) in images.withIndex()) {
            runOnMainSync { v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), true) }
            waitForIdleSync()
            var ref: Bitmap? = null
            var bounds = Rect()
            var failure: Throwable? = null
            runOnMainSync {
                try {
                    val pos = IntArray(2)
                    v.getLocationOnScreen(pos)
                    val visible = Rect()
                    check(v.getGlobalVisibleRect(visible) && visible.width() == v.width && visible.height() == v.height) { "Photo clipped $visible ${v.width}x${v.height}" }
                    bounds = Rect(pos[0], pos[1], pos[0] + v.width, pos[1] + v.height)
                    ref = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        v.draw(Canvas(it))
                    }
                } catch (t: Throwable) {
                    failure = t
                }
            }
            failure?.let { throw it }
            val reference = checkNotNull(ref)
            var error = 999.0
            val end = SystemClock.elapsedRealtime() + 5000
            try {
                while (SystemClock.elapsedRealtime() < end) {
                    val frame = checkNotNull(uiAutomation.takeScreenshot())
                    var total = 0L
                    var samples = 0
                    for (y in 0 until reference.height step 3) for (x in 0 until reference.width step 3) {
                        val sx = bounds.left + x
                        val sy = bounds.top + y
                        check(sx in 0 until frame.width && sy in 0 until frame.height)
                        val a = reference.getPixel(x, y)
                        val b = frame.getPixel(sx, sy)
                        total += kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(
                            Color.green(a) - Color.green(b)
                        ) + kotlin.math.abs(Color.blue(a) - Color.blue(b))
                        samples++
                    }
                    error = total.toDouble() / (samples * 3)
                    if (error < 5) {
                        File(
                            targetContext.filesDir,
                            "private-photo-image-$index-v229-final.png"
                        ).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        frame.recycle()
                        break
                    }
                    frame.recycle()
                    Thread.sleep(60)
                }
                check(error < 5) { "Photo $index CPU/native mean RGB error $error" }
                errors.put(error)
            } finally {
                reference.recycle()
            }
        }
        return errors
    }

    override fun onStart() {
        val result = Bundle()
        try {
            control(JSONObject().put("mode", "normal"))
            val fixture = IntegrationIdentity.install(targetContext, "photo_token")
            val id = fixture.getString("private_case_event_id")
            val provenance =
                JSONObject(File(targetContext.filesDir, "private-access-result.json").readText())
            check(provenance.getString("event_id") == id)
            val client = AuthenticatedEventsClient(targetContext)
            val photos = client.photosForDisplay(id).value
            check(photos.size == 2)
            for (p in photos) {
                val expected = (0 until provenance.getJSONArray("photos")
                    .length()).map { provenance.getJSONArray("photos").getJSONObject(it) }
                    .single { it.getString("photo_id") == p.id }
                check(expected.getString("source_sha256").matches(Regex("[a-f0-9]{64}")))
                check(expected.getString("upload_sha256") == p.uploadSha256)
                check(expected.getString("stored_sha256") == p.sha256)
                check(CasePhoto.hash(client.photoForDisplay(p).value) == p.sha256)
            }
            open(id)
            loaded()
            val errors = imagePixels()
            expandMetadata()
            save("normal-metadata")
            rows.put(
                JSONObject().put("state", "normal").put("image_mean_rgb_errors", errors)
                    .put("https_source_receipt_binding", true)
            )
            val old =
                views().filterIsInstance<ImageView>().map { (it.drawable as BitmapDrawable).bitmap }
            IntegrationIdentity.install(targetContext, "other_owner_token")
            await("사진을 지웠습니다")
            absent()
            check(old.all { it.isRecycled })
            save("account-cleared")
            rows.put(JSONObject().put("state", "account-cleared"))
            for (key in listOf("other_owner_token", "no_vehicle_token")) {
                IntegrationIdentity.install(targetContext, key)
                open(id)
                await("사진")
                until("Private access rejection") { labels().contains("사진 다시 조회") }
                absent()
                check(!labels().contains("개인 확인 메모"))
                check(
                    labels().contains("이 계정에는 사진 조회 권한이 없습니다. 계정 연결을 확인해 주세요.") && !labels().contains(
                        "지원하지 않습니다"
                    )
                )
                save(key)
                rows.put(JSONObject().put("state", key).put("message", labels()))
            }
            IntegrationIdentity.install(targetContext, "photo_token")
            open(id)
            loaded()
            expandMetadata()
            val panel = views().filterIsInstance<CasePhotosPanel>().single()
            runOnMainSync {
                CasePhotosPanel::class.java.getDeclaredField("lease").apply { isAccessible = true }
                    .set(
                        panel,
                        AccountDisplayLease(
                            System.currentTimeMillis() + 31000,
                            System.currentTimeMillis(),
                            SystemClock.elapsedRealtime()
                        )
                    )
            }
            await("사진을 지웠습니다")
            absent()
            save("lease-cleared")
            rows.put(JSONObject().put("state", "shortened-display-lease-cleared"))
            open(id)
            loaded()
            expandMetadata()
            runOnMainSync { screen!!.displayAllowed = false }
            await("사진을 지웠습니다")
            absent()
            save("host-cleared")
            rows.put(JSONObject().put("state", "host-cleared"))
            control(JSONObject().put("mode", "delay").put("event_id", id))
            open(id)
            until("Actual photo request arrived") { control().optBoolean("arrived") }
            IntegrationIdentity.install(targetContext, "other_owner_token")
            control(JSONObject().put("mode", "release"))
            until("Late response completed") { !labels().contains("확인하고 있습니다") }
            absent()
            Thread.sleep(700)
            absent()
            save("late-response")
            rows.put(
                JSONObject().put("state", "late-response-account-change")
                    .put("actual_request_arrived", true)
            )
            control(JSONObject().put("mode", "unsupported"))
            IntegrationIdentity.install(targetContext, "photo_token")
            open(id)
            await("이 서비스는 첨부 사진 조회를 지원하지 않습니다.")
            absent()
            check(!labels().contains("사진 다시 조회") && !labels().contains("메모"))
            save("unsupported")
            rows.put(JSONObject().put("state", "unsupported-no-retry"))
            File(targetContext.filesDir, "private-photo-result-v229-final.json").writeText(
                JSONObject().put("states", rows).put("direct_taps", false)
                    .put("actual_oem_restriction", false).put("production_login", false)
                    .put("lease_test", "shortened local display lease, not actual JWT expiry").put(
                    "source_provenance",
                    "server fixture source/upload/stored receipts plus actual HTTPS byte hash; original source independently verified by fixture generator"
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: ${rows.length()} private photo states; actual HTTPS bytes, two native image frames, account/lease/host removal, arrived/released late response, denied accounts and unsupported UI. Method-driven preview; no taps, production login or actual OEM restriction claim.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            runCatching { save("failure") }
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runCatching {
                control(JSONObject().put("mode", "release"))
                control(JSONObject().put("mode", "normal"))
            }
            runOnMainSync { screen?.finish() }
            LoginStateStore(targetContext).write(net.openid.appauth.AuthState())
        }
    }
}
