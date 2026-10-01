package com.largebatata.fcmhelper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.largebatata.fcmhelper.core.EventHistoryEntry
import com.largebatata.fcmhelper.core.ReaderState
import com.largebatata.fcmhelper.core.WeChatFcmType
import com.largebatata.fcmhelper.ui.theme.ThemeMode
import com.largebatata.fcmhelper.ui.theme.ThemePreference
import com.largebatata.fcmhelper.ui.theme.WeChatFCMHelperTheme
import com.largebatata.fcmhelper.wake.EnhancedWakeManager
import com.largebatata.fcmhelper.wake.WakeOutcome
import com.largebatata.fcmhelper.wake.WakeUiState
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var readGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        EnhancedWakeManager.refresh("activity_resume")
    }

    private fun refreshPermissions() {
        readGranted = checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        when (ThemePreference.read(this)) {
            ThemeMode.SYSTEM -> setTheme(R.style.Theme_WeChatFCMHelper)
            ThemeMode.LIGHT -> setTheme(R.style.Theme_WeChatFCMHelper_Light)
            ThemeMode.DARK -> setTheme(R.style.Theme_WeChatFCMHelper_Dark)
        }
        super.onCreate(savedInstanceState)
        refreshPermissions()
        setContent {
            val setupPreference = remember { SetupPreference(this) }
            var themeMode by remember { mutableStateOf(ThemePreference.read(this)) }
            var showSetup by rememberSaveable { mutableStateOf(!setupPreference.completed) }
            var showSettings by rememberSaveable { mutableStateOf(false) }
            var showHistory by rememberSaveable { mutableStateOf(false) }
            WeChatFCMHelperTheme(themeMode = themeMode) {
                if (showSetup) {
                    SetupScreen(
                        onNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onStartListening = {
                            if (readGranted) {
                                startForegroundService(Intent(this, MonitorService::class.java))
                                setupPreference.completed = true
                                showSetup = false
                            }
                        },
                        onSkip = {
                            setupPreference.completed = true
                            showSetup = false
                        },
                    )
                } else if (showHistory) {
                    HistoryScreen(onBack = { showHistory = false })
                } else if (showSettings) {
                    SettingsScreen(
                        selectedTheme = themeMode,
                        onThemeSelected = {
                            themeMode = it
                            ThemePreference.write(this, it)
                        },
                        onBack = { showSettings = false },
                    )
                } else {
                    HomeScreen(
                        onSettings = { showSettings = true },
                        onHistory = { showHistory = true },
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SetupScreen(
        onNotificationPermission: () -> Unit,
        onStartListening: () -> Unit,
        onSkip: () -> Unit,
    ) {
        val clipboard = LocalClipboardManager.current
        BackHandler(onBack = onSkip)
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("初始设置") },
                    actions = { TextButton(onClick = onSkip) { Text("跳过") } },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Column(
                Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PrivacyAndDataSection()
                SectionCard("1 · 环境检查") {
                    DetailRow("Android", "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                    DetailRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                }
                SectionCard("2 · 通知权限") {
                    Text(
                        if (notificationsGranted) "✓ 已授权，可在锁屏时显示提醒。"
                        else "锁屏提醒需要通知权限。",
                        color = if (notificationsGranted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!notificationsGranted) {
                        TextButton(onClick = onNotificationPermission) { Text("授权通知权限") }
                    }
                }
                SectionCard("3 · READ_LOGS 授权") {
                    Text(
                        if (readGranted) "✓ 已授权"
                        else "此受限权限没有普通弹窗，需要在电脑上通过 ADB 授予。",
                        color = if (readGranted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!readGranted) {
                        Text(
                            READ_LOGS_COMMAND,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(READ_LOGS_COMMAND))
                        }) { Text("复制命令") }
                    }
                }
                SectionCard("4 · 开始监听") {
                    Text(
                        if (readGranted) "权限已就绪，可以启动前台监听服务。"
                        else "授予 READ_LOGS 后返回此页即可继续。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onStartListening,
                        enabled = readGranted,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("开始监听") }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeScreen(onSettings: () -> Unit, onHistory: () -> Unit) {
        val status by MonitorUi.status.collectAsState()
        var messages by remember { mutableStateOf(MonitorUi.messagesEnabled) }
        var calls by remember { mutableStateOf(MonitorUi.callsEnabled) }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("FCM Helper", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "微信 FCM 推送监听与提醒",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        Image(
                            painter = painterResource(R.drawable.ic_brand_symbol),
                            contentDescription = "FCM Helper 图标",
                            modifier = Modifier.padding(start = 16.dp).size(32.dp),
                        )
                    },
                    actions = { TextButton(onClick = onSettings) { Text("设置") } },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 1.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                ListeningCard(
                    running = status.serviceRunning,
                    onStart = {
                        refreshPermissions()
                        startForegroundService(Intent(this@MainActivity, MonitorService::class.java))
                    },
                    onStop = {
                        startService(
                            Intent(this@MainActivity, MonitorService::class.java)
                                .setAction(MonitorService.ACTION_STOP),
                        )
                    },
                )
                HealthCard(status)
                PermissionCard(status.serviceRunning)
                StatisticsGrid(status)
                RecentEventCard(status, onHistory)
                ReminderSettings(messages, calls, onMessages = {
                    messages = it
                    MonitorUi.messagesEnabled = it
                }, onCalls = {
                    calls = it
                    MonitorUi.callsEnabled = it
                })
            }
        }
    }

    @Composable
    private fun ListeningCard(running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (running) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1.35f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("监听状态", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (running) "●" else "○",
                            color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (running) "运行中" else "已停止", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        if (running) "正在监听微信 FCM" else "当前未监听",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onStart, enabled = !running && readGranted, modifier = Modifier.fillMaxWidth()) {
                        Text("开始监听")
                    }
                    OutlinedButton(onClick = onStop, enabled = running, modifier = Modifier.fillMaxWidth()) {
                        Text("停止监听")
                    }
                }
            }
        }
    }

    @Composable
    private fun HealthCard(status: MonitorStatus) {
        SectionCard("运行健康") {
            val health = readerHealth(status.readerState)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactMetric("监听器", health.first, health.second, Modifier.weight(1f))
                CompactMetric("最后读取", homeTime(status.lastSuccessfulLogLine), modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactMetric("重连", status.reconnectCount.toString(), modifier = Modifier.weight(1f))
                CompactMetric("最近微信 FCM", homeTime(status.lastFcm), modifier = Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun CompactMetric(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier) {
        Column(modifier) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }

    @Composable
    private fun PermissionCard(serviceRunning: Boolean) {
        val clipboard = LocalClipboardManager.current
        SectionCard("权限与服务") {
            if (readGranted) {
                DetailRow("读取系统日志", "✓ 已授权", stateColor(true))
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("读取系统日志", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(READ_LOGS_COMMAND))
                    }) { Text("复制授权命令") }
                }
            }
            if (notificationsGranted) {
                DetailRow("通知权限", "✓ 已授权", stateColor(true))
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("通知权限", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        refreshPermissions()
                        if (Build.VERSION.SDK_INT >= 33) {
                            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }) { Text("授权") }
                }
            }
            DetailRow("前台服务", if (serviceRunning) "✓ 正在运行" else "已停止", stateColor(serviceRunning))
        }
    }

    @Composable
    private fun StatisticsGrid(status: MonitorStatus) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionHeader("FCM统计", Modifier.padding(horizontal = 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatCard("普通消息", "MESSAGE", status.messageCount, Modifier.weight(1f))
                StatCard("微信电话", "CALL", status.callCount, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatCard("电脑登录", "PC_LOGIN", status.pcLoginCount, Modifier.weight(1f))
                StatCard("未知事件", "UNKNOWN", status.unknownCount, Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun StatCard(title: String, code: String, count: Int, modifier: Modifier) {
        Card(
            modifier.height(58.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                Text(
                    title,
                    modifier = Modifier.height(16.dp),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 16.sp),
                )
                Text(
                    count.toString(),
                    modifier = Modifier.height(23.dp),
                    maxLines = 1,
                    style = MaterialTheme.typography.titleLarge.copy(lineHeight = 23.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    code,
                    modifier = Modifier.height(13.dp),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun RecentEventCard(status: MonitorStatus, onClick: () -> Unit) {
        SectionCard(title = "最近事件", onClick = onClick) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (status.recentEvent == null) "暂无" else eventLabel(status.recentEvent),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (status.recentEvent != null) {
                    Text(homeTime(status.lastFcm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun ReminderSettings(
        messages: Boolean,
        calls: Boolean,
        onMessages: (Boolean) -> Unit,
        onCalls: (Boolean) -> Unit,
    ) {
        SectionCard("提醒") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactToggle("消息与电脑登录", messages, onMessages, Modifier.weight(1f))
                CompactToggle("微信电话", calls, onCalls, Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun CompactToggle(
        label: String,
        checked: Boolean,
        onChecked: (Boolean) -> Unit,
        modifier: Modifier,
    ) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Switch(checked = checked, onCheckedChange = onChecked)
        }
    }

    @Composable
    private fun ToggleRow(
        label: String,
        checked: Boolean,
        onChecked: (Boolean) -> Unit,
        enabled: Boolean = true,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
        }
    }

    @Composable
    private fun SectionCard(
        title: String,
        onClick: (() -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        Card(
            Modifier
                .fillMaxWidth()
                .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                SectionHeader(title)
                content()
            }
        }
    }

    @Composable
    private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
        Text(
            title,
            modifier = modifier.height(24.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HistoryScreen(onBack: () -> Unit) {
        val events by EventHistoryRepository.entries.collectAsState()
        LaunchedEffect(Unit) { EventHistoryRepository.refresh() }
        BackHandler(onBack = onBack)
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                    title = { Text("最近 24 小时") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                    actions = {
                        if (events.isNotEmpty()) {
                            TextButton(onClick = EventHistoryRepository::clear) { Text("清空") }
                        }
                    },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (events.isEmpty()) {
                    item {
                        SectionCard("事件历史") {
                            Text("最近 24 小时暂无事件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(events) { event -> HistoryEventRow(event) }
                }
            }
        }
    }

    @Composable
    private fun HistoryEventRow(event: EventHistoryEntry) {
        val labels = historyEventLabels(event.type)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(labels.first, style = MaterialTheme.typography.bodyMedium)
                    Text(labels.second, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(historyTime(event.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun ExpandableCard(
        title: String,
        expanded: Boolean,
        onToggle: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                    Text(title, Modifier.weight(1f), textAlign = TextAlign.Start)
                    Text(if (expanded) "收起" else "展开")
                }
                AnimatedVisibility(expanded) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        content()
                    }
                }
            }
        }
    }

    @Composable
    private fun DetailRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, color = valueColor, textAlign = TextAlign.End, modifier = Modifier.weight(1.25f))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen(
        selectedTheme: ThemeMode,
        onThemeSelected: (ThemeMode) -> Unit,
        onBack: () -> Unit,
    ) {
        val wakeState by EnhancedWakeManager.state.collectAsState()
        val status by MonitorUi.status.collectAsState()
        var advancedExpanded by rememberSaveable { mutableStateOf(false) }
        val clipboard = LocalClipboardManager.current
        LaunchedEffect(Unit) { EnhancedWakeManager.refresh("settings_enter") }
        BackHandler(onBack = onBack)
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                    title = { Text("设置") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Column(
                Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionCard("外观") {
                    ThemeSelectorRow(selectedTheme, onThemeSelected)
                }
                PrivacyAndDataSection()
                SectionCard("增强唤醒（实验性）") {
                    DetailRow("Shizuku", shizukuStatus(wakeState), stateColor(wakeState.running))
                    DetailRow(
                        "权限",
                        wakeState.permissionLabel,
                        if (wakeState.running) stateColor(wakeState.permissionGranted)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ToggleRow(
                        "微信后台唤醒",
                        wakeState.enabled,
                        EnhancedWakeManager::setEnabled,
                        enabled = wakeState.switchEnabled,
                    )
                    if (wakeState.canRequestPermission) {
                        TextButton(onClick = EnhancedWakeManager::requestPermission) { Text("授予 Shizuku 权限") }
                    }
                    wakeState.notice?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "可尝试通过 Shizuku 唤醒主微信后台。可用性因设备、系统及微信版本而异，失败不影响 FCM 监听与提醒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DetailRow("最近唤醒", wakeLastStatus(wakeState))
                }
                SectionCard("数据与统计") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = MonitorUi::clearStatistics) { Text("重置统计") }
                        TextButton(onClick = EventHistoryRepository::clear) { Text("清空历史") }
                    }
                }
                ExpandableCard("高级信息", advancedExpanded, { advancedExpanded = !advancedExpanded }) {
                    val exporter = remember { DiagnosticExporter(this@MainActivity) }
                    val app = remember { exporter.packageVersion(packageName) }
                    val weChat = remember { exporter.packageVersion("com.tencent.mm").first }
                    val microG = remember { exporter.packageVersion("com.google.android.gms").first }
                    DetailRow("FCM Helper", "${app.first} (${app.second})")
                    DetailRow("Android", "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                    DetailRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                    DetailRow("微信", weChat)
                    DetailRow("microG", microG)
                    DetailRow("READ_LOGS", if (readGranted) "已授权" else "未授权")
                    DetailRow("通知权限", if (notificationsGranted) "已授权" else "未授权")
                    DetailRow("监听器 PID", status.readerPid?.toString() ?: "无")
                    DetailRow("监听器子进程", if (status.readerAlive) "运行" else "不存在")
                    DetailRow("重连次数", status.reconnectCount.toString())
                    DetailRow("最近启动", fullTime(status.lastReaderStart))
                    DetailRow("最后成功日志", fullTime(status.lastSuccessfulLogLine))
                    DetailRow("最后微信 FCM", fullTime(status.lastFcm))
                    DetailRow("最近退出码", status.lastReaderExitCode?.toString() ?: "无")
                    DetailRow("故障类型", status.lastReaderFailureType ?: "无")
                    DetailRow("Shizuku", shizukuStatus(wakeState))
                    DetailRow("Shizuku 权限", wakeState.permissionLabel)
                    DetailRow("最近唤醒", wakeLastStatus(wakeState))
                    val summary = diagnosticSummary(status)
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(summary)) }) {
                        Text("复制脱敏诊断摘要")
                    }
                    Button(onClick = { shareDiagnostics(status, wakeState) }) {
                        Text("导出诊断信息")
                    }
                }
                SectionCard("关于") {
                    DetailRow("App / 版本", "FCM Helper · ${appVersion()}")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Package", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            packageName,
                            modifier = Modifier.weight(1.7f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PrivacyAndDataSection() {
        var showDataUse by rememberSaveable { mutableStateOf(false) }
        SectionCard(getString(R.string.privacy_section_title)) {
            Text(
                getString(R.string.privacy_section_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = ::openPrivacyPolicy,
                    modifier = Modifier.weight(0.4f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text(getString(R.string.privacy_policy_button), textAlign = TextAlign.Center)
                }
                TextButton(
                    onClick = { showDataUse = true },
                    modifier = Modifier.weight(0.6f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text(getString(R.string.permissions_data_use_title), textAlign = TextAlign.Center)
                }
            }
        }
        if (showDataUse) {
            AlertDialog(
                onDismissRequest = { showDataUse = false },
                title = { Text(getString(R.string.permissions_data_use_title)) },
                text = {
                    Text(
                        getString(R.string.permissions_data_use_body),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showDataUse = false }) {
                        Text(getString(R.string.privacy_close_button))
                    }
                },
            )
        }
    }

    private fun openPrivacyPolicy() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url))))
        }.onFailure {
            Toast.makeText(this, R.string.privacy_policy_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    private fun ThemeSelectorRow(
        selected: ThemeMode,
        onSelected: (ThemeMode) -> Unit,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "跟随系统" to ThemeMode.SYSTEM,
                "浅色" to ThemeMode.LIGHT,
                "深色" to ThemeMode.DARK,
            ).forEach { (label, mode) ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { onSelected(mode) },
                    label = { Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
    }.getOrDefault("未知")

    private fun shareDiagnostics(status: MonitorStatus, wakeState: WakeUiState) {
        runCatching {
            val file = DiagnosticExporter(this).create(status, wakeState)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val share = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(share, "导出诊断信息"))
        }.onFailure {
            Toast.makeText(this, "诊断信息导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fullTime(value: Long?): String =
        value?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it)) } ?: "尚无"

    private fun homeTime(value: Long?): String {
        value ?: return "尚无"
        val event = Calendar.getInstance().apply { timeInMillis = value }
        val today = Calendar.getInstance()
        val pattern = when {
            sameDay(event, today) -> "HH:mm:ss"
            sameDay(event, (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }) -> "'昨天' HH:mm"
            else -> "M月d日 HH:mm"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(value))
    }

    private fun sameDay(first: Calendar, second: Calendar): Boolean =
        first.get(Calendar.ERA) == second.get(Calendar.ERA) &&
            first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)

    private fun eventLabel(type: WeChatFcmType?): String = when (type) {
        WeChatFcmType.MESSAGE -> "普通消息 · MESSAGE"
        WeChatFcmType.CALL -> "微信电话 · CALL"
        WeChatFcmType.PC_LOGIN -> "电脑登录 · PC_LOGIN"
        WeChatFcmType.UNKNOWN -> "未知事件 · UNKNOWN"
        null -> "尚无事件"
    }

    private fun historyEventLabels(type: WeChatFcmType): Pair<String, String> = when (type) {
        WeChatFcmType.MESSAGE -> "普通消息" to "MESSAGE"
        WeChatFcmType.CALL -> "微信电话" to "CALL"
        WeChatFcmType.PC_LOGIN -> "电脑登录" to "PC_LOGIN"
        WeChatFcmType.UNKNOWN -> "未知事件" to "UNKNOWN"
    }

    private fun historyTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    private fun wakeHomeStatus(state: WakeUiState): String = when {
        !state.enabled -> "已关闭"
        state.ready -> "✓ 已就绪"
        else -> "Shizuku 不可用"
    }

    private fun shizukuStatus(state: WakeUiState): String = when {
        !state.installed -> "未安装"
        state.running -> "✓ 已连接"
        else -> "未运行"
    }

    private fun wakeLastStatus(state: WakeUiState): String {
        val wake = state.lastWake ?: return "暂无"
        val result = when (wake.outcome) {
            WakeOutcome.SUCCESS -> "成功"
            WakeOutcome.TIMEOUT -> "失败 · 执行超时"
            WakeOutcome.PERMISSION_DENIED -> "失败 · 权限不足"
            WakeOutcome.UNAVAILABLE -> "失败 · Shizuku不可用"
            WakeOutcome.COMPONENT_NOT_FOUND -> "失败 · 组件不存在"
            WakeOutcome.COMPONENT_NOT_EXPORTED,
            WakeOutcome.BACKGROUND_RESTRICTED -> "失败 · 不支持"
            WakeOutcome.COMMAND_FAILED,
            WakeOutcome.EXCEPTION -> "失败 · 未知错误"
            WakeOutcome.NOT_ELIGIBLE, WakeOutcome.DISABLED -> "未执行"
        }
        return "${homeTime(wake.timestamp)} · $result"
    }

    @Composable
    private fun stateColor(ok: Boolean): Color =
        if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    @Composable
    private fun readerHealth(state: ReaderState): Pair<String, Color> = when (state) {
        ReaderState.WaitingForLogs, ReaderState.Receiving -> "✓ 正常" to MaterialTheme.colorScheme.primary
        ReaderState.Starting, ReaderState.Reconnecting -> "↻ 恢复中" to MaterialTheme.colorScheme.secondary
        ReaderState.PermissionMissing, ReaderState.AccessDenied, ReaderState.Error -> "! 异常" to MaterialTheme.colorScheme.error
        ReaderState.Stopped -> "已停止" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    private fun diagnosticSummary(status: MonitorStatus): String = buildString {
        appendLine("FCM Helper 诊断摘要")
        appendLine("Service: ${if (status.serviceRunning) "RUNNING" else "STOPPED"}")
        appendLine("Reader: ${status.readerState.name}")
        appendLine("Reader alive: ${status.readerAlive}")
        appendLine("Reader PID: ${status.readerPid ?: "none"}")
        appendLine("Reconnects: ${status.reconnectCount}")
        appendLine("Last start: ${fullTime(status.lastReaderStart)}")
        appendLine("Last log: ${fullTime(status.lastSuccessfulLogLine)}")
        appendLine("Last FCM: ${fullTime(status.lastFcm)}")
        append("Failure: ${status.lastReaderFailureType ?: "none"}")
    }

    private companion object {
        const val READ_LOGS_COMMAND =
            "adb shell pm grant com.largebatata.fcmhelper android.permission.READ_LOGS"
    }
}
