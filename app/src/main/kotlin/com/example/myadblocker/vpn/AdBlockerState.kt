package com.example.myadblocker.vpn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class DnsLogEntry(
    val id: Long,
    val domain: String,
    val isBlocked: Boolean,
    val timestamp: String
)

object AdBlockerState {
    private const val PREFS_NAME = "adblocker_prefs"
    private const val KEY_BLOCKED_COUNT = "blocked_count"
    private const val MAX_RECENT_LOGS = 50
    private const val MAX_DNS_LOGS = 200
    private const val PREFS_FLUSH_INTERVAL = 20L

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var logIdSeq = 0L
    private var unsavedBlockIncrements = 0L

    private val dnsLogBuffer = ArrayDeque<DnsLogEntry>(MAX_DNS_LOGS + 1)
    private val recentBlockedBuffer = ArrayDeque<String>(MAX_RECENT_LOGS + 1)

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _pauseRemainingSeconds = MutableStateFlow(0)
    val pauseRemainingSeconds: StateFlow<Int> = _pauseRemainingSeconds.asStateFlow()

    private val _blockedCount = MutableStateFlow(0L)
    val blockedCount: StateFlow<Long> = _blockedCount.asStateFlow()

    private val _recentBlocked = MutableStateFlow<List<String>>(emptyList())
    val recentBlocked: StateFlow<List<String>> = _recentBlocked.asStateFlow()

    private val _dnsLogs = MutableStateFlow<List<DnsLogEntry>>(emptyList())
    val dnsLogs: StateFlow<List<DnsLogEntry>> = _dnsLogs.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _blockedCount.value = prefs.getLong(KEY_BLOCKED_COUNT, 0L)
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
        if (!running) {
            _isPaused.value = false
            _pauseRemainingSeconds.value = 0
        }
    }

    fun setPauseState(paused: Boolean, remainingSeconds: Int) {
        _isPaused.value = paused
        _pauseRemainingSeconds.value = remainingSeconds.coerceAtLeast(0)
    }

    fun recordDnsQuery(context: Context, domain: String, blocked: Boolean) {
        val now = LocalTime.now().format(timeFormatter)
        synchronized(this) {
            val entry = DnsLogEntry(
                id = ++logIdSeq,
                domain = domain,
                isBlocked = blocked,
                timestamp = now
            )
            dnsLogBuffer.addFirst(entry)
            if (dnsLogBuffer.size > MAX_DNS_LOGS) {
                dnsLogBuffer.removeLast()
            }
            if (_dnsLogs.subscriptionCount.value > 0) {
                _dnsLogs.value = dnsLogBuffer.toList()
            }

            if (blocked) {
                _blockedCount.update { it + 1L }
                unsavedBlockIncrements++
                if (unsavedBlockIncrements >= PREFS_FLUSH_INTERVAL) {
                    flushStats(context)
                }

                recentBlockedBuffer.addFirst(domain)
                if (recentBlockedBuffer.size > MAX_RECENT_LOGS) {
                    recentBlockedBuffer.removeLast()
                }
                if (_recentBlocked.subscriptionCount.value > 0) {
                    _recentBlocked.value = recentBlockedBuffer.toList()
                }
            }
        }
    }

    fun syncLogsForUi() {
        synchronized(this) {
            _dnsLogs.value = dnsLogBuffer.toList()
            _recentBlocked.value = recentBlockedBuffer.toList()
        }
    }

    fun flushStats(context: Context) {
        synchronized(this) {
            if (unsavedBlockIncrements == 0L) return
            unsavedBlockIncrements = 0L
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_BLOCKED_COUNT, _blockedCount.value)
                .apply()
        }
    }

    fun clearLogs() {
        synchronized(this) {
            dnsLogBuffer.clear()
            recentBlockedBuffer.clear()
            _dnsLogs.value = emptyList()
            _recentBlocked.value = emptyList()
        }
    }

    fun resetStats(context: Context) {
        synchronized(this) {
            unsavedBlockIncrements = 0L
            _blockedCount.value = 0L
            dnsLogBuffer.clear()
            recentBlockedBuffer.clear()
            _recentBlocked.value = emptyList()
            _dnsLogs.value = emptyList()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_BLOCKED_COUNT, 0L)
                .apply()
        }
    }
}
