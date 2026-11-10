package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** AI image through actual private conversion and HTTPS; no real camera/picker claim. */
class PhotoSceneHttpsProbe : Instrumentation() {
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
                    .put("category", "incident").put("reported_at", JSONObject.NULL)
                    .put("title", "주변 장면 보관 시연")
                    .put("description", "AI 합성 이미지 · 실제 사건과 촬영 자료가 아닙니다.")
                    .put(
                        "own_vehicle",
                        JSONObject().put("plate", "DEMO-PHOTO-187").put("model", "사진 시연")
                            .put("color", "")
                    ), session
            ).value
            val id = event.getString("id")
            val original = targetContext.assets.open("garage-b2-v187.png").use { it.readBytes() }
            val converted = CasePhotoDraft.fromBytes(original)
            val caption = "AI 합성 시연 · B2 / 24 · 실제 촬영 사진 아님"
            val upload = JSONObject().put("schema", "road-guardian.case-photo-upload.v1")
                .put("request_id", UUID.randomUUID().toString()).put("caption", caption).put(
                "image_base64",
                android.util.Base64.encodeToString(converted, android.util.Base64.NO_WRAP)
            )
            val receipt = client.savePhotoForDisplay(id, upload, session).value
            check(receipt.uploadSha256 == CasePhoto.hash(converted))
            check(CasePhoto.hash(client.photoForDisplay(receipt).value) == receipt.sha256)
            fun views(): List<View> {
                val list = mutableListOf<View>()
                runOnMainSync {
                    fun visit(v: View) {
                        if (v.isShown) list.add(v)
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(screen!!.window.decorView)
                }
                return list
            }

            fun labels() =
                views().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }

            fun await(text: String) {
                val end = android.os.SystemClock.elapsedRealtime() + 15000
                while (!labels().contains(text) && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(
                    50
                )
                check(labels().contains(text)) { "Missing $text; visible=${labels()}" }
            }
            for (mode in listOf("day", "night", "large")) {
                PhotoPanelPreviewActivity.previewFontScale = if (mode == "large") 1.5f else 1f
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        PhotoPanelPreviewActivity::class.java
                    ).putExtra("event_id", id).putExtra("photoreal_demo", true)
                        .putExtra("night", mode == "night")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ) as PhotoPanelPreviewActivity
                await(caption)
                waitForIdleSync()
                Thread.sleep(500)
                check(labels().contains("보관한 사진 1/4"))
                val rendered = views()
                runOnMainSync {
                    val image = rendered.filterIsInstance<ImageView>().first { it.drawable != null }
                    val rect = android.graphics.Rect()
                    check(image.getGlobalVisibleRect(rect))
                    check(rect.width() == image.width && rect.height() == image.height)
                    for (label in rendered.filterIsInstance<TextView>()) {
                        val layout = checkNotNull(label.layout)
                        for (i in 0 until layout.lineCount) check(layout.getEllipsisCount(i) == 0)
                    }
                }
                val frame = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "photo-scene-$mode-v187.png").outputStream()
                    .use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                frame.recycle()
                if (mode == "large") {
                    runOnMainSync { screen!!.displayAllowed = false }
                    await("사진을 지웠습니다")
                    check(views().filterIsInstance<ImageView>().none { it.drawable != null })
                }
                runOnMainSync { screen!!.finish() }
                screen = null
            }
            File(
                targetContext.filesDir,
                "photo-scene-receipt-v187.json"
            ).writeText(
                JSONObject().put("event_id", id).put("photo_id", receipt.id)
                    .put("upload_sha256", receipt.uploadSha256).put("stored_sha256", receipt.sha256)
                    .put("source", "AI_generated_synthetic_scene").put("actual_camera", false)
                    .toString(2)
            )
            result.putString(
                "stream",
                "PASS: AI scene conversion, real private HTTPS upload/read/hash, day/night/large shared photo renderer, image visibility and host-condition clearing. No real camera, picker or taps.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
            PhotoPanelPreviewActivity.previewFontScale = 1f
            LoginStateStore(targetContext).write(net.openid.appauth.AuthState())
        }
    }
}
