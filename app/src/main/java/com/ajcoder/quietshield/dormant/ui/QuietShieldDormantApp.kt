package com.ajcoder.quietshield.dormant.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ajcoder.quietshield.dormant.domain.AppPolicy
import com.ajcoder.quietshield.dormant.domain.AppSection
import com.ajcoder.quietshield.dormant.domain.InstalledApp
import com.ajcoder.quietshield.dormant.domain.SleepMode
import com.ajcoder.quietshield.dormant.domain.SyncMode
import com.ajcoder.quietshield.dormant.domain.ThemeChoice
import com.ajcoder.quietshield.dormant.domain.formatMinutes
import com.ajcoder.quietshield.dormant.domain.policySummary
import com.ajcoder.quietshield.dormant.ui.theme.QuietShieldDormantTheme

private val timeoutPresets = listOf(1, 2, 5, 10, 15, 30, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietShieldDormantApp(viewModel: QuietShieldViewModel) {
    val themeChoice by viewModel.theme.collectAsState()
    val state by viewModel.uiState.collectAsState()
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showGroupEditor by remember { mutableStateOf(false) }

    val selectableApps = state.visibleApps.filter { it.section != AppSection.CORE }
    val selectedApps = state.apps.filter { it.packageName in selectedPackages }

    LaunchedEffect(state.selectedSection, state.query, state.apps) {
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
                    themeChoice = themeChoice,
                    onThemeSelected = viewModel::setTheme,
                    onRefresh = viewModel::refreshApps,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (!state.hasUsageAccess) {
                    SimpleSetupBanner(onPermissionReturned = viewModel::refreshPermissionState)
                }

                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    label = { Text("Search apps") },
                    singleLine = true,
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

                if (state.selectedSection != AppSection.CORE && !state.loading) {
                    SelectionBar(
                        apps = selectableApps,
                        selectedPackages = selectedPackages,
                        onSelectAll = {
                            selectedPackages = selectableApps.mapTo(mutableSetOf()) { it.packageName }
                        },
                        onClear = { selectedPackages = emptySet() },
                        onSetBehavior = { showGroupEditor = true },
                    )
                }

                when {
                    state.loading -> LoadingState()
                    state.errorMessage != null -> ErrorState(
                        message = state.errorMessage,
                        onRetry = viewModel::refreshApps,
                    )
                    state.visibleApps.isEmpty() -> EmptyState()
                    else -> LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        items(
                            items = state.visibleApps,
                            key = { it.packageName },
                        ) { app ->
                            AppRow(
                                app = app,
                                policy = state.policyFor(app),
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
                    onSave = {
                        viewModel.savePolicy(it)
                        selectedApp = null
                    },
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
    }
}

@Composable
private fun AppHeader(
    themeChoice: ThemeChoice,
    onThemeSelected: (ThemeChoice) -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(shadowElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "QuietShield Dormant",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Test build · Automatic closing is not active yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefresh) { Text("Reload") }
            ThemeMenu(themeChoice, onThemeSelected)
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
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected.label)
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
private fun SimpleSetupBanner(onPermissionReturned: () -> Unit) {
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
                text = "Allow QuietShield Dormant to see when apps were last used.",
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
private fun SelectionBar(
    apps: List<InstalledApp>,
    selectedPackages: Set<String>,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onSetBehavior: () -> Unit,
) {
    val allSelected = apps.isNotEmpty() && apps.all { it.packageName in selectedPackages }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
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
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSetBehavior,
                enabled = selectedPackages.isNotEmpty(),
            ) { Text("Set behavior") }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    policy: AppPolicy,
    selected: Boolean,
    onSelectionChange: ((Boolean) -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onSelectionChange != null) {
            Checkbox(
                checked = selected,
                onCheckedChange = onSelectionChange,
            )
            Spacer(Modifier.width(4.dp))
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.label.firstOrNull()?.uppercase() ?: "?",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
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
                    text = when (app.section) {
                        AppSection.CORE -> "Protected"
                        AppSection.SYSTEM -> "Built-in"
                        AppSection.USER -> if (policy.aggressive) "Close sooner" else "Installed"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (app.section == AppSection.CORE) {
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
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No apps found.")
    }
}

@Composable
private fun PolicyEditor(
    app: InstalledApp,
    initialPolicy: AppPolicy,
    onSave: (AppPolicy) -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember(app.packageName, initialPolicy) { mutableStateOf(initialPolicy) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp),
    ) {
        item {
            Text(app.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(app.classificationReason, style = MaterialTheme.typography.bodyMedium)
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
                    subtitle = "Use stronger handling when this app keeps running or sends too many alerts.",
                    checked = draft.aggressive,
                    onCheckedChange = { draft = draft.copy(aggressive = it) },
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { onSave(draft) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save changes") }
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
private fun InfoCard(text: String, warning: Boolean) {
    Surface(
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
