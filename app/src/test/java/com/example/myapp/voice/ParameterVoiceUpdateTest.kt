package com.example.myapp.voice

import com.example.myapp.communication.DetectionParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterVoiceUpdateTest {
    private val defaults = DetectionParameters(
        lv1Sensitivity = 30,
        lv1Strength = 100,
        lv1Density = 0,
        enhancedInference = false,
        lv1AreaMask = false,
        minArea = 5000,
        template = "legacy.engine",
        lv2Strength = 100,
        lv3Strength = 60,
        actionDuration = 1200,
        rejectDelay = 700
    )

    @Test
    fun everyVoiceParameterUpdatesOnlyItsTargetField() {
        val cases = listOf(
            command(VoiceParameter.lv1Sensitivity, ParameterValue.IntValue(32)) to
                defaults.copy(lv1Sensitivity = 32),
            command(VoiceParameter.lv1Strength, ParameterValue.IntValue(117)) to
                defaults.copy(lv1Strength = 117),
            command(VoiceParameter.lv1Density, ParameterValue.IntValue(33)) to
                defaults.copy(lv1Density = 33),
            command(VoiceParameter.enhancedInference, ParameterValue.BooleanValue(true)) to
                defaults.copy(enhancedInference = true),
            command(VoiceParameter.lv1AreaMask, ParameterValue.BooleanValue(true)) to
                defaults.copy(lv1AreaMask = true),
            command(VoiceParameter.minArea, ParameterValue.IntValue(4501)) to
                defaults.copy(minArea = 4501),
            command(VoiceParameter.template, ParameterValue.StringValue("400mmBase.engine")) to
                defaults.copy(template = "400mmBase.engine"),
            command(VoiceParameter.lv2Strength, ParameterValue.IntValue(119)) to
                defaults.copy(lv2Strength = 119),
            command(VoiceParameter.lv3Strength, ParameterValue.IntValue(67)) to
                defaults.copy(lv3Strength = 67),
            command(VoiceParameter.actionDuration, ParameterValue.IntValue(1234)) to
                defaults.copy(actionDuration = 1234),
            command(VoiceParameter.rejectDelay, ParameterValue.IntValue(789)) to
                defaults.copy(rejectDelay = 789)
        )

        cases.forEach { (command, expected) ->
            assertEquals(command.parameter.wireName, expected, defaults.withVoiceCommand(command))
        }
        assertEquals(
            DetectionParameters(30, 100, 0, false, false, 5000, "legacy.engine", 100, 60, 1200, 700),
            defaults
        )
    }

    @Test
    fun wrongTypedValuesFailInsteadOfCoercing() {
        assertThrows(IllegalArgumentException::class.java) {
            defaults.withVoiceCommand(
                command(VoiceParameter.lv1Sensitivity, ParameterValue.BooleanValue(true))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            defaults.withVoiceCommand(
                command(VoiceParameter.enhancedInference, ParameterValue.IntValue(1))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            defaults.withVoiceCommand(
                command(VoiceParameter.template, ParameterValue.BooleanValue(true))
            )
        }
    }

    @Test
    fun trimmedCaseInsensitiveSuccessProducesAnAppliedUpdate() {
        val command = command(VoiceParameter.minArea, ParameterValue.IntValue(4501))

        assertEquals(
            ParameterVoiceUpdate.Applied(defaults.copy(minArea = 4501)),
            defaults.voiceUpdateForResponse(command, "  SuCcEsS\r\n")
        )
    }

    @Test
    fun nonSuccessResponseRejectsUpdateAndPreservesSourceInstance() {
        val command = command(VoiceParameter.minArea, ParameterValue.IntValue(4501))

        assertSame(ParameterVoiceUpdate.Rejected, defaults.voiceUpdateForResponse(command, "failed"))
        assertEquals(5000, defaults.minArea)
    }

    @Test
    fun sharedSendGateBlocksSnapshotInterleavingsWithoutDroppingCandidates() {
        val idle = sharedParameterSendGate(
            voiceSending = false,
            footerPending = false,
            footerSending = false
        )
        assertTrue(idle.canPrepareFooterCommand)
        assertTrue(idle.canApplyVoiceCommand)

        var footerCandidateCaptured = false
        val duringVoiceSend = sharedParameterSendGate(
            voiceSending = true,
            footerPending = false,
            footerSending = false
        )
        if (duringVoiceSend.canPrepareFooterCommand) {
            footerCandidateCaptured = true
        }
        assertFalse(footerCandidateCaptured)

        var voiceSendStarted = false
        var footerCandidateStillPending = true
        val whileFooterPending = sharedParameterSendGate(
            voiceSending = false,
            footerPending = footerCandidateStillPending,
            footerSending = false
        )
        if (whileFooterPending.canApplyVoiceCommand) {
            voiceSendStarted = true
        }
        assertFalse(voiceSendStarted)
        assertTrue(footerCandidateStillPending)

        val duringFooterSend = sharedParameterSendGate(
            voiceSending = false,
            footerPending = footerCandidateStillPending,
            footerSending = true
        )
        assertFalse(duringFooterSend.canApplyVoiceCommand)
        assertTrue(footerCandidateStillPending)
    }

    private fun command(
        parameter: VoiceParameter,
        value: ParameterValue
    ) = SetParameterCommand(
        action = "SET_PARAMETER",
        device = MachineDevice.machine_2,
        parameter = parameter,
        value = value
    )
}
