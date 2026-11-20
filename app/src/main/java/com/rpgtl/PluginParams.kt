package com.rpgtl

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** O que o cabeçalho (@param/@type) e o código de um plugin dizem sobre seus parâmetros. */
class PluginHeader(
    val types: Map<String, String?>,   // nome do parâmetro -> @type (null = texto comum)
    val literals: Set<String>,         // textos japoneses que o código COMPARA (nunca traduzir)
    val hardCoded: Set<String>         // textos japoneses escritos direto no código (não traduzidos)
)

object PluginHeaders {
    private const val JPR = "\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF"
    private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("(?m)^\\s*//.*$")
    private val LITERAL = Regex("(?:===?|!==?|case|includes\\(|indexOf\\()\\s*\\(?['\"]([^'\"\\n]*[$JPR][^'\"\\n]*)['\"]")
    private val ANY_JP_STRING = Regex("['\"]([^'\"\\n]*[$JPR][^'\"\\n]*)['\"]")

    fun parse(source: String): PluginHeader {
        val types = HashMap<String, String?>()
        var cur: String? = null
        for (raw in source.lineSequence()) {
            val line = raw.trim().removePrefix("*").trim()
            when {
                line.startsWith("@param ") -> {
                    val name = line.substring(7).trim()
                    cur = if (name.isEmpty()) null else name
                    if (cur != null && !types.containsKey(cur)) types[cur] = null
                }
                line.startsWith("@type ") -> {
                    val c = cur
                    if (c != null) types[c] = line.substring(6).trim()
                }
                line.startsWith("@arg ") || line.startsWith("@command ") -> cur = null
            }
        }
        val code = LINE_COMMENT.replace(BLOCK_COMMENT.replace(source, ""), "")
        val lits = LinkedHashSet<String>()
        for (m in LITERAL.findAll(code)) lits.add(m.groupValues[1].trim())
        val hard = LinkedHashSet<String>()
        for (m in ANY_JP_STRING.findAll(code)) {
            val t = m.groupValues[1].trim()
            if (t !in lits) hard.add(t)
        }
        return PluginHeader(types, lits, hard)
    }
}

/**
 * Traduz os parâmetros de TEXTO do js/plugins.js (rótulos de menu criados por plugins, como botões extras
 * de batalha). Só mexe em parâmetros declarados como texto (@type string / multiline_string / note ou sem
 * @type) e nunca em: valores que o código do plugin compara, nomes de eventos do jogo, código JS,
 * arquivos, números, switches, variáveis e listas de seleção.
 */
