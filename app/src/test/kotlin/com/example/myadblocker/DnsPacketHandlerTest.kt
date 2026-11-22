package com.example.myadblocker

import com.example.myadblocker.vpn.BlocklistManager
import com.example.myadblocker.vpn.DnsPacketHandler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class DnsPacketHandlerTest {

    @Test
    fun blocklist_matchesExactAndSubdomain() {
        assertTrue(BlocklistManager.isBlocked("doubleclick.net"))
        assertTrue(BlocklistManager.isBlocked("ad.doubleclick.net"))
        assertTrue(BlocklistManager.isBlocked("PAGEAD2.GOOGLESYNDICATION.COM."))
        assertFalse(BlocklistManager.isBlocked("example.com"))
        assertFalse(BlocklistManager.isBlocked("google.com"))
    }

    @Test
    fun parseAndBuildBlockedResponse_worksForARecord() {
        val dnsQuery = buildDnsQueryBytes(txId = 0x1234, domain = "ads.example.com", qType = 1)
        val ipUdpPacket = wrapInIpv4Udp(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 53,
            payload = dnsQuery
        )

        val parsed = DnsPacketHandler.parseIpv4UdpDnsQuery(ipUdpPacket, ipUdpPacket.size)
        assertNotNull(parsed)
        assertEquals("ads.example.com", parsed!!.domain)
        assertEquals(1, parsed.qType)

        val blockedDns = DnsPacketHandler.buildBlockedDnsResponse(parsed)
        // Check TX ID and QR=1, ANCOUNT=1
        assertEquals(0x12.toByte(), blockedDns[0])
        assertEquals(0x34.toByte(), blockedDns[1])
        assertEquals(0x81.toByte(), blockedDns[2])
        assertEquals(0x80.toByte(), blockedDns[3])
        assertEquals(0x00.toByte(), blockedDns[6])
        assertEquals(0x01.toByte(), blockedDns[7])

        // Last 4 bytes should be 0.0.0.0
        val ipAnswer = blockedDns.copyOfRange(blockedDns.size - 4, blockedDns.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), ipAnswer)

        val respPacket = DnsPacketHandler.buildResponseIpUdpPacket(parsed, blockedDns)
        // Swapped IP addresses
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), respPacket.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(10, 0, 0, 2), respPacket.copyOfRange(16, 20))
    }

    private fun buildDnsQueryBytes(txId: Int, domain: String, qType: Int): ByteArray {
        val labels = domain.split(".")
        val qNameLen = labels.sumOf { 1 + it.length } + 1
        val buf = ByteBuffer.allocate(12 + qNameLen + 4)
        buf.putShort(txId.toShort())
        buf.putShort(0x0100.toShort()) // RD=1
        buf.putShort(1) // QDCOUNT=1
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        for (label in labels) {
            buf.put(label.length.toByte())
            buf.put(label.toByteArray(Charsets.US_ASCII))
        }
        buf.put(0)
        buf.putShort(qType.toShort())
        buf.putShort(1) // IN
        return buf.array()
    }

    private fun wrapInIpv4Udp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLen = 20 + 8 + payload.size
        val buf = ByteBuffer.allocate(totalLen)
        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64)
        buf.put(17) // UDP
        buf.putShort(0)
        buf.put(srcIp)
        buf.put(dstIp)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort((8 + payload.size).toShort())
        buf.putShort(0)
        buf.put(payload)
        return buf.array()
    }
}
