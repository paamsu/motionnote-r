package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

class VoiceCommandProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val out = Bundle()
        var count = 0
        fun checkPhrase(p: String?, a: VoiceCommand.Action) {
            check(VoiceCommand.parse(p) == a) { "command boundary $count" }
            count++
        }
        try {
            for (p in listOf("차량이 이상해 진단해", "차량 진단", "진단해 줘", "차량 진단해주세요!")) checkPhrase(
                p,
                VoiceCommand.Action.DIAGNOSE
            )
            for (p in listOf(
                "진단하지 마",
                "진단 취소",
                "차량 진단하지 말아 주세요",
                "진단 중단",
                "진단 필요 없어",
                "진단 안 해",
                "그만"
            )) checkPhrase(p, VoiceCommand.Action.CANCEL)
            for (p in listOf("진단 결과 알려줘", "진단이라는 단어", "음악 틀어줘", "차량이 이상해")) checkPhrase(
                p,
                VoiceCommand.Action.UNSUPPORTED
            )
            checkPhrase(null, VoiceCommand.Action.NO_RESULT)
            checkPhrase("  ", VoiceCommand.Action.NO_RESULT)
            out.putString("result", "PASS $count checks; text policy only, no STT")
            finish(Activity.RESULT_OK, out)
        } catch (t: Throwable) {
            out.putString("error", t.toString())
            finish(Activity.RESULT_CANCELED, out)
        }
    }
}
