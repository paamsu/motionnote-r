package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import net.openid.appauth.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.net.ssl.SSLException

/** Real local TLS and RS256-authorized API calls with synthetic AppAuth state. No provider login. */
class AuthenticatedHttpsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    override fun onStart() {
        val result = Bundle()
        val store = LoginStateStore(targetContext)
        var screen: Activity? = null
        var step = "configuration"
        try {
            val fixture = IntegrationIdentity.install(targetContext)
            val client = AuthenticatedEventsClient(targetContext)
            step = "authenticated-capabilities"
            val capabilities = client.capabilitiesForDisplay().value
            check(capabilities.known && capabilities.vehicleAllowed && capabilities.records)
            step = "authenticated-pages"
            val first = client.pageForDisplay(null)
            check(first.lease.valid(System.currentTimeMillis(), SystemClock.elapsedRealtime()))
            check(first.value.events.length() == 25 && first.value.next != null)
            val second = client.pageForDisplay(first.value.next)
            check(second.value.events.length() == 3 && second.value.next == null)
            val ids = mutableSetOf<String>()
            for (page in listOf(first.value, second.value)) for (i in 0 until page.events.length())
                check(ids.add(page.events.getJSONObject(i).getString("id")))
            check(fixture.getString("foreign_id") !in ids && ids.size == 28)
            step = "category-pages"
            val maintenance = client.pageForDisplay(null, "maintenance").value
            check(maintenance.category == "maintenance" && maintenance.events.length() == 1 && maintenance.next == null)
            check(
                maintenance.events.getJSONObject(0).getString("id") == fixture.getString("event_id")
            )
            val roads = client.pageForDisplay(null, "road").value
            check(roads.events.length() == 25 && roads.next != null)
            val olderRoads = client.pageForDisplay(roads.next, "road").value
            check(olderRoads.events.length() == 1 && olderRoads.next == null)
            check(client.pageForDisplay(null, "incident").value.events.length() == 1)
            val crossCategory =
                runCatching { client.pageForDisplay(roads.next, "maintenance") }.exceptionOrNull()
            check(crossCategory is AccountAccessException && crossCategory.reason == AccountAccessException.Reason.UNAVAILABLE)
            step = "evidence-dossier"
            for (key in listOf("crash_id", "road_id")) {
                step = "evidence-dossier-$key"
                val supplied = client.eventForDisplay(fixture.getString(key)).value
                val inventory = supplied.getJSONObject("evidence_dossier")
                check(inventory.getString("schema") == "road-guardian.evidence-dossier.v1")
                check(!inventory.getBoolean("external_submission") && !inventory.getBoolean("responsibility_determined"))
                val pack = client.evidencePackage(fixture.getString(key))
                ZipFile(pack).use { archive ->
                    val entry = checkNotNull(archive.getEntry("evidence-index.json"))
                    val exportedIndex = JSONObject(
                        archive.getInputStream(entry).use { it.bufferedReader().readText() })
                    check(exportedIndex.getString("event_id") == fixture.getString(key))
                    val savedItems = exportedIndex.getJSONArray("items")
                    val apiItems = inventory.getJSONArray("items")
                    check(savedItems.length() == apiItems.length())
                    for (index in 0 until savedItems.length()) for (field in listOf(
                        "key",
                        "label",
                        "state",
                        "detail"
                    ))
                        check(
                            savedItems.getJSONObject(index)
                                .getString(field) == apiItems.getJSONObject(index).getString(field)
                        )
                }
            }
            step = "authenticated-detail"
            val event = client.eventForDisplay(fixture.getString("event_id")).value
            check(event.getString("hash") == fixture.getString("original_hash"))
            check(EvidenceScope.canNotifyComparison(event))
            check(
                event.getJSONObject("repair_case").getString("stage") == "observed_signal_recovered"
            )
            step = "vehicle-isolation"
            val forbidden =
                runCatching { client.event(fixture.getString("foreign_id")) }.exceptionOrNull()
            check(forbidden is AccountAccessException && forbidden.reason == AccountAccessException.Reason.NOT_FOUND)
            step = "expired-token"
            val expired = runCatching {
                AuthenticatedTransport().events(
                    BuildConfig.AUTH_API_URL,
                    fixture.getString("expired_token")
                )
            }.exceptionOrNull()
            check(expired is AccountAccessException && expired.reason == AccountAccessException.Reason.SIGN_IN)
            step = "unauthenticated-request"
            val unauthenticated =
                URL(BuildConfig.AUTH_API_URL + "/api/events").openConnection() as HttpURLConnection
            try {
                unauthenticated.connectTimeout = 4000
                unauthenticated.readTimeout = 6000
                check(unauthenticated.responseCode == 401)
            } finally {
                unauthenticated.disconnect()
            }
            step = "untrusted-certificate"
            val untrusted = runCatching {
                AuthenticatedTransport().events(
                    "https://localhost:9444",
                    fixture.getString("token")
                )
            }.exceptionOrNull()
            check(untrusted is SSLException)
            step = "ip-endpoint-rejected"
            val wrongIdentity = runCatching {
                AuthenticatedTransport().events(
                    "https://127.0.0.1:9443",
                    fixture.getString("token")
                )
            }.exceptionOrNull()
            check(wrongIdentity is SSLException)
            step = "authenticated-export"
            val exported = client.evidencePackage(fixture.getString("event_id"))
            check(sha(exported.readBytes()) == fixture.getString("export_hash"))
            ZipFile(exported).use { archive ->
                fun bytes(name: String) =
                    archive.getInputStream(checkNotNull(archive.getEntry(name)))
                        .use { it.readBytes() }

                val manifest =
                    JSONObject(String(bytes("manifest.json"), Charsets.UTF_8)).getJSONArray("files")
                val expected = mutableSetOf("manifest.json")
                for (i in 0 until manifest.length()) {
                    val item = manifest.getJSONObject(i)
                    val name = item.getString("path")
                    expected.add(name)
                    val content = bytes(name)
                    check(
                        content.size.toLong() == item.getLong("bytes") && sha(content) == item.getString(
                            "sha256"
                        )
                    )
                }
                check(archive.entries().asSequence().map { it.name }.toSet() == expected)
                check(sha(bytes("sensor-original.json")) == fixture.getString("original_hash"))
                check(sha(bytes("repair-follow-up-original.json")) == fixture.getString("followup_hash"))
            }
            exported.copyTo(File(targetContext.filesDir, "verified-export.zip"), overwrite = true)
            fun labels(): String {
                val values = mutableListOf<String>()
                fun collect(view: View) {
                    if (view is TextView) values.add(view.text.toString())
                    if (view is ViewGroup) for (i in 0 until view.childCount) collect(
                        view.getChildAt(
                            i
                        )
                    )
                }
                runOnMainSync { collect(checkNotNull(screen).window.decorView) }
                return values.joinToString("\n")
            }

            fun awaitText(value: String) {
                val deadline = SystemClock.elapsedRealtime() + 20000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (labels().contains(value)) return
                    Thread.sleep(50)
                }
                error("Expected authenticated view did not appear")
            }

