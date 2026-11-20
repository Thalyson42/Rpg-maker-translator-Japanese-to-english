package com.rpgtl

import java.util.IdentityHashMap

/**
 * Coleta e regrava os textos dos arquivos do RPG Maker XP (.rxdata), VX (.rvdata)
 * e VX Ace (.rvdata2). No XP a primeira linha de cada mensagem fica no parâmetro 0
 * do comando 101; no VX/Ace todas as linhas ficam em comandos 401.
 */
class MarshalTextCollector(
    private val width: Int,
    private val xp: Boolean,
    private val utf8Strings: Boolean
) {
    private val jobs = ArrayList<TextJob>()
    private val finalizers = ArrayList<() -> Unit>()
    private val seen = IdentityHashMap<MNode, Boolean>()

    fun sources(): Set<String> = jobs.mapTo(LinkedHashSet()) { it.source }

    fun finish(translations: Map<String, String>) {
        for (j in jobs) j.result = translations[j.source] ?: j.source
        for (f in finalizers) f()
    }

    companion object {
        private val DB = setOf("actors", "classes", "skills", "items", "weapons", "armors", "enemies", "states")
        private val MAP = Regex("(?i)map\\d+")
        private val DB_FIELDS = listOf(
            "@name", "@nickname", "@description", "@message1", "@message2", "@message3", "@message4"
        )

        fun isTranslatable(base: String): Boolean {
            val b = base.lowercase()
            return b in DB || b == "system" || b == "commonevents" || b == "troops" || MAP.matches(base)
        }
    }

    fun collect(base: String, root: MNode): Boolean {
        val b = base.lowercase()
        when {
            b in DB -> {
                val a = root as? MArr ?: return false
                for (e in a.items) if (e is MObj) dbObj(e)
            }
            b == "system" -> system(root as? MObj ?: return false)
            b == "commonevents" -> {
                val a = root as? MArr ?: return false
                for (e in a.items) if (e is MObj) list(e)
            }
            b == "troops" -> {
                val a = root as? MArr ?: return false
                for (e in a.items) if (e is MObj) pages(e)
            }
            MAP.matches(base) -> map(root as? MObj ?: return false)
            else -> return false
        }
        return true
    }

    // ---------- utilidades ----------

    private fun field(o: MObj, name: String): MNode? {
        for ((k, v) in o.fields) if (k == name) return v
        return null
    }

    private fun setField(o: MObj, name: String, v: MNode) {
        val i = o.fields.indexOfFirst { it.first == name }
        if (i >= 0) o.fields[i] = name to v else o.fields.add(name to v)
    }

    private fun code(c: MObj): Int = (field(c, "@code") as? MFix)?.v ?: -1
    private fun indent(c: MObj): Int = (field(c, "@indent") as? MFix)?.v ?: 0
    private fun params(c: MObj): MArr? = field(c, "@parameters") as? MArr
    private fun paramStr(c: MObj, i: Int): MStr? = params(c)?.items?.getOrNull(i) as? MStr

    private fun newJob(src: String): TextJob? {
        val t = src.trim { it.isWhitespace() || it == '\u3000' }
        if (t.isEmpty() || !TextProtector.hasJapanese(t)) return null
        return TextJob(t).also { jobs.add(it) }
    }

    private fun bind(src: String, apply: (String) -> Unit) {
        val j = newJob(src) ?: return
        finalizers.add { apply(j.result) }
    }

    private fun bindStr(n: MStr, leadingSpace: Boolean = false) {
        if (seen.put(n, true) != null) return
        bind(MText.get(n)) { MText.set(n, if (leadingSpace && !it.startsWith(" ")) " $it" else it) }
    }

    private fun bindStrings(n: MNode?) {
        when (n) {
            is MStr -> bindStr(n)
            is MArr -> for (x in n.items) if (x is MStr) bindStr(x)
            else -> {}
        }
    }

    // ---------- arquivos ----------

    private fun dbObj(o: MObj) {
        for (k in DB_FIELDS) {
            val s = field(o, k) as? MStr ?: continue
            bindStr(s, leadingSpace = k.startsWith("@message"))
        }
    }

    private fun deepStrings(o: MObj) {
        for ((_, v) in o.fields) {
            if (v is MObj) deepStrings(v) else bindStrings(v)
        }
    }

    private fun system(o: MObj) {
        bindStrings(field(o, "@game_title"))
        bindStrings(field(o, "@currency_unit"))
        for (k in listOf("@elements", "@skill_types", "@weapon_types", "@armor_types")) {
            bindStrings(field(o, k))
        }
        for (k in listOf("@terms", "@words")) {
            (field(o, k) as? MObj)?.let { deepStrings(it) }
        }
    }

    private fun map(o: MObj) {
        bindStrings(field(o, "@display_name"))
        val ev = field(o, "@events") as? MHash ?: return
        for ((_, e) in ev.pairs) if (e is MObj) pages(e)
    }

    private fun pages(holder: MObj) {
        val pages = field(holder, "@pages") as? MArr ?: return
        for (p in pages.items) if (p is MObj) list(p)
    }

    // ---------- comandos de evento ----------

    private inner class Block(
        val header: MObj,
        val code: Int,
        val original: List<MObj>,
        val maxLines: Int,
        val width: Int,
        smart: Boolean,
        val firstInHeader: Boolean
    ) {
        val job: TextJob?

        init {
            val texts = ArrayList<String>()
            if (firstInHeader) texts.add(paramStr(header, 0)?.let { MText.get(it) }.orEmpty())
            for (c in original) texts.add(paramStr(c, 0)?.let { MText.get(it) }.orEmpty())
            job = newJob(TextProtector.joinLines(texts, smart))
        }

        private fun makeLine(text: String, ind: Int, template: MObj?): MObj {
            val c: MObj = if (template != null) {
                MText.deepCopy(template) as MObj
            } else {
                MObj(
                    "RPG::EventCommand",
                    mutableListOf(
                        "@code" to MFix(code),
                        "@indent" to MFix(ind),
                        "@parameters" to MArr(mutableListOf<MNode>(MText.newString("", utf8Strings)))
                    )
                )
            }
            setField(c, "@code", MFix(code))
            setField(c, "@indent", MFix(ind))
            val s = paramStr(c, 0)
            if (s != null) MText.set(s, text)
            else params(c)?.items?.add(MText.newString(text, utf8Strings))
            return c
        }

        fun emit(out: MutableList<MNode>) {
            val j = job
            if (j == null || j.result == j.source) {
                out.add(header)
                out.addAll(original)
                return
            }
            val lines = TextProtector.wrap(j.result, width)
            val ind = indent(header)
            val chunks = if (maxLines > 0) lines.chunked(maxLines) else listOf(lines)
            val template = original.firstOrNull()
            chunks.forEachIndexed { idx, chunk ->
                val h = if (idx == 0) header else MText.deepCopy(header) as MObj
                var start = 0
                if (firstInHeader) {
                    paramStr(h, 0)?.let { MText.set(it, chunk[0]) }
                    start = 1
                }
                out.add(h)
                for (k in start until chunk.size) out.add(makeLine(chunk[k], ind, template))
            }
        }
    }

    private fun list(holder: MObj) {
        val listNode = field(holder, "@list") as? MArr ?: return
        val items = ArrayList<MNode>(listNode.items)
        val plan = ArrayList<Any>()
        var i = 0
        while (i < items.size) {
            val cmd = items[i] as? MObj
            if (cmd == null) { plan.add(items[i]); i++; continue }
            when (code(cmd)) {
                101, 105 -> {
                    val isMsg = code(cmd) == 101
                    val textCode = if (isMsg) 401 else 405
                    val lines = ArrayList<MObj>()
                    var j = i + 1
                    while (j < items.size) {
                        val nx = items[j] as? MObj ?: break
                        if (code(nx) != textCode) break
                        lines.add(nx)
                        j++
                    }
                    if (isMsg) {
                        val face = if (xp) "" else paramStr(cmd, 0)?.let { MText.get(it) }.orEmpty()
                        val w = if (face.isNotEmpty()) width - 10 else width
                        plan.add(Block(cmd, 401, lines, 4, w, true, xp))
                    } else {
                        plan.add(Block(cmd, 405, lines, 0, width, false, false))
                    }
                    i = j
                }
                102 -> {
                    val arr = params(cmd)?.items?.getOrNull(0) as? MArr
                    if (arr != null) for (x in arr.items) if (x is MStr) bindStr(x)
                    plan.add(cmd); i++
                }
                320, 324, 325 -> {
                    paramStr(cmd, 1)?.let { bindStr(it) }
                    plan.add(cmd); i++
                }
                else -> { plan.add(cmd); i++ }
            }
        }
        finalizers.add {
            val out = ArrayList<MNode>()
            for (p in plan) {
                if (p is Block) p.emit(out) else out.add(p as MNode)
            }
            listNode.items.clear()
            listNode.items.addAll(out)
        }
    }
}
