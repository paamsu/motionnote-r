package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Actual private upload/read and shared pair renderer. No UI controls are activated. */
class PhotoPairHttpsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: PhotoPanelPreviewActivity? = null
        try {
            IntegrationIdentity.install(targetContext, "photo_token")
            val client = AuthenticatedEventsClient(targetContext)
            val session = client.sessionFingerprint()
            val event = client.createOwnerCaseForDisplay(
                JSONObject().put("schema", "road-guardian.owner-case.v1")
                    .put("request_id", UUID.randomUUID().toString())
                    .put("vehicle_id", client.registeredVehiclesForDisplay().value.single())
                    .put("category", "road").put("reported_at", JSONObject.NULL)
                    .put("title", "도로 사진 A/B 합성 비교")
                    .put("description", "AI 합성 원본과 보수 가정 편집본 · 실제 촬영 시각·도로 보수 확인 아님")
                    .put(
                        "own_vehicle",
                        JSONObject().put("plate", "TEST-PAIR-197").put("model", "도로 사진 비교")
                            .put("color", "")
                    ), session
            ).value
            val id = event.getString("id")
            val receipts = JSONArray()
            for ((index, asset) in listOf(
                "pair-road-a-v197.png",
                "pair-road-b-v197.png"
            ).withIndex()) {
                val original = targetContext.assets.open(asset).use { it.readBytes() }
                val converted = CasePhotoDraft.fromBytes(original)
                val caption = if (index == 0) "합성 A · 포트홀 가정" else "합성 B · 보수 가정"
                val upload = JSONObject().put("schema", "road-guardian.case-photo-upload.v1")
                    .put("request_id", UUID.randomUUID().toString()).put("caption", caption).put(
                    "image_base64",
                    android.util.Base64.encodeToString(converted, android.util.Base64.NO_WRAP)
                )
                val receipt = client.savePhotoForDisplay(id, upload, session).value
                check(receipt.uploadSha256 == CasePhoto.hash(converted))
                check(CasePhoto.hash(client.photoForDisplay(receipt).value) == receipt.sha256)
                File(
                    targetContext.filesDir,
                    "pair-stored-$index-v197.jpg"
                ).writeBytes(client.photoForDisplay(receipt).value)
                receipts.put(
                    JSONObject().put("asset", asset).put("source_sha256", CasePhoto.hash(original))
                        .put("photo_id", receipt.id).put("stored_sha256", receipt.sha256)
                        .put("upload_sha256", receipt.uploadSha256)
                )
            }
            fun views(): List<View> {
                val values = mutableListOf<View>()
                runOnMainSync {
                    fun visit(v: View) {
                        if (v.isShown) values.add(v)
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(screen!!.window.decorView)
                }
                return values
            }

            fun labels() =
                views().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }

            fun await(text: String) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 15000
                while (!labels().contains(text) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(
                    80
                )
                check(labels().contains(text)) { "Missing $text; ${labels()}" }
            }

            val rows = JSONArray()
            for (mode in listOf("day", "night", "large")) {
                PhotoPanelPreviewActivity.previewFontScale = if (mode == "large") 1.5f else 1f
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        PhotoPanelPreviewActivity::class.java
                    ).putExtra("event_id", id).putExtra("photoreal_demo", true)
                        .putExtra("night", mode == "night").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ) as PhotoPanelPreviewActivity
                await("합성 B")
                waitForIdleSync()
                var pictures = emptyList<ImageView>()
                var previous = emptyList<ImageView>()
                var stable = 0
                val layoutDeadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (android.os.SystemClock.elapsedRealtime() < layoutDeadline) {
                    pictures = views().filterIsInstance<ImageView>()
                        .filter { it.drawable is BitmapDrawable }
                    var measured = false
                    runOnMainSync {
                        measured =
                            pictures.size == 2 && pictures.all { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
                    }
                    stable = if (measured && pictures == previous) stable + 1 else 0
                    if (stable >= 2) break
                    previous = pictures
                    Thread.sleep(80)
                    waitForIdleSync()
                }
                check(stable >= 2) { "Pair layout did not settle" }
                check(labels().contains("촬영 시각·동일 장소 미확인") && !labels().contains("보수 완료"))
                var referenceFailure: Throwable? = null
                val texts = views().filterIsInstance<TextView>()
                runOnMainSync {
                    try {
                        for (v in texts) {
                            val layout = v.layout ?: continue
                            for (line in 0 until layout.lineCount) {
                                check(layout.getEllipsisCount(line) == 0)
                                check(layout.getLineMax(line) <= v.width - v.compoundPaddingLeft - v.compoundPaddingRight + 2)
                            }
                        }
                    } catch (t: Throwable) {
                        referenceFailure = t
                    }
                }
                referenceFailure?.let { throw it }
                data class Target(val bounds: Rect, val bitmap: Bitmap)

                val targets = mutableListOf<Target>()
                runOnMainSync {
                    try {
                        for (v in if (mode == "large") pictures.take(1) else pictures) {
                            val at = IntArray(2)
                            v.getLocationOnScreen(at)
                            val visible = Rect()
                            check(v.getGlobalVisibleRect(visible))
                            check(visible.width() >= v.width && visible.height() >= v.height) { "$mode: image bounds $visible vs ${v.width}x${v.height} at ${at.toList()}" }
                            val reference =
                                Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                            reference.eraseColor(
                                if (mode == "night") android.graphics.Color.rgb(
                                    24,
                                    29,
                                    35
                                ) else android.graphics.Color.WHITE
                            )
                            v.draw(Canvas(reference))
                            targets.add(
                                Target(
                                    Rect(
                                        at[0],
                                        at[1],
                                        at[0] + v.width,
                                        at[1] + v.height
                                    ), reference
                                )
                            )
                        }
                    } catch (t: Throwable) {
                        referenceFailure = t
                    }
                }
                referenceFailure?.let { throw it }
                var frame: Bitmap? = null
                var errors = emptyList<Double>()
                val deadline = android.os.SystemClock.elapsedRealtime() + 10000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    val candidate = checkNotNull(uiAutomation.takeScreenshot())
                    errors = targets.map { t ->
                        var error = 0L
                        var samples = 0
                        for (y in 0 until t.bitmap.height step 3) for (x in 0 until t.bitmap.width step 3) {
                            val sx = t.bounds.left + x
                            val sy = t.bounds.top + y
                            if (sx !in 0 until candidate.width || sy !in 0 until candidate.height) {
                                error += 765
                                samples++
                                continue
                            }
                            val a = t.bitmap.getPixel(x, y)
                            val b = candidate.getPixel(sx, sy)
                            error += kotlin.math.abs(
                                android.graphics.Color.red(a) - android.graphics.Color.red(
                                    b
                                )
                            ) + kotlin.math.abs(
                                android.graphics.Color.green(a) - android.graphics.Color.green(
                                    b
                                )
                            ) + kotlin.math.abs(
                                android.graphics.Color.blue(a) - android.graphics.Color.blue(
                                    b
                                )
                            )
                            samples++
                        }
                        error.toDouble() / (samples * 3)
                    }
                    if (errors.all { it < 5 }) {
                        frame = candidate
                        break
                    }
                    candidate.recycle()
                    Thread.sleep(80)
                }
                targets.forEach { it.bitmap.recycle() }
                val bitmap =
                    checkNotNull(frame) { "Pair native images not visible for $mode: $errors" }
                File(targetContext.filesDir, "photo-pair-$mode-v197.png").outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                rows.put(
                    JSONObject().put("mode", mode).put("native_image_errors", JSONArray(errors))
                        .put("both_images_native_verified", mode != "large")
                        .put("large_second_image_below_viewport", mode == "large")
                )
                if (mode == "large") {
                    val old = pictures.map { (it.drawable as BitmapDrawable).bitmap }
                    runOnMainSync { screen!!.displayAllowed = false }
                    await("사진을 지웠습니다")
                    check(views().filterIsInstance<ImageView>().none { it.drawable != null })
                    check(old.all { it.isRecycled })
                }
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            File(
                targetContext.filesDir,
                "photo-pair-result-v197.json"
            ).writeText(
                JSONObject().put("event_id", id).put("receipts", receipts).put("states", rows)
                    .put("direct_taps", false).put("actual_capture_or_repair", false).toString(2)
            )
            result.putString(
                "stream",
                "PASS: actual private upload/read/hash and3 pair render modes; native image pixels, no text overflow and host-condition bitmap recycling. No taps, capture timestamp or road repair claim.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            uiAutomation.takeScreenshot()?.let { b ->
                File(targetContext.filesDir, "photo-pair-failed-v197.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
            PhotoPanelPreviewActivity.previewFontScale = 1f
            LoginStateStore(targetContext).write(net.openid.appauth.AuthState())
        }
    }
}
