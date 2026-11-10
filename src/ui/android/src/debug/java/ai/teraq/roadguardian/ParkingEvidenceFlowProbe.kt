package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Same-event parking evidence Main routes; no camera recognition or direct taps. */
class ParkingEvidenceFlowProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        try {
            val client = BackendClient("http://127.0.0.1:9493")
            val manifest = JSONObject(client.request("/api/dev/parking-evidence-fixture"))
            val ids = manifest.getJSONObject("ids")
            main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val activity = main
            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

            fun method(name: String) =
                MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }

            fun await(label: String, test: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 18000
                while (SystemClock.elapsedRealtime() < deadline) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(50)
                }
                error("Timeout: $label")
            }

            fun labels(screen: Activity): List<TextView> {
                val nodes = mutableListOf<TextView>()
                fun visit(view: View) {
                    if (!view.isShown) return
                    if (view is TextView) nodes.add(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                        view.getChildAt(
                            i
                        )
                    )
                }
                visit(screen.window.decorView)
                return nodes
            }

            data class PixelAnchor(val bitmap: Bitmap, val left: Int, val top: Int, val ink: Int)

            fun frame(screen: Activity, name: String, vararg expected: String) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val anchors = mutableListOf<PixelAnchor>()
                    runOnMainSync {
                        for (text in expected) labels(screen).firstOrNull { it.text.toString() == text && it.layout?.text?.toString() == text && !it.isLayoutRequested && it.width > 0 && it.height > 0 }
                            ?.let { view ->
                                val at = IntArray(2)
                                view.getLocationOnScreen(at)
                                val bitmap = Bitmap.createBitmap(
                                    view.width,
                                    view.height,
                                    Bitmap.Config.ARGB_8888
                                ).also { view.draw(Canvas(it)) }
                                anchors.add(
                                    PixelAnchor(
                                        bitmap,
                                        at[0],
                                        at[1],
                                        view.currentTextColor
                                    )
                                )
                            }
                    }
                    if (anchors.size != expected.size) {
                        anchors.forEach { it.bitmap.recycle() }
                        Thread.sleep(60)
                        continue
                    }
                    val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                    val presented = try {
                        anchors.all { (mask, left, top, ink) ->
                            var pixels = 0
                            var matched = 0
                            for (y in 0 until mask.height) for (x in 0 until mask.width) {
                                val value = mask.getPixel(x, y)
                                if (Color.alpha(value) < 245 || kotlin.math.abs(
                                        Color.red(value) - Color.red(
                                            ink
                                        )
                                    ) > 24 || kotlin.math.abs(Color.green(value) - Color.green(ink)) > 24 || kotlin.math.abs(
                                        Color.blue(value) - Color.blue(ink)
                                    ) > 24
                                ) continue
                                pixels++
                                if (left + x !in 0 until screenshot.width || top + y !in 0 until screenshot.height) continue
                                val actual = screenshot.getPixel(left + x, top + y)
                                if (kotlin.math.abs(Color.red(value) - Color.red(actual)) < 12 && kotlin.math.abs(
                                        Color.green(value) - Color.green(actual)
                                    ) < 12 && kotlin.math.abs(Color.blue(value) - Color.blue(actual)) < 12
                                ) matched++
                            }
                            pixels > 30 && matched >= pixels * .97
                        }
                    } finally {
                        anchors.forEach { it.bitmap.recycle() }
                    }
                    if (presented && SystemClock.elapsedRealtime() <= deadline) {
                        File(targetContext.filesDir, name).outputStream()
                            .use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        screenshot.recycle()
                        return
                    }
                    screenshot.recycle()
                    Thread.sleep(60)
                }
                error("Native expected pixels absent: $name / ${expected.toList()}")
            }

            fun stopPoll() {
                val handler = field("timer").get(activity) as Handler
                for (name in listOf(
                    "feedPoll",
                    "drivingWatch"
                )) handler.removeCallbacks(field(name).get(activity) as Runnable)
            }

            fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            await("idle/OEM allowance") {
                !field("busy").getBoolean(activity) && !field("comparisonLoading").getBoolean(
                    activity
                ) && (field("uxMonitor").get(activity) as? VehicleUxMonitor)?.restricted == false
            }
            runOnMainSync {
                stopPoll()
                field("client").set(activity, client);
                (field("drivingState").get(activity) as DrivingState).apply {
                    useSimulation(true)
                    setSimulatedMoving(false)
                }
                field("selected").set(activity, "기록")
                field("light").setBoolean(activity, true)
            }
            val rows = JSONArray()
            for (light in listOf(true, false)) for (case in listOf(
                "parking",
                "missing",
                "corrupt"
            )) {
                val id = ids.getString(case)
                val event = JSONObject(client.request("/api/events/$id"))
                val title = "주차 중 충격 기록"
                val status = when (case) {
                    "parking" -> "기록 확보 완료"
                    "missing" -> "사진 미첨부"
                    else -> "사진 확인 실패"
                }
                runOnMainSync {
                    stopPoll()
                    field("light").setBoolean(activity, light)
                    field("opened").set(activity, null)
                    field("detailReturnId").set(activity, id)
                    method("refreshReturnedDetail").invoke(activity)
                }
                await("$case fresh Main") {
                    field("detailReturnId").get(activity) == null && (field(
                        "opened"
                    ).get(activity) as? JSONObject)?.optString("id") == id && labels(activity).any { it.text.toString() == status }
                }
                check(!labelsSafe(activity).any { it.contains("가상 설정 · 번호판") })
                val name = "parking-evidence-$case-${if (light) "day" else "night"}"
                frame(activity, "$name-main.png", title, status, "상세 기록 펼치기")
                var photoSame = false
                if (case == "parking") {
                    val bytes = client.scenePhoto(id)
                    val meta = event.getJSONObject("video").getJSONObject("input_photo")
                    check(sha(bytes) == meta.getString("sha256"))
                    var sample = 1
                    while (maxOf(
                            meta.getInt("width"),
                            meta.getInt("height")
                        ) / sample > 1024
                    ) sample *= 2
                    val expected = android.graphics.BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.size,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    runOnMainSync {
                        fun images(v: View): List<android.widget.ImageView> =
                            (if (v is android.widget.ImageView && v.isShown) listOf(v) else emptyList()) + (if (v is ViewGroup) (0 until v.childCount).flatMap {
                                images(v.getChildAt(it))
                            } else emptyList())

                        val actual =
                            images(activity.window.decorView).single { it.contentDescription == "이 사건 기록에 보관된 사진" }.drawable as android.graphics.drawable.BitmapDrawable
                        photoSame = actual.bitmap.sameAs(expected)
                    }
                    expected.recycle()
                    check(photoSame)
                    if (light) {
                        val zip = File(targetContext.filesDir, "parking-evidence.zip").apply {
                            writeBytes(client.evidencePackage(id))
                        }
                        EvidencePackageVerifier.verify(zip, id)
                        ZipFile(zip).use { archive ->
                            val report = JSONObject(
                                archive.getInputStream(archive.getEntry("event-report.json"))
                                    .readBytes().toString(Charsets.UTF_8)
                            )
                            val photo = report.getJSONObject("video").getJSONObject("input_photo")
                            check(
                                archive.getInputStream(archive.getEntry(photo.getString("path")))
                                    .readBytes().contentEquals(bytes)
                            )
                        }
                    }
                }
                runOnMainSync {
                    labels(activity).single { it.text.toString() == "상세 기록 펼치기" }.performClick()
                }
                await("$case detail") { labels(activity).any { it.text.toString() == "상세 기록 접기" } }
                frame(activity, "$name-detail.png", "상세 기록 접기")
                rows.put(
                    JSONObject().put("case", case).put("light", light).put("event_id", id)
                        .put("initially_collapsed", true).put("visible_glyph_pixels", true)
                        .put("same_event_photo_pixels", photoSame).put("status", status)
                )
            }
            val normalId = ids.getString("parking")
            runOnMainSync {
                stopPoll()
                field("light").setBoolean(activity, true)
                field("opened").set(activity, null)
                field("detailReturnId").set(activity, normalId)
                method("refreshReturnedDetail").invoke(activity)
            }
            await("normal photo before restriction") { labels(activity).any { it.text.toString() == "기록 확보 완료" } }
            runOnMainSync {
                (field("drivingState").get(activity) as DrivingState).setSimulatedMoving(
                    true
                )
            }
            await("photo cleared by driving restriction") { labels(activity).any { it.text.toString() == "사진 표시 종료" } }
            runOnMainSync {
                fun visiblePhoto(v: View): Boolean =
                    (v is android.widget.ImageView && v.isShown && v.contentDescription == "이 사건 기록에 보관된 사진") || (v is ViewGroup && (0 until v.childCount).any {
                        visiblePhoto(v.getChildAt(it))
                    })
                check(!visiblePhoto(activity.window.decorView))
            }
            frame(activity, "parking-evidence-driving-cleared.png", "사진 표시 종료")
            rows.put(
                JSONObject().put("case", "simulated_driving_restriction").put("photo_removed", true)
                    .put("physical_vehicle", false)
            )
            runOnMainSync {
                (field("drivingState").get(activity) as DrivingState).setSimulatedMoving(
                    false
                )
            }
            File(
                targetContext.filesDir,
                "parking-evidence-flow.json"
            ).writeText(
                JSONObject().put("pass", true).put("cases", rows).put(
                    "scope",
                    "Actual Main day/night, programmatic navigation, same-event decoded bitmap equality, visible text pixels, original-photo ZIP. No real camera inference or physical vehicle."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: parking evidence Main day/night normal/missing/corrupt, original bitmap and ZIP.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            runCatching {
                uiAutomation.takeScreenshot()?.let { b ->
                    File(targetContext.filesDir, "parking-evidence-failure.png").outputStream()
                        .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    b.recycle()
                }
            }
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { main?.finish() }
        }
    }

    private fun labelsSafe(activity: Activity): List<String> {
        val values = mutableListOf<String>()
        runOnMainSync {
            fun walk(v: View) {
                if (!v.isShown) return
                if (v is TextView) values.add(v.text.toString())
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(activity.window.decorView)
        }
        return values
    }
}
