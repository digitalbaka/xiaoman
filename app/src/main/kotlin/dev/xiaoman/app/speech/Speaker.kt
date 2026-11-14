package dev.xiaoman.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Reads replies with the phone's own TTS engine. Long answers are split into chunks that the
 * engine queues, so nothing blocks the UI and stopping works at any point.
 */
class Speaker(context: Context) {
    private val tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
    private var ready = false
    private var queued: String? = null
    private var chunks = 0

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ready = true
        runCatching { tts.language = Locale.getDefault() }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastId()) _speaking.value = false
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _speaking.value = false
            }
        })
        queued?.let { text ->
            queued = null
            speak(text)
        }
    }

    private fun lastId() = "xiaoman-${chunks - 1}"

    /** Speaks [text]; calling again replaces whatever is being read. */
    fun speak(text: String) {
        val plain = clean(text)
        if (plain.isBlank()) return
        if (!ready) {
            queued = plain
            return
        }
        stop()
        val pieces = split(plain)
        chunks = pieces.size
        pieces.forEachIndexed { index, piece ->
            tts.speak(piece, TextToSpeech.QUEUE_ADD, null, "xiaoman-$index")
        }
    }

    fun stop() {
        queued = null
        chunks = 0
        tts.stop()
        _speaking.value = false
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    /** Markdown markers and links read badly, so reduce the reply to plain sentences. */
    private fun clean(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("[#>*_`~|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Splits on sentence endings, keeping every piece comfortably inside the TTS limit. */
    private fun split(text: String): List<String> {
        val limit = minOf(TextToSpeech.getMaxSpeechInputLength() - 200, 1500)
        if (text.length <= limit) return listOf(text)
        val pieces = mutableListOf<String>()
        val sentence = StringBuilder()
        for (char in text) {
            sentence.append(char)
            val boundary = char == '。' || char == '！' || char == '？' || char == '\n' ||
                char == '.' || char == '!' || char == '?'
            if (boundary && sentence.length >= limit / 3) {
                pieces += sentence.toString()
                sentence.clear()
            } else if (sentence.length >= limit) {
                pieces += sentence.toString()
                sentence.clear()
            }
        }
        if (sentence.isNotBlank()) pieces += sentence.toString()
        return pieces.filter { it.isNotBlank() }
    }
}
