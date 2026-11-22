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

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var logIdSeq = 0L

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
        val entry = synchronized(this) {
            DnsLogEntry(
                id = ++logIdSeq,
                domain = domain,
                isBlocked = blocked,
                timestamp = now
            )
        }
        _dnsLogs.update { current ->
            (listOf(entry) + current).take(MAX_DNS_LOGS)
        }

        if (blocked) {
            _blockedCount.update { it + 1L }
            val newCount = _blockedCount.value
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_BLOCKED_COUNT, newCount)
                .apply()

            _recentBlocked.update { current ->
                (listOf(domain) + current).take(MAX_RECENT_LOGS)
            }
        }
    }

    fun clearLogs() {
        _dnsLogs.value = emptyList()
        _recentBlocked.value = emptyList()
    }

    fun resetStats(context: Context) {
        _blockedCount.value = 0L
        _recentBlocked.value = emptyList()
        _dnsLogs.value = emptyList()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_BLOCKED_COUNT, 0L)
            .apply()
    }
}
