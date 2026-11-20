package com.rpgtl

import org.json.JSONArray
import org.json.JSONObject

class TextJob(val source: String) {
    var result: String = source
}

/**
 * Percorre o JSON de um arquivo do RPG Maker MV/MZ, registra os textos traduzíveis
 * e, depois da tradução, grava os resultados de volta na mesma estrutura.
 */
/** Nomes citados em tags de nota (traduzidos de forma consistente) e layout das janelas do jogo. */
class RefContext(
    val refMap: Map<String, String>,
    val keywords: Set<String>,
    val charsPerLine: Int,
    val labelChars: Int,
    val allowSpaces: Boolean = false   // true quando o plugin que lê as tags foi corrigido (js_en/plugins)
) {
    companion object {
        val NONE = RefContext(emptyMap(), emptySet(), 52, 10)
    }
}

class EventTextCollector(private val width: Int, private val ctx: RefContext = RefContext.NONE) {

    /** Avisos para o log (textos que provavelmente não cabem na janela do jogo). */
    val warnings = ArrayList<String>()

    private val jobs = ArrayList<TextJob>()
    private val finalizers = ArrayList<() -> Unit>()

    fun sources(): Set<String> = jobs.mapTo(LinkedHashSet()) { it.source }

    fun finish(translations: Map<String, String>) {
        for (j in jobs) j.result = translations[j.source] ?: j.source
        for (f in finalizers) f()
    }

    companion object {
        private val MAP = Regex("Map\\d+\\.json")
        private val MSG = setOf("message1", "message2", "message3", "message4")
        private val DB = mapOf(
            "Actors.json" to listOf("name", "nickname", "profile"),
            "Classes.json" to listOf("name"),
            "Items.json" to listOf("name", "description"),
            "Skills.json" to listOf("name", "description", "message1", "message2"),
            "Weapons.json" to listOf("name", "description"),
            "Armors.json" to listOf("name", "description"),
            "States.json" to listOf("name", "message1", "message2", "message3", "message4"),
            "Enemies.json" to listOf("name")
        )

        fun isTranslatable(name: String) =
            name in DB || name == "System.json" || name == "CommonEvents.json" ||
                name == "Troops.json" || MAP.matches(name)
    }

    /** Retorna false se o arquivo não é tratado. */
    fun collect(fileName: String, root: Any): Boolean {
        val dbKeys = DB[fileName]
        when {
            dbKeys != null -> database(root as? JSONArray ?: return false, dbKeys)
            fileName == "System.json" -> system(root as? JSONObject ?: return false)
            fileName == "CommonEvents.json" -> {
                val a = root as? JSONArray ?: return false
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { list(it) }
            }
            fileName == "Troops.json" -> {
                val a = root as? JSONArray ?: return false
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { pages(it) }
            }
            MAP.matches(fileName) -> map(root as? JSONObject ?: return false)
            else -> return false
        }
        return true
    }

    // ---------- registro de textos ----------

    private fun newJob(src: String): TextJob? {
        val t = src.trim { it.isWhitespace() || it == '\u3000' }
        if (t.isEmpty() || !TextProtector.hasJapanese(t)) return null
        // Nome citado em tag de nota: usa a tradução já fixada (igual à que foi gravada nas tags).
        ctx.refMap[t]?.let { fixed -> return TextJob(t).also { it.result = fixed } }
        return TextJob(t).also { jobs.add(it) }
    }

    private fun bind(src: String, apply: (String) -> Unit) {
        val j = newJob(src) ?: return
        finalizers.add { apply(j.result) }
    }

    private fun isRef(s: String) = ctx.refMap.containsKey(s.trim { it.isWhitespace() || it == '\u3000' })

    /** [prep] ajusta o texto de origem; [post] ajusta o resultado (nomes citados em tags ficam intocados). */
    private fun bindText(
        o: JSONObject, key: String,
        prep: (String) -> String = { it },
        post: (String) -> String = { it }
    ) {
        val v = o.opt(key) as? String ?: return
        val src = prep(v)
        val fixed = isRef(src)
        bind(src) { o.put(key, if (fixed) it else post(it)) }
    }

    private fun bindArray(a: JSONArray, post: (String) -> String = { it }) {
        for (i in 0 until a.length()) {
            val v = a.opt(i) as? String ?: continue
            val fixed = isRef(v)
            bind(v) { a.put(i, if (fixed) it else post(it)) }
        }
    }

    /** Reescreve, nas tags de nota, os nomes que foram traduzidos (o plugin continua achando o alvo). */
    private fun bindNote(o: JSONObject) {
        val note = o.opt("note") as? String ?: return
        if (!note.contains('<')) return
        // Rótulos de botão dentro de tags (ex.: o texto "鞭攻撃" em <コマンド:スキル/鞭,48,鞭攻撃,76>).
        val labels = LinkedHashMap<String, TextJob>()
        for (tok in NoteRefs.labels(note, ctx.keywords)) {
            if (!ctx.refMap.containsKey(tok) && !labels.containsKey(tok)) newJob(tok)?.let { labels[tok] = it }
        }
        if (ctx.refMap.isEmpty() && labels.isEmpty()) return
        finalizers.add {
            val map = HashMap<String, String>(ctx.refMap)
            for ((tok, j) in labels) {
                val s = NoteRefs.forTag(j.result, ctx.allowSpaces)
                if (s != null && j.result != j.source) map[tok] = s
            }
            val n = NoteRefs.rewrite(note, map, ctx.keywords)
            if (n != note) o.put("note", n)
        }
    }

    // ---------- arquivos ----------

