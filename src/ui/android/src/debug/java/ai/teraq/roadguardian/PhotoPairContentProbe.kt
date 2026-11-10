package ai.teraq.roadguardian

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject

/** Debug-only shared renderer test, not private upload, direct touch or chronology validation. */
class PhotoPairContentPreviewActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        PhoneAccountUi(this).install()
    }
}

class PhotoPairContentProbe : Instrumentation() {
    private var scenePhotos = false
    private var fourPhotos = false
    override fun onCreate(arguments: Bundle?) {
        scenePhotos = arguments?.getString("scene_photos") == "true"
        fourPhotos = arguments?.getString("four_photos") == "true"
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            activity = startActivitySync(
                Intent(
                    targetContext,
                    PhotoPairContentPreviewActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val screen = activity
            val event = "00000000-0000-4000-8000-000000000000"
            fun image(color: Int): ByteArray {
                val b = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                c.drawColor(Color.WHITE)
                val paint = Paint().apply { this.color = color }
                c.drawCircle(320f, 180f, 105f, paint)
                paint.color = Color.BLACK
                paint.textSize = 24f
                c.drawText("SYNTHETIC COMPARISON INPUT", 90f, 40f, paint)
                return ByteArrayOutputStream().also {
                    b.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    b.recycle()
                }.toByteArray()
            }

            fun sceneImage(name: String): ByteArray {
                val bitmap = targetContext.assets.open(name)
                    .use { android.graphics.BitmapFactory.decodeStream(it) }
                return ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    bitmap.recycle()
                }.toByteArray()
            }

            val a = if (scenePhotos) sceneImage("pair-road-a-v197.png") else image(Color.BLUE)
            val b = if (scenePhotos) sceneImage("pair-road-b-v197.png") else image(
                Color.rgb(
                    230,
                    120,
                    20
                )
            )

            fun photo(index: Int, raw: ByteArray): Pair<CasePhoto, ByteArray> {
                val dimensions =
                    android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, dimensions)
                return CasePhoto(
                    "00000000-0000-4000-8000-00000000000$index",
                    event,
                    "합성 입력 $index",
                    dimensions.outWidth,
                    dimensions.outHeight,
                    raw.size,
                    CasePhoto.hash(raw),
                    CasePhoto.hash(raw),
                    1700000000.0 + index
                ) to raw
            }

            val photos = listOf(photo(1, a), photo(2, b), photo(3, a)) + if (fourPhotos) listOf(
                photo(
                    4,
                    b
                )
            ) else emptyList()
            var allowed = true
            lateinit var panel: CasePhotoComparison
            fun install(items: List<Pair<CasePhoto, ByteArray>>) {
                runOnMainSync {
                    val ui = PhoneAccountUi(screen)
                    val body = ui.install()
                    body.addView(ui.text("합성 사진 비교 검사", 24f, true))
                    panel = CasePhotoComparison(ui, items) { allowed }
                    body.addView(panel)
                }
                waitForIdleSync()
            }

            fun texts(root: View): List<TextView> {
                val out = mutableListOf<TextView>()
                fun walk(v: View) {
                    if (v is TextView) out.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(root)
                return out
            }

            fun click(root: View, label: String) {
                runOnMainSync { texts(root).single { it.text.toString() == label }.performClick() }
                waitForIdleSync()
            }

            fun selection() = CasePhotoComparison::class.java.getDeclaredField("selection")
                .apply { isAccessible = true }.get(panel) as PhotoPairSelection

            fun capture(name: String) {
                waitForIdleSync()
                Thread.sleep(250)
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(targetContext.filesDir, "photo-content-$name.png").outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }

            val initialSecond = photos[if (fourPhotos) 3 else 1].first.id
            install(photos)
            check(selection().get(0) == photos[0].first.id && selection().get(1) == initialSecond)
            capture("distinct")
            click(panel, "B 선택")
            val chooser = CasePhotoComparison::class.java.getDeclaredField("chooser")
                .apply { isAccessible = true }.get(panel) as Dialog
            click(chooser.window!!.decorView, "사진 3")
            check(selection().get(1) == initialSecond)
            runOnMainSync { check(texts(chooser.window!!.decorView).any { it.text.toString() == "같은 사진입니다. 다른 사진을 선택하세요." }) }
            capture("refused")
            click(chooser.window!!.decorView, "취소")
            click(panel, "A ↔ B")
            check(selection().get(0) == initialSecond && selection().get(1) == photos[0].first.id)
            capture("swapped")
            allowed = false
            click(panel, "A 선택")
            runOnMainSync {
                check(texts(panel).any {
                    it.text.toString().contains("비교 사진을 지웠습니다")
                })
            }
            capture("access-cleared")
            allowed = true
            install(listOf(photos[0], photos[2]))
            runOnMainSync { check(texts(panel).any { it.text.toString() == "같은 이미지뿐입니다. 다른 사진을 추가해 주세요." }) }
            capture("all-identical")
            fun context(
                at: String = "2026-11-02T14:30:00+09:00",
                place: String = "B12",
                view: String = "front",
                source: String = "authored_synthetic"
            ) =
                PhotoCaptureContext.parse(
                    JSONObject().put("source", source).put("captured_at", at).put("place_id", place)
                        .put("viewpoint_id", view)
                )

            val early = context()
            val late = context("2026-11-02T15:30:00+09:00")
            fun pair(
                ca: PhotoCaptureContext?,
                cb: PhotoCaptureContext?,
                label: String,
                name: String
            ) {
                install(
                    listOf(
                        photos[0].first.copy(captureContext = ca) to a,
                        photos[1].first.copy(captureContext = cb) to b
                    )
                )
                runOnMainSync { check(texts(panel).any { it.text.toString() == label }) }
                capture("context-$name")
            }
            pair(early, late, "합성 작성 기준 · A 이전 · B 이후", "ordered")
            click(panel, "A ↔ B")
            runOnMainSync { check(texts(panel).any { it.text.toString() == "합성 작성 기준 · B 이전 · A 이후" }) }
            capture("context-swapped")
            pair(early, context("2026-11-02T05:30:00Z"), "합성 작성 기준 · 같은 시각", "equal-instant")
            pair(early, context(place = "B13"), "합성 작성 기준 · 장소 다름", "place")
            pair(early, context(view = "rear"), "합성 작성 기준 · 시야 다름", "view")
            pair(early, null, "촬영 시각·동일 장소 미확인 · A/B는 비교용 구분", "missing")
            pair(early, context(source = "owner_reported"), "작성 출처 다름 · 상세 확인", "source")
            for (invalid in listOf(
                "2026-11-02T14:30:00",
                "2026-02-30T14:30:00Z",
                "2026-11-02T14:30:00-00:00",
                "2026-11-02T14:30:00+15:00"
            )) check(runCatching { context(invalid) }.isFailure)
            check(runCatching { context(place = " ") }.isFailure)
            File(
                targetContext.filesDir,
                "photo-context-result.json"
            ).writeText(
                JSONObject().put("pass", true).put(
                    "scope",
                    "Shared production comparison renderer; authored context, not capture attestation or HTTPS upload"
                ).put(
                    "cases",
                    listOf(
                        "ordered",
                        "swap",
                        "same instant across offsets",
                        "place differs",
                        "view differs",
                        "missing",
                        "mixed source",
                        "invalid metadata refused"
                    )
                ).toString(2)
            )
            File(
                targetContext.filesDir,
                "photo-content-result.json"
            ).writeText(
                JSONObject().put("pass", true).put(
                    "cases",
                    listOf(
                        "distinct initial pair",
                        "different ID same bytes refused",
                        "swap",
                        "access cleared",
                        "all identical refused"
                    )
                ).put(
                    "scope",
                    "Programmatic shared renderer on emulator; generated JPEG inputs. No upload, capture-time, scene-identity or direct-touch claims."
                ).toString(2)
            )
            result.putString(
                "stream",
                "PASS: distinct content, chooser refusal, swap, private clear, all-identical notice\n"
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
