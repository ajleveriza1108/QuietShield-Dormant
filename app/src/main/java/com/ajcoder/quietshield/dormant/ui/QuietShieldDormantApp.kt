package com.ajcoder.quietshield.dormant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.ajcoder.quietshield.dormant.domain.AggressiveSuggestion
import com.ajcoder.quietshield.dormant.domain.AppPolicy
import com.ajcoder.quietshield.dormant.domain.AppRuntimeState
import com.ajcoder.quietshield.dormant.domain.AppSection
import com.ajcoder.quietshield.dormant.domain.AutoAggressiveMode
import com.ajcoder.quietshield.dormant.domain.InstalledApp
import com.ajcoder.quietshield.dormant.domain.SafetyLevel
import com.ajcoder.quietshield.dormant.domain.SleepMode
import com.ajcoder.quietshield.dormant.domain.SyncMode
import com.ajcoder.quietshield.dormant.domain.ThemeChoice
import com.ajcoder.quietshield.dormant.domain.formatMinutes
import com.ajcoder.quietshield.dormant.domain.policySummary
import com.ajcoder.quietshield.dormant.ui.theme.QuietShieldDormantTheme
import com.ajcoder.quietshield.dormant.wireless.WirelessPairingService

private val timeoutPresets = listOf(1, 2, 5, 10, 15, 30, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietShieldDormantApp(viewModel: QuietShieldViewModel) {
    val themeChoice by viewModel.theme.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showGroupEditor by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showAutomaticSetupDialog by remember { mutableStateOf(false) }
    var showResultsSheet by remember { mutableStateOf(false) }
    var pendingEnableChange by remember { mutableStateOf<Pair<InstalledApp, Boolean>?>(null) }
    var notificationPermissionDenied by remember { mutableStateOf(false) }

    val selectableApps = state.visibleApps.filter { it.section != AppSection.CORE }
    val selectedApps = state.apps.filter { it.packageName in selectedPackages }

    fun openWirelessPairingScreen() {
        WirelessPairingService.start(context)
        WirelessPairingService.openWirelessDebugging(context)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionDenied = !granted
        if (granted) openWirelessPairingScreen()
    }

    fun beginWirelessPairing() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationPermissionDenied = false
            openWirelessPairingScreen()
        }
    }

    LaunchedEffect(state.automaticClosing) {
        if (state.automaticClosing) showAutomaticSetupDialog = false
    }

    LaunchedEffect(
        state.selectedSection,
        state.query,
        state.apps,
        state.showRunningOnly,
        state.engineSnapshot,
    ) {
        val allowedPackages = state.visibleApps
            .filter { it.section != AppSection.CORE }
            .mapTo(mutableSetOf()) { it.packageName }
        selectedPackages = selectedPackages.intersect(allowedPackages)
        if (selectedPackages.isEmpty()) showGroupEditor = false
    }

    QuietShieldDormantTheme(choice = themeChoice) {
        Scaffold(
            topBar = {
                AppHeader(
                    state = state,
                    themeChoice = themeChoice,
                    onThemeSelected = viewModel::setTheme,
                    onRefresh = viewModel::refreshApps,
                    onAutomaticClosingChanged = { enabled ->
                        if (!enabled) {
                            viewModel.setAutomaticClosing(false)
                        } else if (state.setupReady && state.hasUsageAccess) {
                            viewModel.setAutomaticClosing(true)
                        } else {
                            showAutomaticSetupDialog = true
                        }
                    },
                    onAddQuickSetting = { DormantQuickTileRequest.addTile(context) },
                    onShowResults = { showResultsSheet = true },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (!state.hasUsageAccess) {
                    UsageSetupBanner(onPermissionReturned = viewModel::refreshPermissionState)
                }
                state.errorMessage?.let { message ->
                    InfoCard(
                        text = message,
                        warning = true,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    label = { Text("Search apps") },
                    singleLine = true,
                    trailingIcon = if (state.query.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = viewModel::clearQuery,
                                modifier = Modifier.semantics { contentDescription = "Clear search" },
                            ) {
                                Text("×", style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    } else {
                        null
                    },
                )

                val sections = AppSection.entries
                TabRow(selectedTabIndex = sections.indexOf(state.selectedSection)) {
                    sections.forEach { section ->
                        val count = state.apps.count { it.section == section }
                        Tab(
                            selected = state.selectedSection == section,
                            onClick = {
                                selectedPackages = emptySet()
                                viewModel.selectSection(section)
                            },
                            text = { Text("${section.title}\n$count") },
                        )
                    }
                }

                ListTools(
                    state = state,
                    apps = selectableApps,
                    selectedPackages = selectedPackages,
                    onRunningOnlyChanged = viewModel::toggleRunningOnly,
                    onSelectAll = {
                        selectedPackages = selectableApps.mapTo(mutableSetOf()) { it.packageName }
                    },
                    onClear = { selectedPackages = emptySet() },
                    onSetBehavior = { showGroupEditor = true },
                    onResetTab = { showResetDialog = true },
                )

                when {
                    state.loading -> LoadingState()
                    state.visibleApps.isEmpty() -> EmptyState(
                        text = if (state.showRunningOnly) {
                            "No apps are running in this section."
                        } else {
                            "No apps found."
                        },
                    )
                    else -> LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(bottom = 4.dp),
                    ) {
                        items(
                            items = state.visibleApps,
                            key = { it.packageName },
                        ) { app ->
                            AppRow(
                                app = app,
                                policy = state.policyFor(app),
                                runtimeState = state.runtimeStateFor(app),
                                suggestion = state.suggestions[app.packageName],
                                selected = app.packageName in selectedPackages,
                                onSelectionChange = if (app.section == AppSection.CORE) {
                                    null
                                } else {
                                    { checked ->
                                        selectedPackages = if (checked) {
                                            selectedPackages + app.packageName
                                        } else {
                                            selectedPackages - app.packageName
                                        }
                                    }
                                },
                                onClick = { selectedApp = app },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        selectedApp?.let { app ->
            ModalBottomSheet(onDismissRequest = { selectedApp = null }) {
                PolicyEditor(
                    app = app,
                    initialPolicy = state.policyFor(app),
                    runtimeState = state.runtimeStateFor(app),
                    suggestion = state.suggestions[app.packageName],
                    isDisabled = state.isDisabled(app),
                    canChangeEnabled = state.setupReady,
                    onSave = {
                        viewModel.savePolicy(it)
                        selectedApp = null
                    },
                    onAcceptSuggestion = { viewModel.acceptSuggestion(app.packageName) },
                    onDismissSuggestion = { neverAgain ->
                        viewModel.dismissSuggestion(app.packageName, neverAgain)
                    },
                    onSetEnabled = { enabled -> pendingEnableChange = app to enabled },
                    onClose = { selectedApp = null },
                )
            }
        }

        if (showGroupEditor && selectedApps.isNotEmpty()) {
            ModalBottomSheet(onDismissRequest = { showGroupEditor = false }) {
                GroupPolicyEditor(
                    apps = selectedApps,
                    onApply = { template ->
                        viewModel.savePolicies(selectedApps, template)
                        showGroupEditor = false
                        selectedPackages = emptySet()
                    },
                    onClose = { showGroupEditor = false },
                )
            }
        }

        if (showResetDialog) {
            val section = state.selectedSection
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("Reset ${section.title}?") },
                text = {
                    Text(
                        if (section == AppSection.CORE) {
                            "Core Apps are always left alone. This will clear any saved changes in this tab."
                        } else {
                            "Every app in ${section.title} will return to Leave this app alone. Other tabs will not change."
                        },
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.resetSection(section)
                            selectedPackages = emptySet()
                            showResetDialog = false
                        },
                    ) { Text("Reset") }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
                },
            )
        }


        if (showResultsSheet) {
            ModalBottomSheet(onDismissRequest = { showResultsSheet = false }) {
                BetaResultsSheet(
                    state = state,
                    onAutoAggressiveChanged = viewModel::setAutoAggressiveMode,
                    onRestoreChanged = viewModel::setRestoreAfterRestart,
                    onStartBaseline = viewModel::startBaselineTest,
                    onStopBaseline = viewModel::stopBaselineTest,
                    onForgetPairing = viewModel::forgetWirelessPairing,
                    onShareReport = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "QuietShield Dormant Beta Report")
                            putExtra(Intent.EXTRA_TEXT, viewModel.buildBetaReport())
                        }
                        context.startActivity(Intent.createChooser(share, "Share beta report"))
                    },
                    onClose = { showResultsSheet = false },
                )
            }
        }

        pendingEnableChange?.let { (app, enable) ->
            AlertDialog(
                onDismissRequest = { pendingEnableChange = null },
                title = { Text(if (enable) "Enable ${app.label}?" else "Disable ${app.label}?") },
                text = {
                    Text(
                        if (enable) {
                            "This app will be available again."
                        } else {
                            "The app may disappear and stop working until you enable it again."
                        },
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.setAppEnabled(app, enable)
                            pendingEnableChange = null
                            selectedApp = null
                        },
                    ) { Text(if (enable) "Enable" else "Disable") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingEnableChange = null }) { Text("Cancel") }
                },
            )
        }

        if (showAutomaticSetupDialog) {
            AlertDialog(
                onDismissRequest = {
                    if (!state.wirelessBusy) {
                        showAutomaticSetupDialog = false
                        viewModel.clearWirelessMessage()
                    }
                },
                title = { Text("Turn on automatic closing") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Wireless setup happens on this phone. You do not need a computer or USB cable.")

                        if (!state.hasUsageAccess) {
                            Text("1. Allow QuietShield Dormant to see when apps are opened and closed.")
                            OutlinedButton(
                                enabled = !state.wirelessBusy,
                                onClick = {
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                },
                            ) {
                                Text("Open app activity setting")
                            }
                        } else {
                            Text("1. App activity access is ready.")
                        }

                        Text("2. Open Wireless Debugging and choose Pair device with pairing code.")
                        Text("3. Keep that screen open. Dormant finds the changing port automatically.")
                        Text("4. Enter only the 6-digit code in the Dormant notification, then tap Pair.")

                        Button(
                            enabled = !state.wirelessBusy,
                            onClick = ::beginWirelessPairing,
                        ) {
                            Text("Open Wireless Debugging")
                        }

                        if (notificationPermissionDenied) {
                            Text(
                                "Allow Dormant notifications so the 6-digit code can be entered without leaving Wireless Debugging.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        if (state.hasSavedPairing) {
                            OutlinedButton(
                                enabled = !state.wirelessBusy,
                                onClick = viewModel::restoreWireless,
                            ) {
                                Text("Restore automatic closing")
                            }
                        }

                        state.wirelessMessage?.let { message ->
                            Text(
                                text = message,
                                color = if (state.setupReady) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }

                        if (state.wirelessBusy) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                                Text("Restoring automatic closing…")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !state.wirelessBusy,
                        onClick = {
                            showAutomaticSetupDialog = false
                            viewModel.clearWirelessMessage()
                        },
                    ) {
                        Text("Close")
                    }
                },
                dismissButton = {},
            )
        }
    }
}

