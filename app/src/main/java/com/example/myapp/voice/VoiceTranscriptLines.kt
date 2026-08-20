package com.example.myapp.voice

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp

@Composable
internal fun VoiceTranscriptLines(
    transcript: VoiceTranscript,
    modifier: Modifier = Modifier
) {
    Text(
        text = transcript.corrected,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_corrected_transcript")
    )
}
