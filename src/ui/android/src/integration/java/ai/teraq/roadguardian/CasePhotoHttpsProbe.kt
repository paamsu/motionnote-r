package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Deterministic image fixture through production conversion, TLS, typed receipts and private UI. */
class CasePhotoHttpsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        var step = "identity"
        try {
            check(!targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE))
            val fixture = IntegrationIdentity.install(targetContext, "photo_token")
            val client = AuthenticatedEventsClient(targetContext)
            val session = client.sessionFingerprint()
            val event = client.createOwnerCaseForDisplay(
                JSONObject().put("schema", "road-guardian.owner-case.v1")
                    .put("request_id", UUID.randomUUID().toString())
                    .put("vehicle_id", client.registeredVehiclesForDisplay().value.single())
                    .put("category", "incident").put("reported_at", JSONObject.NULL)
                    .put("title", "사진 첨부 흐름 시험").put("description", "실제 사진이나 사건이 아닌 그림 입력 시험입니다.")
                    .put(
                        "own_vehicle",
                        JSONObject().put("plate", "TEST-PHOTO-01").put("model", "시험 입력")
                            .put("color", "미기재")
                    ), session
            ).value
            val id = event.getString("id")
            step = "android-image-conversion"
            val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(30, 105, 120))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 40f
            }
            canvas.drawText("PHOTO INPUT TEST", 50f, 90f, paint)
            paint.color = Color.rgb(170, 200, 205)
            canvas.drawRoundRect(80f, 150f, 560f, 330f, 24f, 24f, paint)
            val png = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png))
            bitmap.recycle()
            val raw = CasePhotoDraft.fromBytes(png.toByteArray())
            val selected =
                File.createTempFile("photo-selected-test-", ".png", targetContext.cacheDir)
            try {
                selected.writeBytes(png.toByteArray())
                check(
                    CasePhotoDraft.fromUri(targetContext, android.net.Uri.fromFile(selected))
                        .contentEquals(raw)
                )
                selected.outputStream().use { output ->
                    val block = ByteArray(1024 * 1024)
                    repeat(12) { output.write(block) }
                    output.write(0)
                }
                check(runCatching {
                    CasePhotoDraft.fromUri(
                        targetContext,
                        android.net.Uri.fromFile(selected)
                    )
                }.isFailure)
            } finally {
                selected.delete()
            }
            val body = JSONObject().put("schema", "road-guardian.case-photo-upload.v1")
                .put("request_id", UUID.randomUUID().toString())
                .put("caption", "그림으로 만든 전송 시험 · 실제 현장 사진 아님").put(
                    "image_base64",
                    android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
                )
            step = "https-save-and-read"
            val photo = client.savePhotoForDisplay(id, body, session).value
            check(
                photo.uploadSha256 == CasePhoto.hash(raw) && client.savePhotoForDisplay(
                    id,
                    body,
                    session
                ).value == photo
            )
            check(client.photosForDisplay(id).value.single() == photo)
            val stored = client.photoForDisplay(photo).value
            check(CasePhoto.hash(stored) == photo.sha256)
            check((runCatching {
                client.savePhotoForDisplay(
                    id,
                    JSONObject(body.toString()).put("caption", "바뀐 내용"),
                    session
                )
            }.exceptionOrNull() as? AccountAccessException)?.reason == AccountAccessException.Reason.CONFLICT)
            check(client.eventForDisplay(id).value.getString("hash") == event.getString("hash"))
            step = "verified-photo-package"
            val packageRejected = PhotoPackageChecks.run(targetContext, client, id, photo)
            step = "typed-content-rejection"
            val envelope = JSONObject(
                AuthenticatedTransport().photo(
                    BuildConfig.AUTH_API_URL,
                    id,
                    photo.id,
                    fixture.getString("photo_token")
                )
            )
            var rejected = 0
            for (change in listOf<(JSONObject) -> Unit>(
                {
                    it.put(
                        "event_id",
                        UUID.randomUUID().toString()
                    )
                },
                { it.put("sensor_original", true) },
                { it.put("metadata_removed", "true") },
                { it.put("width", 1601) },
                { it.put("scope", "all_subjects") })) {
                check(runCatching {
                    CasePhoto.parse(
                        JSONObject(envelope.toString()).also(change),
                        id,
                        photo.id
                    )
                }.isFailure)
                rejected++
            }
            check(runCatching {
                photo.content(
                    JSONObject(envelope.toString()).put(
                        "image_base64",
                        "AAAA"
                    )
                )
            }.isFailure)
            rejected++
            step = "same-vehicle-isolation"
            IntegrationIdentity.install(targetContext, "other_owner_token")
            val other = AuthenticatedEventsClient(targetContext)
            check((runCatching { other.photosForDisplay(id) }.exceptionOrNull() as? AccountAccessException)?.reason == AccountAccessException.Reason.NOT_FOUND)
            check((runCatching { other.photoForDisplay(photo) }.exceptionOrNull() as? AccountAccessException)?.reason == AccountAccessException.Reason.NOT_FOUND)
            IntegrationIdentity.install(targetContext, "photo_token")
            fun labels(): String {
                val values = mutableListOf<String>()
                runOnMainSync {
                    fun collect(view: View) {
                        if (view is TextView && view.isShown) values.add(view.text.toString())
                        if (view is ViewGroup) for (i in 0 until view.childCount) collect(
                            view.getChildAt(
                                i
                            )
                        )
                    }
                    collect(screen!!.window.decorView)
                }
                return values.joinToString("\n")
            }
            step = "independent-photo-entry"
            screen = startActivitySync(
                Intent(
                    targetContext,
                    AccountEventsActivity::class.java
                ).putExtra(
                    "notification_event_id",
                    id
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            val entryDeadline = android.os.SystemClock.elapsedRealtime() + 20000
            while (!labels().contains("기록에 첨부한 사진") && android.os.SystemClock.elapsedRealtime() < entryDeadline) Thread.sleep(
                50
            )
            check(labels().contains("기록에 첨부한 사진"))
            step = "actual-photo-activity"
            screen = startActivitySync(
                Intent(
                    targetContext,
                    CasePhotosActivity::class.java
                ).putExtra("event_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val deadline = android.os.SystemClock.elapsedRealtime() + 20000
            while (!labels().contains("보관한 사진 1/4") && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(
                50
            )
            check(
                labels().contains("보관한 사진 1/4") && labels().contains("그림으로 만든 전송 시험") && labels().contains(
                    "사진 선택 후 확인"
                ) && labels().contains("TEST-PHOTO-01")
            )
            val bounds = Rect()
            runOnMainSync {
                fun find(view: View) {
                    if (view is ImageView && view.drawable != null) {
                        check(view.getGlobalVisibleRect(bounds))
                        val a = IntArray(2)
                        val b = IntArray(2)
                        view.getLocationOnScreen(a)
                        view.getLocationInWindow(b)
                        bounds.offset(a[0] - b[0], a[1] - b[1])
                    }
                    if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))
                }
                find(screen!!.window.decorView)
            }
            check(!bounds.isEmpty)
            var ready: Bitmap? = null
            val frameDeadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < frameDeadline) {
                val frame = checkNotNull(uiAutomation.takeScreenshot())
                var matching = 0
                for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(frame.height) step 3)
                    for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(frame.width) step 3) {
                        val c = frame.getPixel(x, y)
                        if (kotlin.math.abs(Color.red(c) - 30) < 4 && kotlin.math.abs(Color.green(c) - 105) < 4 && kotlin.math.abs(
                                Color.blue(c) - 120
                            ) < 4
                        ) matching++
                    }
                if (matching > 1000) {
                    ready = frame
                    break
                }
                frame.recycle()
                Thread.sleep(50)
            }
            val frame = checkNotNull(ready)
            File(targetContext.filesDir, "case-photo-v160.png").outputStream()
                .use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            frame.recycle()
            IntegrationIdentity.install(targetContext, "other_owner_token")
            val clearDeadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (!labels().contains("계정 연결 또는 표시 기한") && android.os.SystemClock.elapsedRealtime() < clearDeadline) Thread.sleep(
                50
            )
            check(
                labels().contains("계정 연결 또는 표시 기한") && !labels().contains("그림으로 만든 전송 시험") && !labels().contains(
                    "TEST-PHOTO-01"
                )
            )
            runOnMainSync {
                fun noPhoto(view: View) {
                    check(view !is ImageView || view.drawable == null)
                    if (view is ViewGroup) for (i in 0 until view.childCount) noPhoto(
                        view.getChildAt(
                            i
                        )
                    )
                }
                noPhoto(screen!!.window.decorView)
            }
            File(targetContext.filesDir, "case-photo-v160.json").writeText(
                JSONObject().put("event_id", id).put("photo_id", photo.id)
                    .put("package_mutations_rejected", packageRejected)
                    .put("sha256", photo.sha256).put("bytes", photo.bytes)
                    .put("rejected_mutations", rejected).put("other_subject_hidden", true)
                    .put(
                        "input",
                        "deterministic drawing; synthetic login; no actual scene photo or incident"
                    ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: Android photo conversion, TLS upload/read/hash/retry/conflict, photo ZIP v2 and v1/disabled/empty compatibility, $packageRejected re-hashed package mutations rejected, original unchanged, co-owner 404, $rejected typed mutations rejected, actual photo entry/activity and account-change clearing; notes_enabled=${BuildConfig.REVIEW_NOTES_ENABLED}. Generated drawing/test login; no picker, typing or save tap.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", "FAIL step=$step: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
            LoginStateStore(targetContext).write(net.openid.appauth.AuthState())
        }
    }
}
