package ai.teraq.roadguardian

import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** 사용자가 입력한 촬영 상황 정보 */
data class PhotoCaptureContext(
    val source: String,
    val capturedAt: String,
    val placeId: String,
    val viewpointId: String
) {
    val instant get() = OffsetDateTime.parse(capturedAt).toInstant()
    val sourceLabel get() = if (source == "authored_synthetic") "합성 작성" else "사용자 작성"
    fun json() =
        JSONObject().put("source", source).put("captured_at", capturedAt).put("place_id", placeId)
            .put("viewpoint_id", viewpointId)

    fun detail(): String = "$sourceLabel · ${
        OffsetDateTime.parse(capturedAt)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"))
    }\n장소 $placeId · 시야 $viewpointId"

    companion object {
        fun parse(value: JSONObject): PhotoCaptureContext {
            require(
                value.keys().asSequence().toSet() == setOf(
                    "source",
                    "captured_at",
                    "place_id",
                    "viewpoint_id"
                )
            )
            fun string(key: String) = value.get(key).also { require(it is String) } as String
            val source = string("source")
            require(source in setOf("authored_synthetic", "owner_reported"))
            val at = string("captured_at")
            require(at.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(Z|[+-][0-9]{2}:[0-9]{2})")))
            require(at.takeLast(6) != "-00:00")
            val parsed = OffsetDateTime.parse(at)
            require(parsed.year in 1..9999 && kotlin.math.abs(parsed.offset.totalSeconds) <= 14 * 3600)
            fun identifier(key: String): String {
                val s = string(key)
                require(s.isNotBlank() && s == s.trim() && s.length <= 80 && s.none {
                    Character.isISOControl(
                        it
                    ) || Character.isSurrogate(it)
                })
                return s
            }
            return PhotoCaptureContext(
                source,
                at,
                identifier("place_id"),
                identifier("viewpoint_id")
            )
        }

        fun comparison(a: PhotoCaptureContext?, b: PhotoCaptureContext?): String {
            if (a == null || b == null) return "촬영 시각·동일 장소 미확인 · A/B는 비교용 구분"
            if (a.source != b.source) return "작성 출처 다름 · 상세 확인"
            val prefix = if (a.source == "authored_synthetic") "합성 작성 기준" else "사용자 작성 기준"
            if (a.placeId != b.placeId) return "$prefix · 장소 다름"
            if (a.viewpointId != b.viewpointId) return "$prefix · 시야 다름"
            val order = when {
                a.instant < b.instant -> "A 이전 · B 이후"
                a.instant > b.instant -> "B 이전 · A 이후"
                else -> "같은 시각"
            }
            return "$prefix · $order"
        }
    }
}
