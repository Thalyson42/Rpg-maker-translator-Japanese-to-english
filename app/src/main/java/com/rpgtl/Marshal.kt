package com.rpgtl

import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap

/**
 * Leitor/gravador do formato Marshal 4.8 do Ruby (usado por RPG Maker XP, VX e VX Ace:
 * .rxdata, .rvdata, .rvdata2). A árvore preserva a identidade dos objetos, então
 * referências compartilhadas (links) são gravadas de volta corretamente.
 */
sealed class MNode {
    var ivars: MutableList<Pair<String, MNode>>? = null
    var extended: MutableList<String>? = null
    var userClass: String? = null
}

class MConst(val c: Char) : MNode()                 // '0' nil, 'T' true, 'F' false
class MFix(val v: Int) : MNode()
class MSym(val name: String) : MNode()
class MStr(var bytes: ByteArray) : MNode()
class MArr(val items: MutableList<MNode>) : MNode()
class MHash(val pairs: MutableList<Pair<MNode, MNode>>, var def: MNode?) : MNode()
class MObj(val cls: String, val fields: MutableList<Pair<String, MNode>>) : MNode()
class MStruct(val cls: String, val fields: MutableList<Pair<String, MNode>>) : MNode()
class MFloat(val raw: ByteArray) : MNode()
class MBig(val sign: Int, val data: ByteArray) : MNode()
class MRegex(val src: ByteArray, val opts: Int) : MNode()
class MUserDef(val cls: String, val data: ByteArray) : MNode()
class MUserMarshal(val cls: String, var inner: MNode) : MNode()
class MData(val cls: String, var inner: MNode) : MNode()
class MClass(val kind: Char, val name: ByteArray) : MNode()

class MarshalReader(private val b: ByteArray) {
    private var p = 0
    private val syms = ArrayList<String>()
    private val objs = ArrayList<MNode>()

    fun read(): MNode {
        if (b.size < 2 || u8() != 4 || u8() != 8) {
            throw IllegalArgumentException("Not a Marshal 4.8 file")
        }
        return obj()
    }

    private fun u8(): Int = b[p++].toInt() and 0xFF

    private fun long(): Int {
        val c = b[p++].toInt()
        if (c == 0) return 0
        if (c > 0) {
            if (c in 5..127) return c - 5
            var x = 0
            for (i in 0 until c) x = x or (u8() shl (8 * i))
            return x
        }
        if (c in -128..-5) return c + 5
        val n = -c
        var x = -1
        for (i in 0 until n) {
            x = x and (0xFF shl (8 * i)).inv()
            x = x or (u8() shl (8 * i))
        }
        return x
    }

    private fun copy(n: Int): ByteArray {
        val a = b.copyOfRange(p, p + n)
        p += n
        return a
    }

    private fun symbolBody(): String {
        val n = long()
        val s = String(b, p, n, Charsets.ISO_8859_1)
        p += n
        syms.add(s)
        return s
    }

    private fun symbol(): String {
        val t = u8().toChar()
        when (t) {
            ':' -> return symbolBody()
            ';' -> return syms[long()]
            'I' -> {
                if (u8().toChar() != ':') throw IllegalArgumentException("Invalid symbol")
                val s = symbolBody()
                val n = long()
                for (i in 0 until n) { symbol(); obj() }
                return s
            }
            else -> throw IllegalArgumentException("Unexpected symbol: $t")
        }
    }

    private fun <T : MNode> reg(n: T): T {
        objs.add(n)
        return n
    }

    private fun obj(): MNode {
        val t = u8().toChar()
        when (t) {
            '0', 'T', 'F' -> return MConst(t)
            'i' -> return MFix(long())
            ':' -> return MSym(symbolBody())
            ';' -> return MSym(syms[long()])
            '@' -> return objs[long()]
            'I' -> {
                val inner = obj()
                val n = long()
                val list = ArrayList<Pair<String, MNode>>()
                for (i in 0 until n) {
                    val k = symbol()
                    list.add(k to obj())
                }
                inner.ivars = list
                return inner
            }
            'e' -> {
                val m = symbol()
                val inner = obj()
                val ext = inner.extended ?: ArrayList<String>().also { inner.extended = it }
                ext.add(0, m)
                return inner
            }
            'C' -> {
                val c = symbol()
                val inner = obj()
                inner.userClass = c
                return inner
            }
            '"' -> {
                val n = long()
                return reg(MStr(copy(n)))
            }
            '[' -> {
                val n = long()
                val a = reg(MArr(ArrayList(n)))
                for (i in 0 until n) a.items.add(obj())
                return a
            }
            '{', '}' -> {
                val n = long()
                val h = reg(MHash(ArrayList(n), null))
                for (i in 0 until n) {
                    val k = obj()
                    val v = obj()
                    h.pairs.add(k to v)
                }
                if (t == '}') h.def = obj()
                return h
            }
            'o' -> {
                val c = symbol()
                val o = reg(MObj(c, ArrayList()))
                val n = long()
                for (i in 0 until n) {
                    val k = symbol()
                    o.fields.add(k to obj())
                }
                return o
            }
            'S' -> {
                val c = symbol()
                val o = reg(MStruct(c, ArrayList()))
                val n = long()
                for (i in 0 until n) {
                    val k = symbol()
                    o.fields.add(k to obj())
                }
                return o
            }
            'f' -> {
                val n = long()
                return reg(MFloat(copy(n)))
            }
            'l' -> {
                val sign = u8()
                val len = long()
                return reg(MBig(sign, copy(len * 2)))
            }
            '/' -> {
                val n = long()
                val src = copy(n)
                val opts = u8()
                return reg(MRegex(src, opts))
            }
            'u' -> {
                val c = symbol()
                val n = long()
                return reg(MUserDef(c, copy(n)))
            }
            'U' -> {
                val c = symbol()
                val u = reg(MUserMarshal(c, MConst('0')))
                u.inner = obj()
                return u
            }
            'd' -> {
                val c = symbol()
                val u = reg(MData(c, MConst('0')))
                u.inner = obj()
                return u
            }
            'c', 'm', 'M' -> {
                val n = long()
                return reg(MClass(t, copy(n)))
            }
            else -> throw IllegalArgumentException("Unknown Marshal type: '$t' at position $p")
        }
    }
}

