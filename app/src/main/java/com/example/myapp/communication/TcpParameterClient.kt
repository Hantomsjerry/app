package com.example.myapp.communication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * 基于“一行请求、一行响应”协议的长连接 TCP 客户端。
 *
 * 所有连接和收发操作通过 [operationMutex] 串行执行；[resourceLock] 仅保护 socket、
 * reader、writer 三者的原子替换，使其他线程调用 [close] 时不会留下半连接状态。
 */
class TcpParameterClient(
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 5_000,
    private val maxResponseChars: Int = 4_096,
    private val responseDeadlineMillis: Long = 5_000
) : Closeable {
    private val operationMutex = Mutex()
    private val resourceLock = Any()

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    val isConnected: Boolean
        get() = synchronized(resourceLock) {
            val activeSocket = socket
            activeSocket != null &&
                activeSocket.isConnected &&
                !activeSocket.isClosed &&
                reader != null &&
                writer != null
        }

    suspend fun connect(endpoint: TcpEndpoint) {
        operationMutex.withLock {
            // 重连前先释放旧资源，确保任意时刻只存在一组活动流。
            close()

            withContext(Dispatchers.IO) {
                val newSocket = Socket()
                synchronized(resourceLock) {
                    socket = newSocket
                }

                try {
                    newSocket.connect(
                        InetSocketAddress(endpoint.host, endpoint.port),
                        connectTimeoutMillis
                    )
                    newSocket.soTimeout = readTimeoutMillis
                    val newReader = newSocket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                    val newWriter = newSocket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                    val retained = synchronized(resourceLock) {
                        if (socket !== newSocket || newSocket.isClosed) {
                            false
                        } else {
                            reader = newReader
                            writer = newWriter
                            true
                        }
                    }

                    if (!retained) {
                        newSocket.closeQuietly()
                        throw IOException("TCP client was closed while connecting")
                    }
                } catch (failure: Throwable) {
                    closeResourcesIfCurrent(newSocket)
                    newSocket.closeQuietly()
                    throw failure
                }
            }
        }
    }

    suspend fun sendAndReceive(wireText: String): String = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val connection = synchronized(resourceLock) {
                val activeSocket = socket
                    ?: throw IllegalStateException("TCP client is not connected")
                val activeWriter = writer
                    ?: throw IllegalStateException("TCP client is not connected")
                val activeReader = reader
                    ?: throw IllegalStateException("TCP client is not connected")
                ActiveConnection(activeSocket, activeReader, activeWriter)
            }

            try {
                connection.writer.write(wireText)
                connection.writer.flush()
                readBoundedResponseLine(connection)
            } catch (failure: Throwable) {
                // 发生协议或网络错误后连接状态已不可再信任，必须整体关闭。
                closeResourcesIfCurrent(connection.socket)
                throw failure
            }
        }
    }

    private fun readBoundedResponseLine(connection: ActiveConnection): String {
        val response = StringBuilder()
        val startedAtNanos = System.nanoTime()
        val deadlineDurationNanos = TimeUnit.MILLISECONDS.toNanos(responseDeadlineMillis)

        while (true) {
            val elapsedNanos = System.nanoTime() - startedAtNanos
            val remainingNanos = deadlineDurationNanos - elapsedNanos
            if (remainingNanos <= 0) {
                throw responseDeadlineExceeded()
            }

            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1)
            // 单次 read 超时防止完全无响应；绝对截止时间防止服务端用“慢速滴答”无限续命。
            val deadlineLimitsThisRead =
                readTimeoutMillis == 0 || remainingMillis <= readTimeoutMillis.toLong()
            val effectiveReadTimeoutMillis = if (readTimeoutMillis == 0) {
                remainingMillis
            } else {
                minOf(readTimeoutMillis.toLong(), remainingMillis)
            }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            connection.socket.soTimeout = effectiveReadTimeoutMillis

            val next = try {
                connection.reader.read()
            } catch (timeout: SocketTimeoutException) {
                if (deadlineLimitsThisRead) {
                    throw responseDeadlineExceeded(timeout)
                }
                throw SocketTimeoutException(
                    "Timed out waiting for a response character after $readTimeoutMillis milliseconds"
                ).apply { initCause(timeout) }
            }

            if (System.nanoTime() - startedAtNanos >= deadlineDurationNanos) {
                throw responseDeadlineExceeded()
            }
            if (next == -1) {
                throw EOFException("Server closed the connection before completing a response line")
            }
            if (next == '\n'.code) {
                // 同时兼容 LF 和 CRLF，但返回值不包含协议分隔符。
                if (response.isNotEmpty() && response.last() == '\r') {
                    response.setLength(response.length - 1)
                }
                return response.toString()
            }

            response.append(next.toChar())
            val isPossibleTrailingCr =
                response.length == maxResponseChars + 1 && response.last() == '\r'
            if (response.length > maxResponseChars && !isPossibleTrailingCr) {
                throw IOException(
                    "Response exceeded maximum length of $maxResponseChars characters"
                )
            }
        }
    }

    private fun responseDeadlineExceeded(cause: Throwable? = null): SocketTimeoutException =
        SocketTimeoutException(
            "Response deadline exceeded after $responseDeadlineMillis milliseconds"
        ).apply {
            if (cause != null) {
                initCause(cause)
            }
        }

    override fun close() {
        // 先在锁内摘除引用，再在锁外执行可能阻塞的 close。
        val resources = synchronized(resourceLock) { takeResourcesLocked() }
        resources.closeQuietly()
    }

    private fun closeResourcesIfCurrent(expectedSocket: Socket) {
        val resources = synchronized(resourceLock) {
            if (socket === expectedSocket) {
                takeResourcesLocked()
            } else {
                null
            }
        }
        resources?.closeQuietly()
    }

    private fun takeResourcesLocked(): DetachedResources {
        val resources = DetachedResources(socket, reader, writer)
        writer = null
        reader = null
        socket = null
        return resources
    }

    private fun DetachedResources.closeQuietly() {
        socket.closeQuietly()
        writer.closeQuietly()
        reader.closeQuietly()
    }

    private fun Closeable?.closeQuietly() {
        try {
            this?.close()
        } catch (_: Exception) {
            // Cleanup must not hide the operation failure that triggered it.
        }
    }

    private data class ActiveConnection(
        val socket: Socket,
        val reader: BufferedReader,
        val writer: BufferedWriter
    )

    private data class DetachedResources(
        val socket: Socket?,
        val reader: BufferedReader?,
        val writer: BufferedWriter?
    )
}
