package com.munawar.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Thin wrapper around SpeechRecognizer that keeps listening like a live call. Main thread only. */
class Stt(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        fun onRms(level: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onFatal(message: String)
    }

    var lang: String = "ur-PK"

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var active = false
    private var gen = 0
    private var errorStreak = 0

    fun start() {
        active = true
        errorStreak = 0
        main.removeCallbacksAndMessages(null)
        begin()
    }

    fun restart() {
        if (active) restartLater(150)
    }

    fun pause() {
        active = false
        gen++
        main.removeCallbacksAndMessages(null)
        val r = rec
        rec = null
        main.post { destroy(r) }
    }

    private fun destroy(r: SpeechRecognizer?) {
        try {
            r?.cancel()
            r?.destroy()
        } catch (e: Exception) {
        }
    }

    private fun restartLater(ms: Long) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ begin() }, ms)
    }

    private fun begin() {
        if (!active) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            cb.onFatal("Speech recognition is not available on this phone")
            return
        }
        destroy(rec)
        rec = null
        gen++
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        r.setRecognitionListener(listenerFor(gen))
        rec = r
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        try {
            r.startListening(i)
        } catch (e: Exception) {
            restartLater(800)
        }
    }

    private fun first(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()

    private fun listenerFor(myGen: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            if (myGen == gen && active) cb.onRms(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (myGen != gen || !active) return
            val t = first(partialResults)
            if (t.isNotBlank()) cb.onPartial(t)
        }

        override fun onResults(results: Bundle?) {
            if (myGen != gen || !active) return
            errorStreak = 0
            val t = first(results)
            if (t.isEmpty()) restartLater(250) else cb.onFinal(t)
        }

        override fun onError(error: Int) {
            if (myGen != gen || !active) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartLater(200)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartLater(1000)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    cb.onFatal("Microphone permission is missing")
                12, 13 -> cb.onFatal("This speech language is not available. Choose another language in settings.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    errorStreak++
                    if (errorStreak >= 6) cb.onFatal("Speech recognition needs internet") else restartLater(1500)
                }
                else -> {
                    errorStreak++
                    if (errorStreak >= 8) cb.onFatal("Speech recognition error $error") else restartLater(700)
                }
            }
        }
    }
}
