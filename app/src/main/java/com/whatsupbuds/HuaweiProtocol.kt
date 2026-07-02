package com.whatsupbuds

/**
 * Huawei proprietary SPP/"MDN" protocol — packet build & parse.
 *
 * Packet layout:
 *   | 0      | 1  | Constant 0x5A                                            |
 *   | 1      | 2  | Data length, big-endian = (params length + 3)           |
 *   | 3      | 1  | Constant 0x00                                           |
 *   | 4      | 2  | Command ID (big-endian)                                 |
 *   | 6      | N  | Parameters, TLV encoded (type:1, length:1, value:length)|
 *   | 6+N    | 2  | CRC16/XMODEM of all preceding bytes (big-endian)        |
 *
 * "Data length" covers byte 3 (0x00) + the 2 command bytes + the params,
 * i.e. everything between the length field and the CRC. Hence params + 3.
 */
object HuaweiProtocol {

    const val START_BYTE: Int = 0x5A

    /** Battery request AND its response share this command ID. */
    const val CMD_BATTERY: Int = 0x0108

    /** Unsolicited push emitted by the device on battery change. Same body. */
    const val CMD_BATTERY_PUSH: Int = 0x0127

    private const val PARAM_BATTERY: Int = 0x02   // L, R, Case percent
    private const val PARAM_CHARGING: Int = 0x03  // L, R, Case charging flag

    /** Sentinel for "component value not present / unparseable". */
    const val NA: Int = -1

    data class BatteryInfo(
        val leftPercent: Int,
        val rightPercent: Int,
        val casePercent: Int,
        val leftCharging: Boolean,
        val rightCharging: Boolean,
        val caseCharging: Boolean,
    )

    /**
     * Build a full framed packet (start byte + length + 0x00 + command +
     * params + CRC) ready to write to the RFCOMM socket.
     */
    fun buildPacket(commandId: Int, params: ByteArray = ByteArray(0)): ByteArray {
        val dataLen = params.size + 3
        val out = ByteArray(3 + dataLen + 2)
        out[0] = START_BYTE.toByte()
        out[1] = ((dataLen ushr 8) and 0xFF).toByte()
        out[2] = (dataLen and 0xFF).toByte()
        out[3] = 0x00
        out[4] = ((commandId ushr 8) and 0xFF).toByte()
        out[5] = (commandId and 0xFF).toByte()
        System.arraycopy(params, 0, out, 6, params.size)
        val crc = Crc16.xmodem(out, 0, out.size - 2)
        out[out.size - 2] = ((crc ushr 8) and 0xFF).toByte()
        out[out.size - 1] = (crc and 0xFF).toByte()
        return out
    }

    /**
     * Battery request: command 0x0108. Unlike a bare request, we send three
     * empty TLVs (types 1, 2, 3) asking the device to report each field. This
     * matches OpenFreeBuds' proven request — a paramless 0x0108 is ignored by
     * FreeBuds SE 2, which leaves the read loop waiting forever.
     *
     * Encodes to: 5A 00 09 00 01 08  01 00 02 00 03 00  <crc16>
     */
    fun buildBatteryRequest(): ByteArray = buildPacket(
        CMD_BATTERY,
        byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x03, 0x00),
    )

    /** Command ID of a received packet, or null if too short. */
    fun commandIdOf(packet: ByteArray): Int? {
        if (packet.size < 6) return null
        return ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
    }

    /** Verify the trailing CRC16/XMODEM (big-endian) against the body. */
    fun verifyCrc(packet: ByteArray): Boolean {
        if (packet.size < 4) return false
        val calc = Crc16.xmodem(packet, 0, packet.size - 2)
        val given = ((packet[packet.size - 2].toInt() and 0xFF) shl 8) or
            (packet[packet.size - 1].toInt() and 0xFF)
        return calc == given
    }

    /**
     * Parse a battery response/push. Defensive: bounds-checked TLV walking,
     * returns null on anything unexpected, and leaves individual components
     * as [NA] if their parameter is missing or too short.
     */
    fun parseBattery(packet: ByteArray): BatteryInfo? {
        // Minimum: 0x5A + len(2) + 0x00 + cmd(2) + crc(2) = 8 bytes
        if (packet.size < 8) return null
        if ((packet[0].toInt() and 0xFF) != START_BYTE) return null

        val dataLen = ((packet[1].toInt() and 0xFF) shl 8) or (packet[2].toInt() and 0xFF)
        val paramsStart = 6
        // Params occupy [6, 3 + dataLen). The CRC follows.
        val paramsEnd = 3 + dataLen
        if (paramsEnd < paramsStart) return null
        if (paramsEnd > packet.size - 2) return null // would run into / past CRC

        var battL = NA
        var battR = NA
        var battC = NA
        var chgL = false
        var chgR = false
        var chgC = false

        var i = paramsStart
        while (i + 2 <= paramsEnd) {
            val type = packet[i].toInt() and 0xFF
            val len = packet[i + 1].toInt() and 0xFF
            val valStart = i + 2
            if (valStart + len > paramsEnd) break // truncated TLV — stop gracefully

            when (type) {
                PARAM_BATTERY -> if (len >= 3) {
                    battL = packet[valStart].toInt() and 0xFF
                    battR = packet[valStart + 1].toInt() and 0xFF
                    battC = packet[valStart + 2].toInt() and 0xFF
                }
                PARAM_CHARGING -> if (len >= 3) {
                    chgL = (packet[valStart].toInt() and 0xFF) == 1
                    chgR = (packet[valStart + 1].toInt() and 0xFF) == 1
                    chgC = (packet[valStart + 2].toInt() and 0xFF) == 1
                }
            }
            i = valStart + len
        }

        // If we didn't recover a single battery figure, treat it as not a
        // battery packet we understand.
        if (battL == NA && battR == NA && battC == NA) return null

        return BatteryInfo(battL, battR, battC, chgL, chgR, chgC)
    }
}