class MarshalWriter {
    private val out = ByteArrayOutputStream()
    private val syms = HashMap<String, Int>()
    private val ids = IdentityHashMap<MNode, Int>()
    private var nextId = 0

    fun write(root: MNode): ByteArray {
        out.write(4)
        out.write(8)
        node(root)
        return out.toByteArray()
    }

    private fun long(v: Int) {
        var x = v
        when {
            x == 0 -> out.write(0)
            x in 1..122 -> out.write(x + 5)
            x in -123..-1 -> out.write((x - 5) and 0xFF)
            else -> {
                val buf = ByteArray(4)
                var len = 0
                for (i in 1..4) {
                    buf[i - 1] = (x and 0xFF).toByte()
                    x = x shr 8
                    if (x == 0) { len = i; break }
                    if (x == -1) { len = -i; break }
                }
                out.write(len and 0xFF)
                out.write(buf, 0, if (len < 0) -len else len)
            }
        }
    }

    private fun bytes(a: ByteArray) {
        long(a.size)
        out.write(a, 0, a.size)
    }

    private fun symbol(s: String) {
        val i = syms[s]
        if (i != null) {
            out.write(';'.code)
            long(i)
        } else {
            syms[s] = syms.size
            out.write(':'.code)
            bytes(s.toByteArray(Charsets.ISO_8859_1))
        }
    }

    private fun reg(n: MNode) {
        ids[n] = nextId++
    }

    private fun node(n: MNode) {
        val simple = n is MConst || n is MFix || n is MSym
        if (!simple) {
            val id = ids[n]
            if (id != null) {
                out.write('@'.code)
                long(id)
                return
            }
        }
        val iv = n.ivars
        if (iv != null) out.write('I'.code)
        n.extended?.forEach { out.write('e'.code); symbol(it) }
        n.userClass?.let { out.write('C'.code); symbol(it) }
        when (n) {
            is MConst -> out.write(n.c.code)
            is MFix -> { out.write('i'.code); long(n.v) }
            is MSym -> symbol(n.name)
            is MStr -> { reg(n); out.write('"'.code); bytes(n.bytes) }
            is MArr -> {
                reg(n)
                out.write('['.code)
                long(n.items.size)
                for (x in n.items) node(x)
            }
            is MHash -> {
                reg(n)
                out.write((if (n.def != null) '}' else '{').code)
                long(n.pairs.size)
                for ((k, v) in n.pairs) { node(k); node(v) }
                n.def?.let { node(it) }
            }
            is MObj -> {
                reg(n)
                out.write('o'.code)
                symbol(n.cls)
                long(n.fields.size)
                for ((k, v) in n.fields) { symbol(k); node(v) }
            }
            is MStruct -> {
                reg(n)
                out.write('S'.code)
                symbol(n.cls)
                long(n.fields.size)
                for ((k, v) in n.fields) { symbol(k); node(v) }
            }
            is MFloat -> { reg(n); out.write('f'.code); bytes(n.raw) }
            is MBig -> {
                reg(n)
                out.write('l'.code)
                out.write(n.sign)
                long(n.data.size / 2)
                out.write(n.data, 0, n.data.size)
            }
            is MRegex -> { reg(n); out.write('/'.code); bytes(n.src); out.write(n.opts) }
            is MUserDef -> { reg(n); out.write('u'.code); symbol(n.cls); bytes(n.data) }
            is MUserMarshal -> { reg(n); out.write('U'.code); symbol(n.cls); node(n.inner) }
            is MData -> { reg(n); out.write('d'.code); symbol(n.cls); node(n.inner) }
            is MClass -> { reg(n); out.write(n.kind.code); bytes(n.name) }
        }
        if (iv != null) {
            long(iv.size)
            for ((k, v) in iv) { symbol(k); node(v) }
        }
    }
}
