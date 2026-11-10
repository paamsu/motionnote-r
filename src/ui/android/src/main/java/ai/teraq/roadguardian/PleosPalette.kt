package ai.teraq.roadguardian

import android.graphics.Color

/** 공식 Pleos 회색조 토큰의 중립 색. 동작 색은 따로 관리한다 */
object PleosPalette {
    fun ink(light: Boolean) = Color.parseColor(if (light) "#131417" else "#FFFFFF")
    fun muted(light: Boolean) = Color.parseColor(if (light) "#686A72" else "#C7C9CE")
    fun action(light: Boolean) = Color.parseColor(if (light) "#246BD1" else "#8EB8FF")
    fun background(light: Boolean) = Color.parseColor(if (light) "#FFFFFF" else "#131417")
    fun surface(light: Boolean) = Color.parseColor(if (light) "#F7F8FA" else "#1D1E21")
    fun panel(light: Boolean) = Color.parseColor(if (light) "#FFFFFF" else "#1D1E21")
    fun line(light: Boolean) = Color.parseColor(if (light) "#E0E1E6" else "#44464E")
    fun selected(light: Boolean) = Color.parseColor(if (light) "#EAF1FC" else "#25344D")
}
