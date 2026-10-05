package com.munawar.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

enum class Phase { OFF, LISTENING, THINKING, SPEAKING }

data class Msg(val fromUser: Boolean, val text: String)

data class UiState(
    val phase: Phase = Phase.OFF,
    val partial: String = "",
    val log: List<Msg> = emptyList(),
    val notice: String? = null,
)

/** Shared state between the foreground service (writer) and the UI (reader). */
object Bus {
    val state = MutableStateFlow(UiState())
    val level = MutableStateFlow(0f)
    fun update(f: (UiState) -> UiState) = state.update(f)
}

private data class Reply(val text: String, val stop: Boolean = false, val ok: Boolean = true)

/**
 * The brain: listen -> (wake word) -> local parser -> Gemini -> whitelisted action -> speak -> listen.
 * All calls happen on the main thread; network/contacts work is moved to IO inside the callees.
 */
class AssistantCore(
    private val ctx: Context,
    private val onShutdown: () -> Unit,
) : Stt.Callback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = PrefsRepo(ctx)
    private val tts = Tts(ctx)
    private val executor = ActionExecutor(ctx)
    private val gemini = GeminiClient()
    private val stt = Stt(ctx, this)

    private var s = AppSettings()
    private var running = false
    private var busy = false
    private var stopped = false
    private var lastInteraction = 0L
    private var smoothLevel = 0f
    private var pending: AiResult? = null
    private var job: Job? = null
    private val history = ArrayList<Pair<String, String>>()

    private val wakeVariants = listOf(
        "hey munawar", "hey munawwar", "munawar", "munawwar",
        "منور", "منوّر", "مناور", "मुनव्वर", "मुनावर", "मुन्नवर",
    )

    // ---------- lifecycle ----------

    fun start() {
        running = true
        Bus.state.value = UiState(phase = Phase.LISTENING)
        scope.launch {
            applySettings(prefs.current())
            launch { prefs.flow.collect { applySettings(it) } }
            val ready = tr(s.lang, "منور اے آئی تیار ہے", "मुनव्वर एआई तैयार है", "Munawar AI is ready")
            Bus.update { it.copy(phase = Phase.SPEAKING) }
            tts.speak(ready) { beginListening() }
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        running = false
        job?.cancel()
        stt.pause()
        tts.shutdown()
        scope.cancel()
        Bus.level.value = 0f
        Bus.update { it.copy(phase = Phase.OFF, partial = "") }
    }

    private fun applySettings(n: AppSettings) {
        s = n
        stt.lang = n.lang
        executor.lang = n.lang
        executor.countryCode = n.countryCode
        tts.configure(n.lang, n.rate, n.pitch)
    }

    private fun beginListening() {
        if (!running) return
        busy = false
        Bus.level.value = 0f
        Bus.update { it.copy(phase = Phase.LISTENING, partial = "") }
        stt.start()
    }

    // ---------- Stt.Callback ----------

    override fun onRms(level: Float) {
        smoothLevel = smoothLevel * 0.5f + level * 0.5f
        Bus.level.value = smoothLevel
    }

    override fun onPartial(text: String) {
        Bus.update { if (it.partial == text) it else it.copy(partial = text) }
    }

    override fun onFinal(text: String) {
        handleHeard(text)
    }

    override fun onFatal(message: String) {
        Bus.update { it.copy(notice = message) }
        onShutdown()
    }

    // ---------- pipeline ----------

    private fun stripWake(t: String): String? {
        val l = t.lowercase()
        for (w in wakeVariants) {
            val i = l.indexOf(w)
            if (i >= 0) return (l.substring(0, i) + " " + l.substring(i + w.length)).trim()
        }
        return null
    }

    private fun handleHeard(raw: String) {
        if (busy || !running) return
        val cmd: String
        if (s.wakeWord) {
            val stripped = stripWake(raw)
            val inConversation = System.currentTimeMillis() - lastInteraction < 10_000
            if (stripped != null) {
                cmd = stripped
            } else if (inConversation) {
                cmd = raw
            } else {
                Bus.update { it.copy(partial = "") }
                stt.restart()
                return
            }
        } else {
            cmd = raw
        }
        if (cmd.isBlank()) {
            busy = true
            stt.pause()
            deliver(tr(s.lang, "جی؟", "जी?", "Yes?"), false)
            return
        }
        process(cmd)
    }

    private fun process(cmd: String) {
        busy = true
        stt.pause()
        Bus.level.value = 0f
        Bus.update {
            it.copy(phase = Phase.THINKING, partial = "", log = (it.log + Msg(true, cmd)).takeLast(30))
        }
        job = scope.launch {
            val reply = try {
                think(cmd)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Reply(friendlyError(e), ok = false)
            }
            deliver(reply.text, reply.stop)
        }
    }

    private suspend fun think(cmd: String): Reply {
        val p = pending
        if (p != null) {
            pending = null
            if (isYes(cmd)) return runAction(p, confirmed = true)
            if (isNo(cmd)) return Reply(tr(s.lang, "ٹھیک ہے، منسوخ کر دیا", "ठीक है, रद्द कर दिया", "Okay, cancelled"))
        }

        val local = LocalParser.parse(cmd)
        if (local != null) {
            val r = runAction(local)
            if (r.ok || local.action != ActionType.OPEN_APP || s.apiKey.isBlank()) return r
        }

        if (s.apiKey.isBlank()) {
            return Reply(
                tr(s.lang, "پہلے سیٹنگز میں API کی ڈالیں", "पहले सेटिंग्स में API की डालें", "Please add your API key in settings"),
                ok = false,
            )
        }

        val ai = gemini.ask(s.apiKey, s.model, systemPrompt(s), history.toList(), cmd)
        history.add("user" to cmd)
        history.add("model" to ai.reply.ifBlank { "OK" })
        while (history.size > 12) {
            history.removeAt(0)
            history.removeAt(0)
        }
        if (ai.action == ActionType.NONE) {
            return Reply(ai.reply.ifBlank { tr(s.lang, "جی", "जी", "Okay") })
        }
        return runAction(ai)
    }

    private suspend fun runAction(r: AiResult, confirmed: Boolean = false): Reply {
        if (r.action == ActionType.STOP) {
            return Reply(r.reply.ifBlank { tr(s.lang, "اللہ حافظ", "अलविदा", "Goodbye") }, stop = true)
        }
        if (r.action == ActionType.CALL && !confirmed) {
            val who = r.params["name"]?.takeIf { it.isNotBlank() } ?: r.params["number"].orEmpty()
            if (who.isNotBlank()) {
                pending = r
                return Reply(
                    tr(
                        s.lang,
                        "$who کو کال کروں؟ ہاں یا نہیں بولیں",
                        "$who को कॉल करूँ? हाँ या नहीं बोलें",
                        "Call $who? Say yes or no.",
                    )
                )
            }
        }
        val out = executor.run(r.action, r.params)
        val text = if (!out.ok || out.info) out.message.orEmpty() else r.reply.ifBlank { out.message.orEmpty() }
        return Reply(text.ifBlank { tr(s.lang, "ٹھیک ہے", "ठीक है", "Okay") }, ok = out.ok)
    }

    private fun deliver(text: String, stop: Boolean) {
        if (!running) return
        lastInteraction = System.currentTimeMillis()
        Bus.update { it.copy(phase = Phase.SPEAKING, log = (it.log + Msg(false, text)).takeLast(30)) }
        tts.speak(text) {
            lastInteraction = System.currentTimeMillis()
            if (stop) onShutdown() else beginListening()
        }
    }

    // ---------- helpers ----------

    private fun wordsOf(t: String) = t.lowercase().split(Regex("[\\s,.!?۔،]+")).filter { it.isNotEmpty() }

    private fun isNo(t: String): Boolean {
        val w = wordsOf(t)
        val low = t.lowercase()
        return w.any { it in setOf("no", "nahi", "nahin", "nope", "cancel", "mat", "نہیں", "نہ", "مت", "नहीं", "न", "मत") } ||
            low.contains("رہنے دو") || low.contains("रहने दो")
    }

    private fun isYes(t: String): Boolean {
        if (isNo(t)) return false
        val w = wordsOf(t)
        val low = t.lowercase()
        return w.any {
            it in setOf("yes", "yeah", "yep", "ok", "okay", "haan", "han", "haa", "ji", "karo", "ہاں", "جی", "ٹھیک", "کرو", "हाँ", "हां", "जी", "ठीक", "करो")
        } || low.contains("کر دو") || low.contains("kar do") || low.contains("कर दो")
    }

    private fun friendlyError(e: Exception): String = when {
        e is GeminiException && (e.code == 400 || e.code == 401 || e.code == 403) -> tr(
            s.lang,
            "API کی میں مسئلہ ہے، سیٹنگز میں چیک کریں",
            "API की में दिक्कत है, सेटिंग्स में जाँचें",
            "There is a problem with the API key. Please check settings.",
        )
        e is GeminiException && e.code == 404 -> tr(
            s.lang,
            "ماڈل کا نام درست نہیں، سیٹنگز میں چیک کریں",
            "मॉडल का नाम सही नहीं है, सेटिंग्स में जाँचें",
            "The model name is not valid. Please check settings.",
        )
        e is GeminiException && e.code == 429 -> tr(
            s.lang,
            "ابھی بہت زیادہ درخواستیں ہو گئیں، تھوڑی دیر بعد کوشش کریں",
            "अभी बहुत ज़्यादा अनुरोध हो गए, थोड़ी देर बाद कोशिश करें",
            "Too many requests right now. Please try again shortly.",
        )
        e is IOException -> tr(
            s.lang,
            "انٹرنیٹ نہیں مل رہا",
            "इंटरनेट नहीं मिल रहा",
            "I can't reach the internet",
        )
        else -> tr(s.lang, "کچھ گڑبڑ ہو گئی، دوبارہ کہیں", "कुछ गड़बड़ हो गई, फिर से कहें", "Something went wrong. Please try again.")
    }
}
