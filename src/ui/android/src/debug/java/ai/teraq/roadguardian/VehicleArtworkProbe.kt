package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle

/** Pixel checks for the real drawable and data marker; no input or navigation. */
class VehicleArtworkProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val image = checkNotNull(
                android.graphics.BitmapFactory.decodeResource(
                    targetContext.resources,
                    R.drawable.generic_vehicle_top_v189
                )
            )
            check(image.hasAlpha() && image.width >= 1000 && image.height >= 1500)
            check(android.graphics.Color.alpha(image.getPixel(0, 0)) == 0)
            image.recycle()
            var failure: Throwable? = null
            runOnMainSync {
                try {
                    for (light in listOf(true, false)) {
                        fun rendered(area: String?): Bitmap {
                            val v = VehicleIdentityOutline(targetContext, area, light)
                            v.layout(0, 0, 220, 300)
                            return Bitmap.createBitmap(220, 300, Bitmap.Config.ARGB_8888)
                                .also { v.draw(Canvas(it)) }
                        }

                        val base = rendered(null)
                        val colors = HashSet<Int>()
                        for (y in 30 until 270) for (x in 65 until 155) colors.add(
                            base.getPixel(
                                x,
                                y
                            )
                        )
                        check(colors.size > 1000) { "Vehicle materials did not decode" }
                        for (area in listOf("미확인", "충격 부위 불명", "정보 없음")) {
                            val unknown = rendered(area)
                            check(base.sameAs(unknown)) { "Unknown area created a marker" }
                            unknown.recycle()
                        }
                        for ((area, right, front) in listOf(
                            Triple("전면 우측", true, true),
                            Triple("전면 좌측", false, true),
                            Triple("후면 우측", true, false),
                            Triple("후면 좌측", false, false)
                        )) {
                            val marked = rendered(area)
                            var changed = 0
                            for (y in 0 until 300) for (x in 0 until 220) if (base.getPixel(
                                    x,
                                    y
                                ) != marked.getPixel(x, y)
                            ) {
                                changed++
                                check(if (right) x > 110 else x < 110) { "Wrong lateral area: $area" }
                                check(if (front) y < 150 else y > 150) { "Wrong longitudinal area: $area" }
                            }
                            check(changed > 200) { "Missing impact marker: $area" }
                            marked.recycle()
                        }
                        base.recycle()
                    }
                } catch (t: Throwable) {
                    failure = t
                }
            }
            failure?.let { throw it }
            result.putString(
                "stream",
                "PASS: overhead alpha/decoded materials, four impact quadrants and three unknown-area suppression cases in light/dark. Canvas rendering only, no taps or vehicle recognition.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
