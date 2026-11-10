package ai.teraq.roadguardian

import android.content.Context
import java.util.Locale

/** 전압 중앙값 비교용으로 공통 비교 그림을 맞춘 것 */
class MeasurementComparisonGraphic(
    context: Context,
    before: Double,
    after: Double,
    light: Boolean,
    uiScale: Float = 1f
) :
    PairedObservationGraphic(
        context, before, after, light, uiScale, "V", 2, "앞 기록", "뒤 기록", null,
        "앞 기록 중앙값 ${
            String.format(
                Locale.KOREAN,
                "%.2f",
                before
            )
        }V, 뒤 기록 중앙값 ${
            String.format(
                Locale.KOREAN,
                "%.2f",
                after
            )
        }V. 보고된 조건에서의 수치 차이이며 수리 성공 판정이 아닙니다."
    )
