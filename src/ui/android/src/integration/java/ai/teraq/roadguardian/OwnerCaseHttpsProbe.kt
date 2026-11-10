package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Authored test input through production Android TLS transport, then actual private detail read. */
class OwnerCaseHttpsProbe : Instrumentation() {
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
            IntegrationIdentity.install(targetContext, "owner_case_token")
            val client = AuthenticatedEventsClient(targetContext)
            val session = client.sessionFingerprint()
            val vehicles = client.registeredVehiclesForDisplay().value
            check(vehicles.size == 1)
            val body = JSONObject().put("schema", "road-guardian.owner-case.v1")
                .put("request_id", UUID.randomUUID().toString())
                .put("vehicle_id", vehicles.single()).put("category", "incident")
                .put("reported_at", JSONObject.NULL)
                .put("title", "직접 남긴 자료 · 입력 시험")
                .put("description", "이 내용은 API 검증용 작성 자료입니다. 실제 충돌이나 책임을 주장하지 않습니다.")
                .put(
                    "own_vehicle",
                    JSONObject().put("plate", "TEST-INPUT-01").put("model", "직접 입력 차종")
                        .put("color", "미기재")
                )
            step = "https-create"
            val event = client.createOwnerCaseForDisplay(body, session).value
            val id = event.getString("id")
            check(client.createOwnerCaseForDisplay(body, session).value.getString("id") == id)
            val mismatch = JSONObject(body.toString()).put("title", "다른 입력")
            check((runCatching {
                client.createOwnerCaseForDisplay(
                    mismatch,
                    session
                )
            }.exceptionOrNull() as? AccountAccessException)?.reason == AccountAccessException.Reason.CONFLICT)
            check(
                client.pageForDisplay(
                    null,
                    "incident"
                ).value.events.let { items ->
                    (0 until items.length()).any {
                        items.getJSONObject(it).getString("id") == id
                    }
                })
            step = "co-owner-isolation"
            IntegrationIdentity.install(targetContext, "other_owner_token")
            val other = AuthenticatedEventsClient(targetContext)
            check((runCatching { other.eventForDisplay(id) }.exceptionOrNull() as? AccountAccessException)?.reason == AccountAccessException.Reason.NOT_FOUND)
            check(
                other.pageForDisplay(
                    null,
                    "incident"
                ).value.events.let { items ->
                    (0 until items.length()).none {
                        items.getJSONObject(it).getString("id") == id
                    }
                })
            IntegrationIdentity.install(targetContext, "owner_case_token")
            step = "actual-phone-detail"
            screen = startActivitySync(
                Intent(
                    targetContext,
                    AccountEventsActivity::class.java
                ).putExtra("notification_event_id", id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            fun labels(): String {
                val values = mutableListOf<String>()
                runOnMainSync {
                    fun collect(view: View) {
                        if (view is TextView) values.add(view.text.toString())
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

            val deadline = android.os.SystemClock.elapsedRealtime() + 20000
            while (!labels().contains("직접 입력 차종") && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(
                50
            )
            val shown = labels()
            check(
                shown.contains("본인 작성") && shown.contains("자동 사고·정비 판정 미실시") && shown.contains("TEST-INPUT-01") && shown.contains(
                    "직접 입력 차종"
                )
            )
            check(!shown.contains("합성 시험 자료") && shown.contains("증거 묶음 저장"))
            // Save only after the actual input plate pixels have reached the display.
            var rendered: Bitmap? = null
            val frameDeadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < frameDeadline) {
                val bounds = android.graphics.Rect()
                var textColor = 0
                runOnMainSync {
                    fun find(view: View) {
                        if (view is TextView && view.text.toString() == "TEST-INPUT-01") {
                            view.getGlobalVisibleRect(bounds)
                            textColor = view.currentTextColor
                        }
                        if (view is ViewGroup) for (i in 0 until view.childCount) find(
                            view.getChildAt(
                                i
                            )
                        )
                    }
                    find(screen!!.window.decorView)
                }
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                val colors = mutableSetOf<Int>()
                if (!bounds.isEmpty) for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(
                    bitmap.height
                ) step 2)
                    for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width) step 3) colors.add(
                        bitmap.getPixel(x, y)
                    )
                // A transition/dim overlay can contain text-shaped pixels too. Wait for the actual view ink.
                if (colors.size > 4 && textColor in colors) {
                    rendered = bitmap
                    break
                }
                bitmap.recycle()
                Thread.sleep(50)
            }
            val bitmap = checkNotNull(rendered)
            File(targetContext.filesDir, "owner-case-v157.png").outputStream()
                .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            File(targetContext.filesDir, "owner-case-v157.json").writeText(
                JSONObject().put(
                    "case",
                    event
                ).put("retry_verified", true).put("conflict_verified", true)
                    .put("same_vehicle_other_subject_hidden", true)
                    .put("input", "authored test text; not an actual incident").toString(2)
            )
            step = "actual-phone-composer"
            screen = startActivitySync(
                Intent(
                    targetContext,
                    OwnerCaseActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val composerDeadline = android.os.SystemClock.elapsedRealtime() + 20000
            while (!labels().contains("작성 내용 확인") && android.os.SystemClock.elapsedRealtime() < composerDeadline) Thread.sleep(
                50
            )
            check(labels().contains("등록 차량 1") && labels().contains("작성 내용 확인"))
            var emptyFields = 0
            runOnMainSync {
                fun inspect(view: View) {
                    if (view is android.widget.EditText) {
                        check(view.text.isEmpty())
                        check(!view.isSaveEnabled)
                        emptyFields++
                    }
                    if (view is ViewGroup) for (i in 0 until view.childCount) inspect(
                        view.getChildAt(
                            i
                        )
                    )
                }
                inspect(screen!!.window.decorView)
            }
            check(emptyFields == 5)
            IntegrationIdentity.install(targetContext, "other_owner_token")
            val clearDeadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (!labels().contains("입력 내용을 지웠습니다") && android.os.SystemClock.elapsedRealtime() < clearDeadline) Thread.sleep(
                50
            )
            check(labels().contains("입력 내용을 지웠습니다") && !labels().contains("작성 내용 확인"))
            result.putString(
                "stream",
                "PASS: Android HTTPS owner-authored case create/read/hash/retry/conflict and same-vehicle co-owner isolation; real phone detail labels and pixels; actual composer empty fields, disabled state saving and account-change clearing. Test identity/input; no real incident, provider login, typing, preview or save tap.\n"
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