    private fun database(a: JSONArray, keys: List<String>) {
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            for (k in keys) {
                when {
                    // Janela de ajuda: 2 linhas. Quebras manuais do japonês são refeitas.
                    k == "description" -> bindText(
                        o, k,
                        prep = { TextProtector.joinJapanese(it) },
                        post = { TextFit.help(it, ctx.charsPerLine) }
                    )
                    // Log de batalha: 1 linha. Se não couber, diminui a fonte em vez de cortar.
                    k in MSG -> bindText(o, k, post = { s ->
                        val f = TextFit.oneLine(s, ctx.charsPerLine)
                        if (f.startsWith(" ")) f else " $f"
                    })
                    else -> bindText(o, k)
                }
            }
            bindNote(o)
        }
    }

    private fun system(o: JSONObject) {
        bindText(o, "gameTitle")
        bindText(o, "currencyUnit")
        for (k in listOf("armorTypes", "elements", "equipTypes", "skillTypes", "weaponTypes")) {
            o.optJSONArray(k)?.let { arr ->
                bindArray(arr)
                // Tipos de habilidade viram botões da janela de comando de batalha (192px, estreita).
                // Nessas janelas o jogo não aceita \} (fonte menor), então só avisamos.
                if (k == "skillTypes") finalizers.add {
                    for (i in 0 until arr.length()) {
                        val s = arr.opt(i) as? String ?: continue
                        if (TextProtector.visible(s).length > ctx.labelChars) {
                            warnings.add("tipo de habilidade \"$s\" pode ser cortado no menu de batalha (cabem ~${ctx.labelChars} letras)")
                        }
                    }
                }
            }
        }
        val terms = o.optJSONObject("terms") ?: return
        for (k in terms.keys()) {
            when (val v = terms.opt(k)) {
                is JSONArray -> bindArray(v)
                is JSONObject -> for (kk in v.keys()) {
                    bindText(v, kk, post = { s -> TextFit.oneLine(s, ctx.charsPerLine) })
                }
                else -> {}
            }
        }
    }

    private fun map(o: JSONObject) {
        bindText(o, "displayName")
        val ev = o.optJSONArray("events") ?: return
        for (i in 0 until ev.length()) {
            val e = ev.optJSONObject(i) ?: continue
            bindNote(e)
            pages(e)
        }
    }

    private fun pages(holder: JSONObject) {
        val pages = holder.optJSONArray("pages") ?: return
        for (p in 0 until pages.length()) pages.optJSONObject(p)?.let { list(it) }
    }

    // ---------- lista de comandos de evento ----------

    private inner class Block(
        val header: JSONObject,
        val code: Int,
        val original: List<JSONObject>,
        val maxLines: Int,
        val width: Int,
        smart: Boolean
    ) {
        val job: TextJob?

        init {
            val texts = original.map { (it.optJSONArray("parameters")?.opt(0) as? String).orEmpty() }
            job = newJob(TextProtector.joinLines(texts, smart))
        }

        fun emit(out: JSONArray) {
            val j = job
            if (j == null || j.result == j.source) {
                out.put(header)
                original.forEach { out.put(it) }
                return
            }
            val lines = TextProtector.wrap(j.result, width)
            val indent = header.optInt("indent")
            val chunks = if (maxLines > 0) lines.chunked(maxLines) else listOf(lines)
            chunks.forEachIndexed { idx, chunk ->
                out.put(if (idx == 0) header else JSONObject(header.toString()))
                chunk.forEach { line ->
                    out.put(
                        JSONObject()
                            .put("code", code)
                            .put("indent", indent)
                            .put("parameters", JSONArray().put(line))
                    )
                }
            }
        }
    }

    private fun list(holder: JSONObject) {
        val list = holder.optJSONArray("list") ?: return
        val plan = ArrayList<Any>()
        var i = 0
        while (i < list.length()) {
            val cmd = list.optJSONObject(i)
            if (cmd == null) { i++; continue }
            val params = cmd.optJSONArray("parameters")
            when (cmd.optInt("code")) {
                101, 105 -> {
                    val textCode = if (cmd.optInt("code") == 101) 401 else 405
                    val lines = ArrayList<JSONObject>()
                    var j = i + 1
                    while (j < list.length() && list.optJSONObject(j)?.optInt("code") == textCode) {
                        lines.add(list.getJSONObject(j)); j++
                    }
                    if (textCode == 401) {
                        if (params != null) {
                            val speaker = params.opt(4) as? String
                            if (speaker != null) bind(speaker) { params.put(4, it) }
                        }
                        val face = (params?.opt(0) as? String).orEmpty()
                        // Nunca passa do que cabe na tela do jogo (largura e fonte do System.json).
                        val base = minOf(width, ctx.charsPerLine)
                        val w = if (face.isNotEmpty()) base - 8 else base
                        plan.add(Block(cmd, 401, lines, 4, w, true))
                    } else {
                        plan.add(Block(cmd, 405, lines, 0, minOf(width, ctx.charsPerLine), false))
                    }
                    i = j
                }
                102 -> {
                    params?.optJSONArray(0)?.let { bindArray(it) }
                    plan.add(cmd); i++
                }
                320, 324, 325 -> {
                    if (params != null) {
                        val s = params.opt(1) as? String
                        if (s != null) bind(s) { params.put(1, it) }
                    }
                    plan.add(cmd); i++
                }
                else -> { plan.add(cmd); i++ }
            }
        }
        finalizers.add {
            val out = JSONArray()
            for (p in plan) {
                if (p is Block) p.emit(out) else out.put(p)
            }
            holder.put("list", out)
        }
    }
}
