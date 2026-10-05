package com.munawar.ai

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class Tts(ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var lang = "ur-PK"
    private var rate = 1.0f
    private var pitch = 1.0f
    private var pending: Pair<String, () -> Unit>? = null
    private val callbacks = HashMap<String, () -> Unit>()

    init {
        engine = TextToSpeech(ctx.applicationContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    engine?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) = finish(utteranceId)

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) = finish(utteranceId)
                        override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
                    })
                    applyConfig()
                    val p = pending
                    pending = null
                    if (p != null) speak(p.first, p.second)
                } else {
                    failed = true
                    val p = pending
                    pending = null
                    p?.second?.invoke()
                }
            }
        }
    }

    private fun finish(id: String?) {
        if (id == null) return
        main.post { callbacks.remove(id)?.invoke() }
    }

    fun configure(lang: String, rate: Float, pitch: Float) {
        this.lang = lang
        this.rate = rate
        this.pitch = pitch
        if (ready) applyConfig()
    }

    private fun applyConfig() {
        val e = engine ?: return
        var r = e.setLanguage(Locale.forLanguageTag(lang))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            r = e.setLanguage(Locale("hi", "IN"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                e.setLanguage(Locale.US)
            }
        }
        e.setSpeechRate(rate)
        e.setPitch(pitch)
    }

    fun speak(text: String, onDone: () -> Unit) {
        if (failed) {
            onDone()
            return
        }
        if (!ready) {
            pending = text to onDone
            return
        }
        val id = UUID.randomUUID().toString()
        callbacks[id] = onDone
        val r = engine?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, id) ?: TextToSpeech.ERROR
        if (r == TextToSpeech.ERROR) callbacks.remove(id)?.invoke()
    }

    fun stop() {
        callbacks.clear()
        pending = null
        engine?.stop()
    }

    fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
    }
}
