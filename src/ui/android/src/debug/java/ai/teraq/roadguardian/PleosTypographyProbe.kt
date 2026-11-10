package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Inspect actual shaped glyph font files, including Korean fallback; no UI input. */
class PleosTypographyProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        try {
            fun fontFiles(face: Typeface): List<String> {
                if (android.os.Build.VERSION.SDK_INT < 31) throw UnsupportedOperationException("Glyph inspection requires API 31")
                val text = "도로 관측 500 km DEMO"
                val glyphs = TextRunShaper.shapeTextRun(
                    text,
                    0,
                    text.length,
                    0,
                    text.length,
                    0f,
                    0f,
                    false,
                    Paint().apply {
                        typeface = face
                        textSize = 32f
                    })
                return (0 until glyphs.glyphCount()).map {
                    glyphs.getFont(it).file?.name ?: "unknown"
                }.distinct()
            }

            fun fontHashes(face: Typeface): List<String> {
                val text = "도로 관측 500 km DEMO"
                val glyphs = TextRunShaper.shapeTextRun(
                    text,
                    0,
                    text.length,
                    0,
                    text.length,
                    0f,
                    0f,
                    false,
                    Paint().apply {
                        typeface = face
                        textSize = 32f
                    })
                return (0 until glyphs.glyphCount()).map {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    digest.update(glyphs.getFont(it).buffer.duplicate().apply { rewind() })
                    digest.digest().joinToString("") { b -> "%02x".format(b.toInt() and 255) }
                }.distinct()
            }

            fun resourceHash(id: Int): String =
                targetContext.resources.openRawResource(id).use { stream ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val bytes = ByteArray(8192)
                    while (true) {
                        val n = stream.read(bytes)
                        if (n < 0) break
                        digest.update(bytes, 0, n)
                    }
                    digest.digest().joinToString("") { b -> "%02x".format(b.toInt() and 255) }
                }

            val weights = mutableSetOf<Int>()
            var count = 0
            var failure: Throwable? = null
            screen = startActivitySync(
                Intent(
                    targetContext,
                    AccountDetailPreviewActivity::class.java
                ).putExtra("dossier_fixture", "crash")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            waitForIdleSync()
            val regular = fontFiles(PleosTypography.face())
            val medium = fontFiles(PleosTypography.face(true))
            val car =
                targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)
            if (car) check(regular == fontFiles(Typeface.DEFAULT)) { "System default family differs" }
            val regularHashes = fontHashes(PleosTypography.face())
            val mediumHashes = fontHashes(PleosTypography.face(true))
            val expectedRegular = resourceHash(R.font.asta_regular)
            val expectedMedium = resourceHash(R.font.asta_medium)
            if (car) {
                check(regular.all { it.contains("Asta", true) }) { "Asta Sans not used: $regular" }
                check(medium.all {
                    it.contains(
                        "Asta",
                        true
                    )
                }) { "Asta Sans medium not used: $medium" }
            } else {
                check(regularHashes == listOf(expectedRegular)) { "Bundled regular glyph bytes differ: $regularHashes" }
                check(mediumHashes == listOf(expectedMedium)) { "Bundled medium glyph bytes differ: $mediumHashes" }
            }
            runOnMainSync {
                try {
                    fun collect(v: View) {
                        if (v is TextView) {
                            count++
                            weights.add(v.typeface.weight)
                            check(v.typeface.weight in listOf(400, 500))
                            check(!v.paint.isFakeBoldText)
                            if (!car) check(fontHashes(v.typeface).all {
                                it in listOf(
                                    expectedRegular,
                                    expectedMedium
                                )
                            }) { "TextView fallback: ${v.text}" }
                        }
                        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
                    }
                    collect(screen!!.window.decorView)
                } catch (t: Throwable) {
                    failure = t
                }
            }
            failure?.let { throw it }
            check(count > 15)
            File(
                targetContext.filesDir,
                "pleos-typography-v194.json"
            ).writeText(
                JSONObject().put("automotive", car).put("regular_fonts", JSONArray(regular))
                    .put("emphasis_fonts", JSONArray(medium))
                    .put("regular_font_sha256", JSONArray(regularHashes))
                    .put("medium_font_sha256", JSONArray(mediumHashes)).put("text_views", count)
                    .put("weights", JSONArray(weights.toList()))
                    .put("official_bundled_phone_fonts", !car)
                    .put("system_default_fonts", JSONArray(fontFiles(Typeface.DEFAULT))).toString(2)
            )
            result.putString(
                "stream",
                "PASS: actual glyph files=$regular, emphasis=$medium, $count TextViews with weights=$weights; Asta family with OEM car / official bundled phone policy; no fake bold. No taps.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { screen?.finish() }
        }
    }
}