@Composable
private fun AppHeader(
    state: AppUiState,
    themeChoice: ThemeChoice,
    onThemeSelected: (ThemeChoice) -> Unit,
    onRefresh: () -> Unit,
    onAutomaticClosingChanged: (Boolean) -> Unit,
    onAddQuickSetting: () -> Unit,
    onShowResults: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "QuietShield Dormant",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = when {
                            state.automaticClosing -> "Automatic closing is on"
                            state.setupReady -> "Automatic closing is paused"
                            else -> "Tap the switch to set up automatic closing"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.automaticClosing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(
                    checked = state.automaticClosing,
                    onCheckedChange = onAutomaticClosingChanged,
                    enabled = true,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRefresh) { Text("Reload", maxLines = 1) }
                TextButton(onClick = onAddQuickSetting) { Text("Quick Setting", maxLines = 1) }
                TextButton(onClick = onShowResults) { Text("Results", maxLines = 1) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ThemeMenu(themeChoice, onThemeSelected)
            }
        }
    }
}

@Composable
private fun ThemeMenu(
    selected: ThemeChoice,
    onSelected: (ThemeChoice) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.widthIn(min = 152.dp),
        ) {
            Text(
                text = "Theme: ${selected.label}",
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ThemeChoice.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        expanded = false
                        onSelected(choice)
                    },
                )
            }
        }
    }
}

