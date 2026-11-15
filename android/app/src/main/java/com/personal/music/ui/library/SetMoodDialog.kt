package com.personal.music.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Moods offered before you have made any of your own. */
val UsualMoods = listOf("Energetic", "Chill", "Romantic", "Sad", "Party", "Focus")

/** The usual moods plus the ones already in use, each name once (ignoring capital letters). */
fun moodChoices(existing: List<String>): List<String> =
    (existing + UsualMoods).distinctBy { it.trim().lowercase() }.filter { it.isNotBlank() }

/** Pick a mood for one song, pick "No mood", or type a new mood. */
@Composable
fun SetMoodDialog(
    songTitle: String,
    currentMood: String?,
    existingMoods: List<String>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var newMood by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Mood for $songTitle",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                moodChoices(existingMoods).forEach { mood ->
                    MoodOption(
                        text = mood,
                        selected = mood.equals(currentMood, ignoreCase = true),
                        tag = "mood_option_$mood",
                        onClick = { onPick(mood) },
                    )
                }
                MoodOption(
                    text = "No mood",
                    selected = currentMood == null,
                    tag = "mood_option_none",
                    onClick = { onPick(null) },
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newMood,
                        onValueChange = { newMood = it },
                        label = { Text("New mood") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("new_mood_input"),
                    )
                    TextButton(
                        onClick = { onPick(newMood.trim()) },
                        enabled = newMood.isNotBlank(),
                        modifier = Modifier.testTag("add_mood_button"),
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun MoodOption(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag(tag),
    )
}
