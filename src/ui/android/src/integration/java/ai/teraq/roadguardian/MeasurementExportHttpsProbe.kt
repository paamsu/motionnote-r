package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Actual TLS, selected archive checks and production document-result callback. No picker/button taps. */
class MeasurementExportHttpsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        val temporary = mutableListOf<File>()
        var stage = "install identity"
        try {
            stage = "measurement feature enabled"
            check(BuildConfig.MEASUREMENTS_ENABLED) { "Build integration with -ProadGuardianMeasurementsEnabled=true" }
            stage = "phone-only measurement screen"
            check(!targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) { "MeasurementActivity intentionally excludes automotive; run this UI/picker probe on a phone emulator" }
            val fixture = IntegrationIdentity.install(targetContext, "measurement_token")
            val id = fixture.getString("measurement_case_event_id")
            val vehicle = fixture.getJSONObject("measurement_before").getString("vehicle_id")
            val client = AuthenticatedEventsClient(targetContext)
            val session = client.sessionFingerprint()
            stage = "load event and save three measurements"
            val event = client.eventForDisplay(id).value
            val hash = event.getString("hash")
            val before = client.saveMeasurementForDisplay(
                id,
                vehicle,
                fixture.getJSONObject("measurement_before"),
                session
            ).value
            val after = client.saveMeasurementForDisplay(
                id,
                vehicle,
                fixture.getJSONObject("measurement_after"),
                session
            ).value
            val mismatch = client.saveMeasurementForDisplay(
                id,
                vehicle,
                fixture.getJSONObject("measurement_mismatch"),
                session
            ).value
            stage = "check export capability"
            check(client.capabilitiesForDisplay().value.measurementsExport) { "Export capability missing" }
            stage = "download and verify matched pair"
            val downloaded =
                client.measurementPackage(id, hash, vehicle, before.summary, after.summary)
            temporary.add(downloaded)
            check(
                MeasurementPackageVerifier.verify(
                    downloaded,
                    id,
                    hash,
                    vehicle,
                    before.summary.id,
                    after.summary.id
                ).matched
            )
            downloaded.copyTo(
                File(targetContext.filesDir, "measurement-selected-pair-v170.zip"),
                overwrite = true
            )
            val source = linkedMapOf<String, ByteArray>()
            ZipFile(downloaded).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    source[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
                }
            }
            fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

            fun mutation(name: String, edit: (JSONObject) -> Unit): Map<String, ByteArray> {
                val files = LinkedHashMap(source)
                val obj = JSONObject(String(checkNotNull(files[name]), Charsets.UTF_8))
                edit(obj)
                files[name] = obj.toString().toByteArray()
                return files
            }

            val changes = listOf(
                mutation("before.json") {
                    it.getJSONObject("data").getJSONArray("samples").getJSONObject(0)
                        .put("voltage_v", 50)
                },
                mutation("after.json") {
                    it.put(
                        "event_id",
                        "00000000-0000-4000-8000-000000000170"
                    )
                },
                mutation("before.json") { it.put("sensor_original", true) },
                mutation("comparison.json") {
                    it.getJSONObject("comparison").getJSONObject("metrics")
                        .put("paired_delta_median_v", 99)
                },
                mutation("comparison.json") {
                    it.getJSONObject("comparison").put("repair_confirmed", true)
                },
                mutation("comparison.json") { it.put("before_id", after.summary.id) },
                mutation("report.json") { it.put("external_submission", true) },
                mutation("report.json") { it.put("case_sha256", "0".repeat(64)) },
                mutation("authored-original.json") { it.put("vehicle_id", "another-vehicle") },
                LinkedHashMap(source).apply { put("../outside.json", "{}".toByteArray()) },
                LinkedHashMap(source).apply { remove("after.json") }
            )
            val mutationNames = listOf(
                "before voltage",
                "after event id",
                "sensor_original flag",
                "paired delta",
                "repair_confirmed",
                "comparison before id",
                "external submission",
                "case hash",
                "authored vehicle",
                "path traversal",
                "missing after"
            )
            for ((mutationIndex, files) in changes.withIndex()) {
                stage = "reject mutation ${mutationIndex + 1}: ${mutationNames[mutationIndex]}"
                // Recompute the manifest: a self-consistent ZIP must still pass semantic/context checks.
                val entries = JSONArray()
                for ((name, bytes) in files) if (name != "manifest.json") entries.put(
                    JSONObject().put(
                        "path",
                        name
                    ).put("bytes", bytes.size).put("sha256", digest(bytes))
                )
                val changed = LinkedHashMap(files)
                changed["manifest.json"] =
                    JSONObject().put("schema", "road-guardian.measurement-package.v1")
                        .put("event_id", id).put("files", entries).toString().toByteArray()
                val file =
                    File.createTempFile("measurement-mutation-", ".zip", targetContext.cacheDir)
                temporary.add(file)
                ZipOutputStream(file.outputStream()).use { zip ->
                    for ((name, bytes) in changed) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                check(runCatching {
                    MeasurementPackageVerifier.verify(
                        file,
                        id,
                        hash,
                        vehicle,
                        before.summary.id,
                        after.summary.id
                    )
                }.isFailure) { "Semantic mutation was accepted: ${mutationNames[mutationIndex]}" }
            }
            stage = "verify condition mismatch"
            val held =
                client.measurementPackage(id, hash, vehicle, before.summary, mismatch.summary)
            temporary.add(held)
            check(
                !MeasurementPackageVerifier.verify(
                    held,
                    id,
                    hash,
                    vehicle,
                    before.summary.id,
                    mismatch.summary.id
                ).matched
            )
            fun labels(): String {
                val labels = mutableListOf<String>()
                fun visit(view: View) {
                    if (view is TextView && view.isShown) labels.add(view.text.toString())
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                        view.getChildAt(
                            i
                        )
                    )
                }
                runOnMainSync { visit(checkNotNull(screen).window.decorView) }
                return labels.joinToString("\n")
            }

            fun await(text: String) {
                val end = android.os.SystemClock.elapsedRealtime() + 12000
                while (!labels().contains(text) && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(
                    50
                )
                check(labels().contains(text)) { "Expected export UI state missing" }
            }
            stage = "open comparison screen"
            screen = startActivitySync(
                Intent(
                    targetContext,
                    MeasurementActivity::class.java
                ).putExtra("event_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            await("앞 측정 기록")
            runOnMainSync {
                MeasurementActivity::class.java.getDeclaredMethod(
                    "compare",
                    String::class.java,
                    String::class.java
                ).apply { isAccessible = true }.invoke(screen, before.summary.id, after.summary.id)
            }
            await("선택한 두 기록의 비교 묶음 저장")
            runOnMainSync {
                fun scroll(view: View) {
                    if (view is android.widget.ScrollView) view.scrollTo(
                        0,
                        view.getChildAt(0).height
                    ) else if (view is ViewGroup) for (i in 0 until view.childCount) scroll(
                        view.getChildAt(
                            i
                        )
                    )
                }
                scroll(checkNotNull(screen).window.decorView)
            }
            waitForIdleSync()
            Thread.sleep(500)
            val screenshot = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.filesDir, "measurement-export-phone-v170.png").outputStream().use {
                check(
                    screenshot.compress(
                        android.graphics.Bitmap.CompressFormat.PNG,
                        100,
                        it
                    )
                )
            }
            screenshot.recycle()
            stage = "production document callback"
            val directory = File(targetContext.cacheDir, "parking-capture").apply { mkdirs() }
            val destination = File(directory, "measurement-export-callback-v170.zip")
            temporary.add(destination)
            val uri = FileProvider.getUriForFile(
                targetContext,
                BuildConfig.APPLICATION_ID + ".parkingphotos",
                destination
            )
            runOnMainSync {
                val type =
                    MeasurementActivity::class.java.declaredClasses.single { it.simpleName == "ExportSelection" }
                val selection = type.declaredConstructors.single().apply { isAccessible = true }
                    .newInstance(hash, vehicle, before.summary, after.summary, session)
                MeasurementActivity::class.java.getDeclaredField("exportSelection")
                    .apply { isAccessible = true }.set(screen, selection)
                MeasurementActivity::class.java.getDeclaredMethod(
                    "onActivityResult",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Intent::class.java
                ).apply { isAccessible = true }
                    .invoke(screen, 442, Activity.RESULT_OK, Intent().setData(uri))
            }
            await("비교 기록 저장 완료")
            check(
                MeasurementPackageVerifier.verify(
                    destination,
                    id,
                    hash,
                    vehicle,
                    before.summary.id,
                    after.summary.id
                ).matched
            )
            destination.copyTo(
                File(
                    targetContext.filesDir,
                    "measurement-document-callback-v170.zip"
                ), overwrite = true
            )
            // A delayed document result from the former account must not touch the selected document.
            stage = "stale callback and other owner refusal"
            val untouched = File(directory, "measurement-stale-callback-v170.zip")
            temporary.add(untouched)
            untouched.writeText("unchanged")
            val staleUri = FileProvider.getUriForFile(
                targetContext,
                BuildConfig.APPLICATION_ID + ".parkingphotos",
                untouched
            )
            runOnMainSync {
                val type =
                    MeasurementActivity::class.java.declaredClasses.single { it.simpleName == "ExportSelection" }
                val selection = type.declaredConstructors.single().apply { isAccessible = true }
                    .newInstance(hash, vehicle, before.summary, after.summary, session)
                MeasurementActivity::class.java.getDeclaredField("exportSelection")
                    .apply { isAccessible = true }.set(screen, selection)
            }
            IntegrationIdentity.install(targetContext, "other_owner_token")
            runOnMainSync {
                MeasurementActivity::class.java.getDeclaredMethod(
                    "onActivityResult",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Intent::class.java
                ).apply { isAccessible = true }
                    .invoke(screen, 442, Activity.RESULT_OK, Intent().setData(staleUri))
            }
            Thread.sleep(300)
            waitForIdleSync()
            check(untouched.readText() == "unchanged")
            check(runCatching {
                AuthenticatedEventsClient(targetContext).measurementPackage(
                    id,
                    hash,
                    vehicle,
                    before.summary,
                    after.summary
                )
            }.isFailure)
            stage = "read-only selected export"
            IntegrationIdentity.install(targetContext, "read_only_token")
            val reader = AuthenticatedEventsClient(targetContext)
            check(!reader.capabilitiesForDisplay().value.measurementsWrite && reader.capabilitiesForDisplay().value.measurementsExport)
            val readOnly =
                reader.measurementPackage(id, hash, vehicle, before.summary, after.summary)
            temporary.add(readOnly)
            check(
                MeasurementPackageVerifier.verify(
                    readOnly,
                    id,
                    hash,
                    vehicle,
                    before.summary.id,
                    after.summary.id
                ).matched
            )
            File(
                targetContext.filesDir,
                "measurement-export-result-v170.json"
            ).writeText(
                JSONObject().put("synthetic_measurements", true).put("actual_tls", true)
                    .put("semantic_mutations_rejected", changes.size)
                    .put("not_comparable_preserved", true).put("production_document_callback", true)
                    .put("other_owner_rejected", true).put("stale_callback_does_not_write", true)
                    .put("read_only_export", true).put("picker_and_button_taps", false).toString(2)
            )
            result.putString(
                "stream",
                "PASS: actual Android TLS selected-pair archive, 11 rehashed semantic mutations, not-comparable preservation, production document callback and verified output, stale callback no-write, read-only export and other-owner rejection. Synthetic measurements/login; no picker/button taps, real vehicle or repair claim.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString(
                "stream",
                "FAIL at $stage: ${failure.javaClass.simpleName}: ${failure.message}\n${
                    failure.stackTrace.take(8).joinToString("\n")
                }\n"
            )
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            LoginStateStore(targetContext).write(net.openid.appauth.AuthState())
            runOnMainSync { screen?.finish() }
            temporary.forEach { it.delete() }
        }
    }
}
