package com.goga.carlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Minimal QR Code encoder (ISO/IEC 18004): byte mode, error correction level M, versions 1–6 (≤ 106 bytes) —
 * plenty for "http://192.168.43.10:8080". No dependencies; ~3 ms for a URL.
 */
object Qr {
    // version → (ec codewords per block, blocks, data codewords per block), level M
    private val EC = arrayOf(intArrayOf(10, 1, 16), intArrayOf(16, 1, 28), intArrayOf(26, 1, 44), intArrayOf(18, 2, 32), intArrayOf(24, 2, 43), intArrayOf(16, 4, 27))
    private val ALIGN = intArrayOf(0, 18, 22, 26, 30, 34)

    /** Returns modules[y][x] (true = dark), or null if the text is too long. */
    fun encode(text: String): Array<BooleanArray>? {
        val data = text.toByteArray(Charsets.UTF_8)
        var ver = -1
        for (v in 0 until 6) if (data.size + 2 <= EC[v][1] * EC[v][2]) { ver = v; break }   // 4-bit mode + 8-bit count + 4 terminator ≈ 2 bytes
        if (ver < 0) return null
        val (ecLen, blocks, dataLen) = EC[ver].let { Triple(it[0], it[1], it[2]) }
        val capacity = blocks * dataLen
        // ---- bit stream ----
        val bits = ArrayList<Boolean>()
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((v ushr i) and 1 == 1) }
        put(0b0100, 4); put(data.size, 8)
        for (b in data) put(b.toInt() and 0xFF, 8)
        put(0, minOf(4, capacity * 8 - bits.size))
        while (bits.size % 8 != 0) bits.add(false)
        val cw = IntArray(capacity)
        for (i in 0 until bits.size / 8) { var v = 0; for (k in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + k]) 1 else 0); cw[i] = v }
        var pad = 0
        for (i in bits.size / 8 until capacity) { cw[i] = if (pad++ % 2 == 0) 0xEC else 0x11 }
        // ---- Reed–Solomon per block, then interleave ----
        val gen = rsGenerator(ecLen)
        val dataBlocks = Array(blocks) { b -> cw.copyOfRange(b * dataLen, (b + 1) * dataLen) }
        val ecBlocks = Array(blocks) { b -> rsRemainder(dataBlocks[b], gen) }
        val stream = ArrayList<Int>()
        for (i in 0 until dataLen) for (b in 0 until blocks) stream.add(dataBlocks[b][i])
        for (i in 0 until ecLen) for (b in 0 until blocks) stream.add(ecBlocks[b][i])
        // ---- matrix ----
        val size = 17 + 4 * (ver + 1)
        val m = Array(size) { BooleanArray(size) }
        val fixed = Array(size) { BooleanArray(size) }
        fun set(x: Int, y: Int, d: Boolean) { m[y][x] = d; fixed[y][x] = true }
        fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx; val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val d = maxOf(Math.abs(dx), Math.abs(dy))
                set(x, y, d != 2 && d != 4)
            }
        }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
        for (i in 8 until size - 8) { set(i, 6, i % 2 == 0); set(6, i, i % 2 == 0) }
        if (ver > 0) { val p = ALIGN[ver]; for (dy in -2..2) for (dx in -2..2) set(p + dx, p + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1) }
        set(8, size - 8, true)   // dark module
        // reserve format areas
        for (i in 0..8) { if (!fixed[8][i]) set(i, 8, false); if (!fixed[i][8]) set(8, i, false) }
        for (i in 0 until 8) { set(size - 1 - i, 8, false); set(8, size - 1 - i, false) }
        set(8, size - 8, true)
        // data placement (zig-zag)
        val allBits = ArrayList<Boolean>(stream.size * 8)
        for (v in stream) for (k in 7 downTo 0) allBits.add((v ushr k) and 1 == 1)
        var idx = 0
        var right = size - 1
        var up = true
        while (right > 0) {
            if (right == 6) right--
            for (j in 0 until size) {
                val y = if (up) size - 1 - j else j
                for (dx in 0..1) {
                    val x = right - dx
                    if (fixed[y][x]) continue
                    m[y][x] = if (idx < allBits.size) allBits[idx] else false
                    idx++
                }
            }
            up = !up; right -= 2
        }
        // choose the mask with the lowest penalty
        var best: Array<BooleanArray>? = null; var bestScore = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val t = Array(size) { m[it].copyOf() }
            for (y in 0 until size) for (x in 0 until size) if (!fixed[y][x] && maskBit(mask, x, y)) t[y][x] = !t[y][x]
            drawFormat(t, size, mask)
            val s = penalty(t, size)
            if (s < bestScore) { bestScore = s; best = t }
        }
        return best
    }

    private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0; 1 -> y % 2 == 0; 2 -> x % 3 == 0; 3 -> (x + y) % 3 == 0
        4 -> (y / 2 + x / 3) % 2 == 0; 5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0; else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
    }

    /** 15-bit format info: level M (00) + mask, BCH(15,5) with 0x537, XOR 0x5412. */
    fun formatBits(mask: Int): Int {
        val d = (0b00 shl 3) or mask
        var r = d shl 10
        for (i in 14 downTo 10) if ((r ushr i) and 1 == 1) r = r xor (0x537 shl (i - 10))
        return ((d shl 10) or r) xor 0x5412
    }

    private fun drawFormat(t: Array<BooleanArray>, size: Int, mask: Int) {
        val f = formatBits(mask)
        fun bit(i: Int) = (f ushr i) and 1 == 1
        // around the top-left finder
        for (i in 0..5) t[i][8] = bit(i)
        t[7][8] = bit(6); t[8][8] = bit(7); t[8][7] = bit(8)
        for (i in 9..14) t[8][14 - i] = bit(i)
        // split copy: bottom-left (bits 0..6) and top-right (bits 7..14)
        for (i in 0..7) t[8][size - 1 - i] = bit(i)
        for (i in 8..14) t[size - 15 + i][8] = bit(i)
        t[size - 8][8] = true
    }

    private fun penalty(t: Array<BooleanArray>, n: Int): Int {
        var s = 0
        for (y in 0 until n) { var run = 1; for (x in 1 until n) { if (t[y][x] == t[y][x - 1]) { run++; if (run == 5) s += 3 else if (run > 5) s++ } else run = 1 } }
        for (x in 0 until n) { var run = 1; for (y in 1 until n) { if (t[y][x] == t[y - 1][x]) { run++; if (run == 5) s += 3 else if (run > 5) s++ } else run = 1 } }
        for (y in 0 until n - 1) for (x in 0 until n - 1) { val c = t[y][x]; if (c == t[y][x + 1] && c == t[y + 1][x] && c == t[y + 1][x + 1]) s += 3 }
        val pat = booleanArrayOf(true, false, true, true, true, false, true)
        for (y in 0 until n) for (x in 0..n - 7) {
            var h = true; var v = true
            for (k in 0 until 7) { if (t[y][x + k] != pat[k]) h = false; if (t[x + k][y] != pat[k]) v = false }
            if (h) s += 40; if (v) s += 40
        }
        var dark = 0; for (row in t) for (b in row) if (b) dark++
        s += Math.abs(dark * 20 - n * n * 10) / (n * n) * 10
        return s
    }

    // ---- GF(256) with polynomial 0x11D ----
    private val EXP = IntArray(512); private val LOG = IntArray(256)
    init { var x = 1; for (i in 0 until 255) { EXP[i] = x; LOG[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }; for (i in 255 until 512) EXP[i] = EXP[i - 255] }
    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
    private fun rsGenerator(deg: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until deg) {
            val n = IntArray(g.size + 1)
            for (j in g.indices) { n[j] = n[j] xor g[j]; n[j + 1] = n[j + 1] xor mul(g[j], EXP[i]) }
            g = n
        }
        return g
    }
    private fun rsRemainder(data: IntArray, gen: IntArray): IntArray {
        val deg = gen.size - 1
        val r = IntArray(deg)
        for (d in data) {
            val f = d xor r[0]
            System.arraycopy(r, 1, r, 0, deg - 1); r[deg - 1] = 0
            for (k in 0 until deg) r[k] = r[k] xor mul(gen[k + 1], f)
        }
        return r
    }
}

/** Draws a QR code with a quiet zone (4 modules), black on white. Static — drawn once. */
class QrView(c: Context, text: String) : View(c) {
    private val m = Qr.encode(text)
    private val p = Paint().apply { color = Color.BLACK; isAntiAlias = false }
    override fun onDraw(cv: Canvas) {
        cv.drawColor(Color.WHITE)
        val mm = m ?: return
        val n = mm.size + 8
        val cell = minOf(width, height) / n.toFloat()
        val ox = (width - cell * n) / 2f + cell * 4; val oy = (height - cell * n) / 2f + cell * 4
        for (y in mm.indices) for (x in mm.indices) if (mm[y][x]) cv.drawRect(ox + x * cell, oy + y * cell, ox + (x + 1) * cell + 0.5f, oy + (y + 1) * cell + 0.5f, p)
    }
}
