package ai.teraq.roadguardian

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** 시스템 음성 인식기 연결. 엔진이 없으면 없다고 알린다 */
class VoiceInput(private val activity: Activity, private val result: (String?) -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0L
    fun available(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(activity) }.getOrDefault(false)

    fun start(): Boolean {
        close()
        if (!available()) return false
        val ticket = generation
        fun deliver(phrase: String?) {
            if (ticket != generation) return
            close()
            result(phrase)
        }
        return runCatching {
            val next = SpeechRecognizer.createSpeechRecognizer(activity)
            recognizer = next
            next.apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        deliver(null)
                    }

                    override fun onResults(results: Bundle?) {
                        deliver(
                            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                        )
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                })
            }
            true
        }.getOrElse {
            close()
            false
        }
    }

    fun close() {
        generation++
        val previous = recognizer
        recognizer = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
    }
}
