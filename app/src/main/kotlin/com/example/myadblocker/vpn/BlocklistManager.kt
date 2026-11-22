package com.example.myadblocker.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

object BlocklistManager {
    private const val PREFS_NAME = "adblocker_prefs"
    private const val KEY_LAST_LIST_INFO = "last_list_info"
    private const val FILE_DOWNLOADED = "downloaded_blocklist.txt"
    private const val FILE_CUSTOM_BLACKLIST = "custom_blacklist.txt"
    private const val FILE_CUSTOM_WHITELIST = "custom_whitelist.txt"

    const val PRESET_ADGUARD_DNS = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
    const val PRESET_STEVENBLACK = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

    private val defaultBlockedDomains: Set<String> = hashSetOf(
        // Test domain
        "ads.example.com",
        "adtest.example.com",

        // Google Ads / DoubleClick / AdMob / Analytics
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "adservice.google.com",
        "adservice.google.co.jp",
        "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com",
        "ade.googlesyndication.com",
        "google-analytics.com",
        "app-measurement.com",

        // Mobile Ad Networks (Games & Apps)
        "unityads.unity3d.com",
        "ads.unity3d.com",
        "applovin.com",
        "applvn.com",
        "pangleglobal.com",
        "pangle.io",
        "vungle.com",
        "ironsrc.com",
        "supersonicads.com",
        "chartboost.com",
        "mopub.com",
        "inmobi.com",
        "adcolony.com",
        "tapjoy.com",
        "adjust.com",
        "appsflyer.com",
        "branch.io",

        // Japanese Ad Networks & Trackers
        "nend.net",
        "i-mobile.co.jp",
        "microad.jp",
        "microad.net",
        "geniee.jp",
        "gn-ad.jp",
        "adingo.jp",
        "fluct.jp",
        "adstir.com",
        "amoad.com",
        "zucks.net",
        "maist.jp",
        "fivecdm.com",
        "im-apps.net",
        "logly.co.jp",
        "popin.cc",
        "uzou.jp",
        "speee-ad.jp",
        "kau.li",
        "ladsp.com",
        "impact-ad.jp",
        "a8.net",
        "valuecommerce.com",
        "accesstrade.net",
        "rakuten-static.com",
        "yads.c.yimg.jp",
        "yads.yahoo.co.jp",

        // Global Ad / Tracking / Native Ads
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "casalemedia.com",
        "adsrvr.org",
        "scorecardresearch.com",
        "quantserve.com",
        "amazon-adsystem.com",
        "ads-twitter.com",
        "analytics.twitter.com",
        "pixel.facebook.com",
        "an.facebook.com"
    )

    private val reservedHosts: Set<String> = hashSetOf(
        "localhost",
        "localhost.localdomain",
        "local",
        "broadcasthost",
        "ip6-localhost",
        "ip6-loopback",
        "0.0.0.0",
        "127.0.0.1",
        "255.255.255.255"
    )

    private val hostsPrefixes: Set<String> = hashSetOf("0.0.0.0", "127.0.0.1", "::", "::1")

    @Volatile
    private var initialized = false

    @Volatile
    private var downloadedSet: Set<String> = emptySet()

    @Volatile
    private var effectiveBlockedSet: Set<String> = defaultBlockedDomains

    @Volatile
    private var whitelistSet: Set<String> = emptySet()

    private val customBlacklistItems = LinkedHashSet<String>()
    private val customWhitelistItems = LinkedHashSet<String>()

    private val _totalRuleCount = MutableStateFlow(defaultBlockedDomains.size)
    val totalRuleCount: StateFlow<Int> = _totalRuleCount.asStateFlow()

    private val _downloadedRuleCount = MutableStateFlow(0)
    val downloadedRuleCount: StateFlow<Int> = _downloadedRuleCount.asStateFlow()

    private val _lastUpdatedInfo = MutableStateFlow("未取得")
    val lastUpdatedInfo: StateFlow<String> = _lastUpdatedInfo.asStateFlow()

    private val _customBlacklist = MutableStateFlow<List<String>>(emptyList())
    val customBlacklist: StateFlow<List<String>> = _customBlacklist.asStateFlow()

    private val _customWhitelist = MutableStateFlow<List<String>>(emptyList())
    val customWhitelist: StateFlow<List<String>> = _customWhitelist.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext

            val downloadedFile = File(appContext.filesDir, FILE_DOWNLOADED)
            if (downloadedFile.exists()) {
                downloadedSet = downloadedFile.bufferedReader().useLines { parseBlocklistLines(it) }
            }

