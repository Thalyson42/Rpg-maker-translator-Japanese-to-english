package com.rpgtl

/**
 * Ajusta textos em inglês às janelas do jogo: o inglês costuma ocupar mais espaço que o japonês.
 * Quando não cabe, usa o código \} do RPG Maker (diminui a fonte) em vez de deixar o texto cortado.
 */
object TextFit {
    /** `\}` diminui a fonte dentro da janela (MV/MZ). */
    const val SMALL = "\\}"
    private val PLACEHOLDER = Regex("%\\d+")

    /** Caracteres por linha numa janela de tela cheia (largura - 24px de borda; ~0,58 em por caractere). */
    fun charsPerLine(screenWidth: Int, fontSize: Int): Int =
        ((screenWidth - 24) / (fontSize * 0.58)).toInt().coerceIn(24, 90)

    /** Janelas de comando de batalha têm 192px (menos bordas e margem). */
    fun labelChars(fontSize: Int): Int =
        ((192 - 24 - 16) / (fontSize * 0.58)).toInt().coerceAtLeast(6)

    /** Janela de ajuda (descrições de itens/habilidades): no máximo 2 linhas. */
    fun help(text: String, cpl: Int): String {
        val flat = TextProtector.flatten(text)
        val normal = TextProtector.wrap(flat, cpl)
        if (normal.size <= 2) return normal.joinToString("\n")
        return SMALL + TextProtector.wrap(flat, (cpl * 1.7).toInt()).joinToString("\n")
    }

    /** Uma linha só (log de batalha, mensagens do sistema). %1, %2... contam como 8 caracteres. */
    fun oneLine(text: String, cpl: Int): String {
        val flat = TextProtector.flatten(text)
        return if (estimate(flat) <= cpl) flat else SMALL + flat
    }

    private fun estimate(s: String): Int =
        TextProtector.visible(PLACEHOLDER.replace(s, "XXXXXXXX")).length
}
