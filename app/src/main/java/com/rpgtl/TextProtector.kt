package com.rpgtl

/**
 * Protege códigos de controle do RPG Maker (\C[2], \V[1], \N[1], \., \!, %1 ...)
 * para que o tradutor não os altere, e cuida de quebra de linha.
 */
object TextProtector {
    private val CODE = Regex("""\\[A-Za-z]+\[[^\]]*\]|\\[A-Za-z]|\\[{}\x24.|!><^\\]|%\d+""")
    private val JP = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uFF66-\\uFF9F]")
    private val TAGS = Regex("</?(?:x|span)[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")

    private const val TERMINAL = "。！？!?…」』）)〜～♪★☆.．”\"】"
    private const val OPENERS = "「『（(【"
    private const val MIN_FULL = 18

    fun hasJapanese(s: String) = JP.containsMatchIn(s)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Texto -> texto com códigos envolvidos em tags "não traduzir". */
    fun encode(text: String, open: String, close: String): String {
        val sb = StringBuilder()
        var last = 0
        for (m in CODE.findAll(text)) {
            sb.append(esc(text.substring(last, m.range.first)))
            sb.append(open).append(esc(m.value)).append(close)
            last = m.range.last + 1
        }
        sb.append(esc(text.substring(last)))
        return sb.toString()
    }

    /** Resposta do tradutor -> texto original com os códigos de volta. */
    fun decode(s: String): String = unescape(TAGS.replace(s, ""))

    private fun codePoint(cp: Int?, fallback: String): String =
        if (cp == null) fallback else runCatching { String(Character.toChars(cp)) }.getOrDefault(fallback)

    private fun unescape(s: String): String = ENTITY.replace(s) { m ->
        val g = m.groupValues[1]
        when {
            g == "amp" -> "&"
            g == "lt" -> "<"
            g == "gt" -> ">"
            g == "quot" -> "\""
            g == "apos" -> "'"
            g.startsWith("#x") -> codePoint(g.substring(2).toIntOrNull(16), m.value)
            else -> codePoint(g.substring(1).toIntOrNull(), m.value)
        }
    }

    fun sameCodes(a: String, b: String): Boolean =
        codesOf(a) == codesOf(b)

    private fun codesOf(s: String): List<String> =
        CODE.findAll(s).map { it.value }.sorted().toList()

    fun visible(s: String): String = CODE.replace(s, "")

    private fun visibleLength(s: String) = visible(s).length

    /** Divide o texto em pedaços (éCódigo, texto), preservando a ordem. */
    fun split(text: String): List<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>()
        var last = 0
        for (m in CODE.findAll(text)) {
            if (m.range.first > last) out.add(false to text.substring(last, m.range.first))
            out.add(true to m.value)
            last = m.range.last + 1
        }
        if (last < text.length) out.add(false to text.substring(last))
        return out
    }

    /**
     * Junta as linhas de um bloco de diálogo. No modo "smart", linhas longas que não
     * terminam em pontuação são juntas à próxima (quebra automática no meio da frase).
     */
    fun joinLines(lines: List<String>, smart: Boolean): String {
        val sb = StringBuilder()
        lines.forEachIndexed { i, l ->
            sb.append(l)
            if (i < lines.lastIndex) {
                val sep = if (!smart) {
                    "\n"
                } else {
                    val core = CODE.replace(l, "").trimEnd()
                    val next = lines[i + 1].trimStart()
                    if (core.length < MIN_FULL ||
                        core.last() in TERMINAL ||
                        (next.isNotEmpty() && next.first() in OPENERS)
                    ) "\n" else ""
                }
                sb.append(sep)
            }
        }
        return sb.toString()
    }

    /** Une as linhas manuais (\n) de um texto japonês numa frase só (japonês não usa espaço entre palavras). */
    fun joinJapanese(text: String): String {
        val sb = StringBuilder()
        for (raw in text.replace("\r", "").split("\n")) {
            val l = raw.trim { it.isWhitespace() || it == '\u3000' }
            if (l.isEmpty()) continue
            if (sb.isNotEmpty() && sb.last().code < 128 && l.first().code < 128) sb.append(' ')
            sb.append(l)
        }
        return sb.toString()
    }

    /** Une as linhas de um texto já traduzido (inglês) com espaço. */
    fun flatten(text: String): String =
        text.replace("\r", "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** Quebra o texto em linhas de no máximo [width] caracteres visíveis. */
    fun wrap(text: String, width: Int): List<String> {
        val out = ArrayList<String>()
        for (para in text.split("\n")) {
            var line = StringBuilder()
            var len = 0
            for (word in para.split(" ")) {
                if (word.isEmpty()) continue
                val wl = visibleLength(word)
                if (len == 0) {
                    line.append(word); len = wl
                } else if (len + 1 + wl <= width) {
                    line.append(' ').append(word); len += 1 + wl
                } else {
                    out.add(line.toString())
                    line = StringBuilder(word); len = wl
                }
            }
            out.add(line.toString())
        }
        while (out.size > 1 && out.last().isBlank()) out.removeAt(out.lastIndex)
        return out
    }
}