class PluginTextCollector(
    private val refMap: Map<String, String>,
    private val eventNames: Set<String>
) {
    private val jobs = ArrayList<TextJob>()
    private val finalizers = ArrayList<() -> Unit>()
    var changed = 0
        private set

    private val CODEISH = Regex("\\\$game|\\\$data|this\\.|=>|function\\s*\\(")
    // Chaves que guardam identificadores ou nomes de meta-tags (Id, sceneId, HpCostMetaName, Tag...):
    // o plugin ou outros parâmetros as referenciam, então nunca são traduzidas.
    private val SKIP_KEY = Regex("(?i)meta|tag$|keyword|memo|comment|remark|^note$|(^|[a-z])id$|^(key|symbol|identifier)$")
    // Dentro de listas/structs, só chaves com nome de texto de tela (um "name" de lista costuma ser identificador).
    private val DISPLAY_KEY = Regex("(?i)text|message|msg|title|caption|desc|help|word|term|vocab|format|content|category|command|param")
    private var ids: Set<String> = emptySet()
    private var current = ""
    /** Quantos textos foram enviados para tradução, por plugin (para o log). */
    val perPlugin = LinkedHashMap<String, Int>()

    fun sources(): Set<String> = jobs.mapTo(LinkedHashSet()) { it.source }

    fun finish(translations: Map<String, String>) {
        for (j in jobs) j.result = translations[j.source] ?: j.source
        for (f in finalizers) f()
    }

    fun collectPlugin(name: String, p: JSONObject, h: PluginHeader) {
        current = name
        val params = p.optJSONObject("parameters") ?: return
        val keys = ArrayList<String>()
        for (k in params.keys()) keys.add(k)
        // 1ª passada: valores de identificadores deste plugin (qualquer texto igual a eles fica intacto).
        val found = HashSet<String>()
        for (k in keys) (params.opt(k) as? String)?.let { scanIds(k, it, found) }
        ids = found
        for (k in keys) {
            val v = params.opt(k) as? String ?: continue
            nested(h, k, v, { params.put(k, it) }, { changed++ }, true)
        }
    }

    private fun scanIds(key: String, v: String, into: MutableSet<String>) {
        val t = v.trim()
        if (t.isEmpty()) return
        if (t.startsWith("[") || t.startsWith("{")) {
            val parsed = runCatching { JSONTokener(t).nextValue() }.getOrNull()
            if (parsed is JSONArray) {
                for (i in 0 until parsed.length()) (parsed.opt(i) as? String)?.let { scanIds(key, it, into) }
                return
            }
            if (parsed is JSONObject) {
                val ks = ArrayList<String>()
                for (k in parsed.keys()) ks.add(k)
                for (k in ks) (parsed.opt(k) as? String)?.let { scanIds(k, it, into) }
                return
            }
        }
        if (SKIP_KEY.containsMatchIn(key) && TextProtector.hasJapanese(t)) into.add(t)
    }

    private fun eligible(type: String?): Boolean {
        val t = type?.trim()?.lowercase() ?: return true
        return t == "string" || t == "multiline_string" || t == "note" || t == "string[]"
    }

    /** Plugins guardam structs/listas como JSON dentro de uma string; entra nelas. */
    private fun nested(h: PluginHeader, key: String, v: String, set: (String) -> Unit, mark: () -> Unit, top: Boolean) {
        val t = v.trim()
        if (t.isEmpty() || !TextProtector.hasJapanese(t)) return
        if (t.startsWith("[") || t.startsWith("{")) {
            val parsed = runCatching { JSONTokener(t).nextValue() }.getOrNull()
            if (parsed is JSONArray || parsed is JSONObject) {
                var touched = false
                val m = { touched = true }
                if (parsed is JSONArray) walkArray(h, key, parsed, m, top) else walkObject(h, parsed as JSONObject, m)
                finalizers.add { if (touched) { set(parsed.toString()); mark() } }
                return
            }
        }
        leaf(h, key, v, set, mark, top)
    }

    private fun walkArray(h: PluginHeader, key: String, a: JSONArray, mark: () -> Unit, top: Boolean) {
        for (i in 0 until a.length()) {
            when (val e = a.opt(i)) {
                is String -> nested(h, key, e, { a.put(i, it) }, mark, top)
                is JSONArray -> walkArray(h, key, e, mark, top)
                is JSONObject -> walkObject(h, e, mark)
                else -> {}
            }
        }
    }

    private fun walkObject(h: PluginHeader, o: JSONObject, mark: () -> Unit) {
        val keys = ArrayList<String>()
        for (k in o.keys()) keys.add(k)
        for (k in keys) {
            when (val e = o.opt(k)) {
                is String -> nested(h, k, e, { o.put(k, it) }, mark, false)
                is JSONArray -> walkArray(h, k, e, mark, false)
                is JSONObject -> walkObject(h, e, mark)
                else -> {}
            }
        }
    }

    private fun leaf(h: PluginHeader, key: String, v: String, set: (String) -> Unit, mark: () -> Unit, top: Boolean) {
        if (!h.types.containsKey(key) || !eligible(h.types[key]) || SKIP_KEY.containsMatchIn(key)) return
        if (!top && !DISPLAY_KEY.containsMatchIn(key)) return
        val t = v.trim()
        if (t in ids || t in h.literals || t in eventNames || CODEISH.containsMatchIn(t)) return
        // Nome do jogo citado em tag de nota: usa exatamente a mesma tradução.
        refMap[t]?.let { fixed -> set(fixed); mark(); return }
        val j = TextJob(t)
        jobs.add(j)
        perPlugin[current] = (perPlugin[current] ?: 0) + 1
        finalizers.add { if (j.result != j.source) { set(j.result); mark() } }
    }
}
