package com.rpgtl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(message: String, val code: Int) : Exception(message) {
    /** Erros que indicam cota/limite/chave: vale a pena tentar o tradutor de reserva. */
    val switchable: Boolean get() = code == 401 || code == 403 || code == 429 || code == 456
}

interface Translator {
    val name: String
    val charsSent: Long

    /** Recebe textos originais (com códigos de controle) e devolve os textos traduzidos. */
    suspend fun translate(texts: List<String>): List<String>
}

internal object Http {
    fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }
}

abstract class HttpTranslator : Translator {
    protected abstract val maxTexts: Int
    protected abstract val maxChars: Int
    protected abstract val tagOpen: String
    protected abstract val tagClose: String

    private var sent = 0L
    override val charsSent: Long get() = sent

    /** Envia textos já codificados (códigos protegidos) e devolve a resposta crua. */
    protected abstract suspend fun requestBatch(encoded: List<String>): List<String>

    override suspend fun translate(texts: List<String>): List<String> {
        val out = ArrayList<String>(texts.size)
        var i = 0
        while (i < texts.size) {
            val enc = ArrayList<String>()
            var chars = 0
            while (i < texts.size && enc.size < maxTexts) {
                val e = TextProtector.encode(texts[i], tagOpen, tagClose)
                if (enc.isNotEmpty() && chars + e.length > maxChars) break
                enc.add(e); chars += e.length; i++
            }
            val res = requestBatch(enc)
            for (r in res) out.add(TextProtector.decode(r))
            sent += chars
        }
        return out
    }

    protected open fun describe(code: Int, body: String): String = when (code) {
        403 -> "Invalid key or limit reached (HTTP 403)"
        429 -> "Too many requests (HTTP 429)"
        456 -> "Quota exceeded (HTTP 456)"
        else -> "HTTP $code: ${body.take(300)}"
    }

    /** POST com até 5 tentativas (backoff exponencial) para 429, 5xx e erros de rede. */
    protected suspend fun send(url: String, headers: Map<String, String>, body: String): String {
        var delayMs = 2000L
        for (attempt in 1..5) {
            try {
                val (code, text) = withContext(Dispatchers.IO) { Http.post(url, headers, body) }
                if (code in 200..299) return text
                if (code != 429 && code < 500) throw ApiException(describe(code, text), code)
                if (attempt == 5) throw ApiException(describe(code, text), code)
            } catch (e: IOException) {
                if (attempt == 5) throw ApiException("Network error: ${e.message}", 0)
            }
            delay(delayMs)
            delayMs *= 2
        }
        throw ApiException("Failed after several attempts", 0)
    }
}

/** DeepL API (Free: chave termina em ":fx"; Pro: sem sufixo). */
class DeepLTranslator(apiKey: String) : HttpTranslator() {
    private val key = apiKey.trim()
    override val name = "DeepL"
    override val maxTexts = 40
    override val maxChars = 20_000
    override val tagOpen = "<x>"
    override val tagClose = "</x>"

    private val url = if (key.endsWith(":fx"))
        "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
    private var useContext = true

    override suspend fun requestBatch(encoded: List<String>): List<String> {
        val raw = try {
            call(encoded, useContext)
        } catch (e: ApiException) {
            if (e.code == 400 && useContext) {
                useContext = false
                call(encoded, false)
            } else throw e
        }
        val arr = JSONObject(raw).getJSONArray("translations")
        if (arr.length() != encoded.size) throw ApiException("Unexpected DeepL response", 0)
        return List(arr.length()) { arr.getJSONObject(it).getString("text") }
    }

    private suspend fun call(texts: List<String>, withContext: Boolean): String {
        val body = JSONObject()
            .put("text", JSONArray(texts))
            .put("source_lang", "JA")
            .put("target_lang", "EN-US")
            .put("tag_handling", "xml")
            .put("ignore_tags", JSONArray(listOf("x")))
            .put("split_sentences", "nonewlines")
            .put("preserve_formatting", true)
            .put("model_type", "prefer_quality_optimized")
        if (withContext) {
            body.put("context", "Dialogue, names and menu text from a Japanese role-playing video game (RPG).")
        }
        return send(url, mapOf("Authorization" to "DeepL-Auth-Key $key"), body.toString())
    }
}

/** Google Cloud Translation v2 (NMT). */
class GoogleTranslator(apiKey: String) : HttpTranslator() {
    private val key = apiKey.trim()
    override val name = "Google"
    override val maxTexts = 50
    override val maxChars = 5_000
    override val tagOpen = "<span translate=\"no\">"
    override val tagClose = "</span>"

    private val url =
        "https://translation.googleapis.com/language/translate/v2?key=" + URLEncoder.encode(key, "UTF-8")

    override suspend fun requestBatch(encoded: List<String>): List<String> {
        val body = JSONObject()
            .put("q", JSONArray(encoded))
            .put("source", "ja")
            .put("target", "en")
            .put("format", "html")
            .put("model", "nmt")
        val raw = send(url, emptyMap(), body.toString())
        val arr = JSONObject(raw).getJSONObject("data").getJSONArray("translations")
        if (arr.length() != encoded.size) throw ApiException("Unexpected Google response", 0)
        return List(arr.length()) { arr.getJSONObject(it).getString("translatedText") }
    }
}

/** Usa o primeiro tradutor; se a cota/limite acabar, passa para o próximo da lista. */
class FallbackTranslator(
    private val list: List<Translator>,
    private val log: (String) -> Unit
) : Translator {
    private var idx = 0
    override val name: String get() = list.joinToString(" → ") { it.name }
    override val charsSent: Long get() = list.sumOf { it.charsSent }

    override suspend fun translate(texts: List<String>): List<String> {
        while (true) {
            val t = list[idx]
            try {
                return t.translate(texts)
            } catch (e: ApiException) {
                if (!e.switchable || idx >= list.lastIndex) throw e
                log("${t.name} unavailable (${e.message}). Switching to ${list[idx + 1].name}.")
                idx++
            }
        }
    }
}
