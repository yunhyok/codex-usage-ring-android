package io.github.yunhyok.usagering

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.text.DateFormat
import java.util.Date
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import io.github.yunhyok.usagering.app.AppGraph
import io.github.yunhyok.usagering.data.MockUsageRepository
import io.github.yunhyok.usagering.data.LoginFailureKind
import io.github.yunhyok.usagering.data.LoginAction
import io.github.yunhyok.usagering.data.LoginState
import io.github.yunhyok.usagering.data.classifyLoginFailure
import io.github.yunhyok.usagering.domain.UsageQuality
import io.github.yunhyok.usagering.domain.UsageSnapshot
import io.github.yunhyok.usagering.domain.UsageWindowData
import io.github.yunhyok.usagering.domain.remainingPercent
import io.github.yunhyok.usagering.domain.selectUsage
import io.github.yunhyok.usagering.notification.UsageNotificationPublisher
import io.github.yunhyok.usagering.worker.UsageWorkScheduler
import io.github.yunhyok.usagering.widget.UsageRingWidget
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        lifecycleScope.launch {
            UsageWorkScheduler.setNotificationsEnabled(this@MainActivity, granted)
            if (granted) {
                UsageWorkScheduler.schedule(this@MainActivity, UsageWorkScheduler.savedInterval(this@MainActivity))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) { AppGraph.initialize(this@MainActivity) }
        lifecycleScope.launch {
            UsageWorkScheduler.setBootRestoreEnabled(this@MainActivity, true)
            UsageWorkScheduler.schedule(this@MainActivity, UsageWorkScheduler.savedInterval(this@MainActivity))
        }
        setContent { UsageRingTheme { UsageRingApp(onEnableUpdates = ::enableUpdates) } }
    }

    private fun enableUpdates() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            lifecycleScope.launch {
                UsageWorkScheduler.setNotificationsEnabled(this@MainActivity, true)
                UsageWorkScheduler.schedule(this@MainActivity, UsageWorkScheduler.savedInterval(this@MainActivity))
            }
        }
    }
}

