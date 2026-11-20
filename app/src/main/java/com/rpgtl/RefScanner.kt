package com.rpgtl

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Pré-análise (MV/MZ): quais nomes do jogo são citados em tags de nota, e qual o layout da tela. */
object RefScanner {
    private val DB_FILES = setOf(
        "Actors.json", "Classes.json", "Items.json", "Skills.json",
        "Weapons.json", "Armors.json", "States.json", "Enemies.json"
    )
    private val MAP = Regex("Map\\d+\\.json")

    class Result(
        val refs: Set<String>,               // nomes citados em tags (traduzidos de forma consistente)
        val keywords: Set<String>,           // palavras de tag iguais a termos do sistema (sintaxe do plugin)
        val screenWidth: Int,
        val fontSize: Int,
        val untranslatedLabels: Set<String>, // texto japonês em tags que o app não traduz
        val eventNames: Set<String>          // nomes de eventos/mapas (plugins podem localizá-los pelo nome)
    )

    private fun parse(s: Source): Any? = runCatching {
        JSONTokener(s.read().toString(Charsets.UTF_8).removePrefix("\uFEFF")).nextValue()
    }.getOrNull()

    private fun clean(s: String) = s.trim { it.isWhitespace() || it == '\u3000' }

    private fun addStrings(a: JSONArray?, into: MutableSet<String>) {
        if (a == null) return
        for (i in 0 until a.length()) {
            val v = (a.opt(i) as? String)?.let { clean(it) } ?: continue
            if (v.isNotEmpty() && TextProtector.hasJapanese(v)) into.add(v)
        }
    }

    fun scan(sources: List<Source>): Result {
        val pool = HashSet<String>()    // nomes que o app traduziria e que plugins podem citar
        val terms = HashSet<String>()   // termos do sistema (basic/commands/params)
        var sw = 816
        var fs = 26
        val json = sources.filter { it.name.endsWith(".json", ignoreCase = true) }

        json.firstOrNull { it.name.equals("System.json", ignoreCase = true) }?.let { s ->
            val o = parse(s) as? JSONObject ?: return@let
            o.optJSONObject("advanced")?.let { a ->
                sw = a.optInt("screenWidth", sw)
                fs = a.optInt("fontSize", fs)
            }
            for (k in listOf("armorTypes", "elements", "equipTypes", "skillTypes", "weaponTypes")) {
                addStrings(o.optJSONArray(k), pool)
            }
            val t = o.optJSONObject("terms")
            if (t != null) {
                for (k in t.keys()) {
                    val v = t.opt(k)
                    if (v is JSONArray) {
                        addStrings(v, pool)
                        addStrings(v, terms)
                    }
                }
            }
        }

        val notes = ArrayList<String>()
        val eventNames = HashSet<String>()
        for (s in json) {
            val n = s.name
            val isDb = DB_FILES.any { it.equals(n, ignoreCase = true) }
            if (!isDb && !MAP.matches(n)) continue
            val root = parse(s) ?: continue
            if (isDb && root is JSONArray) {
                for (i in 0 until root.length()) {
                    val o = root.optJSONObject(i) ?: continue
                    (o.opt("name") as? String)?.let { nm ->
                        val c = clean(nm)
                        if (c.isNotEmpty() && TextProtector.hasJapanese(c)) pool.add(c)
                    }
                    (o.opt("note") as? String)?.let { if (it.isNotEmpty()) notes.add(it) }
                }
            } else if (root is JSONObject) {
                (root.opt("displayName") as? String)?.let { val c = clean(it); if (c.isNotEmpty()) eventNames.add(c) }
                val ev = root.optJSONArray("events") ?: continue
                for (i in 0 until ev.length()) {
                    val e = ev.optJSONObject(i) ?: continue
                    (e.opt("note") as? String)?.let { if (it.isNotEmpty()) notes.add(it) }
                    (e.opt("name") as? String)?.let { val c = clean(it); if (c.isNotEmpty() && TextProtector.hasJapanese(c)) eventNames.add(c) }
                }
            }
        }

        val refs = LinkedHashSet<String>()
        val keywords = LinkedHashSet<String>()
        val labels = LinkedHashSet<String>()
        for (note in notes) {
            NoteRefs.scan(note, terms) { tok, kw ->
                if (kw) keywords.add(tok) else if (tok in pool) refs.add(tok) else labels.add(tok)
            }
        }
        // Rótulos de botão (campo após o ícone) agora são traduzidos; só os demais ficam em japonês.
        for (note in notes) labels.removeAll(NoteRefs.labels(note, terms).toSet())
        return Result(refs, keywords, sw, fs, labels, eventNames)
    }
}
