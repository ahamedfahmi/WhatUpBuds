package com.whatsupbuds

import java.io.IOException
import java.io.InputStream

/**
 * Turns the RFCOMM byte stream into discrete packets. A single read() from the
 * socket is NOT guaranteed to align with one packet — it may contain a partial
 * packet, several packets, or split a packet mid-field. So we:
 *
 *   1. Scan forward until we hit the 0x5A start byte (resync).
 *   2. Read the 2-byte big-endian length field.
 *   3. Read exactly (dataLen + 2) more bytes: the body plus the 2 CRC bytes.
 *
 * The returned array is the complete framed packet including start byte and CRC.
 */
class PacketFramer(private val input: InputStream) {

    companion object {
        /** Sanity cap so a garbage length can't make us block forever. */
        private const val MAX_DATA_LEN = 1024
    }

    /**
     * Reads and returns the next complete packet.
     * - Returns null on end-of-stream (socket closed / disconnected).
     * - Returns an empty array when it saw a bogus length; the caller should
     *   simply skip it and call again (we've effectively resynced).
     */
    @Throws(IOException::class)
    fun readPacket(): ByteArray? {
        // 1. Resync to the start byte.
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == HuaweiProtocol.START_BYTE) break
        }

        // 2. Length field (big-endian).
        val hi = input.read()
        val lo = input.read()
        if (hi == -1 || lo == -1) return null
        val dataLen = (hi shl 8) or lo
        if (dataLen <= 0 || dataLen > MAX_DATA_LEN) {
            // Not a plausible packet — drop it, resync on the next call.
            return ByteArray(0)
        }

        // 3. Read the remaining body + CRC in full.
        val total = 3 + dataLen + 2
        val buf = ByteArray(total)
        buf[0] = HuaweiProtocol.START_BYTE.toByte()
        buf[1] = hi.toByte()
        buf[2] = lo.toByte()

        var off = 3
        while (off < total) {
            val n = input.read(buf, off, total - off)
            if (n == -1) return null // stream closed mid-packet
            off += n
        }
        return buf
    }
}
