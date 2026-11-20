package com.rpgtl

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import java.io.File
import java.io.IOException

/** Um arquivo de dados do jogo (de uma pasta ou de dentro de um .rgss3a). */
class Source(val name: String, val read: () -> ByteArray)

class Resolved(val sources: List<Source>?, val info: String)

object GameFolders {
    private val DATA_EXT = setOf("json", "rxdata", "rvdata", "rvdata2")

    fun child(dir: DocumentFile, name: String): DocumentFile? =
        dir.listFiles().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Pasta js do jogo (MV/MZ), se a raiz do jogo foi escolhida. */
    fun findJs(root: DocumentFile): DocumentFile? {
        val cands = ArrayList<DocumentFile>()
        child(root, "js")?.let { cands.add(it) }
        child(root, "www")?.let { w -> child(w, "js")?.let { cands.add(it) } }
        return cands.firstOrNull { it.isDirectory && child(it, "plugins.js") != null }
    }

    private fun isDataDir(dir: DocumentFile): Boolean =
        dir.isDirectory && dir.listFiles().any { f ->
            val n = f.name?.lowercase() ?: ""
            n.startsWith("system.") && n.substringAfterLast('.') in DATA_EXT
        }

    private fun fromFolder(resolver: ContentResolver, dir: DocumentFile): List<Source> =
        dir.listFiles()
            .filter { it.isFile && it.name != null }
            .sortedBy { it.name }
            .map { f ->
                Source(f.name!!) {
                    resolver.openInputStream(f.uri)?.use { it.readBytes() }
                        ?: throw IOException("Could not read ${f.name}")
                }
            }

    private fun fromArchive(resolver: ContentResolver, arc: DocumentFile): List<Source> {
        val entries = RgssArchive.list(resolver, arc.uri)
        val out = ArrayList<Source>()
        for (e in entries) {
            val p = e.path
            if (!p.lowercase().startsWith("data/")) continue
            val name = p.substring(5)
            if (name.isEmpty() || name.contains('/')) continue
            out.add(Source(name) { RgssArchive.read(resolver, arc.uri, e) })
        }
        return out
    }

    /** Aceita a pasta de dados, a raiz do jogo (com Data/ ou www/data) ou um .rgss3a/.rgss2a/.rgssad. */
    fun resolve(resolver: ContentResolver, root: DocumentFile): Resolved {
        val rootFiles = root.listFiles()
        fun find(name: String): DocumentFile? = rootFiles.firstOrNull { it.name.equals(name, ignoreCase = true) }

        val candidates = ArrayList<DocumentFile>()
        candidates.add(root)
        find("data")?.let { candidates.add(it) }
        find("www")?.let { w -> child(w, "data")?.let { candidates.add(it) } }
        for (c in candidates) {
            if (isDataDir(c)) return Resolved(fromFolder(resolver, c), "")
        }

        val arc = rootFiles.firstOrNull { f ->
            val n = f.name?.lowercase() ?: ""
            n.endsWith(".rgss3a") || n.endsWith(".rgss2a") || n.endsWith(".rgssad")
        }
        if (arc != null) {
            val list = fromArchive(resolver, arc)
            if (list.isNotEmpty()) return Resolved(list, "")
        }

        if (find("RPG_RT.ldb") != null || find("RPG_RT.lmt") != null) {
            return Resolved(null, "RPG Maker 2000/2003 is not supported yet.")
        }
        val dataDir = find("Data")
        if (find("Data.wolf") != null || (dataDir != null && child(dataDir, "BasicData") != null)) {
            return Resolved(null, "Wolf RPG is not supported yet.")
        }
        return Resolved(
            null,
            "Could not find the game data (System.json, .rxdata, .rvdata, .rvdata2 or an .rgss3a archive)."
        )
    }
}

