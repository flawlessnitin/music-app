package com.personal.music.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    localMusicPath: String = "",
) {
    val ui by viewModel.ui.collectAsState()

    var keyId by remember(ui.keyId) { mutableStateOf(ui.keyId) }
    var applicationKey by remember { mutableStateOf("") }
    var bucket by remember(ui.bucket) { mutableStateOf(ui.bucket) }
    var keyVisible by remember { mutableStateOf(false) }

    var sliderValue by remember(ui.capGb) { mutableFloatStateOf(ui.capGb.toFloat()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
        )

        Text(
            text = "Backblaze B2 Storage",
            style = MaterialTheme.typography.titleLarge,
        )

        OutlinedTextField(
            value = keyId,
            onValueChange = { keyId = it },
            label = { Text("Key ID") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("key_id_input"),
            singleLine = true,
        )

        OutlinedTextField(
            value = applicationKey,
            onValueChange = { applicationKey = it },
            label = { Text("Application Key") },
            placeholder = { if (ui.hasCredentials) Text("Saved. Leave empty to keep it.") },
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(
                    onClick = { keyVisible = !keyVisible },
                    modifier = Modifier.testTag("toggle_key_visibility"),
                ) {
                    Icon(
                        imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (keyVisible) "Hide application key" else "Show application key",
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("app_key_input"),
            singleLine = true,
        )

        OutlinedTextField(
            value = bucket,
            onValueChange = { bucket = it },
            label = { Text("Bucket name") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bucket_input"),
            singleLine = true,
        )

        Button(
            onClick = { viewModel.save(keyId, applicationKey, bucket) },
            enabled = !ui.isTesting,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("save_and_test_button"),
        ) {
            if (ui.isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Testing connection...")
            } else {
                Text("Save and test")
            }
        }

        if (ui.hasCredentials) {
            OutlinedButton(
                onClick = {
                    viewModel.clearKey()
                    applicationKey = ""
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clear_key_button"),
            ) {
                Text("Clear key")
            }
        }

        if (!ui.testMessage.isNullOrBlank()) {
            Text(
                text = ui.testMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("test_message"),
            )
        }

        HorizontalDivider()

        Text(
            text = "Sync & Download",
            style = MaterialTheme.typography.titleLarge,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Wi-Fi only", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Sync and stream only on unmetered Wi-Fi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = ui.wifiOnly,
                onCheckedChange = { viewModel.setWifiOnly(it) },
                modifier = Modifier.testTag("wifi_only_switch"),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Storage cap: ${sliderValue.roundToInt()} GB",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    viewModel.setCapGb(sliderValue.roundToInt())
                },
                valueRange = 1f..40f,
                steps = 38,
                modifier = Modifier.testTag("cap_slider"),
            )
        }

        val usedGb = ui.usedBytes.toDouble() / 1_000_000_000.0
        val usedGbStr = String.format(Locale.US, "%.1f", usedGb)
        Text(
            text = "Used $usedGbStr GB of ${ui.capGb} GB, ${ui.downloadedSongs} songs on phone, ${ui.waitingSongs} waiting",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("storage_summary"),
        )

        Button(
            onClick = { viewModel.syncNow() },
            enabled = ui.hasCredentials,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sync_now_button"),
        ) {
            Text("Sync now")
        }

        val lastSyncText = if (ui.lastSyncFinishedAtMillis != null) {
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(ui.lastSyncFinishedAtMillis!!))
            "Last sync: $timeStr - ${ui.lastSyncMessage ?: "Up to date."}"
        } else {
            "Last sync: Never - ${ui.lastSyncMessage ?: "Add your Backblaze key to start."}"
        }
        Text(
            text = lastSyncText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("last_sync_text"),
        )

        HorizontalDivider()

        Text(
            text = "Music on this phone",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "Songs you copy into this folder stay on the phone only. They are never uploaded." +
                if (localMusicPath.isNotBlank()) "\n$localMusicPath" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("local_music_hint"),
        )
        OutlinedButton(
            onClick = { viewModel.scanLocal() },
            enabled = !ui.isScanningLocal,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("scan_local_button"),
        ) {
            Text(if (ui.isScanningLocal) "Scanning..." else "Scan phone music (${ui.localSongs} songs)")
        }
        if (!ui.localMessage.isNullOrBlank()) {
            Text(
                text = ui.localMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("local_message"),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
