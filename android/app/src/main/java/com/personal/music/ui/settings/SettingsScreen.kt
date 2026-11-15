package com.personal.music.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.personal.music.playback.EqPreset
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    musicPath: String = "",
) {
    val ui by viewModel.ui.collectAsState()

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
            text = "Music on this phone",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "Send songs from your PC with musictool (USB cable). They stay on this phone; the app never uses the internet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (musicPath.isNotBlank()) {
            Text(
                text = musicPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("music_path"),
            )
        }

        val usedGb = String.format(Locale.US, "%.2f", ui.usedBytes / 1_000_000_000.0)
        Text(
            text = "${ui.songs} songs, $usedGb GB",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("library_summary"),
        )

        OutlinedButton(
            onClick = { viewModel.scan() },
            enabled = !ui.isScanning,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("scan_button"),
        ) {
            Text(if (ui.isScanning) "Scanning..." else "Scan for new songs")
        }
        if (!ui.scanMessage.isNullOrBlank()) {
            Text(
                text = ui.scanMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("scan_message"),
            )
        }

        HorizontalDivider()

        Text(
            text = "Sound",
            style = MaterialTheme.typography.titleLarge,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EqPreset.entries.forEach { preset ->
                FilterChip(
                    selected = ui.eqPreset == preset,
                    onClick = { viewModel.setEqPreset(preset) },
                    label = { Text(preset.label) },
                    modifier = Modifier.testTag("eq_${preset.name}"),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