class GameTranslator(
    private val resolver: ContentResolver,
    private val translator: Translator,
    private val cacheFile: File,
    private val width: Int,
    private val log: (String) -> Unit,
    private val progress: (done: Int, total: Int, status: String) -> Unit
) {
    private var refCtx = RefContext.NONE
    private var scanResult: RefScanner.Result? = null
    /** Plugins corrigidos (nome no plugins.js -> código novo). Vazio = nenhuma correção aplicável. */
    private var patched: Map<String, String> = emptyMap()

    /**
     * Analisa tags de nota e layout do jogo (MV/MZ) antes de traduzir. Nomes citados em tags são
     * traduzidos primeiro, para o nome e a tag ficarem idênticos. Retorna true se parou por limite.
     */
    private suspend fun prepare(sources: List<Source>, pipeline: TranslationPipeline): Boolean {
        val scan = RefScanner.scan(sources)
        scanResult = scan
        var stopped = false
        var map: Map<String, String> = emptyMap()
        if (scan.refs.isNotEmpty()) {
            log("Names referenced by note tags: ${scan.refs.sorted().joinToString(", ")} (the translation is also written into the tags)")
            map = try {
                pipeline.translateAll(scan.refs) { _, _ -> }
            } catch (e: ApiException) {
                stopped = true
                pipeline.offline = true
                log("STOPPED: ${e.message}")
                pipeline.translateAll(scan.refs) { _, _ -> }
            }
        }
        // Nomes citados em tags: sem espaços (plugins removem espaços das tags) e sem dígitos; se não der,
        // o nome fica em japonês (mesmo texto no jogo e na tag) em vez de quebrar a opção.
        val allow = patched.isNotEmpty()
        val safeMap = map.mapValues { e -> NoteRefs.forTag(e.value, allow) ?: e.key }
        for ((k, v) in map) {
            if (NoteRefs.forTag(v, allow) == null && v != k) {
                log("Warning: \"$k\" -> \"$v\" has digits/empty text, so it is kept in Japanese to keep note tags working.")
            }
        }
        refCtx = RefContext(
            safeMap, scan.keywords,
            TextFit.charsPerLine(scan.screenWidth, scan.fontSize),
            TextFit.labelChars(scan.fontSize),
            allow
        )
        if (scan.keywords.isNotEmpty()) {
            log("Tag words equal to system terms, kept as plugin syntax: ${scan.keywords.sorted().joinToString(", ")}")
        }
        if (scan.untranslatedLabels.isNotEmpty()) {
            log("Warning: note tags contain Japanese text that this app does NOT translate (e.g. ${scan.untranslatedLabels.sorted().take(6).joinToString(", ")}).")
        }
        log("Layout: ${refCtx.charsPerLine} characters per line (screen ${scan.screenWidth}px, font ${scan.fontSize})")
        return stopped
    }

    private class LoadedPlugins(
        val text: String, val a: Int, val b: Int,
        val arr: org.json.JSONArray, val plugDir: DocumentFile?
    )

    private fun loadPluginsJs(gameRoot: DocumentFile): LoadedPlugins? {
        val js = GameFolders.findJs(gameRoot) ?: return null
        val pjs = GameFolders.child(js, "plugins.js") ?: return null
        val text = resolver.openInputStream(pjs.uri)?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8)?.removePrefix("\uFEFF") ?: return null
        val a = text.indexOf('[')
        val b = text.lastIndexOf(']')
        if (a < 0 || b < a) return null
        val arr = runCatching { org.json.JSONArray(text.substring(a, b + 1)) }.getOrNull() ?: return null
        return LoadedPlugins(text, a, b, arr, GameFolders.child(js, "plugins"))
    }

    /** Plugins podem estar em subpastas (ex.: "MokuseiPengin/MPP_SmoothBattleLog"). */
    private fun pluginFile(plugDir: DocumentFile?, name: String): DocumentFile? {
        var cur: DocumentFile = plugDir ?: return null
        val parts = name.split('/')
        for ((i, part) in parts.withIndex()) {
            cur = GameFolders.child(cur, if (i == parts.lastIndex) "$part.js" else part) ?: return null
        }
        return cur
    }

    private fun readPlugin(plugDir: DocumentFile?, name: String): String? {
        val f = pluginFile(plugDir, name) ?: return null
        return resolver.openInputStream(f.uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8)
    }

    /** Procura plugins ativos com correção conhecida (PluginPatches) cujo código bate exatamente. */
    private fun detectPatches(gameRoot: DocumentFile): Map<String, String> {
        val lp = loadPluginsJs(gameRoot) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (i in 0 until lp.arr.length()) {
            val p = lp.arr.optJSONObject(i) ?: continue
            if (!p.optBoolean("status", true)) continue
            val name = p.optString("name")
            val patch = PluginPatches.forPlugin(name.substringAfterLast('/')) ?: continue
            val src = readPlugin(lp.plugDir, name) ?: continue
            val fixed = patch.apply(src)
            if (fixed != null) out[name] = fixed
            else log("Plugin $name: known fix not applied (different version?). Names used in its note tags will have no spaces.")
        }
        return out
    }

    private fun ensureDir(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory }
            ?: parent.createDirectory(name)
            ?: throw IOException("Could not create the $name folder")

    /** Grava js_en/plugins/<caminho>.js com os plugins corrigidos. */
    private fun writePatched(outRoot: DocumentFile) {
        if (patched.isEmpty()) return
        val plugins = ensureDir(ensureDir(outRoot, "js_en"), "plugins")
        for ((name, src) in patched) {
            val parts = name.split('/')
            var dir = plugins
            for (part in parts.dropLast(1)) dir = ensureDir(dir, part)
            write(dir, parts.last() + ".js", src.toByteArray(Charsets.UTF_8))
            log("Fixed plugin: $name -> js_en/plugins/$name.js (copy it over js/plugins/ - REQUIRED, otherwise battle commands named in note tags will not appear)")
        }
    }

    /**
     * Traduz parâmetros de texto do js/plugins.js (rótulos de menu criados por plugins) e grava
     * js_en/plugins.js. Textos escritos direto no código dos plugins não são alterados (só listados).
     */
    private suspend fun translatePlugins(gameRoot: DocumentFile, outRoot: DocumentFile, pipeline: TranslationPipeline) {
        val lp = loadPluginsJs(gameRoot)
        if (lp == null) {
            log("plugins.js not found. To also translate plugin menu text, choose the game's ROOT folder (not only data).")
            return
        }
        writePatched(outRoot)
        val col = PluginTextCollector(refCtx.refMap, scanResult?.eventNames ?: emptySet())
        val hard = ArrayList<String>()
        for (i in 0 until lp.arr.length()) {
            val p = lp.arr.optJSONObject(i) ?: continue
            if (!p.optBoolean("status", true)) continue
            val name = p.optString("name")
            val src = patched[name] ?: readPlugin(lp.plugDir, name) ?: continue
            val h = PluginHeaders.parse(src)
            col.collectPlugin(name, p, h)
            if (h.hardCoded.isNotEmpty()) hard.add("$name (${h.hardCoded.size})")
        }
        val srcs = col.sources()
        val map = pipeline.translateAll(srcs) { d, t -> progress(0, 1, "[plugins.js] translating $d/$t") }
        col.finish(map)
        if (col.changed > 0) {
            val outJs = ensureDir(outRoot, "js_en")
            val sb = StringBuilder(lp.text.substring(0, lp.a)).append("[\n")
            for (i in 0 until lp.arr.length()) {
                if (i > 0) sb.append(",\n")
                sb.append(lp.arr.get(i).toString())
            }
            sb.append("\n]").append(lp.text.substring(lp.b + 1))
            write(outJs, "plugins.js", sb.toString().toByteArray(Charsets.UTF_8))
            log("plugins.js: ${srcs.size} unique plugin texts translated -> js_en/plugins.js (copy it over js/plugins.js)")
            log("Plugin texts per plugin: " + col.perPlugin.entries.joinToString(", ") { "${it.key.substringAfterLast('/')} (${it.value})" })
        } else {
            log("plugins.js: no translatable plugin text found.")
        }
        if (hard.isNotEmpty()) {
            log("Note: these plugins have Japanese text written directly in their code, which this app does not translate: ${hard.take(10).joinToString(", ")}")
        }
    }

    /** Retorna true se tudo foi traduzido; false se parou por limite (saída parcial). */
    suspend fun run(
        sources: List<Source>, outRoot: DocumentFile,
        gameRoot: DocumentFile? = null, pluginText: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val outDir = outRoot.findFile("data_en")?.takeIf { it.isDirectory }
            ?: outRoot.createDirectory("data_en")
            ?: throw IOException("Could not create the output folder")

        val pipeline = TranslationPipeline(translator, cacheFile, log)
        log("${sources.size} files | translator: ${translator.name} | cache: ${pipeline.cacheSize} texts")

        var stopped = false
        try {
            patched = if (pluginText && gameRoot != null && sources.any { it.name.endsWith(".json", ignoreCase = true) }) {
                runCatching { detectPatches(gameRoot) }.getOrDefault(emptyMap())
            } else emptyMap()
            if (prepare(sources, pipeline)) stopped = true
            sources.forEachIndexed { idx, src ->
                ensureActive()
                progress(idx, sources.size, "[${src.name}]")
                val kind = kindOf(src.name)
                if (kind == null) {
                    write(outDir, src.name, src.read())
                } else {
                    try {
                        safeTranslate(src, kind, outDir, pipeline, idx, sources.size)
                    } catch (e: ApiException) {
                        stopped = true
                        pipeline.offline = true
                        log("STOPPED: ${e.message}")
                        log("Writing the rest using only what is already cached (partial output)...")
                        safeTranslate(src, kind, outDir, pipeline, idx, sources.size)
                    }
                }
            }
            if (pluginText && gameRoot != null && sources.any { it.name.endsWith(".json", ignoreCase = true) }) {
                try {
                    translatePlugins(gameRoot, outRoot, pipeline)
                } catch (e: ApiException) {
                    stopped = true
                    pipeline.offline = true
                    log("STOPPED: ${e.message}")
                    translatePlugins(gameRoot, outRoot, pipeline)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Plugin text step failed: ${e.message}")
                }
            }
        } finally {
            pipeline.save()
        }
        progress(sources.size, sources.size, if (stopped) "Partial" else "Done")
        log("Output: data_en/ | characters sent: ${translator.charsSent} | discarded: ${pipeline.failures}")
        if (stopped) {
            log("Texts still untranslated: ${pipeline.untranslated}. Run again (you can switch translator) to continue.")
        } else {
            // Tradução completa: o cache não é mais necessário (e o próximo jogo começa do zero).
            pipeline.clear()
            log("Translation complete. Translation cache cleared.")
        }
        !stopped
    }

    private fun kindOf(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val base = name.substringBeforeLast('.')
        return when {
            ext == "json" && EventTextCollector.isTranslatable(name) -> "json"
            (ext == "rxdata" || ext == "rvdata" || ext == "rvdata2") &&
                MarshalTextCollector.isTranslatable(base) -> "marshal"
            else -> null
        }
    }

    private suspend fun CoroutineScope.safeTranslate(
        src: Source, kind: String, outDir: DocumentFile,
        pipe: TranslationPipeline, idx: Int, total: Int
    ) {
        try {
            translateFile(src, kind, outDir, pipe, idx, total)
        } catch (e: ApiException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Error in ${src.name}: ${e.message} (copied untranslated)")
            write(outDir, src.name, src.read())
        }
    }

    private suspend fun CoroutineScope.translateFile(
        src: Source, kind: String, outDir: DocumentFile,
        pipe: TranslationPipeline, idx: Int, total: Int
    ) {
        val name = src.name
        val bytes = src.read()
        val texts: Set<String>
        val finish: (Map<String, String>) -> ByteArray

        if (kind == "json") {
            val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            val root = JSONTokener(text).nextValue()
            val c = EventTextCollector(width, refCtx)
            if (!c.collect(name, root)) {
                write(outDir, name, bytes)
                return
            }
            texts = c.sources()
            finish = { m ->
                c.finish(m)
                c.warnings.forEach { log("Warning: $it") }
                root.toString().toByteArray(Charsets.UTF_8)
            }
        } else {
            val ext = name.substringAfterLast('.').lowercase()
            val base = name.substringBeforeLast('.')
            val root = MarshalReader(bytes).read()
            val w = if (ext == "rxdata") minOf(width, 44) else minOf(width, 48)
            val c = MarshalTextCollector(w, ext == "rxdata", ext == "rvdata2")
            if (!c.collect(base, root)) {
                write(outDir, name, bytes)
                return
            }
            texts = c.sources()
            finish = { m -> c.finish(m); MarshalWriter().write(root) }
        }

        val map = pipe.translateAll(texts) { done, all ->
            progress(idx, total, "[$name] translating $done/$all")
        }
        ensureActive()
        write(outDir, name, finish(map))
        log("$name: ${texts.size} unique texts")
    }

    private fun write(outDir: DocumentFile, name: String, data: ByteArray) {
        outDir.findFile(name)?.delete()
        val mime = when {
            name.endsWith(".json", ignoreCase = true) -> "application/json"
            name.endsWith(".js", ignoreCase = true) -> "application/javascript"
            else -> "application/octet-stream"
        }
        val dst = outDir.createFile(mime, name) ?: throw IOException("Failed to create $name")
        resolver.openOutputStream(dst.uri, "wt")?.use { it.write(data) }
            ?: throw IOException("Failed to write $name")
    }
}
