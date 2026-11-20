package com.rpgtl

import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Codificação/decodificação das strings do Ruby (UTF-8 no VX Ace; Shift-JIS/CP932 no XP e VX). */
object MText {
    private val CP932: Charset =
        runCatching { Charset.forName("windows-31j") }.getOrElse { Charset.forName("Shift_JIS") }

    private fun encName(s: MStr): String? {
        val iv = s.ivars ?: return null
        for ((k, v) in iv) {
            if (k == "E") return if (v is MConst && v.c == 'T') "UTF-8" else "US-ASCII"
            if (k == "encoding" && v is MStr) return String(v.bytes, Charsets.ISO_8859_1)
        }
        return null
    }

    private fun validUtf8(bytes: ByteArray): Boolean = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
        true
    }.getOrDefault(false)

    private fun charset(s: MStr): Charset {
        val n = encName(s)
        return when {
            n == "UTF-8" -> Charsets.UTF_8
            n == "US-ASCII" -> if (validUtf8(s.bytes)) Charsets.UTF_8 else CP932
            n != null -> runCatching { Charset.forName(n) }.getOrDefault(CP932)
            validUtf8(s.bytes) -> Charsets.UTF_8
            else -> CP932
        }
    }

    fun get(s: MStr): String = String(s.bytes, charset(s))

    private fun asciiFy(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            sb.append(
                when (ch) {
                    '\u2018', '\u2019' -> '\''
                    '\u201C', '\u201D' -> '"'
                    '\u2013', '\u2014' -> '-'
                    '\u00A0' -> ' '
                    else -> ch
                }
            )
        }
        return sb.toString().replace("\u2026", "...")
    }

    fun set(s: MStr, text: String) {
        val cs = charset(s)
        val t = if (cs == Charsets.UTF_8) text else asciiFy(text)
        if (t.all { it.code < 128 }) {
            s.bytes = t.toByteArray(Charsets.US_ASCII)
            return
        }
        if (cs == Charsets.UTF_8) {
            s.bytes = t.toByteArray(Charsets.UTF_8)
            val iv = s.ivars
            if (iv != null) {
                val i = iv.indexOfFirst { it.first == "E" }
                if (i >= 0) iv[i] = "E" to MConst('T')
            }
            return
        }
        val enc = cs.newEncoder()
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .onMalformedInput(CodingErrorAction.REPLACE)
            .replaceWith(byteArrayOf('?'.code.toByte()))
        val bb = enc.encode(CharBuffer.wrap(t))
        val arr = ByteArray(bb.remaining())
        bb.get(arr)
        s.bytes = arr
    }

    fun newString(text: String, utf8: Boolean): MStr {
        val s = MStr(ByteArray(0))
        if (utf8) s.ivars = mutableListOf("E" to MConst('T'))
        set(s, text)
        return s
    }

    fun deepCopy(n: MNode): MNode {
        val c: MNode = when (n) {
            is MConst -> MConst(n.c)
            is MFix -> MFix(n.v)
            is MSym -> MSym(n.name)
            is MStr -> MStr(n.bytes.copyOf())
            is MArr -> MArr(n.items.mapTo(ArrayList<MNode>()) { deepCopy(it) })
            is MObj -> MObj(n.cls, n.fields.mapTo(ArrayList<Pair<String, MNode>>()) { it.first to deepCopy(it.second) })
            is MHash -> MHash(
                n.pairs.mapTo(ArrayList<Pair<MNode, MNode>>()) { deepCopy(it.first) to deepCopy(it.second) },
                n.def?.let { deepCopy(it) }
            )
            is MFloat -> MFloat(n.raw.copyOf())
            is MUserDef -> MUserDef(n.cls, n.data.copyOf())
            else -> n
        }
        if (c !== n) {
            n.ivars?.let { iv ->
                c.ivars = iv.mapTo(ArrayList<Pair<String, MNode>>()) { it.first to deepCopy(it.second) }
            }
            c.extended = n.extended?.toMutableList()
            c.userClass = n.userClass
        }
        return c
    }
}
