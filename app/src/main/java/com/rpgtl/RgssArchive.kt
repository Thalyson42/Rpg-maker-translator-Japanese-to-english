package com.rpgtl

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Leitor dos arquivos empacotados do RPG Maker: .rgssad (XP), .rgss2a (VX) e .rgss3a (VX Ace). */
object RgssArchive {
    class Entry(val path: String, val offset: Long, val size: Int, val key: Long)

    private const val MASK = 0xFFFFFFFFL

    private fun readAt(ch: FileChannel, pos: Long, n: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        var p = pos
        while (buf.hasRemaining()) {
            val r = ch.read(buf, p)
            if (r < 0) throw IOException("Packed archive is truncated")
            p += r
        }
        buf.flip()
        return buf
    }

    private fun u32(ch: FileChannel, pos: Long): Long = readAt(ch, pos, 4).int.toLong() and MASK

    private fun next(k: Long): Long = (k * 7 + 3) and MASK

    fun list(resolver: ContentResolver, uri: Uri): List<Entry> {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Could not open the file")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            val ch = stream.channel
            val head = readAt(ch, 0, 8)
            val magic = ByteArray(6)
            head.get(magic)
            if (String(magic, Charsets.ISO_8859_1) != "RGSSAD") throw IOException("Invalid packed archive")
            head.get()
            val ver = head.get().toInt()
            val out = ArrayList<Entry>()
            val size = ch.size()
            if (ver == 1) {
                var key = 0xDEADCAFEL
                var pos = 8L
                while (pos + 4 <= size) {
                    val nameLen = (u32(ch, pos) xor key) and MASK
                    pos += 4
                    key = next(key)
                    if (nameLen > 1024) throw IOException("Packed archive is corrupted")
                    val nb = readAt(ch, pos, nameLen.toInt())
                    pos += nameLen
                    val name = ByteArray(nameLen.toInt())
                    for (i in name.indices) {
                        name[i] = (nb.get(i).toInt() xor (key and 0xFF).toInt()).toByte()
                        key = next(key)
                    }
                    val fsize = (u32(ch, pos) xor key) and MASK
                    pos += 4
                    key = next(key)
                    out.add(Entry(String(name, Charsets.UTF_8).replace('\\', '/'), pos, fsize.toInt(), key))
                    pos += fsize
                }
            } else if (ver == 3) {
                val key = (u32(ch, 8) * 9 + 3) and MASK
                var pos = 12L
                while (pos + 16 <= size) {
                    val off = (u32(ch, pos) xor key) and MASK
                    val fsize = (u32(ch, pos + 4) xor key) and MASK
                    val fkey = (u32(ch, pos + 8) xor key) and MASK
                    val nlen = (u32(ch, pos + 12) xor key) and MASK
                    pos += 16
                    if (off == 0L) break
                    if (nlen > 1024) throw IOException("Packed archive is corrupted")
                    val nb = readAt(ch, pos, nlen.toInt())
                    pos += nlen
                    val name = ByteArray(nlen.toInt())
                    for (i in name.indices) {
                        name[i] = (nb.get(i).toInt() xor ((key shr (8 * (i % 4))).toInt() and 0xFF)).toByte()
                    }
                    out.add(Entry(String(name, Charsets.UTF_8).replace('\\', '/'), off, fsize.toInt(), fkey))
                }
            } else {
                throw IOException("Unsupported packed archive version: $ver")
            }
            return out
        }
    }

    fun read(resolver: ContentResolver, uri: Uri, e: Entry): ByteArray {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Could not open the file")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            val buf = readAt(stream.channel, e.offset, e.size)
            val data = ByteArray(e.size)
            buf.get(data)
            var key = e.key and MASK
            for (i in data.indices) {
                if (i > 0 && i % 4 == 0) key = next(key)
                data[i] = (data[i].toInt() xor ((key shr (8 * (i % 4))).toInt() and 0xFF)).toByte()
            }
            return data
        }
    }
}