@androidx.compose.runtime.Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun UsageRingApp(onEnableUpdates: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<UsageSnapshot?>(null) }
    var selected by remember { mutableStateOf(selectUsage(null, System.currentTimeMillis())) }
    fun refreshImmediately() {
        scope.launch {
            snapshot = AppGraph.usageRepository(context).refresh()
            selected = selectUsage(snapshot, System.currentTimeMillis())
            UsageRingWidget().updateAll(context)
            if (UsageWorkScheduler.notificationsEnabled(context)) {
                snapshot?.let { UsageNotificationPublisher(context).publish(it) }
            }
        }
    }
    LaunchedEffect(Unit) {
        snapshot = AppGraph.usageRepository(context).read()
        if (snapshot == null && BuildConfig.FLAVOR == "mock") {
            snapshot = AppGraph.usageRepository(context).refresh()
        }
        selected = selectUsage(snapshot, System.currentTimeMillis())
    }
    var tab by remember { mutableIntStateOf(0) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.dashboard)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.settings)) })
            }
            if (tab == 0) Dashboard(snapshot, selected, ::refreshImmediately) else {
                val loginOperations = AppGraph.loginOperations(context)
                val loginAction by loginOperations.actionFlow.collectAsState()
                Settings(
                    onEnableUpdates,
                    ::refreshImmediately,
                    loginAction = loginAction,
                    onBeginLogin = { loginOperations.start() },
                    onCancelLogin = { loginOperations.cancel() },
                    onLogout = { loginOperations.logout() },
                    onRetryLogout = { loginOperations.retryLogout() },
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Dashboard(snapshot: UsageSnapshot?, selected: io.github.yunhyok.usagering.domain.SelectedUsage, onRefresh: () -> Unit) {
    val label = selected.remainingPercent?.let { stringResource(R.string.remaining_percent, it) } ?: stringResource(R.string.unknown)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        UsageRing(selected.remainingPercent, label)
        Spacer(Modifier.height(16.dp))
        Text(when (selected.quality) {
            UsageQuality.STALE -> stringResource(R.string.stale)
            UsageQuality.ERROR -> stringResource(R.string.error)
            else -> label
        })
        WindowSummary(stringResource(R.string.five_hour_window), snapshot?.fiveHour)
        WindowSummary(stringResource(R.string.seven_day_window), snapshot?.sevenDay)
        snapshot?.let {
            val ageMinutes = ((System.currentTimeMillis() - it.capturedAtEpochMillis).coerceAtLeast(0L) / 60_000L).toInt()
            Text(stringResource(R.string.fetched_at, formatTime(it.capturedAtEpochMillis), ageMinutes))
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
    }
}

@androidx.compose.runtime.Composable
private fun WindowSummary(label: String, data: UsageWindowData?) {
    val expired = data?.resetAtEpochMillis?.let { it <= System.currentTimeMillis() } == true
    val remaining = if (expired) null else remainingPercent(data?.usedPercent)
    val value = remaining?.let { stringResource(R.string.remaining_percent, it) } ?: stringResource(R.string.unknown)
    val reset = data?.resetAtEpochMillis?.let { formatTime(it) } ?: stringResource(R.string.unknown)
    Text(stringResource(R.string.window_summary, label, value, reset))
}

private fun formatTime(epochMillis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMillis))

@androidx.compose.runtime.Composable
private fun UsageRing(remaining: Int?, label: String) {
    val progress = (remaining ?: 0).coerceIn(0, 100) / 100f
    Card {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Canvas(Modifier.size(150.dp).semantics { contentDescription = label }) {
                val stroke = 16.dp.toPx()
                drawArc(Color.LightGray, -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                if (remaining != null) drawArc(Color(0xFF6750A4), -90f, 360f * progress, false, style = Stroke(stroke, cap = StrokeCap.Round))
                drawCircle(Color(0xFF25232A), radius = size.minDimension * .20f)
                drawCircle(Color.White, radius = size.minDimension * .10f, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, radius = size.minDimension * .035f)
            }
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@androidx.compose.runtime.Composable
private fun Settings(
    onEnableUpdates: () -> Unit,
    onRefresh: () -> Unit,
    loginAction: LoginAction?,
    onBeginLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onLogout: () -> Unit,
    onRetryLogout: () -> Unit,
) {
    val context = LocalContext.current
    val mockRepository = if (BuildConfig.FLAVOR == "mock") AppGraph.mockRepository(context) else null
    val scope = rememberCoroutineScope()
    var interval by remember { mutableStateOf(UsageWorkScheduler.RefreshInterval.THIRTY) }
    var notifications by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        interval = UsageWorkScheduler.savedInterval(context)
        notifications = UsageWorkScheduler.notificationsEnabled(context)
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.login_device_code_description))
        Spacer(Modifier.height(12.dp))
        val loginController = remember { AppGraph.loginController(context) }
        val loginState by loginController.stateFlow.collectAsState()
        when (val current = loginState) {
            LoginState.SignedOut -> Button(
                enabled = BuildConfig.FLAVOR == "native" && loginAction == null,
                onClick = onBeginLogin,
            ) {
                Text(stringResource(if (loginAction == LoginAction.START) R.string.login_starting else R.string.login))
            }
            is LoginState.WaitingForApproval -> {
                Text(stringResource(if (loginAction == LoginAction.START) R.string.login_starting else R.string.login_waiting))
                Text(stringResource(R.string.login_verification_url, current.verificationUri))
                Text(stringResource(R.string.login_user_code, current.userCode))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = loginAction == null, onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(current.verificationUri)))
                        }
                    }) { Text(stringResource(R.string.open_browser)) }
                    Button(enabled = loginAction == null, onClick = onCancelLogin) {
                        Text(stringResource(if (loginAction == LoginAction.CANCEL) R.string.cancel_in_progress else R.string.cancel))
                    }
                }
            }
            LoginState.Authenticated -> {
                Text(stringResource(R.string.login_authenticated))
                Button(
                    enabled = BuildConfig.FLAVOR == "native" && loginAction == null,
                    onClick = onLogout,
                ) {
                    Text(stringResource(if (loginAction == LoginAction.LOGOUT) R.string.logout_in_progress else R.string.logout))
                }
            }
            is LoginState.Failed -> {
                Text(stringResource(
                    when {
                        current.code == "POLLING_SERVICE_UNAVAILABLE" -> R.string.login_polling_unavailable
                        classifyLoginFailure(current.code) == LoginFailureKind.TLS_CONNECTION -> R.string.login_tls_connection_failed
                        else -> R.string.login_failed
                    },
                ))
                val isLogoutRetry = current.code == "LOGOUT_FAILED"
                val actionInFlight = loginAction != null
                Button(
                    enabled = !actionInFlight,
                    onClick = if (isLogoutRetry) onRetryLogout else onBeginLogin,
                ) {
                    Text(stringResource(
                        when (loginAction) {
                            LoginAction.START -> R.string.login_starting
                            LoginAction.CANCEL -> R.string.cancel_in_progress
                            LoginAction.LOGOUT -> R.string.logout_in_progress
                            LoginAction.LOGOUT_RETRY -> R.string.logout_in_progress
                            null -> R.string.retry
                        },
                    ))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.permission_description))
        Button(onClick = onEnableUpdates) { Text(stringResource(R.string.enable_updates)) }
        Button(onClick = onRefresh) { Text(stringResource(R.string.refresh_now)) }
        Button(onClick = { requestPinWidget(context) }) { Text(stringResource(R.string.add_widget)) }
        Text(stringResource(R.string.refresh_interval, interval.minutes), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UsageWorkScheduler.RefreshInterval.entries.forEach { choice ->
                Button(onClick = { interval = choice; scope.launch { UsageWorkScheduler.setInterval(context, choice) } }) { Text("${choice.minutes}m") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = notifications, onCheckedChange = { enabled ->
                notifications = enabled
                if (enabled) onEnableUpdates()
                scope.launch { UsageWorkScheduler.setNotificationsEnabled(context, enabled) }
            })
            Text(stringResource(R.string.notifications_toggle))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.privacy_summary), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.license_summary), style = MaterialTheme.typography.bodySmall)
        if (mockRepository != null) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.mock_scenarios), style = MaterialTheme.typography.titleMedium)
            MockUsageRepository.Scenario.entries.forEach { scenario ->
                Button(onClick = {
                    mockRepository.scenario = scenario
                    onRefresh()
                }) { Text(scenario.label) }
            }
        }
    }
}

private fun requestPinWidget(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val manager = AppWidgetManager.getInstance(context)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(
            ComponentName(context, io.github.yunhyok.usagering.widget.UsageRingWidgetReceiver::class.java),
            null,
            null,
        )
    }
}

@androidx.compose.runtime.Composable
private fun UsageRingTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    MaterialTheme(content = content)
}
