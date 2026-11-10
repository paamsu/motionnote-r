package ai.teraq.roadguardian

/** 최종 텍스트만 해석한다. 마이크 인식 여부는 판단하지 않는다 */
object VoiceCommand {
    enum class Action { DIAGNOSE, CANCEL, UNSUPPORTED, NO_RESULT }

    fun parse(phrase: String?): Action {
        if (phrase == null || phrase.isBlank()) return Action.NO_RESULT
        val text = phrase.trim().replace(Regex("[\\s.!?。！？,]+"), "")
        if (listOf(
                "취소",
                "중단",
                "그만",
                "하지마",
                "하지말",
                "하지않",
                "필요없",
                "안해",
                "안할",
                "안하",
                "싫어"
            ).any { text.contains(it) }
        ) return Action.CANCEL
        return if (text in setOf(
                "차량진단", "차량진단해", "차량진단해줘", "차량진단해줘요", "차량진단해주세요", "차량을진단해", "차량을진단해주세요",
                "진단해", "진단해줘", "진단해줘요", "진단해주세요", "차량이이상해진단해", "차량이이상해진단해줘", "차량이이상해진단해주세요",
                "차가이상해진단해", "차가이상해진단해줘", "차가이상해진단해주세요"
            )
        ) Action.DIAGNOSE else Action.UNSUPPORTED
    }
}
