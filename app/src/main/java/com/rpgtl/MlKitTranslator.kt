package com.rpgtl

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Tradução gratuita e offline (no aparelho). Não tem cota nem chave.
 * O modelo (~30 MB) é baixado uma vez. Os códigos de controle ficam fora da tradução:
 * o texto é dividido nos códigos e só os pedaços em japonês são traduzidos.
 */
class MlKitTranslator(private val log: (String) -> Unit) : Translator {
    override val name = "ML Kit"
    private var sent = 0L
    override val charsSent: Long get() = sent

    private val client = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )
    private var ready = false

    private suspend fun ensureModel() {
        if (ready) return
        log("ML Kit: checking/downloading the ja→en model (~30 MB, first time only)...")
        try {
            client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("Failed to download the ML Kit model (internet required the first time): ${e.message}", 0)
        }
        ready = true
    }

    override suspend fun translate(texts: List<String>): List<String> {
        ensureModel()
        try {
            return texts.map { translateOne(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("ML Kit: ${e.message}", 0)
        }
    }

    private suspend fun translateOne(text: String): String {
        val sb = StringBuilder()
        for ((isCode, seg) in TextProtector.split(text)) {
            if (isCode || !TextProtector.hasJapanese(seg)) {
                sb.append(seg)
                continue
            }
            val t = translateSegment(seg).trim()
            val vis = TextProtector.visible(sb.toString())
            if (t.isNotEmpty() && vis.isNotEmpty() && !vis.last().isWhitespace() && t.first() !in ".,!?;:)'\"") {
                sb.append(' ')
            }
            sb.append(t)
        }
        sent += text.length
        return sb.toString()
    }

    private suspend fun translateSegment(seg: String): String {
        val lines = seg.split("\n")
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            out.add(if (TextProtector.hasJapanese(line)) client.translate(line).await() else line)
        }
        return out.joinToString("\n")
    }
}
