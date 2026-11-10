package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Independent reference photo and nonvisual measurement inputs, with actual HTTPS/hash/package linkage. */
class MeasurementMediaHttpsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            val fixture = IntegrationIdentity.install(targetContext, "measurement_photo_token")
            val id = fixture.getString("measurement_case_event_id")
            val client = AuthenticatedEventsClient(targetContext)
            val session = client.sessionFingerprint()
            val event = client.eventForDisplay(id).value
            val hash = event.getString("hash")
            val vehicle = fixture.getJSONObject("measurement_before").getString("vehicle_id")
            val rows = JSONArray()
            val receipts = mutableListOf<MeasurementProtocol.Receipt>()
            for (key in listOf("measurement_before", "measurement_after", "measurement_mismatch")) {
                val input = fixture.getJSONObject(key)
                val receipt = client.saveMeasurementForDisplay(id, vehicle, input, session).value
                val read = client.measurementForDisplay(id, receipt.summary.id, vehicle).value
                check(read.summary == receipt.summary && read.data.toString() == receipt.data.toString())
                File(targetContext.filesDir, "$key-v198.json").writeText(input.toString())
                rows.put(
                    JSONObject().put("input", key).put("run_id", read.summary.id)
                        .put("canonical_sha256", read.summary.hash)
                )
                receipts.add(read)
            }
            val original =
                targetContext.assets.open("measurement-reference-v198.png").use { it.readBytes() }
            val converted = CasePhotoDraft.fromBytes(original)
            val photo =
                client.photosForDisplay(id).value.firstOrNull { it.caption == "AI 합성 참고 사진 · 전압·실측·수리 증명 아님" }
                    ?: client.savePhotoForDisplay(
                        id,
                        JSONObject().put("schema", "road-guardian.case-photo-upload.v1")
                            .put("request_id", UUID.randomUUID().toString())
                            .put("caption", "AI 합성 참고 사진 · 전압·실측·수리 증명 아님").put(
                                "image_base64",
                                android.util.Base64.encodeToString(
                                    converted,
                                    android.util.Base64.NO_WRAP
                                )
                            ),
                        session
                    ).value
            val bytes = client.photoForDisplay(photo).value
            check(
                photo.uploadSha256 == CasePhoto.hash(converted) && photo.sha256 == CasePhoto.hash(
                    bytes
                )
            )
            File(targetContext.filesDir, "measurement-stored-photo-v198.jpg").writeBytes(bytes)
            val compared =
                client.measurementComparisonForDisplay(id, hash, receipts[0], receipts[1]).value
            val held =
                client.measurementComparisonForDisplay(id, hash, receipts[0], receipts[2]).value
            check(compared.matched && !held.matched)
            File(
                targetContext.filesDir,
                "measurement-comparison-v198.json"
            ).writeText(compared.raw.toString(2))
            File(
                targetContext.filesDir,
                "measurement-held-v198.json"
            ).writeText(held.raw.toString(2))
            val archive = client.measurementPackage(
                id,
                hash,
                vehicle,
                receipts[0].summary,
                receipts[1].summary
            )
            try {
                check(
                    MeasurementPackageVerifier.verify(
                        archive,
                        id,
                        hash,
                        vehicle,
                        receipts[0].summary.id,
                        receipts[1].summary.id
                    ).matched
                )
                archive.copyTo(
                    File(targetContext.filesDir, "measurement-media-pair-v198.zip"),
                    overwrite = true
                )
            } finally {
                archive.delete()
            }
            check(client.eventForDisplay(id).value.getString("hash") == hash)
            screen = startActivitySync(
                android.content.Intent(
                    targetContext,
                    CasePhotosActivity::class.java
                ).putExtra("event_id", id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            fun pictures(): List<android.widget.ImageView> {
                val values = mutableListOf<android.widget.ImageView>()
                runOnMainSync {
                    fun visit(v: android.view.View) {
                        if (v is android.widget.ImageView && v.isShown && v.drawable is android.graphics.drawable.BitmapDrawable) values.add(
                            v
                        )
                        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) visit(
                            v.getChildAt(
                                i
                            )
                        )
                    }
                    visit(screen!!.window.decorView)
                }
                return values
            }

            val end = android.os.SystemClock.elapsedRealtime() + 10000
            while (pictures().none { it.width > 0 && it.height > 0 } && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(
                80
            )
            val picture = pictures().single()
            var expected: Bitmap? = null
            val at = IntArray(2)
            var drawing: Result<Unit>? = null
            runOnMainSync {
                drawing = runCatching {
                    picture.getLocationOnScreen(at)
                    expected =
                        Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888)
                            .apply {
                                eraseColor(android.graphics.Color.WHITE)
                                picture.draw(android.graphics.Canvas(this))
                            }
                }
            }
            checkNotNull(drawing).getOrThrow()
            val reference = checkNotNull(expected)
            var matched: Bitmap? = null
            var nativeError = Double.POSITIVE_INFINITY
            val presentedEnd = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < presentedEnd) {
                val frame = checkNotNull(uiAutomation.takeScreenshot())
                var error = 0L
                var count = 0
                for (y in 0 until reference.height step 3) for (x in 0 until reference.width step 3) {
                    val sx = at[0] + x
                    val sy = at[1] + y
                    if (sx !in 0 until frame.width || sy !in 0 until frame.height) {
                        error += 765
                        count++
                        continue
                    }
                    val a = reference.getPixel(x, y)
                    val b = frame.getPixel(sx, sy)
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
                    count++
                }
                nativeError = error.toDouble() / (count * 3)
                var clock = 0
                for (y in 20 until minOf(100, frame.height)) for (x in 20 until minOf(
                    180,
                    frame.width
                )) if (android.graphics.Color.red(
                        frame.getPixel(
                            x,
                            y
                        )
                    ) < 140 && android.graphics.Color.green(
                        frame.getPixel(
                            x,
                            y
                        )
                    ) < 140 && android.graphics.Color.blue(frame.getPixel(x, y)) < 140
                ) clock++
                if (nativeError < 5 && clock > 100) {
                    matched = frame
                    break
                }
                frame.recycle()
                Thread.sleep(80)
            }
            reference.recycle()
            checkNotNull(matched) { "Reference photo not presented: $nativeError" }.let { frame ->
                File(targetContext.filesDir, "measurement-reference-phone-v198.png").outputStream()
                    .use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                frame.recycle()
            }
            File(targetContext.filesDir, "measurement-media-receipt-v198.json").writeText(
                JSONObject().put("event_id", id).put("case_sha256", hash).put("runs", rows)
                    .put("source_photo_sha256", CasePhoto.hash(original)).put("photo_id", photo.id)
                    .put("stored_photo_sha256", photo.sha256)
                    .put("photo_is_reference_only", true).put("photo_proves_voltage", false)
                    .put("native_photo_error", nativeError).put("actual_tls", true)
                    .put("actual_capture", false).toString(2)
            )
            result.putString(
                "stream",
                "PASS:3 actual private measurement saves/reads, same-record reference photo upload/read, matched/held computation, verified selected ZIP and unchanged authored original. No UI taps or photo-based voltage inference.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
            LoginStateStore(targetContext).write(net.openid.appauth.AuthState())
        }
    }
}
