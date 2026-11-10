package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Actual DocumentsUI via accessibility actions; no injected document result or direct human taps. */
class MeasurementSystemPickerProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        var stage = "setup"
        try {
            check(BuildConfig.MEASUREMENTS_ENABLED)
            val fixture = IntegrationIdentity.install(targetContext, "measurement_token")
            val client = AuthenticatedEventsClient(targetContext)
            val session = client.sessionFingerprint()
            val id = fixture.getString("measurement_case_event_id")
            val vehicle = fixture.getJSONObject("measurement_before").getString("vehicle_id")
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
            val eventHash = client.eventForDisplay(id).value.getString("hash")
            screen = startActivitySync(
                Intent(
                    targetContext,
                    MeasurementActivity::class.java
                ).putExtra("event_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            fun texts(): List<TextView> {
                val out = mutableListOf<TextView>()
                runOnMainSync {
                    fun walk(v: View) {
                        if (v is TextView && v.isShown) out.add(v)
                        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                    }
                    walk(screen!!.window.decorView)
                }
                return out
            }

            fun await(label: String) {
                val end = android.os.SystemClock.elapsedRealtime() + 15000
                while (android.os.SystemClock.elapsedRealtime() < end) {
                    if (texts().any { it.text.toString().contains(label) }) return
                    Thread.sleep(100)
                }
                error("Missing app state: $label")
            }

            fun appClick(label: String) {
                val view = texts().single { it.text.toString() == label }
                runOnMainSync { check(view.performClick()) }
                waitForIdleSync()
            }

            fun capture(name: String) {
                Thread.sleep(350)
                val b = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "measurement-system-$name.png").outputStream()
                    .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }

            fun nodes(): List<AccessibilityNodeInfo> {
                val out = mutableListOf<AccessibilityNodeInfo>()
                fun walk(n: AccessibilityNodeInfo) {
                    out.add(n)
                    for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
                }
                uiAutomation.rootInActiveWindow?.let { walk(it) }
                return out
            }

            fun awaitPicker() {
                val end = android.os.SystemClock.elapsedRealtime() + 10000
                while (android.os.SystemClock.elapsedRealtime() < end) {
                    if (nodes().any {
                            it.packageName?.toString()?.endsWith("documentsui") == true
                        }) return
                    Thread.sleep(100)
                }
                error("DocumentsUI absent")
            }

            fun comparison() {
                await("앞 측정 기록")
                runOnMainSync {
                    MeasurementActivity::class.java.getDeclaredMethod(
                        "compare",
                        String::class.java,
                        String::class.java
                    ).apply { isAccessible = true }
                        .invoke(screen, before.summary.id, after.summary.id)
                }
                await("선택한 두 기록의 비교 묶음 저장")
            }

            val filename = "measurement-system-${java.util.UUID.randomUUID()}.zip"
            val cancelFilename = "measurement-cancel-${java.util.UUID.randomUUID()}.zip"
            fun nameDocument(value: String) {
                val editor =
                    nodes().single { it.className?.toString() == "android.widget.EditText" }
                check(
                    editor.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                value
                            )
                        })
                )
            }
            comparison()
            stage = "cancel actual picker"
            appClick("선택한 두 기록의 비교 묶음 저장")
            awaitPicker()
            nameDocument(cancelFilename)
            capture("cancel-picker")
            check(uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            await("앞 측정 기록")
            capture("cancel-return")
            comparison()
            stage = "save actual picker"
            appClick("선택한 두 기록의 비교 묶음 저장")
            awaitPicker()
            check(nodes().any {
                it.text?.toString() in setOf(
                    "Downloads",
                    "다운로드"
                )
            }) { "Expected Downloads location" }
            nameDocument(filename)
            capture("save-picker")
            val save = nodes().firstOrNull {
                it.isEnabled && it.text?.toString()?.lowercase() in setOf("save", "저장")
            } ?: error("Save control absent")
            var action: AccessibilityNodeInfo? = save
            while (action != null && !action.isClickable) action = action.parent
            check(action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            stage = "receive real picker result and write"
            await("비교 기록 저장 완료")
            capture("saved")
            val expected =
                client.measurementPackage(id, eventHash, vehicle, before.summary, after.summary)
            check(
                MeasurementPackageVerifier.verify(
                    expected,
                    id,
                    eventHash,
                    vehicle,
                    before.summary.id,
                    after.summary.id
                ).matched
            )
            expected.copyTo(
                File(targetContext.filesDir, "measurement-system-expected.zip"),
                overwrite = true
            )
            expected.delete()
            File(targetContext.filesDir, "measurement-system-result.json").writeText(
                JSONObject().put("pass", true).put("event_id", id).put("case_sha256", eventHash)
                    .put("vehicle_id", vehicle).put("before_id", before.summary.id)
                    .put("after_id", after.summary.id).put("filename", filename)
                    .put("cancel_filename", cancelFilename)
                    .put(
                        "scope",
                        "Phone emulator actual DocumentsUI cancel/save through accessibility actions, production callback and HTTPS; saved document requires independent byte verification. Programmatic comparison selection, not direct human taps."
                    ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: actual system picker cancel/save and production success; independent destination verification required\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL at $stage: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
