package com.example.myapp.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceUiStateTest {
    @Test
    fun newSessionWaitsForSpeechModel() {
        val previous = VoiceUiState.Ready(4, "old command", command(), "send failed")

        assertEquals(
            VoiceUiState.PreparingSpeechModel(5),
            reduceVoiceState(previous, VoiceEvent.NewSession(5))
        )
    }

    @Test
    fun recordingStartsOnlyAfterControllerCallback() {
        assertEquals(
            VoiceUiState.Recording(5, ""),
            reduceVoiceState(
                VoiceUiState.PreparingSpeechModel(5),
                VoiceEvent.RecordingStarted(5)
            )
        )
    }

    @Test
    fun currentPartialUpdatesOnlyRecording() {
        assertEquals(
            VoiceUiState.Recording(5, "Lv1 灵敏度"),
            reduceVoiceState(
                VoiceUiState.Recording(5, ""),
                VoiceEvent.PartialText(5, "Lv1 灵敏度")
            )
        )
    }

    @Test
    fun partialsAreIgnoredOutsideCurrentRecordingState() {
        val partial = VoiceEvent.PartialText(5, "old partial")
        listOf<VoiceUiState>(
            VoiceUiState.PreparingSpeechModel(5),
            VoiceUiState.Transcribing(5),
            VoiceUiState.Parsing(5, "current"),
            VoiceUiState.Ready(5, "current", command())
        ).forEach { state ->
            assertSame(state, reduceVoiceState(state, partial))
        }
    }

    @Test
    fun recordingStoppedEntersTranscribing() {
        assertEquals(
            VoiceUiState.Transcribing(3),
            reduceVoiceState(
                VoiceUiState.Recording(3),
                VoiceEvent.RecordingStopped(3)
            )
        )
    }

    @Test
    fun finalTextMovesOnlyTranscribingToParsing() {
        val finalText = VoiceEvent.FinalText(3, "set level one strength to 12")
        val recording = VoiceUiState.Recording(3)

        assertSame(recording, reduceVoiceState(recording, finalText))
        assertEquals(
            VoiceUiState.Parsing(3, "set level one strength to 12"),
            reduceVoiceState(VoiceUiState.Transcribing(3), finalText)
        )
    }

    @Test
    fun recordingStoppedOutsideRecordingIsIgnored() {
        val transcribing = VoiceUiState.Transcribing(3)

        assertSame(
            transcribing,
            reduceVoiceState(transcribing, VoiceEvent.RecordingStopped(3))
        )
    }

    @Test
    fun modelPreparationKeepsTranscript() {
        assertEquals(
            VoiceUiState.PreparingModel(3, "set level one strength to 12"),
            reduceVoiceState(
                VoiceUiState.Parsing(3, "set level one strength to 12"),
                VoiceEvent.ModelPreparationStarted(3)
            )
        )
    }

    @Test
    fun parsedCommandEntersReady() {
        val command = command()

        assertEquals(
            VoiceUiState.Ready(3, "set level one strength to 12", command),
            reduceVoiceState(
                VoiceUiState.Parsing(3, "set level one strength to 12"),
                VoiceEvent.ParsedCommand(3, command)
            )
        )
    }

    @Test
    fun sendStartMovesReadyCandidateToSending() {
        val ready = VoiceUiState.Ready(3, "set level one strength to 12", command())

        assertEquals(
            VoiceUiState.Sending(3, "set level one strength to 12", command()),
            reduceVoiceState(ready, VoiceEvent.SendStarted(3))
        )
    }

    @Test
    fun sendFailureReturnsToReadyWithSameCandidateAndRetryError() {
        val command = command()
        val state = reduceVoiceState(
            VoiceUiState.Sending(3, "set level one strength to 12", command),
            VoiceEvent.SendFailed(3, "network unavailable")
        )

        assertEquals(
            VoiceUiState.Ready(3, "set level one strength to 12", command, "network unavailable"),
            state
        )
    }

    @Test
    fun sendSuccessMovesToSuccess() {
        val command = command()

        assertEquals(
            VoiceUiState.Success(3, "set level one strength to 12", command),
            reduceVoiceState(
                VoiceUiState.Sending(3, "set level one strength to 12", command),
                VoiceEvent.SendSucceeded(3)
            )
        )
    }

    @Test
    fun recognitionErrorsAreAcceptedInBothLocalVoicePhases() {
        assertEquals(
            VoiceUiState.Error(3, "model unavailable"),
            reduceVoiceState(
                VoiceUiState.PreparingSpeechModel(3),
                VoiceEvent.RecognitionFailed(3, "model unavailable")
            )
        )
        assertEquals(
            VoiceUiState.Error(3, "recording failed"),
            reduceVoiceState(
                VoiceUiState.Recording(3),
                VoiceEvent.RecognitionFailed(3, "recording failed")
            )
        )
        assertEquals(
            VoiceUiState.Error(3, "transcription failed"),
            reduceVoiceState(
                VoiceUiState.Transcribing(3),
                VoiceEvent.RecognitionFailed(3, "transcription failed")
            )
        )
    }

    @Test
    fun parseAndModelErrorsNeverContainCommand() {
        val parseError = reduceVoiceState(
            VoiceUiState.Parsing(3, "set level one"),
            VoiceEvent.ParseFailed(3, "parse failed")
        )
        val modelError = reduceVoiceState(
            VoiceUiState.PreparingModel(3, "set level one"),
            VoiceEvent.ModelFailed(3, "model failed")
        )

        assertEquals(VoiceUiState.Error(3, "parse failed"), parseError)
        assertEquals(VoiceUiState.Error(3, "model failed"), modelError)
    }

    @Test
    fun staleCallbacksAreIgnoredAcrossLocalAndExistingPhases() {
        val states = listOf<VoiceUiState>(
            VoiceUiState.Recording(5),
            VoiceUiState.Transcribing(5),
            VoiceUiState.Parsing(5, "current"),
            VoiceUiState.Ready(5, "current", command())
        )
        val staleEvents = listOf<VoiceEvent>(
            VoiceEvent.RecordingStopped(4),
            VoiceEvent.RecordingStarted(4),
            VoiceEvent.PartialText(4, "old partial"),
            VoiceEvent.FinalText(4, "old final"),
            VoiceEvent.RecognitionFailed(4, "old recognition failure"),
            VoiceEvent.ModelPreparationStarted(4),
            VoiceEvent.ParsedCommand(4, command()),
            VoiceEvent.ParseFailed(4, "old parse failure"),
            VoiceEvent.SendStarted(4),
            VoiceEvent.SendFailed(4, "old send failure"),
            VoiceEvent.SendSucceeded(4)
        )

        states.forEach { current ->
            staleEvents.forEach { event ->
                assertSame(current, reduceVoiceState(current, event))
            }
        }
    }

    @Test
    fun staleOrDuplicateNewSessionsAreIgnored() {
        val current = VoiceUiState.Transcribing(5)

        assertSame(current, reduceVoiceState(current, VoiceEvent.NewSession(4)))
        assertSame(current, reduceVoiceState(current, VoiceEvent.NewSession(5)))
    }

    @Test
    fun microphoneActionStartsFromRestingStates() {
        listOf<VoiceUiState>(
            VoiceUiState.Idle,
            VoiceUiState.Error(3, "failed"),
            VoiceUiState.Success(3, "done", command()),
            VoiceUiState.Ready(3, "ready", command())
        ).forEach { state ->
            assertEquals(VoiceMicrophoneAction.StartRecording, microphoneActionFor(state))
        }
    }

    @Test
    fun microphoneActionStopsRecordingWithoutClearingIt() {
        assertEquals(
            VoiceMicrophoneAction.StopAndTranscribe,
            microphoneActionFor(VoiceUiState.Recording(3))
        )
    }

    @Test
    fun microphoneActionIgnoresBusyStates() {
        listOf<VoiceUiState>(
            VoiceUiState.Transcribing(3),
            VoiceUiState.Parsing(3, "text"),
            VoiceUiState.PreparingModel(3, "text"),
            VoiceUiState.Sending(3, "text", command())
        ).forEach { state ->
            assertEquals(VoiceMicrophoneAction.Ignore, microphoneActionFor(state))
        }
    }

    @Test
    fun recognizerMustBeReadyBeforeStartingANewRecording() {
        assertFalse(
            microphoneEnabledFor(VoiceUiState.Idle, SpeechRecognizerStatus.Loading)
        )
        assertFalse(
            microphoneEnabledFor(VoiceUiState.Idle, SpeechRecognizerStatus.Failed)
        )
        assertTrue(
            microphoneEnabledFor(VoiceUiState.Idle, SpeechRecognizerStatus.Ready)
        )
    }

    @Test
    fun anActiveRecordingCanAlwaysBeStopped() {
        SpeechRecognizerStatus.entries.forEach { recognizerStatus ->
            assertTrue(
                microphoneEnabledFor(VoiceUiState.Recording(3), recognizerStatus)
            )
        }
    }

    private fun command() = SetParameterCommand(
        action = "SET_PARAMETER",
        device = MachineDevice.machine_1,
        parameter = VoiceParameter.lv1Strength,
        value = ParameterValue.IntValue(12)
    )
}
