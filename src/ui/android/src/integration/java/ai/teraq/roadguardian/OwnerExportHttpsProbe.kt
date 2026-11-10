package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Real TLS and private APIs with explicitly authored test text and missing SDK values. No UI input. */
class OwnerExportHttpsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val temporary = mutableListOf<File>()
        var step = "identity"
        try {
            IntegrationIdentity.install(targetContext, "owner_case_token")
            val client = AuthenticatedEventsClient(targetContext)
            val session = client.sessionFingerprint()
            val body = JSONObject().put("schema", "road-guardian.owner-case.v1")
                .put("request_id", UUID.randomUUID().toString())
                .put("vehicle_id", client.registeredVehiclesForDisplay().value.single())
                .put("category", "maintenance").put("reported_at", JSONObject.NULL)
                .put("title", "작성 자료 내보내기 시험")
                .put("description", "실제 고장·사고·실차 관측을 주장하지 않는 시험 입력입니다.")
                .put(
                    "own_vehicle",
                    JSONObject().put("plate", "TEST-EXPORT-01").put("model", "미확인").put("color", "")
                )
            val event = client.createOwnerCaseForDisplay(body, session).value
            val id = event.getString("id")
            step = "empty-private-additions"
            val empty = client.evidencePackage(id)
            temporary.add(empty)
            check(EvidencePackageVerifier.verify(empty, id).files == 4)
            ZipFile(empty).use { zip ->
                val report = JSONObject(
                    zip.getInputStream(checkNotNull(zip.getEntry("owner-report.json")))
                        .use { it.readBytes().toString(Charsets.UTF_8) })
                check(
                    report.getInt("private_note_revision") == 0 && report.getInt("sdk_observation_count") == 0 && report.getString(
                        "private_note_status"
                    ) == "not_present"
                )
            }
            step = "private-additions"
            client.saveNoteForDisplay(
                id,
                0,
                "본인 계정 내보내기 시험 메모",
                UUID.randomUUID().toString(),
                session
            )
            val missing = JSONObject().put("schema", "road-guardian.sdk-observation.v1")
                .put("request_id", UUID.randomUUID().toString())
                .put("environment", "vehicle_unattested").put("sdk_version", "test-no-sdk")
                .put("captured_at", System.currentTimeMillis() / 1000.0)
                .put(
                    "samples", JSONArray().put(
                        JSONObject().put("elapsed_ms", 0)
                            .put(
                                "gear",
                                JSONObject().put("value", JSONObject.NULL)
                                    .put("age_ms", JSONObject.NULL)
                            )
                            .put(
                                "speed",
                                JSONObject().put("kmh", JSONObject.NULL)
                                    .put("age_ms", JSONObject.NULL)
                            )
                    )
                )
            client.saveSdkObservationForDisplay(id, missing, session)
            step = "production-download-and-verify"
            val exported = client.evidencePackage(id)
            temporary.add(exported)
            val checked = EvidencePackageVerifier.verify(exported, id)
            check(checked.files == 5)
            val originals = ZipFile(exported).use { zip ->
                zip.entries().asSequence().associate { entry ->
                    entry.name to zip.getInputStream(entry).use { it.readBytes() }
                }
            }
            check("sensor-original.json" !in originals && "test-pattern-video.mp4" !in originals)
            check(client.eventForDisplay(id).value.getString("hash") == event.getString("hash"))
            val report =
                JSONObject(originals.getValue("owner-report.json").toString(Charsets.UTF_8))
            check(report.getInt("private_note_revision") == 1 && report.getInt("sdk_observation_count") == 1)
            check(report.get("sensor_original") == false && report.get("external_submission") == false)
            step = "same-vehicle-other-subject"
            IntegrationIdentity.install(targetContext, "other_owner_token")
            check((runCatching { AuthenticatedEventsClient(targetContext).evidencePackage(id) }.exceptionOrNull() as? AccountAccessException)?.reason == AccountAccessException.Reason.NOT_FOUND)
            IntegrationIdentity.install(targetContext, "owner_case_token")
            fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

            fun changed(name: String, edit: (JSONObject) -> Unit): MutableMap<String, ByteArray> {
                val files = originals.toMutableMap()
                val json = JSONObject(files.getValue(name).toString(Charsets.UTF_8))
                edit(json)
                files[name] = json.toString().toByteArray(Charsets.UTF_8)
                return files
            }

            var rejected = 0
            fun reject(
                files: MutableMap<String, ByteArray>,
                rebind: Boolean = true,
                expected: String = id
            ) {
                if (rebind) {
                    val manifest =
                        JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8))
                    val list = JSONArray()
                    files.filterKeys { it != "manifest.json" }.toSortedMap()
                        .forEach { (name, bytes) ->
                            list.put(
                                JSONObject().put("path", name).put("bytes", bytes.size)
                                    .put("sha256", sha(bytes))
                            )
                        }
                    manifest.put("files", list)
                    files["manifest.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
                }
                val file =
                    File.createTempFile("owner-export-mutation-", ".zip", targetContext.cacheDir)
                temporary.add(file)
                ZipOutputStream(file.outputStream()).use { zip ->
                    files.forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                val failure =
                    runCatching { EvidencePackageVerifier.verify(file, expected) }.exceptionOrNull()
                check(failure is AccountAccessException && failure.reason == AccountAccessException.Reason.INTEGRITY)
                rejected++
            }
            step = "reject-rehashed-mutations"
            reject(originals.toMutableMap(), expected = UUID.randomUUID().toString())
            reject(changed("owner-report.json") { it.put("id", UUID.randomUUID().toString()) })
            reject(changed("owner-report.json") { it.put("sensor_original", true) })
            reject(changed("owner-report.json") { it.put("automatic_assessment", "false") })
            reject(changed("owner-report.json") { it.put("content_sha256", "0".repeat(64)) })
            reject(changed("owner-report.json") {
                it.getJSONObject("assessment").put("responsibility_determined", true)
            })
            reject(changed("owner-report.json") { it.put("sdk_observation_count", 2) })
            reject(changed("authored-original.json") { it.put("title", "바뀐 제목") }, rebind = false)
            reject(changed("private-review-note.json") {
                it.put(
                    "event_id",
                    UUID.randomUUID().toString()
                )
            })
            reject(changed("private-review-note.json") { it.put("evidence_statement", "false") })
            reject(changed("private-sdk-observations.json") { it.put("scope", "all_subjects") })
            reject(changed("private-sdk-observations.json") {
                it.getJSONArray("observations").getJSONObject(0).put("canonical_json", "{}")
            })
            reject(originals.toMutableMap().apply { remove("private-review-note.json") })
            reject(
                originals.toMutableMap().apply { put("sensor-original.json", "{}".toByteArray()) })
            reject(changed("manifest.json") {
                it.put("schema", "road-guardian.package.v1").put("source", "synthetic")
            })
            exported.copyTo(
                File(targetContext.filesDir, "owner-verified-export-v158.zip"),
                overwrite = true
            )
            File(targetContext.filesDir, "owner-export-v158.json").writeText(
                JSONObject().put("event_id", id).put("verified_files", checked.files)
                    .put("rejected_mutations", rejected)
                    .put("same_vehicle_other_subject_hidden", true).put("original_unchanged", true)
                    .put(
                        "input",
                        "authored test text; SDK values absent; synthetic login; no actual incident or vehicle verification"
                    ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: production Android TLS owner package download/verification, private note and missing-value observation binding, same-vehicle co-owner 404, original unchanged, $rejected mutations rejected including recomputed manifests. No document picker or save tap.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", "FAIL step=$step: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            LoginStateStore(targetContext).write(net.openid.appauth.AuthState())
            temporary.forEach { it.delete() }
        }
    }
}