            fun capture(name: String) {
                Thread.sleep(600)
                waitForIdleSync()
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, name).outputStream()
                    .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }

            val automotive =
                targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)
            step = "authenticated-list-screen"
            if (automotive) {
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        MainActivity::class.java
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                awaitText("선택한 분류에서 받은 목록")
                runOnMainSync {
                    step = "vehicle-mode"
                    fun field(name: String) = MainActivity::class.java.getDeclaredField(name)
                        .apply { isAccessible = true }.get(screen)
                    check(field("demoMode") == false)
                    check(!(field("drivingState") as DrivingState).isSimulation)
                    val sdk = (field("adapter") as PleosAdapter).diagnosticSnapshot()
                    File(targetContext.filesDir, "vehicle-sdk-attempt-v151.json").writeText(
                        JSONObject()
                            .put("gear", sdk.optString("gear"))
                            .put("fresh", sdk.optBoolean("fresh_gear_received"))
                            .put("age_ms", sdk.optLong("gear_age_ms", -1)).put(
                                "ux_restricted",
                                (field("uxMonitor") as VehicleUxMonitor).restricted
                            ).toString(2)
                    )
                    step = "vehicle-gear"
                    check(sdk.optString("gear") == "GEAR_PARK" && sdk.optBoolean("fresh_gear_received"))
                    step = "vehicle-ux"
                    check(!(field("uxMonitor") as VehicleUxMonitor).restricted)
                    File(targetContext.filesDir, "vehicle-sdk-https-v147.json").writeText(
                        JSONObject()
                            .put("source", "official_pleos_emulator")
                            .put("gear", sdk.getString("gear"))
                            .put("fresh_gear_received", true)
                            .put("gear_age_ms", sdk.getLong("gear_age_ms"))
                            .put("ux_restricted", false).put("simulated_gear", false)
                            .put("synthetic_backend", false).toString(2)
                    )
                }
                capture("authenticated-list-v146.png")
            } else {
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        AccountEventsActivity::class.java
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                awaitText("선택한 분류에서 받은 목록")
                capture("authenticated-list-v146.png")
                runOnMainSync { screen!!.finish() }
                screen = null
                step = "authenticated-comparison-screen"
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        AccountEventsActivity::class.java
                    ).putExtra("notification_event_id", fixture.getString("event_id"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                awaitText("최초 측정과 재측정")
                check(labels().contains("DEMO-OWN-01"))
                capture("authenticated-comparison-v146.png")
                for (key in listOf("crash_id", "road_id")) {
                    runOnMainSync { screen!!.finish() }
                    screen = null
                    screen = startActivitySync(
                        Intent(
                            targetContext,
                            AccountEventsActivity::class.java
                        ).putExtra("notification_event_id", fixture.getString(key))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    awaitText("자료별 확인 내용")
                    check(labels().contains("외부 제출 미실시") && !labels().contains("보험·접수 자료 상세"))
                    capture("authenticated-$key-v149.png")
                }
                runOnMainSync { screen!!.finish() }
                screen = null
                step = "connection-ready-screen"
                screen = startActivitySync(
                    Intent(
                        targetContext,
                        LoginActivity::class.java
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                awaitText("차량 사건 기록을 조회할 수 있습니다")
                fun recordButtonEnabled(): Boolean {
                    var enabled = false
                    fun find(view: View) {
                        if (view is android.widget.Button && view.text.toString() == "차량 사건 기록 보기") enabled =
                            view.isEnabled
                        if (view is ViewGroup) for (index in 0 until view.childCount) find(
                            view.getChildAt(
                                index
                            )
                        )
                    }
                    runOnMainSync { find(screen!!.window.decorView) }
                    return enabled
                }
                check(recordButtonEnabled())
                capture("connection-ready-v152.png")
                step = "connection-no-vehicle-screen"
                IntegrationIdentity.install(targetContext, "no_vehicle_token")
                awaitText("조회할 차량이 등록되지 않았습니다")
                check(!recordButtonEnabled())
                capture("connection-no-vehicle-v152.png")
                IntegrationIdentity.install(targetContext, "read_only_token")
                if (capabilities.notesRead) awaitText("메모 저장은 허용되지 않았습니다") else awaitText("차량 사건 기록을 조회할 수 있습니다")
                check(recordButtonEnabled())
                IntegrationIdentity.install(targetContext)
            }
            val report = JSONObject().put("source", "synthetic")
                .put("transport", "real local TLS and RS256 backend verification")
                .put("evidence_dossier_api_zip_verified", true)
                .put("server_category_filter_verified", true)
                .put("cross_category_cursor_rejected", true).put("pages", 2)
                .put("authorized_records", 28).put("foreign_record_rejected", true)
                .put("capabilities_verified", true).put("connection_screen_verified", !automotive)
                .put("expired_token_rejected", true).put("unauthenticated_rejected", true)
                .put("untrusted_certificate_rejected", true).put("ip_endpoint_rejected", true)
                .put("zip_manifest_and_originals_verified", true)
                .put("authenticated_views_rendered", true).put("vehicle_device", automotive)
                .put("authenticated_comparison_view_rendered", !automotive)
                .put("provider_login_performed", false).put("provider_push_performed", false)
                .put("ui_taps_performed", false)
            File(targetContext.filesDir, "https-result-v146.json").writeText(report.toString(2))
            result.putString(
                "stream",
                "PASS: actual Android local HTTPS, RS256-authenticated pagination/detail/export, foreign object/expired token/no token rejected, untrusted certificate/IP endpoint rejected, authenticated list rendered; comparison view is checked on phone only. Synthetic AppAuth state; no provider login/push/taps.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString(
                "stream",
                "FAIL step=$step class=${failure.javaClass.simpleName}; credentials and response bodies suppressed\n"
            )
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
            store.write(AuthState())
        }
    }
}