@Composable
private fun UsageSetupBanner(onPermissionReturned: () -> Unit) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Allow QuietShield Dormant to see when apps open and close.",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                },
            ) { Text("Set up") }
            TextButton(onClick = onPermissionReturned) { Text("Check") }
        }
    }
}

@Composable
private fun ListTools(
    state: AppUiState,
    apps: List<InstalledApp>,
    selectedPackages: Set<String>,
    onRunningOnlyChanged: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onSetBehavior: () -> Unit,
    onResetTab: () -> Unit,
) {
    val selectable = state.selectedSection != AppSection.CORE
    val allSelected = apps.isNotEmpty() && apps.all { it.packageName in selectedPackages }
    val runningCount = state.apps.count {
        it.section == state.selectedSection && state.runtimeStateFor(it) in setOf(
            AppRuntimeState.OPEN_NOW,
            AppRuntimeState.PLAYING_MEDIA,
            AppRuntimeState.WORKING_IN_BACKGROUND,
            AppRuntimeState.KEPT_READY,
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.setupReady) {
                    FilterChip(
                        selected = state.showRunningOnly,
                        onClick = onRunningOnlyChanged,
                        label = { Text("Show running only ($runningCount)") },
                    )
                } else {
                    Text(
                        text = "Running status appears after automatic closing setup.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.setupReady) {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onResetTab) { Text("Reset this tab") }
            }

            if (selectable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { checked -> if (checked) onSelectAll() else onClear() },
                        enabled = apps.isNotEmpty(),
                    )
                    TextButton(
                        onClick = { if (allSelected) onClear() else onSelectAll() },
                        enabled = apps.isNotEmpty(),
                    ) {
                        Text(if (allSelected) "Clear all" else "Select all")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${selectedPackages.size} selected",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = onSetBehavior,
                        enabled = selectedPackages.isNotEmpty(),
                    ) { Text("Set behavior") }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    policy: AppPolicy,
    runtimeState: AppRuntimeState,
    suggestion: AggressiveSuggestion?,
    selected: Boolean,
    onSelectionChange: ((Boolean) -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onSelectionChange != null) {
            Checkbox(
                checked = selected,
                onCheckedChange = onSelectionChange,
            )
            Spacer(Modifier.width(4.dp))
        }
        InstalledAppIcon(app)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when {
                        app.section == AppSection.CORE -> "Protected"
                        app.safetyLevel == SafetyLevel.RECOMMENDED_PROTECTION -> "Protect"
                        policy.aggressive -> "Close sooner"
                        app.section == AppSection.SYSTEM -> "Built-in"
                        else -> "Installed"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (
                        app.section == AppSection.CORE ||
                        app.safetyLevel == SafetyLevel.RECOMMENDED_PROTECTION
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = if (app.section == AppSection.CORE) "Always left alone" else policySummary(policy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RuntimeBadge(runtimeState)
                if (suggestion != null && !policy.aggressive) {
                    Text(
                        text = "Suggestion: close sooner",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeBadge(state: AppRuntimeState) {
    val highlighted = state != AppRuntimeState.NOT_RUNNING
    Surface(
        shape = RoundedCornerShape(50),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (highlighted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    ),
            )
            Text(
                text = state.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun InstalledAppIcon(app: InstalledApp) {
    val context = LocalContext.current
    val image = remember(app.packageName) {
        runCatching {
            context.packageManager
                .getApplicationIcon(app.packageName)
                .toBitmap(width = 48, height = 48)
                .asImageBitmap()
        }.getOrNull()
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "${app.label} icon",
                modifier = Modifier.size(40.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = app.label.firstOrNull()?.uppercase() ?: "?",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Loading apps…")
        }
    }
}

@Composable
private fun ErrorState(message: String?, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message ?: "The app list could not be loaded.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}

@Composable
private fun PolicyEditor(
    app: InstalledApp,
    initialPolicy: AppPolicy,
    runtimeState: AppRuntimeState,
    suggestion: AggressiveSuggestion?,
    isDisabled: Boolean,
    canChangeEnabled: Boolean,
    onSave: (AppPolicy) -> Unit,
    onAcceptSuggestion: () -> Unit,
    onDismissSuggestion: (Boolean) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var draft by remember(app.packageName, initialPolicy) { mutableStateOf(initialPolicy) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InstalledAppIcon(app)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    RuntimeBadge(runtimeState)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(app.classificationReason, style = MaterialTheme.typography.bodyMedium)
            app.protectionReason?.let { reason ->
                Spacer(Modifier.height(10.dp))
                InfoCard(text = reason, warning = true)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${app.packageName}")
                    }
                    runCatching { context.startActivity(intent) }
                },
            ) { Text("App info") }
            Spacer(Modifier.height(16.dp))
        }

        if (app.section == AppSection.CORE) {
            item {
                InfoCard(
                    text = "Your phone needs this app. QuietShield Dormant will always leave it alone.",
                    warning = false,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        } else {
            if (app.section == AppSection.SYSTEM) {
                item {
                    InfoCard(
                        text = "This app came with your phone. Changing it may stop a phone feature. QuietShield Dormant will never change it on its own.",
                        warning = true,
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }

            if (suggestion != null && !draft.aggressive) {
                item {
                    InfoCard(
                        text = "Close sooner may help. ${suggestion.reason}",
                        warning = false,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TextButton(onClick = onAcceptSuggestion) { Text("Use it") }
                        TextButton(onClick = { onDismissSuggestion(false) }) { Text("Not now") }
                        TextButton(onClick = { onDismissSuggestion(true) }) { Text("Never suggest") }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            item {
                SectionTitle("How should this app behave?")
                Spacer(Modifier.height(4.dp))
            }
            items(SleepMode.entries) { mode ->
                ChoiceRow(
                    title = mode.label,
                    description = mode.description,
                    selected = draft.sleepMode == mode,
                    onClick = { draft = draft.copy(sleepMode = mode) },
                )
            }

            if (draft.sleepMode != SleepMode.PROTECTED) {
                item {
                    Spacer(Modifier.height(12.dp))
                    TimeoutSelector(
                        title = "After leaving the app",
                        helper = "Choose how long QuietShield Dormant waits before the first action.",
                        value = draft.backgroundTimeoutMinutes,
                        onChange = { draft = draft.copy(backgroundTimeoutMinutes = it) },
                    )
                }
            }

            if (draft.sleepMode == SleepMode.STANDBY_THEN_FORCE_STOP) {
                item {
                    Spacer(Modifier.height(14.dp))
                    TimeoutSelector(
                        title = "After the app goes to sleep",
                        helper = "Choose how long QuietShield Dormant waits before closing the app.",
                        value = draft.inactiveTimeoutMinutes,
                        onChange = { draft = draft.copy(inactiveTimeoutMinutes = it) },
                    )
                }
            }

            item {
                Spacer(Modifier.height(18.dp))
                SectionTitle("Activity in the background")
                Spacer(Modifier.height(4.dp))
            }
            items(SyncMode.entries) { mode ->
                ChoiceRow(
                    title = mode.label,
                    description = mode.description,
                    selected = draft.syncMode == mode,
                    onClick = { draft = draft.copy(syncMode = mode) },
                )
            }

            item {
                Spacer(Modifier.height(10.dp))
                SettingSwitch(
                    title = "Keep playing apps active",
                    subtitle = "Do not sleep or close this app while music, video, or other media is playing.",
                    checked = draft.mediaProtection,
                    onCheckedChange = { draft = draft.copy(mediaProtection = it) },
                )
                SettingSwitch(
                    title = "Close this app sooner",
                    subtitle = "Use stronger handling when this app repeatedly returns or keeps working too long.",
                    checked = draft.aggressive,
                    onCheckedChange = { draft = draft.copy(aggressive = it) },
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { onSave(draft) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save changes") }
                OutlinedButton(
                    onClick = { onSetEnabled(isDisabled) },
                    enabled = canChangeEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isDisabled) "Enable app" else "Disable app")
                }
                if (!canChangeEnabled) {
                    Text(
                        text = "Turn on automatic closing before enabling or disabling apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun GroupPolicyEditor(
    apps: List<InstalledApp>,
    onApply: (AppPolicy) -> Unit,
    onClose: () -> Unit,
) {
    val firstApp = apps.first()
    var draft by remember(apps.map { it.packageName }) {
        mutableStateOf(AppPolicy.defaultFor(firstApp).copy(packageName = ""))
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp),
    ) {
        item {
            Text(
                text = "Set behavior for ${apps.size} apps",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "These choices will be applied to every selected app.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            if (firstApp.section == AppSection.SYSTEM) {
                InfoCard(
                    text = "These apps came with your phone. Review your choices carefully before applying them.",
                    warning = true,
                )
                Spacer(Modifier.height(14.dp))
            }
            SectionTitle("How should these apps behave?")
        }

        items(SleepMode.entries) { mode ->
            ChoiceRow(
                title = mode.label,
                description = mode.description,
                selected = draft.sleepMode == mode,
                onClick = { draft = draft.copy(sleepMode = mode) },
            )
        }

        if (draft.sleepMode != SleepMode.PROTECTED) {
            item {
                Spacer(Modifier.height(12.dp))
                TimeoutSelector(
                    title = "After leaving an app",
                    helper = "Choose how long QuietShield Dormant waits before the first action.",
                    value = draft.backgroundTimeoutMinutes,
                    onChange = { draft = draft.copy(backgroundTimeoutMinutes = it) },
                )
            }
        }

        if (draft.sleepMode == SleepMode.STANDBY_THEN_FORCE_STOP) {
            item {
                Spacer(Modifier.height(14.dp))
                TimeoutSelector(
                    title = "After an app goes to sleep",
                    helper = "Choose how long QuietShield Dormant waits before closing it.",
                    value = draft.inactiveTimeoutMinutes,
                    onChange = { draft = draft.copy(inactiveTimeoutMinutes = it) },
                )
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            SectionTitle("Activity in the background")
        }
        items(SyncMode.entries) { mode ->
            ChoiceRow(
                title = mode.label,
                description = mode.description,
                selected = draft.syncMode == mode,
                onClick = { draft = draft.copy(syncMode = mode) },
            )
        }

        item {
            Spacer(Modifier.height(10.dp))
            SettingSwitch(
                title = "Keep playing apps active",
                subtitle = "Do not sleep or close selected apps while media is playing.",
                checked = draft.mediaProtection,
                onCheckedChange = { draft = draft.copy(mediaProtection = it) },
            )
            SettingSwitch(
                title = "Close these apps sooner",
                subtitle = "Use stronger handling when selected apps keep running or send too many alerts.",
                checked = draft.aggressive,
                onCheckedChange = { draft = draft.copy(aggressive = it) },
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = { onApply(draft) }, modifier = Modifier.fillMaxWidth()) {
                Text("Apply to ${apps.size} apps")
            }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
private fun BetaResultsSheet(
    state: AppUiState,
    onAutoAggressiveChanged: (AutoAggressiveMode) -> Unit,
    onRestoreChanged: (Boolean) -> Unit,
    onStartBaseline: () -> Unit,
    onStopBaseline: () -> Unit,
    onForgetPairing: () -> Unit,
    onShareReport: () -> Unit,
    onClose: () -> Unit,
) {
    val summary = state.betaSummary
    val baselineRunning = state.baselineUntil > System.currentTimeMillis()
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Beta results",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "These results are measured on this phone. They do not use cloud processing.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item {
            SectionTitle("What Dormant has done")
            Text("Apps put to sleep: ${summary.sleptCount}")
            Text("Apps closed: ${summary.closedCount}")
            Text("Actions skipped for safety: ${summary.skippedCount}")
            Text("Close-sooner suggestions: ${state.suggestions.size}")
        }

        item {
            SectionTitle("Screen-off battery use")
            Text("Before management: ${formatDrain(summary.baselineDrainPerHour)}")
            Text("With management: ${formatDrain(summary.managedDrainPerHour)}")
            Spacer(Modifier.height(8.dp))
            if (baselineRunning) {
                OutlinedButton(onClick = onStopBaseline) {
                    Text("Stop before-management test")
                }
                Text(
                    text = "The three-day before-management test is running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedButton(
                    onClick = onStartBaseline,
                    enabled = !state.automaticClosing,
                ) {
                    Text("Measure before management for 3 days")
                }
                Text(
                    text = if (state.automaticClosing) {
                        "Pause automatic closing before starting the before-management test."
                    } else {
                        "Run this before enabling automatic closing for the clearest comparison."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionTitle("Close-sooner suggestions")
            Text(
                text = "Dormant looks for User Apps that repeatedly return or keep working too long.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(AutoAggressiveMode.entries) { mode ->
            ChoiceRow(
                title = mode.label,
                description = mode.description,
                selected = state.autoAggressiveMode == mode,
                onClick = { onAutoAggressiveChanged(mode) },
            )
        }

        item {
            SettingSwitch(
                title = "Restore after restart",
                subtitle = "Dormant will try to restore automatic closing after the phone restarts. If Wireless Debugging is off, it will ask you to restore it.",
                checked = state.restoreAfterRestart,
                onCheckedChange = onRestoreChanged,
            )
        }

        state.compatibility?.let { report ->
            item {
                SectionTitle("Phone check")
                Text(report.deviceSummary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                report.checks.forEach { check ->
                    Text(
                        text = "${if (check.ready) "✓" else "•"} ${check.title}: ${check.message}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = report.phoneTip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.hasSavedPairing) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onForgetPairing) {
                        Text("Forget wireless setup")
                    }
                }
            }
        }

        item {
            SectionTitle("Recent activity")
            if (state.recentActions.isEmpty()) {
                Text("No activity has been recorded yet.")
            } else {
                state.recentActions.take(20).forEach { event ->
                    val time = android.text.format.DateFormat.format("MMM d, h:mm a", event.timestamp)
                    Text(
                        text = "$time · ${event.detail}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Button(onClick = onShareReport, modifier = Modifier.fillMaxWidth()) {
                Text("Share beta report")
            }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}

private fun formatDrain(value: Double?): String =
    value?.let { String.format(java.util.Locale.US, "%.2f%% per hour", it) }
        ?: "Not enough samples yet"

@Composable
private fun InfoCard(
    text: String,
    warning: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(text = text, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun TimeoutSelector(
    title: String,
    helper: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var customEditor by remember { mutableStateOf(value !in timeoutPresets) }
    var customText by remember(value) { mutableStateOf(value.toString()) }

    Column {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(
            helper,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(if (value in timeoutPresets) formatMinutes(value) else "Custom: ${formatMinutes(value)}")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                timeoutPresets.forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text(formatMinutes(minutes)) },
                        onClick = {
                            expanded = false
                            customEditor = false
                            onChange(minutes)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Custom time") },
                    onClick = {
                        expanded = false
                        customEditor = true
                    },
                )
            }
        }
        if (customEditor) {
            OutlinedTextField(
                value = customText,
                onValueChange = { text ->
                    customText = text.filter(Char::isDigit).take(5)
                    customText.toIntOrNull()?.coerceIn(1, 10_080)?.let(onChange)
                },
                label = { Text("Number of minutes") },
                supportingText = { Text("Choose from 1 minute to 7 days.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable { onCheckedChange(!checked) },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
