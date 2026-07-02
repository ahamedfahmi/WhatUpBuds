package com.whatsupbuds

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ProtocolTest {

    @Test
    fun batteryRequest_hasCorrectFraming() {
        val pkt = HuaweiProtocol.buildBatteryRequest()
        // 0x5A + len(2) + 0x00 + cmd(2) + params(6) + crc(2) = 14 bytes.
        // Params are three empty TLVs: 01 00 02 00 03 00.
        assertEquals(14, pkt.size)
        assertEquals(0x5A.toByte(), pkt[0])
        assertEquals(0x00.toByte(), pkt[1]) // dataLen high
        assertEquals(0x09.toByte(), pkt[2]) // dataLen low = params(6) + 3
        assertEquals(0x00.toByte(), pkt[3])
        assertEquals(0x01.toByte(), pkt[4]) // cmd high
        assertEquals(0x08.toByte(), pkt[5]) // cmd low
        assertArrayEquals(
            byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x03, 0x00),
            pkt.copyOfRange(6, 12),
        )
        assertTrue(HuaweiProtocol.verifyCrc(pkt))
    }

    @Test
    fun crc16Xmodem_knownVector() {
        // CRC16/XMODEM of ASCII "123456789" is 0x31C3.
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x31C3, Crc16.xmodem(data))
    }

    @Test
    fun buildPacket_crcRoundTrips() {
        val params = byteArrayOf(0x02, 0x03, 82, 79, 45, 0x03, 0x03, 1, 0, 1)
        val pkt = HuaweiProtocol.buildPacket(HuaweiProtocol.CMD_BATTERY, params)
        assertTrue(HuaweiProtocol.verifyCrc(pkt))
        assertEquals(HuaweiProtocol.CMD_BATTERY, HuaweiProtocol.commandIdOf(pkt))
    }

    @Test
    fun parseBattery_extractsAllComponents() {
        // TLV: type 0x02 (battery) len 3 -> L=82 R=79 Case=45
        //      type 0x03 (charging) len 3 -> L=1 R=0 Case=1
        val params = byteArrayOf(0x02, 0x03, 82, 79, 45, 0x03, 0x03, 1, 0, 1)
        val pkt = HuaweiProtocol.buildPacket(HuaweiProtocol.CMD_BATTERY, params)

        val info = HuaweiProtocol.parseBattery(pkt)
        assertNotNull(info)
        info!!
        assertEquals(82, info.leftPercent)
        assertEquals(79, info.rightPercent)
        assertEquals(45, info.casePercent)
        assertTrue(info.leftCharging)
        assertFalse(info.rightCharging)
        assertTrue(info.caseCharging)
    }

    @Test
    fun parseBattery_pushCommandBodyIsIdentical() {
        val params = byteArrayOf(0x02, 0x03, 50, 60, 70, 0x03, 0x03, 0, 0, 0)
        val pkt = HuaweiProtocol.buildPacket(HuaweiProtocol.CMD_BATTERY_PUSH, params)
        assertEquals(HuaweiProtocol.CMD_BATTERY_PUSH, HuaweiProtocol.commandIdOf(pkt))
        val info = HuaweiProtocol.parseBattery(pkt)!!
        assertEquals(50, info.leftPercent)
        assertEquals(70, info.casePercent)
    }

    @Test
    fun parseBattery_missingChargingParam_defaultsFalse() {
        val params = byteArrayOf(0x02, 0x03, 30, 40, 50)
        val pkt = HuaweiProtocol.buildPacket(HuaweiProtocol.CMD_BATTERY, params)
        val info = HuaweiProtocol.parseBattery(pkt)!!
        assertEquals(30, info.leftPercent)
        assertFalse(info.leftCharging)
        assertFalse(info.caseCharging)
    }

    @Test
    fun parseBattery_truncatedTlv_doesNotCrash() {
        // Declares battery len 3 but only supplies 1 value byte.
        val params = byteArrayOf(0x02, 0x03, 30)
        val pkt = HuaweiProtocol.buildPacket(HuaweiProtocol.CMD_BATTERY, params)
        // No complete battery TLV recovered -> null, no exception.
        assertNull(HuaweiProtocol.parseBattery(pkt))
    }

    @Test
    fun framer_splitsBackToBackPackets() {
        val params = byteArrayOf(0x02, 0x03, 82, 79, 45, 0x03, 0x03, 1, 0, 1)
        val a = HuaweiProtocol.buildPacket(HuaweiProtocol.CMD_BATTERY, params)
        val b = HuaweiProtocol.buildPacket(HuaweiProtocol.CMD_BATTERY_PUSH, params)

        // Two packets glued together, plus a stray noise byte in front.
        val stream = ByteArrayInputStream(byteArrayOf(0x00) + a + b)
        val framer = PacketFramer(stream)

        val first = framer.readPacket()
        assertArrayEquals(a, first)
        val second = framer.readPacket()
        assertArrayEquals(b, second)
        assertNull(framer.readPacket()) // end of stream
    }

    @Test
    fun framer_handlesPacketSplitAcrossReads() {
        val params = byteArrayOf(0x02, 0x03, 10, 20, 30, 0x03, 0x03, 0, 1, 0)
        val pkt = HuaweiProtocol.buildPacket(HuaweiProtocol.CMD_BATTERY, params)

        // A stream that hands out one byte at a time, forcing multi-read reassembly.
        val slow = object : java.io.InputStream() {
            private var i = 0
            override fun read(): Int = if (i < pkt.size) pkt[i++].toInt() and 0xFF else -1
        }
        val framer = PacketFramer(slow)
        assertArrayEquals(pkt, framer.readPacket())
    }
}
