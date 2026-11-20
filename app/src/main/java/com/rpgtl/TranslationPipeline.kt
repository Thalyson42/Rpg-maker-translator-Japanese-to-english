package com.rpgtl

import org.json.JSONObject
import java.io.File

/**
 * Deduplica, usa cache em disco (compartilhado entre tradutores), agrupa em lotes
 * e valida os códigos de controle. Se [offline] for true, só usa o cache.
 */
class TranslationPipeline(
    private val translator: Translator,
    private val cacheFile: File,
    private val log: (String) -> Unit
) {
    private val cache = HashMap<String, String>()
    var offline = false
    var failures = 0
        private set
    var untranslated = 0
        private set
    private var batches = 0

    init {
        runCatching {
            if (cacheFile.exists()) {
                val o = JSONObject(cacheFile.readText())
                for (k in o.keys()) cache[k] = o.getString(k)
            }
        }
    }

    val cacheSize: Int get() = cache.size

    /** Apaga o cache (memória e disco). Chamado quando a tradução termina por completo. */
    fun clear() {
        cache.clear()
        runCatching { cacheFile.delete() }
        runCatching { File(cacheFile.path + ".tmp").delete() }
    }

    fun save() {
        runCatching {
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(JSONObject(cache as Map<*, *>).toString())
            tmp.renameTo(cacheFile)
        }
    }

    suspend fun translateAll(
        sources: Collection<String>,
        onProgress: (done: Int, total: Int) -> Unit
    ): Map<String, String> {
        val result = HashMap<String, String>()
        val pending = ArrayList<String>()
        for (s in sources) {
            val c = cache[s]
            if (c != null) result[s] = c else pending.add(s)
        }
        if (offline) {
            untranslated += pending.size
            return result
        }
        var done = 0
        try {
            var i = 0
            while (i < pending.size) {
                val batch = ArrayList<String>()
                var chars = 0
                while (i < pending.size && batch.size < 30) {
                    val len = pending[i].length
                    if (batch.isNotEmpty() && chars + len > 2500) break
                    batch.add(pending[i]); chars += len; i++
                }
                val out = translator.translate(batch)
                if (out.size != batch.size) throw ApiException("Unexpected response size", 0)
                for (k in batch.indices) {
                    val dec = out[k].trim()
                    if (dec.isNotEmpty() && TextProtector.sameCodes(batch[k], dec)) {
                        cache[batch[k]] = dec
                        result[batch[k]] = dec
                    } else {
                        failures++
                        result[batch[k]] = batch[k]
                        log("Warning: translation discarded (control codes changed): " + batch[k].take(40))
                    }
                }
                done += batch.size
                batches++
                if (batches % 5 == 0) save()
                onProgress(done, pending.size)
            }
        } finally {
            save()
        }
        return result
    }
}
