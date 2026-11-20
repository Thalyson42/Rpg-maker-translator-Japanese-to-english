package com.rpgtl

/**
 * Plugins leem tags de nota do tipo <nome:arg1,valor/valor,...> e muitas vezes citam NOMES do jogo
 * (tipos de habilidade, itens, elementos...). Se o nome for traduzido e a tag não, o plugin não
 * encontra mais o alvo e a opção some (ex.: botões do menu de batalha).
 *
 * Regra: a 1ª palavra da tag que for igual a um termo do sistema (攻撃, 防御, アイテム, スキル...)
 * é tratada como palavra-chave da sintaxe do plugin e nunca é alterada. Qualquer outra palavra
 * japonesa da tag que seja igual a um nome do jogo é uma referência e recebe a MESMA tradução do nome.
 */
object NoteRefs {
    private val TAG = Regex("<([^<>:\\n]+):([^<>]*)>")
    private val BAD = Regex("[,/:<>\\r\\n]")
    private val SPACES = Regex("\\s+")

    /** Tradução segura para dentro de tag: sem os separadores da sintaxe (, / : < >). */
    fun safe(s: String): String = SPACES.replace(BAD.replace(s, " "), " ").trim()

    /**
     * Forma "colada" do texto para uso dentro de tags: alguns plugins (ex.: Keke_VariableActorCommand)
     * removem TODOS os espaços do conteúdo da tag antes de comparar com o nome no banco de dados.
     * "Martial arts" vira "MartialArts"; o nome no jogo e a tag recebem exatamente o mesmo texto.
     * Retorna null se o texto tiver dígitos (alguns plugins tratam número como ID) ou ficar vazio.
     */
    fun compact(s: String): String? {
        val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val joined = if (words.size == 1) words[0]
        else words.joinToString("") { w -> w.replaceFirstChar { c -> c.uppercaseChar() } }
        return if (joined.any { it.isDigit() }) null else joined
    }

    /** Texto de tag: com espaços (plugin corrigido) ou "colado" (plugin que apaga espaços). Null = manter japonês. */
    fun forTag(s: String, allowSpaces: Boolean): String? {
        val t = safe(s)
        if (t.isEmpty()) return null
        return if (allowSpaces) t else compact(t)
    }

    /** Chama [onToken] para cada palavra japonesa das tags; keyword=true se for palavra-chave. */
    fun scan(note: String, terms: Set<String>, onToken: (token: String, keyword: Boolean) -> Unit) {
        if (!note.contains('<')) return
        for (m in TAG.findAll(note)) {
            m.groupValues[2].split(',').forEachIndexed { ai, arg ->
                arg.split('/').forEachIndexed { si, seg ->
                    val t = seg.trim()
                    if (t.isNotEmpty() && TextProtector.hasJapanese(t)) {
                        onToken(t, ai == 0 && si == 0 && t in terms)
                    }
                }
            }
        }
    }

    /**
     * Rótulos de botão: em tags como <comando:palavra/valor,ÍCONE,RÓTULO,...> o 3º campo, quando o 2º é um
     * número (índice de ícone), é o texto mostrado no botão. Retorna os rótulos japoneses encontrados.
     */
    fun labels(note: String, terms: Set<String>): List<String> {
        val out = ArrayList<String>()
        if (!note.contains('<')) return out
        for (m in TAG.findAll(note)) {
            val args = m.groupValues[2].split(',')
            if (args.size >= 3 && args[1].trim().toIntOrNull() != null) {
                val t = args[2].trim()
                if (t.isNotEmpty() && TextProtector.hasJapanese(t) && t !in terms) out.add(t)
            }
        }
        return out
    }

    /** Troca as referências pelas traduções em [map] (nome original -> tradução já sanitizada). */
    fun rewrite(note: String, map: Map<String, String>, terms: Set<String>): String {
        if (map.isEmpty() || !note.contains('<')) return note
        return TAG.replace(note) { m ->
            val args = m.groupValues[2].split(',').mapIndexed { ai, arg ->
                arg.split('/').mapIndexed { si, seg ->
                    val t = seg.trim()
                    val keyword = ai == 0 && si == 0 && t in terms
                    val r = if (t.isEmpty() || keyword) null else map[t]
                    if (r.isNullOrEmpty()) seg else seg.replaceFirst(t, r)
                }.joinToString("/")
            }.joinToString(",")
            "<" + m.groupValues[1] + ":" + args + ">"
        }
    }
}
