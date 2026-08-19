package com.example.myapp.communication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TcpParameterClientTest {
    @Test
    fun exchangesTwoRequestsAndResponsesOverOneAcceptedSocket() = runBlocking<Unit> {
        val serverSocket = ServerSocket(0)
        val acceptedConnections = AtomicInteger(0)
        val receivedWireLines = AtomicReference<List<String>>(emptyList())
        val serverFinished = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>()
        val serverThread = Thread {
            try {
                serverSocket.accept().use { socket ->
                    acceptedConnections.incrementAndGet()
                    val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                    val requests = buildList {
                        repeat(2) { index ->
                            val request = reader.readLine()
                                ?: throw EOFException("Client closed before sending request ${index + 1}")
                            add(request)
                            val lineEnding = if (index == 0) "\r\n" else "\n"
                            writer.write("success-${index + 1}$lineEnding")
                            writer.flush()
                        }
                    }
                    receivedWireLines.set(requests)
                }
            } catch (failure: Throwable) {
                serverFailure.set(failure)
            } finally {
                serverFinished.countDown()
            }
        }
        val client = TcpParameterClient(connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000)

        try {
            serverThread.start()
            client.connect(TcpEndpoint("127.0.0.1", serverSocket.localPort))

            assertTrue(client.isConnected)
            assertEquals(
                "success-1",
                client.sendAndReceive("{\"type\":\"apply_parameters\"}\n")
            )
            assertEquals(
                "success-2",
                client.sendAndReceive("{\"type\":\"save_parameters\"}\n")
            )
            assertTrue("Server did not finish both exchanges", serverFinished.await(2, TimeUnit.SECONDS))
            assertEquals(1, acceptedConnections.get())
            assertEquals(
                listOf(
                    "{\"type\":\"apply_parameters\"}",
                    "{\"type\":\"save_parameters\"}"
                ),
                receivedWireLines.get()
            )

            client.close()
            assertFalse(client.isConnected)
            client.close()
        } finally {
            client.close()
            serverSocket.close()
            serverThread.join(2_000)
            assertFalse("Server thread did not stop within the bounded wait", serverThread.isAlive)
            serverFailure.get()?.let { throw AssertionError("Local server failed", it) }
        }
    }

    @Test
    fun rejectsEofBeforeCompleteResponseLine() = runBlocking<Unit> {
        val serverSocket = ServerSocket(0)
        val serverFailure = AtomicReference<Throwable?>()
        val serverThread = Thread {
            try {
                serverSocket.accept().use { socket ->
                    socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                    socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).apply {
                        write("partial")
                        flush()
                    }
                }
            } catch (failure: Throwable) {
                if (!serverSocket.isClosed) {
                    serverFailure.set(failure)
                }
            }
        }
        val client = TcpParameterClient(connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000)

        try {
            serverThread.start()
            client.connect(TcpEndpoint("127.0.0.1", serverSocket.localPort))

            val result = withTimeout(2_000) {
                runCatching { client.sendAndReceive("request\n") }
            }

            assertTrue("Expected EOF before a newline to fail", result.isFailure)
            assertEquals(
                "Server closed the connection before completing a response line",
                result.exceptionOrNull()?.message
            )
            assertFalse(client.isConnected)
        } finally {
            client.close()
            serverSocket.close()
            serverThread.join(2_000)
            assertFalse("Server thread did not stop within the bounded wait", serverThread.isAlive)
            serverFailure.get()?.let { throw AssertionError("Local server failed", it) }
        }
    }

    @Test
    fun rejectsResponseLongerThanConfiguredMaximumWithoutNewline() = runBlocking<Unit> {
        val serverSocket = ServerSocket(0)
        val serverFailure = AtomicReference<Throwable?>()
        val serverThread = Thread {
            try {
                serverSocket.accept().use { socket ->
                    socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                    socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).apply {
                        write("x".repeat(17))
                        flush()
                    }
                }
            } catch (failure: Throwable) {
                if (!serverSocket.isClosed) {
                    serverFailure.set(failure)
                }
            }
        }
        val client = TcpParameterClient(
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = 2_000,
            maxResponseChars = 16
        )

        try {
            serverThread.start()
            client.connect(TcpEndpoint("127.0.0.1", serverSocket.localPort))

            val result = withTimeout(2_000) {
                runCatching { client.sendAndReceive("request\n") }
            }

            assertTrue("Expected an oversized response to fail", result.isFailure)
            assertTrue(result.exceptionOrNull() is IOException)
            assertEquals(
                "Response exceeded maximum length of 16 characters",
                result.exceptionOrNull()?.message
            )
            assertFalse(client.isConnected)
        } finally {
            client.close()
            serverSocket.close()
            serverThread.join(2_000)
            assertFalse("Server thread did not stop within the bounded wait", serverThread.isAlive)
            serverFailure.get()?.let { throw AssertionError("Local server failed", it) }
        }
    }

    @Test
    fun rejectsTrickledResponseAtAbsoluteDeadline() = runBlocking<Unit> {
        val serverSocket = ServerSocket(0)
        val serverFailure = AtomicReference<Throwable?>()
        val serverThread = Thread {
            try {
                serverSocket.accept().use { socket ->
                    socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                    val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                    repeat(20) {
                        writer.write("x")
                        writer.flush()
                        Thread.sleep(50)
                    }
                    writer.write("\n")
                    writer.flush()
                }
            } catch (_: IOException) {
                // The bounded client is expected to close while the server is still trickling.
            } catch (failure: Throwable) {
                if (!serverSocket.isClosed) {
                    serverFailure.set(failure)
                }
            }
        }
        val client = TcpParameterClient(
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = 200,
            maxResponseChars = 64,
            responseDeadlineMillis = 250
        )

        try {
            serverThread.start()
            client.connect(TcpEndpoint("127.0.0.1", serverSocket.localPort))
            val startedAtNanos = System.nanoTime()

            val result = withTimeout(1_500) {
                runCatching { client.sendAndReceive("request\n") }
            }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

            assertTrue("Expected the absolute response deadline to fail", result.isFailure)
            assertTrue(result.exceptionOrNull() is SocketTimeoutException)
            assertEquals(
                "Response deadline exceeded after 250 milliseconds",
                result.exceptionOrNull()?.message
            )
            assertTrue("Deadline was not bounded: ${elapsedMillis}ms", elapsedMillis < 1_000)
            assertFalse(client.isConnected)
        } finally {
            client.close()
            serverSocket.close()
            serverThread.join(2_000)
            assertFalse("Server thread did not stop within the bounded wait", serverThread.isAlive)
            serverFailure.get()?.let { throw AssertionError("Local server failed", it) }
        }
    }

    @Test
    fun rejectsSendingWhileDisconnected() = runBlocking<Unit> {
        val client = TcpParameterClient(connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000)

        try {
            try {
                client.sendAndReceive("{\"type\":\"apply_parameters\"}\n")
            } catch (_: IllegalStateException) {
                return@runBlocking
            }

            throw AssertionError("Expected sending while disconnected to throw IllegalStateException")
        } finally {
            client.close()
        }
    }

    @Test
    fun closeCancelsAnInFlightResponseReadWithoutWaitingForTheSocketTimeout() = runBlocking<Unit> {
        val serverSocket = ServerSocket(0)
        val requestReceived = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>()
        val serverThread = Thread {
            try {
                serverSocket.accept().use { socket ->
                    socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                    requestReceived.countDown()
                    releaseServer.await(2, TimeUnit.SECONDS)
                }
            } catch (failure: Throwable) {
                if (!serverSocket.isClosed) {
                    serverFailure.set(failure)
                }
            }
        }
        val client = TcpParameterClient(connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000)
        val closer = Executors.newSingleThreadExecutor()

        try {
            serverThread.start()
            client.connect(TcpEndpoint("127.0.0.1", serverSocket.localPort))
            val sendResult = async(Dispatchers.Default) {
                runCatching { client.sendAndReceive("{\"type\":\"apply_parameters\"}\n") }
            }

            assertTrue("Server did not receive the request in time", requestReceived.await(2, TimeUnit.SECONDS))
            closer.submit { client.close() }.get(500, TimeUnit.MILLISECONDS)
            assertTrue(withTimeout(1_000) { sendResult.await().isFailure })
            assertFalse(client.isConnected)
        } finally {
            releaseServer.countDown()
            serverSocket.close()
            closer.shutdownNow()
            client.close()
            serverThread.join(2_000)
            assertFalse("Server thread did not stop within the bounded wait", serverThread.isAlive)
            serverFailure.get()?.let { throw AssertionError("Local server failed", it) }
        }
    }
}
