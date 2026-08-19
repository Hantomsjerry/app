package com.example.myapp.voice

import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeQwenInferenceEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun loadsGeneratesAndClosesAFreshHandleForEachCallInOrder() = runBlocking {
        val store = modelStore()
        val bridge = RecordingBridge(loadResults = listOf(73L, 74L))
        val engine = NativeQwenInferenceEngine(
            modelStore = store,
            bridge = bridge,
            threadCount = 3
        )

        assertEquals("generated:first", engine.generate("first", 128))
        assertEquals("generated:second", engine.generate("second", 7))

        assertEquals(
            listOf(
                LoadCall(store.prepare().absolutePath, 1024, 3),
                LoadCall(store.prepare().absolutePath, 1024, 3)
            ),
            bridge.loads
        )
        assertEquals(
            listOf(
                GenerateCall(73L, "first", 128),
                GenerateCall(74L, "second", 7)
            ),
            bridge.generations
        )
        assertEquals(listOf(73L, 74L), bridge.closedHandles)
        assertEquals(
            listOf(
                "load:73", "generate:73:first", "close:73",
                "load:74", "generate:74:second", "close:74"
            ),
            bridge.callOrder
        )
    }

    @Test
    fun serializesPreparationLoadGenerationAndCloseAsOneCall() = runBlocking {
        val firstCloseEntered = CountDownLatch(1)
        val releaseFirstClose = CountDownLatch(1)
        val preparationChecks = AtomicInteger()
        val bridge = RecordingBridge(
            closeBlock = { handle ->
                if (handle == 41L) {
                    firstCloseEntered.countDown()
                    assertTrue(releaseFirstClose.await(5, TimeUnit.SECONDS))
                }
            }
        )
        val engine = engine(
            bridge = bridge,
            store = modelStore(onHash = { preparationChecks.incrementAndGet() })
        )

        val first = async(Dispatchers.Default) { engine.generate("first", 10) }
        assertTrue(firstCloseEntered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            engine.generate("second", 11)
        }

        assertEquals(1, preparationChecks.get())
        assertEquals(1, bridge.loads.size)
        assertEquals(1, bridge.generations.size)
        assertTrue(bridge.closedHandles.isEmpty())
        releaseFirstClose.countDown()
        assertEquals("generated:first", first.await())
        assertEquals("generated:second", second.await())
        assertEquals(1, preparationChecks.get())
        assertEquals(2, bridge.loads.size)
        assertEquals(2, bridge.closedHandles.size)
    }

    @Test
    fun nativeGenerationFailureClosesThatCallsHandleExactlyOnce() {
        val loadError = IOException("load failed")
        val loadBridge = RecordingBridge(loadBlock = { _, _, _ -> throw loadError })
        val loadEngine = engine(loadBridge)

        val actualLoadError = assertThrows(IOException::class.java) {
            runBlocking { loadEngine.generate("prompt", 128) }
        }
        assertEquals(loadError.message, actualLoadError.message)

        val generationError = IllegalStateException("decode failed")
        val generationBridge = RecordingBridge(
            generateBlock = { _, _, _ -> throw generationError }
        )
        val generationEngine = engine(generationBridge)

        val actualGenerationError = assertThrows(IllegalStateException::class.java) {
            runBlocking { generationEngine.generate("prompt", 128) }
        }
        assertEquals(generationError.message, actualGenerationError.message)
        assertEquals(listOf(41L), generationBridge.closedHandles)
    }

    @Test
    fun cancellationSuppressesNativeResultAndReleasesSerializationLock() = runBlocking {
        val staleEntered = CountDownLatch(1)
        val releaseStale = CountDownLatch(1)
        val bridge = RecordingBridge(
            loadResults = listOf(61L, 62L),
            generateBlock = { _, prompt, _ ->
                if (prompt == "stale") {
                    staleEntered.countDown()
                    assertTrue(releaseStale.await(5, TimeUnit.SECONDS))
                }
                "generated:$prompt"
            }
        )
        val engine = engine(bridge)
        val stale = async(Dispatchers.Default) { engine.generate("stale", 128) }
        assertTrue(staleEntered.await(5, TimeUnit.SECONDS))

        stale.cancel()
        releaseStale.countDown()
        try {
            stale.await()
            fail("A cancelled generation must not deliver its native result")
        } catch (_: CancellationException) {
            // Expected: the blocking JNI call may finish, but Kotlin drops its stale result.
        }

        assertEquals("generated:fresh", engine.generate("fresh", 8))
        assertEquals(
            listOf(
                GenerateCall(61L, "stale", 128),
                GenerateCall(62L, "fresh", 8)
            ),
            bridge.generations
        )
        assertEquals(listOf(61L, 62L), bridge.closedHandles)
    }

    @Test
    fun closeAfterCompletedCallsIsIdempotentAndDoesNotCloseAgain() = runBlocking {
        val bridge = RecordingBridge(loadResult = 91L)
        val engine = engine(bridge)
        engine.generate("prompt", 12)

        assertEquals(listOf(91L), bridge.closedHandles)

        engine.close()
        engine.close()

        assertEquals(listOf(91L), bridge.closedHandles)
    }

    @Test
    fun closeReturnsBeforeBlockedGenerationThenRejectsNewWorkAndFreesOnce() = runBlocking {
        val generationEntered = CountDownLatch(1)
        val releaseGeneration = CountDownLatch(1)
        val bridge = RecordingBridge(
            loadResult = 109L,
            generateBlock = { _, prompt, _ ->
                generationEntered.countDown()
                assertTrue(releaseGeneration.await(5, TimeUnit.SECONDS))
                "generated:$prompt"
            }
        )
        val engine = engine(bridge)
        val generation = async(Dispatchers.Default) { engine.generate("blocked", 32) }
        assertTrue(generationEntered.await(5, TimeUnit.SECONDS))
        val closeReturned = CountDownLatch(1)
        val closeThread = Thread {
            engine.close()
            closeReturned.countDown()
        }.apply { start() }

        try {
            assertTrue(
                "close() must return while native generation is still blocked",
                closeReturned.await(1, TimeUnit.SECONDS)
            )
            assertTrue(bridge.closedHandles.isEmpty())

            val error = try {
                engine.generate("rejected", 8)
                fail("Generation after close must fail immediately")
                null
            } catch (expected: IllegalStateException) {
                expected
            }
            assertEquals("Qwen inference engine is closed", error?.message)
            assertEquals(listOf("blocked"), bridge.generations.map { it.prompt })
        } finally {
            releaseGeneration.countDown()
            closeThread.join(5_000)
        }

        assertFalse("close() thread must not deadlock", closeThread.isAlive)
        assertEquals("generated:blocked", generation.await())
        engine.close()
        assertEquals(listOf(109L), bridge.closedHandles)
    }

    @Test
    fun closeBeforeFirstUseDoesNotLoadOrFreeNativeModel() {
        val bridge = RecordingBridge()
        val engine = engine(bridge)

        engine.close()
        engine.close()

        assertTrue(bridge.loads.isEmpty())
        assertTrue(bridge.closedHandles.isEmpty())
    }

    @Test
    fun admittedGenerationContinuesAfterCloseAndOwnsItsHandle() = runBlocking {
        supervisorScope {
            val preparationEntered = CountDownLatch(1)
            val releasePreparation = CountDownLatch(1)
            val bridge = RecordingBridge()
            val engine = NativeQwenInferenceEngine(
                modelStore = modelStore(openAsset = {
                    object : ByteArrayInputStream("test-model".toByteArray()) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            preparationEntered.countDown()
                            assertTrue(releasePreparation.await(5, TimeUnit.SECONDS))
                            return super.read(buffer, offset, length)
                        }
                    }
                }),
                bridge = bridge,
                threadCount = 2
            )
            val generation = async(Dispatchers.Default) { engine.generate("prompt", 128) }
            assertTrue(preparationEntered.await(5, TimeUnit.SECONDS))

            val closeReturned = CountDownLatch(1)
            val closeThread = Thread {
                engine.close()
                closeReturned.countDown()
            }.apply { start() }

            try {
                assertTrue(closeReturned.await(1, TimeUnit.SECONDS))
                releasePreparation.countDown()

                assertEquals("generated:prompt", generation.await())
                assertEquals(1, bridge.loads.size)
                assertEquals(1, bridge.generations.size)
                assertEquals(listOf(41L), bridge.closedHandles)
            } finally {
                releasePreparation.countDown()
                closeThread.join(5_000)
            }

            assertFalse(closeThread.isAlive)
        }
    }

    @Test
    fun nativeLoadFailureDoesNotCloseAnInvalidHandle() {
        val loadError = IOException("load failed")
        val bridge = RecordingBridge(loadBlock = { _, _, _ -> throw loadError })
        val engine = engine(bridge)

        val error = assertThrows(IOException::class.java) {
            runBlocking { engine.generate("prompt", 128) }
        }

        assertEquals(loadError.message, error.message)
        assertTrue(bridge.generations.isEmpty())
        assertTrue(bridge.closedHandles.isEmpty())
    }

    @Test
    fun cancellationSignalsNativeGenerationAndClosesHandle() = runBlocking {
        val nativeEntered = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)
        val handleClosed = CountDownLatch(1)
        val bridge = object : NativeQwenBridgeApi {
            override fun loadModel(path: String, contextSize: Int, threads: Int) = 41L

            override fun generate(handle: Long, prompt: String, maxTokens: Int) =
                error("engine must use cancellable generation")

            override fun generateCancellable(
                handle: Long,
                prompt: String,
                maxTokens: Int,
                cancelled: AtomicBoolean
            ): String {
                nativeEntered.countDown()
                while (!cancelled.get()) Thread.yield()
                cancellationObserved.countDown()
                return "stale"
            }

            override fun close(handle: Long) {
                handleClosed.countDown()
            }
        }
        val engine = engine(bridge)
        val generation = async(Dispatchers.Default) { engine.generate("prompt", 128) }
        assertTrue(nativeEntered.await(5, TimeUnit.SECONDS))

        generation.cancel()

        assertTrue(cancellationObserved.await(1, TimeUnit.SECONDS))
        assertTrue(handleClosed.await(1, TimeUnit.SECONDS))
        assertTrue(generation.isCancelled)
    }

    @Test
    fun zeroHandleFailsWithoutGenerationOrNativeClose() {
        val bridge = RecordingBridge(loadResult = 0L)
        val engine = engine(bridge)

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.generate("prompt", 128) }
        }

        assertEquals("Native model load returned an invalid handle", error.message)
        assertTrue(bridge.generations.isEmpty())
        assertTrue(bridge.closedHandles.isEmpty())
    }

    @Test
    fun closeWinningBeforeGenerationAdmissionPreventsPreparationAndJni() = runBlocking {
        supervisorScope {
            var assetOpened = false
            val bridge = RecordingBridge()
            val engine = NativeQwenInferenceEngine(
                modelStore = modelStore(openAsset = {
                    assetOpened = true
                    "test-model".byteInputStream()
                }),
                bridge = bridge,
                threadCount = 2
            )
            val startGeneration = CountDownLatch(1)
            val releaseGeneration = CountDownLatch(1)
            val generation = async(Dispatchers.Default) {
                startGeneration.countDown()
                assertTrue(releaseGeneration.await(5, TimeUnit.SECONDS))
                engine.generate("prompt", 128)
            }
            assertTrue(startGeneration.await(5, TimeUnit.SECONDS))
            engine.close()
            releaseGeneration.countDown()

            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { generation.await() }
            }

            assertEquals("Qwen inference engine is closed", error.message)
            assertFalse(assetOpened)
            assertTrue(bridge.loads.isEmpty())
            assertTrue(bridge.generations.isEmpty())
        }
    }

    private fun engine(
        bridge: NativeQwenBridgeApi,
        store: QwenModelStore = modelStore()
    ): NativeQwenInferenceEngine =
        NativeQwenInferenceEngine(
            modelStore = store,
            bridge = bridge,
            threadCount = 2
        )

    private fun modelStore(
        openAsset: () -> ByteArrayInputStream = { "test-model".byteInputStream() },
        onHash: () -> Unit = {}
    ): QwenModelStore {
        val modelBytes = "test-model".toByteArray()
        return QwenModelStore(
            modelDirectory = temporaryFolder.newFolder(),
            fileName = "model.gguf",
            expectedBytes = modelBytes.size.toLong(),
            expectedSha256 = MessageDigest.getInstance("SHA-256")
                .digest(modelBytes)
                .joinToString("") { "%02x".format(it) },
            openAsset = openAsset,
            calculateSha256 = { file ->
                onHash()
                MessageDigest.getInstance("SHA-256")
                    .digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
            }
        )
    }

    private data class LoadCall(val path: String, val contextSize: Int, val threads: Int)
    private data class GenerateCall(val handle: Long, val prompt: String, val maxTokens: Int)

    private class RecordingBridge(
        private val loadResult: Long = 41L,
        private val loadResults: List<Long> = listOf(loadResult),
        private val loadBlock: ((String, Int, Int) -> Long)? = null,
        private val generateBlock: ((Long, String, Int) -> String)? = null,
        private val closeBlock: ((Long) -> Unit)? = null
    ) : NativeQwenBridgeApi {
        val loads = mutableListOf<LoadCall>()
        val generations = mutableListOf<GenerateCall>()
        val closedHandles = mutableListOf<Long>()
        val callOrder = mutableListOf<String>()

        override fun loadModel(path: String, contextSize: Int, threads: Int): Long {
            val loadIndex = synchronized(loads) {
                loads += LoadCall(path, contextSize, threads)
                loads.lastIndex
            }
            val handle = loadBlock?.invoke(path, contextSize, threads)
                ?: loadResults[loadIndex.coerceAtMost(loadResults.lastIndex)]
            synchronized(callOrder) {
                callOrder += "load:$handle"
            }
            return handle
        }

        override fun generate(handle: Long, prompt: String, maxTokens: Int): String {
            synchronized(generations) {
                generations += GenerateCall(handle, prompt, maxTokens)
            }
            synchronized(callOrder) {
                callOrder += "generate:$handle:$prompt"
            }
            return generateBlock?.invoke(handle, prompt, maxTokens) ?: "generated:$prompt"
        }

        override fun close(handle: Long) {
            closeBlock?.invoke(handle)
            synchronized(closedHandles) {
                closedHandles += handle
            }
            synchronized(callOrder) {
                callOrder += "close:$handle"
            }
        }
    }
}
