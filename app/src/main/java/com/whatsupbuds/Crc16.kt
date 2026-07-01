package com.whatsupbuds

/**
 * CRC16/XMODEM: polynomial 0x1021, init 0x0000, no input/output reflection,
 * no final XOR. This is exactly what Huawei's SPP/"MDN" framing uses.
 */
object Crc16 {

    fun xmodem(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0x0000
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            for (bit in 0 until 8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
            }
            crc = crc and 0xFFFF
        }
        return crc and 0xFFFF
    }
}
