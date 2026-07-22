package com.ajcoder.quietshield.dormant.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import com.ajcoder.quietshield.dormant.ui.theme.QuietShieldDormantTheme

private val timeoutPresets = listOf(1, 2, 5, 10, 15, 30, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietShieldDormantApp(viewModel: QuietShieldViewModel) {
    val themeChoice by viewModel.theme.collectAsState()
    val state by viewModel.uiState.collectAsState()
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }

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
                CapabilityBanner()
                if (!state.hasUsageAccess) {
                    UsageAccessBanner(onPermissionReturned = viewModel::refreshPermissionState)
                }

                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    label = { Text("Search apps or package names") },
                    singleLine = true,
                )

                val sections = AppSection.entries
                TabRow(selectedTabIndex = sections.indexOf(state.selectedSection)) {
                    sections.forEach { section ->
                        val count = state.apps.count { it.section == section }
                        Tab(
                            selected = state.selectedSection == section,
                            onClick = { viewModel.selectSection(section) },
                            text = { Text("${section.title}\n$count") },
                        )
                    }
                }

                when {
                    state.loading -> LoadingState()
                    state.errorMessage != null -> ErrorState(
                        message = state.errorMessage,
                        onRetry = viewModel::refreshApps,
                    )
                    state.visibleApps.isEmpty() -> EmptyState(state.selectedSection)
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = state.visibleApps,
                            key = { it.packageName },
                        ) { app ->
                            AppRow(
                                app = app,
                                policy = state.policyFor(app),
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "QuietShield Dormant",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "v0.1.0-alpha1 · Foundation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefresh) { Text("Refresh") }
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
private fun CapabilityBanner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Foundation mode",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Installed-app inventory, safety classification, themes, search, and per-app policy storage are active. Automatic standby, force-stop, and disable controls are intentionally not enabled until the privileged engine is implemented and verified.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun UsageAccessBanner(onPermissionReturned: () -> Unit) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Usage Access is not granted", fontWeight = FontWeight.Bold)
            Text(
                "Grant Usage Access so later builds can determine when managed apps leave the foreground. This permission does not force-stop apps.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    },
                ) { Text("Open Settings") }
                TextButton(onClick = onPermissionReturned) { Text("Check Again") }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    policy: AppPolicy,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                        AppSection.CORE -> "LOCKED"
                        AppSection.SYSTEM -> "CAUTION"
                        AppSection.USER -> if (policy.aggressive) "AGGRESSIVE" else "USER"
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
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (app.section == AppSection.CORE) {
                    "Always protected"
                } else {
                    buildString {
                        append(policy.sleepMode.label)
                        if (policy.sleepMode != SleepMode.PROTECTED) {
                            append(" · ${policy.backgroundTimeoutMinutes} min")
                        }
                        append(" · ${policy.syncMode.label}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
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
            Text("Classifying installed apps safely…")
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
            Text(message ?: "Unable to load apps.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun EmptyState(section: AppSection) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No ${section.title.lowercase()} match the current search.")
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text(app.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            app.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(app.classificationReason, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        if (app.section == AppSection.CORE) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "Core apps are visible for transparency but remain permanently protected. Sleep, force-stop, aggressive, sync-blocking, and disable controls are unavailable.",
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        } else {
            if (app.section == AppSection.SYSTEM) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    text = "System app: policy changes require caution. Automatic aggressive classification and automatic disabling will remain unavailable for system apps.",
                    modifier = Modifier.padding(14.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        SectionTitle("Sleep Mode")
        SleepMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { draft = draft.copy(sleepMode = mode) }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = draft.sleepMode == mode,
                    onClick = { draft = draft.copy(sleepMode = mode) },
                )
                Column {
                    Text(mode.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (draft.sleepMode != SleepMode.PROTECTED) {
            Spacer(Modifier.height(12.dp))
            TimeoutSelector(
                title = "Background Timeout",
                value = draft.backgroundTimeoutMinutes,
                onChange = { draft = draft.copy(backgroundTimeoutMinutes = it) },
            )
        }

        if (draft.sleepMode == SleepMode.STANDBY_THEN_FORCE_STOP) {
            Spacer(Modifier.height(12.dp))
            TimeoutSelector(
                title = "Inactive Timeout",
                value = draft.inactiveTimeoutMinutes,
                onChange = { draft = draft.copy(inactiveTimeoutMinutes = it) },
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("Background Sync")
        SyncMode.entries.forEach { syncMode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { draft = draft.copy(syncMode = syncMode) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = draft.syncMode == syncMode,
                    onClick = { draft = draft.copy(syncMode = syncMode) },
                )
                Text(syncMode.label)
            }
        }

        SettingSwitch(
            title = "Media Protection",
            subtitle = "Pause sleep and force-stop timers while active playback is detected.",
            checked = draft.mediaProtection,
            onCheckedChange = { draft = draft.copy(mediaProtection = it) },
        )

        SettingSwitch(
            title = "Manual Aggressive",
            subtitle = if (app.section == AppSection.SYSTEM) {
                "System apps may be marked manually, but automatic aggressive assignment stays disabled."
            } else {
                "Marks this app for stronger handling once the privileged engine is added."
            },
            checked = draft.aggressive,
            onCheckedChange = { draft = draft.copy(aggressive = it) },
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSave(draft) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save Policy") }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
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
    value: Int,
    onChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var customEditor by remember { mutableStateOf(value !in timeoutPresets) }
    var customText by remember(value) { mutableStateOf(value.toString()) }

    Column {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(
            "1, 2, 5, 10, 15, 30, 60 minutes, or a custom value.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(if (value in timeoutPresets) "$value minutes" else "Custom: $value minutes")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                timeoutPresets.forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text("$minutes minutes") },
                        onClick = {
                            expanded = false
                            customEditor = false
                            onChange(minutes)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Custom") },
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
                label = { Text("Custom minutes (1–10080)") },
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

