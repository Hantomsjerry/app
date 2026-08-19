package com.example.myapp.communication

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterCommandCodecTest {
    private val parameters = DetectionParameters(
        lv1Sensitivity = 30,
        lv1Strength = 100,
        lv1Density = 0,
        enhancedInference = false,
        lv1AreaMask = true,
        minArea = 5000,
        template = "400mmBase.engine",
        lv2Strength = 100,
        lv3Strength = 60,
        actionDuration = 1200,
        rejectDelay = 700
    )

    @Test
    fun encodeContainsOperationTimestampAndEveryParameter() {
        val encoded = ParameterCommandCodec.encode(
            operation = ParameterOperation.Apply,
            timestamp = 1783745905766,
            parameters = parameters
        )

        val root = JSONObject(encoded.compactJson)
        val values = root.getJSONObject("parameters")
        assertEquals("apply_parameters", root.getString("type"))
        assertJsonScalar(root, "timestamp", 1783745905766L, Long::class.javaObjectType)
        assertJsonScalar(values, "lv1Sensitivity", 30, Int::class.javaObjectType)
        assertJsonScalar(values, "lv1Strength", 100, Int::class.javaObjectType)
        assertJsonScalar(values, "lv1Density", 0, Int::class.javaObjectType)
        assertJsonScalar(values, "enhancedInference", false, Boolean::class.javaObjectType)
        assertJsonScalar(values, "lv1AreaMask", true, Boolean::class.javaObjectType)
        assertJsonScalar(values, "minArea", 5000, Int::class.javaObjectType)
        assertJsonScalar(values, "template", "400mmBase.engine", String::class.java)
        assertJsonScalar(values, "lv2Strength", 100, Int::class.javaObjectType)
        assertJsonScalar(values, "lv3Strength", 60, Int::class.javaObjectType)
        assertJsonScalar(values, "actionDuration", 1200, Int::class.javaObjectType)
        assertJsonScalar(values, "rejectDelay", 700, Int::class.javaObjectType)
    }

    @Test
    fun footer_encodes_lv3_as_integer() {
        val json = JSONObject(
            ParameterCommandCodec.encode(
                ParameterOperation.Apply,
                1L,
                parameters.copy(lv3Strength = 65)
            ).compactJson
        )

        assertEquals(65, json.getJSONObject("parameters").getInt("lv3Strength"))
        assertEquals(Int::class.javaObjectType, json.getJSONObject("parameters").get("lv3Strength")::class.java)
    }

    @Test
    fun operationNamesMatchFooterActions() {
        assertEquals("save_parameters", ParameterOperation.Save.wireName)
        assertEquals("apply_parameters", ParameterOperation.Apply.wireName)
        assertEquals("sync_to_device", ParameterOperation.Sync.wireName)
        assertEquals("保存参数", ParameterOperation.Save.displayName)
        assertEquals("应用参数", ParameterOperation.Apply.displayName)
        assertEquals("同步到设备", ParameterOperation.Sync.displayName)
    }

    @Test
    fun wireTextIsCompactJsonFollowedByExactlyOneNewline() {
        val encoded = ParameterCommandCodec.encode(ParameterOperation.Save, 1, parameters)
        assertEquals(encoded.compactJson + "\n", encoded.wireText)
        assertFalse(encoded.compactJson.contains('\n'))
        assertTrue(encoded.prettyJson.contains('\n'))
    }

    @Test
    fun responseParserAcceptsOnlyTrimmedCaseInsensitiveSuccess() {
        assertTrue(ParameterCommandCodec.isSuccessResponse(" success "))
        assertTrue(ParameterCommandCodec.isSuccessResponse("SUCCESS"))
        assertFalse(ParameterCommandCodec.isSuccessResponse("failed"))
        assertFalse(ParameterCommandCodec.isSuccessResponse(""))
    }

    private fun assertJsonScalar(
        json: JSONObject,
        key: String,
        expected: Any,
        expectedClass: Class<*>
    ) {
        val actual = json.get(key)
        assertEquals(expectedClass, actual::class.java)
        assertEquals(expected, actual)
    }
}
