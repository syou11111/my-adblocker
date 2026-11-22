package com.example.myadblocker

import com.example.myadblocker.vpn.BlocklistManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistParserTest {

    @Test
    fun parseBlocklistLines_supportsHostsAdguardAndPlainDomains() {
        val sampleLines = sequenceOf(
            "",
            "# This is a comment",
            "! AdGuard title",
            "// Slash comment",
            "@@||allowed.example.com^",
            "127.0.0.1 localhost",
            "0.0.0.0 localhost.localdomain",
            "0.0.0.0 ads.hosts.example.com # inline comment",
            "127.0.0.1 tracker.hosts.example.com",
            "::1 ipv6.ads.example.com",
            "||banner.adguard.example.com^",
            "||popup.adguard.example.com^\$third-party",
            "||example.com/path/ad.js^",
            "plain.adserver.example.org",
            "INVALID_NO_DOT"
        )

        val parsed = BlocklistManager.parseBlocklistLines(sampleLines)

        assertEquals(
            setOf(
                "ads.hosts.example.com",
                "tracker.hosts.example.com",
                "ipv6.ads.example.com",
                "banner.adguard.example.com",
                "popup.adguard.example.com",
                "plain.adserver.example.org"
            ),
            parsed
        )
    }

    @Test
    fun normalizeDomainInput_extractsHostFromUrlOrPlain() {
        assertEquals("ads.example.com", BlocklistManager.normalizeDomainInput("https://Ads.Example.COM/banner?id=1"))
        assertEquals("sub.test.jp", BlocklistManager.normalizeDomainInput("  sub.test.jp.  "))
        assertEquals("tracker.net", BlocklistManager.normalizeDomainInput("tracker.net:8080/log"))
    }

    @Test
    fun whitelist_overridesParentDomainBlock() {
        val blocked = setOf("doubleclick.net", "ads.example.com")
        val whitelist = setOf("safe.doubleclick.net")

        assertTrue(BlocklistManager.evaluateBlocked("ad.doubleclick.net", blocked, whitelist))
        assertFalse(BlocklistManager.evaluateBlocked("safe.doubleclick.net", blocked, whitelist))
        assertFalse(BlocklistManager.evaluateBlocked("sub.safe.doubleclick.net", blocked, whitelist))
        assertFalse(BlocklistManager.evaluateBlocked("example.com", blocked, whitelist))
    }
}
