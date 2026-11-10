package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Actual SDK/AAOS reads and authenticated loopback TLS; no provider identity or UI taps. */
class SdkObservationHttpsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        var step = "setup"
        try {
            check(targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE))
            val fixture = IntegrationIdentity.install(targetContext, "observation_token")
            val client = AuthenticatedEventsClient(targetContext)
            val id = fixture.getString("event_id")
            val session = client.sessionFingerprint()
            val before = client.eventForDisplay(id).value
            step = "actual-sdk"
            screen = startActivitySync(
                android.content.Intent(targetContext, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            Thread.sleep(6000)
            var snapshot = JSONObject()
            runOnMainSync {
                val field = MainActivity::class.java.getDeclaredField("adapter")
                    .apply { isAccessible = true }
                snapshot = (field.get(screen) as PleosAdapter).diagnosticSnapshot()
            }
            File(
                targetContext.filesDir,
                "sdk-observation-attempt-v155.json"
            ).writeText(snapshot.toString(2))
            check(snapshot.getBoolean("fresh_gear_received") && snapshot.getString("gear") == "GEAR_PARK") {
                "Fresh P required; initialization=${
                    snapshot.optString(
                        "initialization"
                    )
                }, gear=${snapshot.optString("gear_status")}"
            }
            check(!snapshot.getBoolean("synthetic_fallback"))
            // This probe runs only on the official emulator; never promote it to real-vehicle evidence.
            val body = SdkObservation.create(snapshot, UUID.randomUUID().toString(), true)
            val raw = body.toString()
            step = "https-save"
            val receipt = client.saveSdkObservationForDisplay(id, body, session).value
            check(receipt.getString("id") == body.getString("request_id"))
            check(receipt.getString("provenance") == "client_reported_not_attested")
            check(!receipt.getBoolean("sensor_original") && !receipt.getBoolean("assessment_changed"))
            check(receipt.getJSONObject("data").getString("environment") == "pleos_emulator")
            check(
                receipt.getJSONObject("data").getJSONArray("samples").getJSONObject(0)
                    .getJSONObject("gear").getString("value") == "GEAR_PARK"
            )
            val expected = java.security.MessageDigest.getInstance("SHA-256")
                .digest(receipt.getString("canonical_json").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            check(receipt.getString("sha256") == expected)
            step = "retry-and-list"
            check(
                client.saveSdkObservationForDisplay(id, JSONObject(raw), session).value.getString(
                    "sha256"
                ) == expected
            )
            val list = client.sdkObservationsForDisplay(id).value
            check(list.count { it.id == body.getString("request_id") } == 1)
            step = "original-retained"
            val after = client.eventForDisplay(id).value
            check(
                before.getString("hash") == after.getString("hash") && before.getJSONObject("assessment")
                    .toString() == after.getJSONObject("assessment").toString()
            )
            IntegrationIdentity.install(targetContext, "other_owner_token")
            check(AuthenticatedEventsClient(targetContext).sdkObservationsForDisplay(id).value.isEmpty())
            val report = JSONObject().put("source", "official_pleos_emulator_sdk_and_aaos")
                .put("observation", receipt).put("transport", "Android loopback HTTPS and RS256")
                .put("replay_verified", true).put("private_scope_verified", true)
                .put("sensor_original_unchanged", true)
            File(targetContext.filesDir, "sdk-observation-v155.json").writeText(report.toString(2))
            result.putString(
                "stream",
                "PASS: actual Pleos P-gear, AAOS speed availability, Android TLS/RS256 observation save/read/hash/retry/private scope; original unchanged. Emulator and synthetic identity, not vehicle attestation or real-provider login.\n"
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
