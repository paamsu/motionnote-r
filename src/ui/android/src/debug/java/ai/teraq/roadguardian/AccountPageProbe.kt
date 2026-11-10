package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Tests the production parser using Android's JSON implementation, without authentication fixtures. */
class AccountPageProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val id = UUID.randomUUID().toString()
            val sdk = JSONObject().put("sdk_version", "2.3.2").put("synthetic_fallback", false)
                .put("gear", "GEAR_PARK").put("fresh_gear_received", true).put("gear_age_ms", 4999)
                .put(
                    "vehicle_speed",
                    JSONObject().put("source", "aaos_perf_vehicle_speed").put("fresh", true)
                        .put("age_ms", 100).put("kilometers_per_hour", 0.0)
                )

            fun observation() =
                SdkObservation.create(sdk, id, true).getJSONArray("samples").getJSONObject(0)
            check(observation().getJSONObject("gear").getString("value") == "GEAR_PARK")
            check(observation().getJSONObject("speed").getDouble("kmh") == 0.0)
            val envelope = SdkObservation.create(sdk, id, true)
            val encoded = envelope.toString()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val receipt =
                JSONObject().put("id", id).put("canonical_json", encoded).put("sha256", digest)
                    .put("data", JSONObject(encoded))
                    .put("provenance", "client_reported_not_attested").put("sensor_original", false)
                    .put("assessment_changed", false)
            check(SdkObservation.receipt(receipt.toString(), envelope).getString("id") == id)
            check(runCatching {
                SdkObservation.receipt(
                    JSONObject(receipt.toString()).put(
                        "sha256",
                        "0".repeat(64)
                    ).toString(), envelope
                )
            }.isFailure)
            check(runCatching {
                SdkObservation.receipt(
                    JSONObject(receipt.toString()).put(
                        "id",
                        UUID.randomUUID().toString()
                    ).toString(), envelope
                )
            }.isFailure)
            val wrongData = JSONObject(receipt.toString())
            wrongData.getJSONObject("data").put("sdk_version", "changed")
            check(runCatching { SdkObservation.receipt(wrongData.toString(), envelope) }.isFailure)
            check(runCatching {
                SdkObservation.receipt(
                    JSONObject(receipt.toString()).put(
                        "sensor_original",
                        "false"
                    ).toString(), envelope
                )
            }.isFailure)
            receipt.put("received_at", System.currentTimeMillis() / 1000.0)
            fun observationList(
                record: JSONObject = receipt,
                event: String = id,
                scope: String = "current_subject_only"
            ) = JSONObject()
                .put("schema", "road-guardian.sdk-observations.v1").put("event_id", event)
                .put("scope", scope).put("observations", JSONArray().put(record))
            check(
                SdkObservationRecord.parse(observationList(), id)
                    .single().samples.single().gear == "GEAR_PARK"
            )
            check(runCatching {
                SdkObservationRecord.parse(
                    observationList(
                        event = UUID.randomUUID().toString()
                    ), id
                )
            }.isFailure)
            check(runCatching {
                SdkObservationRecord.parse(
                    observationList(scope = "shared"),
                    id
                )
            }.isFailure)
            check(runCatching {
                SdkObservationRecord.parse(
                    observationList(
                        JSONObject(receipt.toString()).put(
                            "received_at",
                            true
                        )
                    ), id
                )
            }.isFailure)
            val duplicate = observationList()
            duplicate.getJSONArray("observations").put(receipt)
            check(runCatching { SdkObservationRecord.parse(duplicate, id) }.isFailure)
            fun changedObservation(change: (JSONObject) -> Unit): JSONObject {
                val data = JSONObject(envelope.toString())
                change(data)
                val raw = data.toString()
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                return JSONObject(receipt.toString()).put("data", data).put("canonical_json", raw)
                    .put("sha256", hash)
            }

            val invalidObservations = listOf(
                changedObservation {
                    it.getJSONArray("samples").getJSONObject(0).getJSONObject("gear")
                        .put("age_ms", 5000)
                },
                changedObservation {
                    it.getJSONArray("samples").getJSONObject(0).put("elapsed_ms", true)
                },
                changedObservation {
                    it.getJSONArray("samples").getJSONObject(0).getJSONObject("speed")
                        .put("kmh", "0")
                },
                changedObservation { it.put("environment", "verified_vehicle") },
                changedObservation { it.put("sdk_version", 2.3) })
            invalidObservations.forEach {
                check(runCatching {
                    SdkObservationRecord.parse(
                        observationList(it),
                        id
                    )
                }.isFailure)
            }
            sdk.put("gear_age_ms", 5000)
            check(observation().getJSONObject("gear").isNull("value"))
            sdk.getJSONObject("vehicle_speed").put("fresh", false)
            check(observation().getJSONObject("speed").isNull("kmh"))
            sdk.put("gear_age_ms", true)
            check(observation().getJSONObject("gear").isNull("value"))
            sdk.put("synthetic_fallback", true)
            check(runCatching { observation() }.isFailure)
            fun page(events: JSONArray, next: Any = JSONObject.NULL) =
                JSONObject().put("events", events).put("next_cursor", next).toString()

            val one = JSONArray().put(JSONObject().put("id", id))
            check(AccountPageResponse.parse(page(one), null).events.length() == 1)
            check(
                AccountPageResponse.parse(
                    page(JSONArray(), "next_page"),
                    "previous"
                ).next == "next_page"
            )
            check(AccountPageResponse.parse(page(JSONArray()), null).next == null)
            fun capabilities(
                access: String = "allowed",
                version: Any = 1,
                read: Any = true,
                write: Any = true
            ) = JSONObject()
                .put("schema", "road-guardian.capabilities.v1").put("api_version", version)
                .put("vehicle_access", access)
                .put(
                    "features",
                    JSONObject().put("case_pages", true).put("case_detail", true)
                        .put("case_video", true)
                        .put("evidence_export", true).put("private_notes_read", read)
                        .put("private_notes_write", write)
                ).toString()
            check(ServiceCapabilities.parse(capabilities()).notesWrite)
            check(!ServiceCapabilities.parse(capabilities(read = false, write = false)).notesRead)
            for (response in listOf(
                capabilities(read = "true"),
                capabilities(read = false),
                capabilities(access = "not_granted"),
                capabilities(version = "1"),
                "{}"
            ))
                check(runCatching { ServiceCapabilities.parse(response) }.exceptionOrNull() is AccountAccessException)
            val future =
                runCatching { ServiceCapabilities.parse(capabilities(version = 2)) }.exceptionOrNull()
            check(future is AccountAccessException && future.reason == AccountAccessException.Reason.INCOMPATIBLE)
            val candidate =
                JSONObject().put("id", id).put("assessment", JSONObject().put("kind", "diagnosis"))

            fun categoryPage(category: String, event: JSONObject) =
                JSONObject(page(JSONArray().put(event))).put("category", category).toString()
            check(
                AccountPageResponse.parse(
                    categoryPage("maintenance", candidate),
                    null,
                    "maintenance"
                ).category == "maintenance"
            )
            for (response in listOf(
                categoryPage("road", candidate),
                categoryPage(
                    "maintenance",
                    JSONObject().put("id", id)
                        .put("assessment", JSONObject().put("kind", "parking"))
                )
            )) {
                check(runCatching {
                    AccountPageResponse.parse(
                        response,
                        null,
                        "maintenance"
                    )
                }.exceptionOrNull() is AccountAccessException)
            }
            val malformed = listOf(
                "{}", "{\"events\":[]}", "{\"events\":null,\"next_cursor\":null}",
                page(JSONArray().put(JSONObject())), page(JSONArray().put(JSONObject.NULL)),
                page(JSONArray().put("not an event")),
                page(JSONArray().put(JSONObject().put("id", "1-1-1-1-1"))),
                page(JSONArray().put(JSONObject().put("id", 7))),
                page(JSONArray().put(JSONObject().put("id", id)).put(JSONObject().put("id", id))),
                page(one, 123), page(one, ""), page(one, "invalid/cursor"), page(one, "same_page"),
                page(JSONArray().apply {
                    repeat(26) {
                        put(
                            JSONObject().put(
                                "id",
                                UUID.randomUUID().toString()
                            )
                        )
                    }
                })
            )
            for (response in malformed) {
                val failure = runCatching {
                    AccountPageResponse.parse(
                        response,
                        "same_page"
                    )
                }.exceptionOrNull()
                check(failure is AccountAccessException) { "Malformed page was accepted: $response" }
            }
            result.putString(
                "stream",
                "PASS: 3 valid pages, ${malformed.size} malformed pages rejected; empty intermediate page preserved. Parser only, no live login or HTTP navigation.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
