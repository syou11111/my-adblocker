package com.example.myadblocker.vpn

import java.nio.ByteBuffer

data class ParsedDnsPacket(
    val ipHeaderLen: Int,
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val dnsPayload: ByteArray,
    val domain: String,
    val qType: Int,
    val questionEndOffsetInDns: Int
)

object DnsPacketHandler {
    private const val PROTOCOL_UDP = 17
    private const val DNS_PORT = 53
    private const val QTYPE_A = 1
    private const val QTYPE_AAAA = 28

    /**
     * Parses an IPv4 UDP DNS query packet. Returns null if not a supported DNS query.
     */
    fun parseIpv4UdpDnsQuery(packet: ByteArray, length: Int): ParsedDnsPacket? {
        if (length < 28) return null

        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8 + 12) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != PROTOCOL_UDP) return null

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != DNS_PORT) return null

        val dnsStart = ihl + 8
        val dnsLen = length - dnsStart
        if (dnsLen < 12) return null

        val dnsPayload = packet.copyOfRange(dnsStart, length)

        // Check QR bit == 0 (query) and QDCOUNT >= 1
        val flags = ((dnsPayload[2].toInt() and 0xFF) shl 8) or (dnsPayload[3].toInt() and 0xFF)
        if ((flags and 0x8000) != 0) return null
        val qdCount = ((dnsPayload[4].toInt() and 0xFF) shl 8) or (dnsPayload[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        val (domain, qNameEnd) = parseQName(dnsPayload, 12) ?: return null
        if (qNameEnd + 4 > dnsPayload.size) return null

        val qType = ((dnsPayload[qNameEnd].toInt() and 0xFF) shl 8) or (dnsPayload[qNameEnd + 1].toInt() and 0xFF)
        val questionEnd = qNameEnd + 4

        return ParsedDnsPacket(
            ipHeaderLen = ihl,
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            dnsPayload = dnsPayload,
            domain = domain,
            qType = qType,
            questionEndOffsetInDns = questionEnd
        )
    }

    private fun parseQName(dns: ByteArray, startOffset: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var offset = startOffset
        var safetyCounter = 0

        while (offset < dns.size && safetyCounter++ < 128) {
            val len = dns[offset].toInt() and 0xFF
            if (len == 0) {
                return sb.toString() to (offset + 1)
            }
            if ((len and 0xC0) != 0) {
                // Compression pointer not expected in standard query QNAME
                return null
            }
            offset++
            if (offset + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) {
                sb.append((dns[offset + i].toInt() and 0xFF).toChar())
            }
            offset += len
        }
        return null
    }

    /**
     * Builds a blocked DNS response payload:
     * - 0.0.0.0 for A (type 1)
     * - :: for AAAA (type 28)
     * - NXDOMAIN for other record types (e.g. HTTPS/SVCB type 65)
     */
    fun buildBlockedDnsResponse(query: ParsedDnsPacket): ByteArray {
        val qSectionLen = query.questionEndOffsetInDns - 12
        val isA = query.qType == QTYPE_A
        val isAAAA = query.qType == QTYPE_AAAA

        val answerLen = when {
            isA -> 12 + 4     // Name ptr(2) + Type(2) + Class(2) + TTL(4) + RDLength(2) + IPv4(4)
            isAAAA -> 12 + 16 // Name ptr(2) + Type(2) + Class(2) + TTL(4) + RDLength(2) + IPv6(16)
            else -> 0
        }

        val resp = ByteArray(12 + qSectionLen + answerLen)
        val buf = ByteBuffer.wrap(resp)

        // Transaction ID
        buf.put(query.dnsPayload[0])
        buf.put(query.dnsPayload[1])

        // Flags: QR=1, RD=1, RA=1; RCODE=0 for A/AAAA, RCODE=3 (NXDOMAIN) for others
        val flags = if (isA || isAAAA) 0x8180.toShort() else 0x8183.toShort()
        buf.putShort(flags)

        // QDCOUNT = 1
        buf.putShort(1)
        // ANCOUNT = 1 if A/AAAA else 0
        buf.putShort(if (isA || isAAAA) 1 else 0)
        // NSCOUNT = 0, ARCOUNT = 0
        buf.putShort(0)
        buf.putShort(0)

        // Question section
        buf.put(query.dnsPayload, 12, qSectionLen)

        if (isA || isAAAA) {
            // Pointer to QNAME at offset 12 (0xC00C)
            buf.putShort(0xC00C.toShort())
            // Type
            buf.putShort(query.qType.toShort())
            // Class IN (1)
            buf.putShort(1)
            // TTL (60s)
            buf.putInt(60)
            // RDLENGTH + RDATA (all zeros: 0.0.0.0 or ::)
            val rdLen = if (isA) 4 else 16
            buf.putShort(rdLen.toShort())
            for (i in 0 until rdLen) {
                buf.put(0)
            }
        }

        return resp
    }

    /**
     * Wraps a DNS response payload into an IPv4 + UDP packet addressed back to the original sender.
     */
    fun buildResponseIpUdpPacket(query: ParsedDnsPacket, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val totalLen = ipHeaderLen + udpHeaderLen + dnsResponse.size
        val packet = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(packet)

        // IPv4 Header
        buf.put(0x45.toByte()) // Version 4, IHL 5
        buf.put(0x00.toByte()) // TOS
        buf.putShort(totalLen.toShort())
        buf.putShort(0x0000.toShort()) // Identification
        buf.putShort(0x4000.toShort()) // Don't Fragment
        buf.put(64.toByte()) // TTL
        buf.put(PROTOCOL_UDP.toByte()) // Protocol UDP
        buf.putShort(0) // Checksum placeholder
        buf.put(query.dstIp) // Swapped: original dst is now src
        buf.put(query.srcIp) // Swapped: original src is now dst

        // Compute IPv4 header checksum
        val ipChecksum = computeChecksum(packet, 0, ipHeaderLen)
        packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header
        val udpLen = udpHeaderLen + dnsResponse.size
        buf.putShort(query.dstPort.toShort()) // Swapped src port (53)
        buf.putShort(query.srcPort.toShort()) // Swapped dst port
        buf.putShort(udpLen.toShort())
        buf.putShort(0) // UDP checksum placeholder

        // DNS Payload
        buf.put(dnsResponse)

        // Compute UDP checksum with IPv4 pseudo-header
        val udpChecksum = computeUdpChecksum(packet, ipHeaderLen, udpLen, query.dstIp, query.srcIp)
        packet[ipHeaderLen + 6] = ((udpChecksum ushr 8) and 0xFF).toByte()
        packet[ipHeaderLen + 7] = (udpChecksum and 0xFF).toByte()

        return packet
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun computeUdpChecksum(
        packet: ByteArray,
        udpOffset: Int,
        udpLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ): Int {
        var sum = 0L
        // Pseudo-header: srcIp, dstIp, zero+protocol, udpLength
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += PROTOCOL_UDP
        sum += udpLen

        var i = udpOffset
        val end = udpOffset + udpLen
        while (i + 1 < end) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val result = (sum.inv() and 0xFFFF).toInt()
        return if (result == 0) 0xFFFF else result
    }
}
