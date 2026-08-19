package com.example.myapp.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpEndpointTest {
    @Test
    fun trimsHostAndParsesPort() {
        val result = validateTcpEndpoint("  192.168.1.20  ", "9000")

        assertTrue(result.isSuccess)
        assertEquals(TcpEndpoint("192.168.1.20", 9000), result.getOrNull())
        assertEquals("192.168.1.20:9000", result.getOrNull()?.label)
    }

    @Test
    fun rejectsEmptyHost() {
        val result = validateTcpEndpoint("   ", "9000")

        assertFalse(result.isSuccess)
        assertEquals("\u8bf7\u8f93\u5165\u670d\u52a1\u5668IP\u5730\u5740", result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsNonnumericPort() {
        val result = validateTcpEndpoint("localhost", "abc")

        assertFalse(result.isSuccess)
        assertEquals("\u7aef\u53e3\u5fc5\u987b\u662f\u6570\u5b57", result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsZeroPort() {
        val result = validateTcpEndpoint("localhost", "0")

        assertFalse(result.isSuccess)
        assertEquals("\u7aef\u53e3\u8303\u56f4\u5fc5\u987b\u662f1\u523065535", result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsPortAboveMaximum() {
        val result = validateTcpEndpoint("localhost", "65536")

        assertFalse(result.isSuccess)
        assertEquals("\u7aef\u53e3\u8303\u56f4\u5fc5\u987b\u662f1\u523065535", result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsNumericPortOutsideIntRangeWithRangeMessage() {
        val result = validateTcpEndpoint("localhost", "9999999999")

        assertFalse(result.isSuccess)
        assertEquals("\u7aef\u53e3\u8303\u56f4\u5fc5\u987b\u662f1\u523065535", result.exceptionOrNull()?.message)
    }
}
