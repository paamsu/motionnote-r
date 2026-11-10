package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import net.openid.appauth.AuthState
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Actual local TLS private-note lifecycle. Synthetic identities; never invokes a UI control. */
class ReviewNotesHttpsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        var step = "identity"
        try {
            check(!targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE))
            val fixture = IntegrationIdentity.install(targetContext)
            val client = AuthenticatedEventsClient(targetContext)
            check(client.canReviewNotes())
            val id = fixture.getString("crash_id")
            val initial = client.noteForDisplay(id)
            val original = client.event(id).getString("hash")
            val text = "합성 확인 메모 · 현장 관측 시각은 추가 확인 필요"
            val requestId = UUID.randomUUID().toString()
            step = "save-and-idempotent-retry"
            val saved = client.saveNoteForDisplay(
                id,
                initial.value.revision,
                text,
                requestId,
                initial.sessionFingerprint
            )
            check(saved.value.revision == initial.value.revision + 1 && saved.value.text == text)
            check(
                client.saveNoteForDisplay(
                    id,
                    initial.value.revision,
                    text,
                    requestId,
                    initial.sessionFingerprint
                ).value == saved.value
            )
            step = "stale-revision"
            val conflict = runCatching {
                client.saveNoteForDisplay(
                    id,
                    initial.value.revision,
                    "오래된 수정",
                    UUID.randomUUID().toString()
                )
            }.exceptionOrNull()
            check(conflict is AccountAccessException && conflict.reason == AccountAccessException.Reason.CONFLICT)
            step = "scope-and-expiry"
            val body =
                JSONObject().put("expected_revision", saved.value.revision).put("note", "권한 없는 시험")
                    .put("request_id", UUID.randomUUID().toString()).toString()
            for ((key, reason) in listOf(
                "read_only_token" to AccountAccessException.Reason.FORBIDDEN,
                "expired_token" to AccountAccessException.Reason.SIGN_IN
            )) {
                val denied = runCatching {
                    AuthenticatedTransport().saveReviewNote(
                        BuildConfig.AUTH_API_URL,
                        id,
                        body,
                        fixture.getString(key)
                    )
                }.exceptionOrNull()
                check(denied is AccountAccessException && denied.reason == reason)
            }
            step = "vehicle-and-account-isolation"
            val foreign = runCatching {
                AuthenticatedTransport().saveReviewNote(
                    BuildConfig.AUTH_API_URL,
                    fixture.getString("foreign_id"),
                    body,
                    fixture.getString("token")
                )
            }.exceptionOrNull()
            check(foreign is AccountAccessException && foreign.reason == AccountAccessException.Reason.NOT_FOUND)
            val other = PrivateReviewNote.parse(
                AuthenticatedTransport().reviewNote(
                    BuildConfig.AUTH_API_URL,
                    id,
                    fixture.getString("other_owner_token")
                ), id
            )
            check(other.revision == 0 && other.text.isEmpty())
            step = "private-export"
            val exported = client.evidencePackage(id)
            ZipFile(exported).use { archive ->
                val privateEntry = checkNotNull(archive.getEntry("private-review-note.json"))
                check(
                    JSONObject(
                        archive.getInputStream(privateEntry)
                            .use { it.bufferedReader().readText() }).getString("note") == text
                )
                val manifest = JSONObject(
                    archive.getInputStream(checkNotNull(archive.getEntry("manifest.json")))
                        .use { it.bufferedReader().readText() }).getJSONArray("files")
                for (index in 0 until manifest.length()) {
                    val item = manifest.getJSONObject(index)
                    val bytes =
                        archive.getInputStream(checkNotNull(archive.getEntry(item.getString("path"))))
                            .use { it.readBytes() }
                    check(
                        sha(bytes) == item.getString("sha256") && bytes.size.toLong() == item.getLong(
                            "bytes"
                        )
                    )
                }
            }
            exported.copyTo(File(targetContext.filesDir, "private-note-v150.zip"), overwrite = true)
            val otherExport = ByteArrayOutputStream()
            AuthenticatedExportTransport().download(
                BuildConfig.AUTH_API_URL,
                id,
                fixture.getString("other_owner_token"),
                otherExport
            )
            ZipInputStream(otherExport.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(entry.name != "private-review-note.json")
                }
            }
            check(client.event(id).getString("hash") == original)
            step = "session-change-before-write"
            val changed = runCatching {
                client.saveNoteForDisplay(
                    id,
                    saved.value.revision,
                    "저장되면 안 되는 메모",
                    UUID.randomUUID().toString(),
                    "old-session"
                )
            }.exceptionOrNull()
            check(changed is AccountAccessException && changed.reason == AccountAccessException.Reason.CHANGED)
            check(client.noteForDisplay(id).value == saved.value)
            fun labels(): String {
                val values = mutableListOf<String>()
                fun collect(view: View) {
                    if (view is TextView) values.add(view.text.toString())
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(
                        view.getChildAt(
                            index
                        )
                    )
                }
                runOnMainSync { collect(checkNotNull(screen).window.decorView) }
                return values.joinToString("\n")
            }

            fun awaitText(value: String) {
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (labels().contains(value)) return
                    Thread.sleep(50)
                }
                error("Expected private-note view did not appear")
            }
            step = "actual-editor-render"
            screen = startActivitySync(
                Intent(
                    targetContext,
                    ReviewNoteActivity::class.java
                ).putExtra("event_id", id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            awaitText(text)
            check(labels().contains("메모 저장"))
            Thread.sleep(600)
            waitForIdleSync()
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.filesDir, "private-note-v150.png").outputStream()
                .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            step = "account-expiry-clears-editor"
            LoginStateStore(targetContext).write(AuthState())
            awaitText("메모를 화면에서 지웠습니다")
            check(!labels().contains(text))
            val report =
                JSONObject().put("source", "synthetic").put("transport", "real local TLS and RS256")
                    .put("private_note_saved", true).put("idempotent_retry_verified", true)
                    .put("stale_revision_rejected", true)
                    .put("read_only_token_rejected", true)
                    .put("other_account_note_and_export_isolated", true)
                    .put("foreign_vehicle_and_expiry_rejected", true)
                    .put("sensor_original_unchanged", true)
                    .put("private_export_manifest_verified", true)
                    .put("account_change_before_write_rejected", true)
                    .put("actual_editor_rendered", true).put("account_change_clears_editor", true)
                    .put("provider_login_performed", false).put("ui_save_tapped", false)
            File(targetContext.filesDir, "private-note-v150-result.json").writeText(
                report.toString(
                    2
                )
            )
            result.putString(
                "stream",
                "PASS: actual HTTPS private note save/retry/conflict, read-only/expired token and foreign vehicle rejection, account and ZIP isolation, unchanged sensor original, real editor render and account-change clearing. Synthetic identity; no provider login or UI save tap.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString(
                "stream",
                "FAIL step=$step class=${failure.javaClass.simpleName}; credentials and private text suppressed\n"
            )
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
            LoginStateStore(targetContext).write(AuthState())
        }
    }
}
