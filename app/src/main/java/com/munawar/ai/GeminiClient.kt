package com.munawar.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The ONLY things the AI is allowed to ask the phone to do. Anything else is ignored. */
enum class ActionType {
    NONE, CALL, SMS, WHATSAPP, OPEN_APP, TORCH, ALARM, TIMER, YOUTUBE, MAPS,
    WEB_SEARCH, VOLUME, SETTINGS, CAMERA, SAVE_NOTE, READ_NOTES, BATTERY, TIME, STOP
}

data class AiResult(val action: ActionType, val params: Map<String, String>, val reply: String)

class GeminiException(val code: Int, message: String) : Exception(message)

class GeminiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun endpoint(model: String) =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    private fun genConfig(model: String): JSONObject {
        val g = JSONObject()
            .put("temperature", 0.6)
            .put("maxOutputTokens", 1024)
            .put("responseMimeType", "application/json")
        if (model.contains("2.5") && model.contains("flash")) {
            g.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }
        return g
    }

    private fun errorText(body: String): String = try {
        JSONObject(body).getJSONObject("error").getString("message")
    } catch (e: Exception) {
        body.take(200)
    }

    private suspend fun post(key: String, model: String, body: JSONObject): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(endpoint(model))
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody(jsonType))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw GeminiException(resp.code, errorText(text))
                text
            }
        }

    private fun textPart(t: String) = JSONObject().put("text", t)

    private fun turn(role: String, t: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(textPart(t)))

    /** Used by the setup screen: a tiny real request to prove the key + model work. */
    suspend fun testKey(key: String, model: String): Result<Unit> = runCatching {
        val body = JSONObject()
            .put("contents", JSONArray().put(turn("user", "Reply with the single word OK")))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 32))
        post(key, model, body)
        Unit
    }

    suspend fun ask(
        key: String,
        model: String,
        system: String,
        history: List<Pair<String, String>>,
        user: String,
    ): AiResult {
        val contents = JSONArray()
        for ((role, text) in history) contents.put(turn(role, text))
        contents.put(turn("user", user))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(textPart(system))))
            .put("contents", contents)
            .put("generationConfig", genConfig(model))
        return parse(post(key, model, body))
    }

    private fun parse(raw: String): AiResult {
        val root = JSONObject(raw)
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
        var t = sb.toString().trim()
        if (t.isEmpty()) throw GeminiException(0, "empty response")
        t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return AiResult(ActionType.NONE, emptyMap(), t)
        val obj = try {
            JSONObject(t.substring(start, end + 1))
        } catch (e: Exception) {
            return AiResult(ActionType.NONE, emptyMap(), t)
        }
        val name = obj.optString("action", "NONE").uppercase()
        val action = ActionType.values().firstOrNull { it.name == name } ?: ActionType.NONE
        val params = mutableMapOf<String, String>()
        val po = obj.optJSONObject("params")
        if (po != null) {
            val it = po.keys()
            while (it.hasNext()) {
                val k = it.next()
                params[k] = po.optString(k)
            }
        }
        return AiResult(action, params, obj.optString("reply", ""))
    }
}

fun systemPrompt(s: AppSettings): String {
    val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
    val language = when {
        s.lang.startsWith("ur") -> "Urdu (write replies in Urdu script)"
        s.lang.startsWith("hi") -> "Hindi (write replies in Devanagari script)"
        else -> "English"
    }
    return """
You are "Munawar AI", a voice assistant running on the user's Android phone.
Personality: ${s.persona.style}
The user speaks $language. Always reply in that language. Current date and time: $now.

Respond with ONE JSON object and nothing else (no markdown):
{"action":"ACTION_NAME","params":{},"reply":"short spoken reply"}

"reply" is read aloud: at most two short sentences, no emojis, no markdown, no lists.
Use action NONE for ordinary conversation and questions (params {}).
If a required detail is missing, use NONE and ask for it in "reply".
Never invent actions. Allowed actions and params (all values are strings):
- CALL {"name":"contact name"} or {"number":"digits"}
- SMS {"name":"contact","text":"message"} or {"number":"digits","text":"message"}
- WHATSAPP {"name":"contact","text":"message"} (name/number optional)
- OPEN_APP {"app":"app name in English as shown on the phone"}
- TORCH {"on":"true"|"false"}
- ALARM {"hour":"0-23","minute":"0-59","label":"text"}
- TIMER {"seconds":"number","label":"text"}
- YOUTUBE {"query":"search text"} (also use for playing songs or videos)
- MAPS {"query":"place to navigate to"}
- WEB_SEARCH {"query":"search text"}
- VOLUME {"direction":"up|down|max|mute"} or {"level":"0-100"}
- SETTINGS {"page":"wifi|bluetooth|display|sound|battery|location|main"}
- CAMERA {}
- SAVE_NOTE {"text":"note"}
- READ_NOTES {}
- BATTERY {}
- TIME {}
- STOP {} (user wants you to stop listening / go to sleep)
For ordinary answers keep them short and natural for speech.
""".trimIndent()
}
