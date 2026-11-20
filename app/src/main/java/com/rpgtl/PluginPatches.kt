package com.rpgtl

/**
 * Correções para plugins conhecidos, aplicadas numa CÓPIA do plugin (js_en/plugins/...). O original nunca é
 * alterado, e a correção só é aplicada se TODOS os trechos esperados existirem exatamente (outra versão
 * do plugin = nada é alterado).
 */
object PluginPatches {
    class Rule(val find: String, val replace: String)

    class Patch(val rules: List<Rule>) {
        /** Devolve o código corrigido, ou null se algum trecho não existir (versão diferente) ou nada mudar. */
        fun apply(src: String): String? {
            if (rules.any { !src.contains(it.find) }) return null
            var out = src
            for (r in rules) out = out.replace(r.find, r.replace)
            return if (out == src) null else out
        }
    }

    /**
     * Keke_VariableActorCommand v1.2.7 apaga TODOS os espaços das tags <コマンド:...> antes de comparar o tipo de
     * habilidade com o nome no jogo, e trata qualquer nome com dígito como ID. Aqui só se removem os espaços ao
     * redor de vírgulas e barras (os espaços dentro dos nomes ficam) e só números puros contam como ID.
     */
    private val KEKE_VARIABLE_ACTOR_COMMAND = Patch(
        listOf(
            Rule(
                "array = array.map(e => e.replace(/\\s/g, \"\"));",
                "array = array.map(e => e.replace(/\\s*([,\\/])\\s*/g, \"\$1\").trim());"
            ),
            Rule(
                ".map(e => e.replace(/\\s/g, \"\")).filter(e => e)];",
                ".map(e => e.replace(/\\s*([,\\/])\\s*/g, \"\$1\").trim()).filter(e => e)];"
            ),
            Rule(
                "const name = data[2] ? data[2].replace(/\\s/g, \"\") : \"\";",
                "const name = data[2] ? data[2].trim() : \"\";"
            ),
            Rule(
                "if (tps[1].match(/\\d+/)) {",
                "if (tps[1].match(/^\\d+\$/)) {"
            )
        )
    )

    /** [baseName] = nome do arquivo do plugin sem pasta e sem .js. */
    fun forPlugin(baseName: String): Patch? =
        if (baseName == "Keke_VariableActorCommand") KEKE_VARIABLE_ACTOR_COMMAND else null
}
