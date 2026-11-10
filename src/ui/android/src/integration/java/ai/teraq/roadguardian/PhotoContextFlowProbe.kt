package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Phone emulator production preview/save over isolated TLS. The OS picker is bypassed with a synthetic draft. */
class PhotoContextFlowProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            IntegrationIdentity.install(targetContext, "photo_token")
            val client = AuthenticatedEventsClient(targetContext)
            val session = client.sessionFingerprint()
            val event = client.createOwnerCaseForDisplay(
                JSONObject().put("schema", "road-guardian.owner-case.v1")
                    .put("request_id", UUID.randomUUID().toString())
                    .put("vehicle_id", client.registeredVehiclesForDisplay().value.single())
                    .put("category", "road").put("reported_at", JSONObject.NULL)
                    .put("title", "합성 사진 촬영 맥락 시험")
                    .put("description", "합성 사진과 작성된 시각·장소·시야 · 실제 촬영 및 보수 확인 아님")
                    .put(
                        "own_vehicle",
                        JSONObject().put("plate", "TEST-CONTEXT").put("model", "").put("color", "")
                    ), session
            ).value
            val id = event.getString("id")
            fun draft(asset: String) =
                JSONObject().put("schema", "road-guardian.case-photo-upload.v1")
                    .put("request_id", UUID.randomUUID().toString())
                    .put("caption", "AI 합성 도로 참고 사진")
                    .put(
                        "image_base64",
                        android.util.Base64.encodeToString(
                            CasePhotoDraft.fromBytes(
                                targetContext.assets.open(asset).use { it.readBytes() }),
                            android.util.Base64.NO_WRAP
                        )
                    )

            val first = draft("pair-road-a-v197.png").put(
                "capture_context",
                JSONObject().put("source", "owner_reported")
                    .put("captured_at", "2026-11-01T12:00:00Z").put("place_id", "시험 도로 1")
                    .put("viewpoint_id", "차량 전방")
            )
            client.savePhotoForDisplay(id, first, session)
            activity = startActivitySync(
                Intent(
                    targetContext,
                    CasePhotosActivity::class.java
                ).putExtra("event_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val screen = activity
            fun texts(): List<TextView> {
                val out = mutableListOf<TextView>()
                runOnMainSync {
                    fun walk(v: View) {
                        if (v is TextView) out.add(v)
                        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                    }
                    walk(screen.window.decorView)
                }
                return out
            }

            fun await(label: String) {
                val end = android.os.SystemClock.elapsedRealtime() + 15000
                while (android.os.SystemClock.elapsedRealtime() < end) {
                    if (texts().any { it.text.toString() == label }) return
                    Thread.sleep(100)
                }
                error("Missing $label")
            }

            fun reveal(v: View) {
                runOnMainSync { v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), true) }
                waitForIdleSync()
                Thread.sleep(250)
            }

            fun click(label: String) {
                val v = texts().single { it.text.toString() == label }
                reveal(v)
                runOnMainSync { check(v.performClick()) }
                waitForIdleSync()
            }

            fun capture(name: String) {
                waitForIdleSync()
                Thread.sleep(300)
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "photo-context-flow-$name.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
            await("보관한 사진 1/4")
            val second = draft("pair-road-b-v197.png")
            runOnMainSync {
                CasePhotosActivity::class.java.getDeclaredField("pending")
                    .apply { isAccessible = true }.set(screen, second)
                CasePhotosActivity::class.java.getDeclaredMethod("preview")
                    .apply { isAccessible = true }.invoke(screen)
            }
            click("촬영 맥락 작성 · 선택 펼치기")
            val inputs = texts().filterIsInstance<EditText>()
            val stamp = inputs.single { it.hint.toString().startsWith("촬영 시각") }
            val place = inputs.single { it.hint.toString().startsWith("장소") }
            val viewpoint = inputs.single { it.hint.toString().startsWith("시야") }
            runOnMainSync {
                stamp.setText("2026-02-30 12:00:00")
                place.setText("시험 도로 1")
                viewpoint.setText("차량 전방")
            }
            reveal(stamp)
            capture("form")
            click("이 사진을 기록에 보관")
            check(stamp.error != null)
            check(texts().any { it.isShown && it.text.toString().startsWith("촬영 시각·장소·시야를 확인") })
            check(client.photosForDisplay(id).value.size == 1)
            reveal(stamp)
            capture("invalid-date")
            runOnMainSync { stamp.setText("2026-11-02 12:00:00") }
            click("이 사진을 기록에 보관")
            await("보관한 사진 2/4")
            val photos = client.photosForDisplay(id).value
            check(photos.size == 2)
            val saved = photos.single { it.id == second.getString("request_id") }
            check(saved.captureContext?.source == "owner_reported")
            check(saved.captureContext?.placeId == "시험 도로 1" && saved.captureContext?.viewpointId == "차량 전방")
            val expected = java.time.LocalDateTime.parse("2026-11-02T12:00:00")
                .atZone(java.time.ZoneId.systemDefault()).toInstant()
            check(saved.captureContext!!.instant == expected)
            await("사용자 작성 기준 · A 이전 · B 이후")
            reveal(texts().single { it.text.toString() == "사용자 작성 기준 · A 이전 · B 이후" })
            capture("saved-order")
            click("A ↔ B")
            await("사용자 작성 기준 · B 이전 · A 이후")
            reveal(texts().single { it.text.toString() == "사용자 작성 기준 · B 이전 · A 이후" })
            capture("swapped")
            click("사진 보관 정보 펼치기")
            val receipt = texts().first {
                it.text.toString().contains("서버 보관") && it.text.toString().contains("촬영 시각 아님")
            }
            reveal(receipt)
            capture("details")
            check(texts().count {
                it.text.toString().contains("서버 보관") && it.text.toString().contains("촬영 시각 아님")
            } == 2)
            for ((index, photo) in photos.withIndex()) {
                val detail =
                    texts().single { it.text.toString().contains(photo.captureContext!!.detail()) }
                check(detail.isShown)
                reveal(detail)
                capture("details-$index")
            }
            runOnMainSync {
                fun bottom(v: View) {
                    if (v is android.widget.ScrollView) v.scrollTo(0, v.getChildAt(0).height)
                    if (v is ViewGroup) for (i in 0 until v.childCount) bottom(v.getChildAt(i))
                }
                bottom(screen.window.decorView)
            }
            capture("details-bottom")
            val archive = client.evidencePackage(id)
            val verified = EvidencePackageVerifier.verify(archive, id)
            ZipFile(archive).use { zip ->
                val index = JSONObject(
                    zip.getInputStream(zip.getEntry("photo-index.json")).reader().readText()
                )
                val entries = index.getJSONArray("photos")
                check(entries.length() == photos.size)
                check((0 until entries.length()).map { entries.getJSONObject(it).getString("id") }
                    .toSet() == photos.map { it.id }.toSet())
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    val meta = CasePhoto.parse(entry, id)
                    check(meta == photos.single { it.id == meta.id })
                    meta.verifiedBytes(
                        zip.getInputStream(zip.getEntry(entry.getString("path"))).readBytes()
                    )
                }
            }
            archive.copyTo(File(targetContext.filesDir, "photo-context-flow.zip"), overwrite = true)
            archive.delete()
            File(targetContext.filesDir, "photo-context-flow.json").writeText(
                JSONObject().put("pass", true).put("event_id", id).put("photos", photos.size)
                    .put("zip_files", verified.files)
                    .put("context", saved.captureContext!!.json()).put(
                        "scope",
                        "Phone emulator production preview validation/save and TLS read/ZIP, programmatic controls; synthetic draft bypasses OS picker; owner-authored claims, not capture attestation"
                    ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: invalid form refused, actual preview saved over HTTPS, comparison swap, details and same-record ZIP\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { activity?.finish() }
        }
    }
}