            val blacklistFile = File(appContext.filesDir, FILE_CUSTOM_BLACKLIST)
            if (blacklistFile.exists()) {
                customBlacklistItems.clear()
                blacklistFile.readLines().forEach { line ->
                    val normalized = normalizeDomainInput(line)
                    if (normalized.isNotEmpty()) customBlacklistItems.add(normalized)
                }
            }

            val whitelistFile = File(appContext.filesDir, FILE_CUSTOM_WHITELIST)
            if (whitelistFile.exists()) {
                customWhitelistItems.clear()
                whitelistFile.readLines().forEach { line ->
                    val normalized = normalizeDomainInput(line)
                    if (normalized.isNotEmpty()) customWhitelistItems.add(normalized)
                }
            }

            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _lastUpdatedInfo.value = prefs.getString(KEY_LAST_LIST_INFO, "未取得") ?: "未取得"

            rebuildSets()
            initialized = true
        }
    }

    private fun rebuildSets() {
        val mergedBlocked = HashSet<String>(defaultBlockedDomains.size + downloadedSet.size + customBlacklistItems.size)
        mergedBlocked.addAll(defaultBlockedDomains)
        mergedBlocked.addAll(downloadedSet)
        mergedBlocked.addAll(customBlacklistItems)

        val mergedWhitelist = HashSet<String>(customWhitelistItems)

        effectiveBlockedSet = mergedBlocked
        whitelistSet = mergedWhitelist

        _downloadedRuleCount.value = downloadedSet.size
        _totalRuleCount.value = mergedBlocked.size
        _customBlacklist.value = customBlacklistItems.toList()
        _customWhitelist.value = customWhitelistItems.toList()
    }

    /**
     * Checks if [domain] is allowed by whitelist or blocked by blocklist.
     * Whitelist always takes precedence over blocklist.
     */
    fun isBlocked(domain: String): Boolean {
        val normalized = domain.lowercase().trimEnd('.')
        return evaluateBlocked(normalized, effectiveBlockedSet, whitelistSet)
    }

    internal fun evaluateBlocked(
        normalizedDomain: String,
        blockedSet: Set<String>,
        allowSet: Set<String>
    ): Boolean {
        if (normalizedDomain.isEmpty()) return false
        if (matchesDomainOrParent(normalizedDomain, allowSet)) {
            return false
        }
        return matchesDomainOrParent(normalizedDomain, blockedSet)
    }

    private fun matchesDomainOrParent(normalizedDomain: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var current = normalizedDomain
        while (current.isNotEmpty()) {
            if (set.contains(current)) {
                return true
            }
            val dotIndex = current.indexOf('.')
            if (dotIndex < 0) break
            current = current.substring(dotIndex + 1)
        }
        return false
    }

    fun getDomainCount(): Int = effectiveBlockedSet.size

    internal fun parseBlocklistLines(lines: Sequence<String>): Set<String> {
        val result = HashSet<String>()
        for (rawLine in lines) {
            var line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith("!") || line.startsWith("//") || line.startsWith("@@")) {
                continue
            }

            // Strip inline comments
            val commentIdx = line.indexOf('#')
            if (commentIdx >= 0) {
                line = line.substring(0, commentIdx).trim()
                if (line.isEmpty()) continue
            }

            val candidate: String? = when {
                line.startsWith("||") -> {
                    val rest = line.substring(2)
                    val caretIdx = rest.indexOf('^')
                    if (caretIdx > 0) {
                        val domainPart = rest.substring(0, caretIdx)
                        if (domainPart.contains('/') || domainPart.contains('*') || domainPart.any { it.isWhitespace() }) {
                            null
                        } else {
                            domainPart
                        }
                    } else {
                        null
                    }
                }
                else -> {
                    val tokens = line.split(Regex("\\s+"))
                    if (tokens.size >= 2 && hostsPrefixes.contains(tokens[0])) {
                        tokens[1]
                    } else if (tokens.size == 1) {
                        tokens[0]
                    } else {
                        null
                    }
                }
            }

            val normalized = candidate?.lowercase()?.trimEnd('.') ?: continue
            if (isValidDomain(normalized)) {
                result.add(normalized)
            }
        }
        return result
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false
        if (reservedHosts.contains(domain)) return false
        if (!domain.contains('.')) return false
        if (domain.startsWith(".") || domain.endsWith(".")) return false
        if (domain.contains('/') || domain.contains('*') || domain.contains('$') || domain.contains(':')) {
            return false
        }
        return domain.all { ch ->
            (ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '.' || ch == '_'
        }
    }

    suspend fun downloadFromUrl(
        context: Context,
        urlString: String,
        sourceLabel: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        init(context)
        val trimmedUrl = urlString.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return@withContext Result.failure(IllegalArgumentException("URLは http:// または https:// で始まる必要があります"))
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL(trimmedUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MyAdBlocker/1.0")
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(IllegalStateException("HTTPエラー: $code"))
            }

            val parsed = connection.inputStream.bufferedReader().useLines { lines ->
                parseBlocklistLines(lines)
            }
            if (parsed.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("有効なドメインが1件も見つかりませんでした"))
            }

            val appContext = context.applicationContext
            synchronized(this@BlocklistManager) {
                val file = File(appContext.filesDir, FILE_DOWNLOADED)
                file.bufferedWriter().use { writer ->
                    for (domain in parsed) {
                        writer.write(domain)
                        writer.newLine()
                    }
                }
                downloadedSet = parsed

                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
                val info = "$sourceLabel ($timestamp)"
                _lastUpdatedInfo.value = info
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_LIST_INFO, info)
                    .apply()

                rebuildSets()
            }

            Result.success(parsed.size)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun download280Blocker(context: Context): Result<Int> {
        val currentYm = YearMonth.now()
        val formatter = DateTimeFormatter.ofPattern("yyyyMM")
        val currentUrl = "https://280blocker.net/files/280blocker_domain_${currentYm.format(formatter)}.txt"
        val currentResult = downloadFromUrl(context, currentUrl, "280blocker")
        if (currentResult.isSuccess) return currentResult

        val prevYm = currentYm.minusMonths(1)
        val prevUrl = "https://280blocker.net/files/280blocker_domain_${prevYm.format(formatter)}.txt"
        return downloadFromUrl(context, prevUrl, "280blocker")
    }

    fun clearDownloadedList(context: Context) {
        init(context)
        val appContext = context.applicationContext
        synchronized(this) {
            val file = File(appContext.filesDir, FILE_DOWNLOADED)
            if (file.exists()) file.delete()
            downloadedSet = emptySet()
            _lastUpdatedInfo.value = "未取得"
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_LIST_INFO, "未取得")
                .apply()
            rebuildSets()
        }
    }

    fun addCustomBlacklist(context: Context, input: String): Boolean {
        init(context)
        val domain = normalizeDomainInput(input)
        if (!isValidDomain(domain)) return false
        val appContext = context.applicationContext
        synchronized(this) {
            if (customBlacklistItems.add(domain)) {
                saveLines(File(appContext.filesDir, FILE_CUSTOM_BLACKLIST), customBlacklistItems)
                rebuildSets()
            }
        }
        return true
    }

    fun removeCustomBlacklist(context: Context, domain: String) {
        init(context)
        val appContext = context.applicationContext
        synchronized(this) {
            if (customBlacklistItems.remove(domain.lowercase())) {
                saveLines(File(appContext.filesDir, FILE_CUSTOM_BLACKLIST), customBlacklistItems)
                rebuildSets()
            }
        }
    }

    fun addCustomWhitelist(context: Context, input: String): Boolean {
        init(context)
        val domain = normalizeDomainInput(input)
        if (!isValidDomain(domain)) return false
        val appContext = context.applicationContext
        synchronized(this) {
            if (customWhitelistItems.add(domain)) {
                saveLines(File(appContext.filesDir, FILE_CUSTOM_WHITELIST), customWhitelistItems)
                rebuildSets()
            }
        }
        return true
    }

    fun removeCustomWhitelist(context: Context, domain: String) {
        init(context)
        val appContext = context.applicationContext
        synchronized(this) {
            if (customWhitelistItems.remove(domain.lowercase())) {
                saveLines(File(appContext.filesDir, FILE_CUSTOM_WHITELIST), customWhitelistItems)
                rebuildSets()
            }
        }
    }

    private fun saveLines(file: File, items: Collection<String>) {
        file.bufferedWriter().use { writer ->
            for (item in items) {
                writer.write(item)
                writer.newLine()
            }
        }
    }

    internal fun normalizeDomainInput(raw: String): String {
        val trimmed = raw.trim().lowercase()
        if (trimmed.isEmpty()) return ""
        val host = if (trimmed.contains("://")) {
            try {
                URI(trimmed).host ?: trimmed
            } catch (_: Exception) {
                trimmed
            }
        } else {
            trimmed.substringBefore('/').substringBefore('?').substringBefore(':')
        }
        return host.trimEnd('.')
    }
}
