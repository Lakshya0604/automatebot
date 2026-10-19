package com.instinct.tvremote

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional fallback: asks the free Gemini API (Google AI Studio key) to map free-form speech in
 * any language to one of the fixed intent ids. The model can only pick from the list, so it
 * cannot invent actions. Needs internet and a free API key typed into the app.
 */
object GeminiIntent {
    private val models = listOf("gemini-2.0-flash", "gemini-1.5-flash")

    fun resolve(apiKey: String, utterance: String): String? {
        if (apiKey.isBlank()) return null
        val ids = CommandParser.intentIds.joinToString(", ")
        val prompt = "You control a TV. The user spoke this (any language, maybe Hindi or Hinglish, " +
            "speech-to-text may have errors): \"$utterance\"\n" +
            "Pick the single best intent from: $ids, none.\n" +
            "Reply as JSON: {\"intent\":\"<id>\"}. Use none if unsure."
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0))
            .toString()
        for (m in models) {
            try {
                val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 6000
                c.readTimeout = 8000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", apiKey)
                c.outputStream.use { it.write(body.toByteArray()) }
                if (c.responseCode != 200) {
                    Logger.log("Gemini $m: HTTP ${c.responseCode}")
                    c.disconnect()
                    continue
                }
                val resp = c.inputStream.bufferedReader().readText()
                c.disconnect()
                val text = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                val id = JSONObject(text).optString("intent", "none")
                return if (id in CommandParser.intentIds) id else null
            } catch (e: Exception) {
                Logger.log("Gemini $m failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return null
    }
}
