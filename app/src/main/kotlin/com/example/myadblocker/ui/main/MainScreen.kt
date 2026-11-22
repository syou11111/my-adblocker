package com.example.myadblocker.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myadblocker.R
import com.example.myadblocker.ui.theme.MyAdBlockerTheme
import com.example.myadblocker.vpn.AdBlockerState
import com.example.myadblocker.vpn.AdBlockerVpnService
import com.example.myadblocker.vpn.BlocklistManager
import com.example.myadblocker.vpn.DnsLogEntry
import com.example.myadblocker.vpn.ExcludedAppsManager
import com.example.myadblocker.vpn.InstalledAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onToggleVpn: (Boolean) -> Unit,
    onResetStats: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val isRunning by AdBlockerState.isRunning.collectAsStateWithLifecycle()
    val isPaused by AdBlockerState.isPaused.collectAsStateWithLifecycle()
    val pauseRemainingSeconds by AdBlockerState.pauseRemainingSeconds.collectAsStateWithLifecycle()
    val blockedCount by AdBlockerState.blockedCount.collectAsStateWithLifecycle()
    val recentBlocked by AdBlockerState.recentBlocked.collectAsStateWithLifecycle()
    val dnsLogs by AdBlockerState.dnsLogs.collectAsStateWithLifecycle()

    val totalRuleCount by BlocklistManager.totalRuleCount.collectAsStateWithLifecycle()
    val downloadedRuleCount by BlocklistManager.downloadedRuleCount.collectAsStateWithLifecycle()
    val lastUpdatedInfo by BlocklistManager.lastUpdatedInfo.collectAsStateWithLifecycle()
    val customBlacklist by BlocklistManager.customBlacklist.collectAsStateWithLifecycle()
    val customWhitelist by BlocklistManager.customWhitelist.collectAsStateWithLifecycle()
    val excludedPackages by ExcludedAppsManager.excludedPackages.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabTitles = listOf("ホーム", "DNSログ", "フィルタ", "アプリ除外")

    var isDownloading by remember { mutableStateOf(false) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }

    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) {
            ExcludedAppsManager.loadInstalledApps(context)
        }
        isLoadingApps = false
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> HomeTab(
                    isRunning = isRunning,
                    isPaused = isPaused,
                    pauseRemainingSeconds = pauseRemainingSeconds,
                    blockedCount = blockedCount,
                    totalRuleCount = totalRuleCount,
                    recentBlocked = recentBlocked,
                    onToggleVpn = { onToggleVpn(isRunning) },
                    onPause = { seconds -> AdBlockerVpnService.pause(context, seconds) },
                    onResume = { AdBlockerVpnService.resume(context) },
                    onResetStats = onResetStats,
                    onAllowDomain = { domain ->
                        if (BlocklistManager.addCustomWhitelist(context, domain)) {
                            scope.launch {
                                snackbarHostState.showSnackbar("$domain を許可リストに追加しました")
                            }
                        }
                    }
                )
                1 -> DnsLogTab(
                    logs = dnsLogs,
                    onClearLogs = { AdBlockerState.clearLogs() },
                    onAllowDomain = { domain ->
                        if (BlocklistManager.addCustomWhitelist(context, domain)) {
                            scope.launch {
                                snackbarHostState.showSnackbar("$domain を許可リストに追加しました")
                            }
                        }
                    },
                    onBlockDomain = { domain ->
                        if (BlocklistManager.addCustomBlacklist(context, domain)) {
                            scope.launch {
                                snackbarHostState.showSnackbar("$domain をブロックリストに追加しました")
                            }
                        }
                    }
                )
                2 -> FilterTab(
                    totalRuleCount = totalRuleCount,
                    downloadedRuleCount = downloadedRuleCount,
                    lastUpdatedInfo = lastUpdatedInfo,
                    customBlacklist = customBlacklist,
                    customWhitelist = customWhitelist,
                    isDownloading = isDownloading,
                    downloadMessage = downloadMessage,
                    onDownloadAdGuard = {
                        scope.launch {
                            isDownloading = true
                            downloadMessage = "AdGuard DNS Filter を取得中..."
                            val result = BlocklistManager.downloadFromUrl(
                                context,
                                BlocklistManager.PRESET_ADGUARD_DNS,
                                "AdGuard DNS Filter"
                            )
                            isDownloading = false
                            downloadMessage = result.fold(
                                onSuccess = { count -> "✓ AdGuard DNS Filter を取得しました（${count}ドメイン）" },
                                onFailure = { err -> "✗ 取得失敗: ${err.message ?: "不明なエラー"}" }
                            )
                        }
                    },
                    onDownload280Blocker = {
                        scope.launch {
                            isDownloading = true
                            downloadMessage = "280blocker を取得中..."
                            val result = BlocklistManager.download280Blocker(context)
                            isDownloading = false
                            downloadMessage = result.fold(
                                onSuccess = { count -> "✓ 280blocker を取得しました（${count}ドメイン）" },
                                onFailure = { err -> "✗ 取得失敗: ${err.message ?: "不明なエラー"}" }
                            )
                        }
                    },
                    onDownloadStevenBlack = {
                        scope.launch {
                            isDownloading = true
                            downloadMessage = "StevenBlack Hosts を取得中..."
                            val result = BlocklistManager.downloadFromUrl(
                                context,
                                BlocklistManager.PRESET_STEVENBLACK,
                                "StevenBlack Hosts"
                            )
                            isDownloading = false
                            downloadMessage = result.fold(
                                onSuccess = { count -> "✓ StevenBlack Hosts を取得しました（${count}ドメイン）" },
                                onFailure = { err -> "✗ 取得失敗: ${err.message ?: "不明なエラー"}" }
                            )
                        }
                    },
                    onDownloadCustomUrl = { url ->
                        scope.launch {
                            isDownloading = true
                            downloadMessage = "カスタムURLから取得中..."
                            val result = BlocklistManager.downloadFromUrl(context, url, "カスタムURL")
                            isDownloading = false
                            downloadMessage = result.fold(
                                onSuccess = { count -> "✓ 取得完了（${count}ドメイン）" },
                                onFailure = { err -> "✗ 取得失敗: ${err.message ?: "不明なエラー"}" }
                            )
                        }
                    },
                    onClearDownloaded = {
                        BlocklistManager.clearDownloadedList(context)
                        downloadMessage = "外部リストをクリアしました"
                    },
                    onAddBlacklist = { domain ->
                        val ok = BlocklistManager.addCustomBlacklist(context, domain)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (ok) "ブロックに追加しました" else "有効なドメインを入力してください"
                            )
                        }
                        ok
                    },
                    onRemoveBlacklist = { domain ->
                        BlocklistManager.removeCustomBlacklist(context, domain)
                    },
                    onAddWhitelist = { domain ->
                        val ok = BlocklistManager.addCustomWhitelist(context, domain)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (ok) "ホワイトリストに追加しました" else "有効なドメインを入力してください"
                            )
                        }
                        ok
                    },
                    onRemoveWhitelist = { domain ->
                        BlocklistManager.removeCustomWhitelist(context, domain)
                    }
                )
                3 -> ExcludedAppsTab(
                    apps = installedApps,
                    excludedPackages = excludedPackages,
                    isLoading = isLoadingApps,
                    onToggleApp = { packageName ->
                        ExcludedAppsManager.toggleExcluded(context, packageName)
                        AdBlockerVpnService.restartIfRunning(context)
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeTab(
    isRunning: Boolean,
    isPaused: Boolean,
    pauseRemainingSeconds: Int,
    blockedCount: Long,
    totalRuleCount: Int,
    recentBlocked: List<String>,
    onToggleVpn: () -> Unit,
    onPause: (Int) -> Unit,
    onResume: () -> Unit,
    onResetStats: () -> Unit,
    onAllowDomain: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isRunning && isPaused -> MaterialTheme.colorScheme.tertiaryContainer
                    isRunning -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_shield),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = when {
                        isRunning && isPaused -> MaterialTheme.colorScheme.tertiary
                        isRunning -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = when {
                        isRunning && isPaused -> "一時停止中（残り ${pauseRemainingSeconds}秒）"
                        isRunning -> "保護中（DNSブロック有効）"
                        else -> "停止中"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "適用中のブロックルール: ${totalRuleCount}ドメイン",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onToggleVpn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = if (isRunning) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(
                        text = if (isRunning) "ブロックを停止する" else "ブロックを開始する",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (isRunning) {
                    if (isPaused) {
                        OutlinedButton(
                            onClick = onResume,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("今すぐブロックを再開する")
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onPause(30) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text("30秒停止")
                            }
                            OutlinedButton(
                                onClick = { onPause(60) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text("1分停止")
                            }
                            OutlinedButton(
                                onClick = { onPause(180) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text("3分停止")
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "累計ブロック数",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$blockedCount 件",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(onClick = onResetStats) {
                    Text("リセット")
                }
            }
        }

        Text(
            text = "💡 通知シェードのクイック設定編集から「広告ブロック」タイルを追加すると、1タップでON/OFFできます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "直近ブロックしたドメイン",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        if (recentBlocked.isEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "まだブロック履歴はありません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(recentBlocked) { domain ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = domain,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onAllowDomain(domain) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("許可")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DnsLogTab(
    logs: List<DnsLogEntry>,
    onClearLogs: () -> Unit,
    onAllowDomain: (String) -> Unit,
    onBlockDomain: (String) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filterMode by rememberSaveable { mutableIntStateOf(0) } // 0: All, 1: Blocked, 2: Allowed

    val filteredLogs = remember(logs, searchQuery, filterMode) {
        val query = searchQuery.trim().lowercase()
        logs.filter { entry ->
            val matchesFilter = when (filterMode) {
                1 -> entry.isBlocked
                2 -> !entry.isBlocked
                else -> true
            }
            val matchesQuery = query.isEmpty() || entry.domain.lowercase().contains(query)
            matchesFilter && matchesQuery
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("ドメインで検索") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = filterMode == 0,
                onClick = { filterMode = 0 },
                label = { Text("すべて") }
            )
            FilterChip(
                selected = filterMode == 1,
                onClick = { filterMode = 1 },
                label = { Text("BLOCK") }
            )
            FilterChip(
                selected = filterMode == 2,
                onClick = { filterMode = 2 },
                label = { Text("PASS") }
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onClearLogs) {
                Text("クリア")
            }
        }

        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "DNSログはまだありません（VPN開始後に通信すると表示されます）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(filteredLogs, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (entry.isBlocked) "BLOCK" else "PASS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (entry.isBlocked) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                                Text(
                                    text = entry.timestamp,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = entry.domain,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (entry.isBlocked) {
                            TextButton(
                                onClick = { onAllowDomain(entry.domain) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("許可")
                            }
                        } else {
                            TextButton(
                                onClick = { onBlockDomain(entry.domain) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("ブロック", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun FilterTab(
    totalRuleCount: Int,
    downloadedRuleCount: Int,
    lastUpdatedInfo: String,
    customBlacklist: List<String>,
    customWhitelist: List<String>,
    isDownloading: Boolean,
    downloadMessage: String?,
    onDownloadAdGuard: () -> Unit,
    onDownload280Blocker: () -> Unit,
    onDownloadStevenBlack: () -> Unit,
    onDownloadCustomUrl: (String) -> Unit,
    onClearDownloaded: () -> Unit,
    onAddBlacklist: (String) -> Boolean,
    onRemoveBlacklist: (String) -> Unit,
    onAddWhitelist: (String) -> Boolean,
    onRemoveWhitelist: (String) -> Unit
) {
    var customUrlInput by rememberSaveable { mutableStateOf("") }
    var customDomainInput by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Public Blocklist Card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "公開ブロックリスト取得",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "総ルール数: ${totalRuleCount} / 外部取得分: ${downloadedRuleCount}ドメイン",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "最終更新: $lastUpdatedInfo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isDownloading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    downloadMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (msg.startsWith("✗")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }

                    Text(
                        text = "おすすめプリセット",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = onDownloadAdGuard,
                        enabled = !isDownloading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("AdGuard DNS Filter を取得")
                    }
                    OutlinedButton(
                        onClick = onDownload280Blocker,
                        enabled = !isDownloading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("280blocker (日本向け) を取得")
                    }
                    OutlinedButton(
                        onClick = onDownloadStevenBlack,
                        enabled = !isDownloading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("StevenBlack Hosts を取得")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    OutlinedTextField(
                        value = customUrlInput,
                        onValueChange = { customUrlInput = it },
                        label = { Text("カスタムリストURL (hosts / AdGuard形式)") },
                        placeholder = { Text("https://example.com/hosts.txt") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onDownloadCustomUrl(customUrlInput) },
                            enabled = !isDownloading && customUrlInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("URLから取得")
                        }
                        if (downloadedRuleCount > 0) {
                            OutlinedButton(
                                onClick = onClearDownloaded,
                                enabled = !isDownloading
                            ) {
                                Text("クリア")
                            }
                        }
                    }
                }
            }
        }

        // Custom Rules Card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "カスタムルール（手動追加）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ホワイトリスト（許可）はすべてのブロックルールより優先されます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customDomainInput,
                        onValueChange = { customDomainInput = it },
                        label = { Text("ドメイン名") },
                        placeholder = { Text("ads.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (onAddBlacklist(customDomainInput)) {
                                    customDomainInput = ""
                                }
                            },
                            enabled = customDomainInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ブロックに追加")
                        }
                        OutlinedButton(
                            onClick = {
                                if (onAddWhitelist(customDomainInput)) {
                                    customDomainInput = ""
                                }
                            },
                            enabled = customDomainInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("許可に追加")
                        }
                    }
                }
            }
        }

        // Manual Blacklist Section
        item {
            Text(
                text = "手動ブロック一覧 (${customBlacklist.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (customBlacklist.isEmpty()) {
            item {
                Text(
                    text = "登録なし",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(customBlacklist, key = { "bl_$it" }) { domain ->
                CustomRuleRow(
                    domain = domain,
                    onRemove = { onRemoveBlacklist(domain) }
                )
            }
        }

        // Whitelist Section
        item {
            Text(
                text = "ホワイトリスト（許可）一覧 (${customWhitelist.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (customWhitelist.isEmpty()) {
            item {
                Text(
                    text = "登録なし",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(customWhitelist, key = { "wl_$it" }) { domain ->
                CustomRuleRow(
                    domain = domain,
                    onRemove = { onRemoveWhitelist(domain) }
                )
            }
        }
    }
}

@Composable
private fun CustomRuleRow(
    domain: String,
    onRemove: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = domain,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRemove) {
                Text("削除", color = MaterialTheme.colorScheme.error)
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ExcludedAppsTab(
    apps: List<InstalledAppInfo>,
    excludedPackages: Set<String>,
    isLoading: Boolean,
    onToggleApp: (String) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val filteredApps = remember(apps, excludedPackages, searchQuery) {
        val query = searchQuery.trim().lowercase()
        apps
            .filter { app ->
                query.isEmpty() ||
                    app.label.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query)
            }
            .sortedWith(
                compareByDescending<InstalledAppInfo> { excludedPackages.contains(it.packageName) }
                    .thenBy { it.label.lowercase() }
            )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ONにしたアプリはVPNを通さず直接通信します（リワード広告を見たいゲームや銀行アプリ等向け）。切り替えは即座に反映されます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("アプリ名・パッケージ名で検索") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isExcluded = excludedPackages.contains(app.packageName)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = isExcluded,
                            onCheckedChange = { onToggleApp(app.packageName) }
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeTabPreview() {
    MyAdBlockerTheme {
        HomeTab(
            isRunning = true,
            isPaused = false,
            pauseRemainingSeconds = 0,
            blockedCount = 128,
            totalRuleCount = 45000,
            recentBlocked = listOf("pagead2.googlesyndication.com", "nend.net", "ads.example.com"),
            onToggleVpn = {},
            onPause = {},
            onResume = {},
            onResetStats = {},
            onAllowDomain = {}
        )
    }
}
