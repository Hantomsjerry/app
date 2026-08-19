package com.example.myapp.voice

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QwenModelStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val validBytes = "valid-model".toByteArray()
    private val validHash = sha256(validBytes)

    @Test
    fun pinsOfficialModelMetadata() = runTest {
        assertEquals(1_117_320_736L, QwenModelStore.EXPECTED_BYTES)
        assertEquals(
            "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            QwenModelStore.EXPECTED_SHA256
        )
    }

    @Test
    fun reusesVerifiedFinalWithoutOpeningAsset() = runTest {
        val finalFile = File(temporaryFolder.root, "model.gguf")
        finalFile.writeBytes(validBytes)
        var opened = false
        val store = store { 
            opened = true
            validBytes.inputStream()
        }

        assertEquals(finalFile, store.prepare())
        assertArrayEquals(validBytes, finalFile.readBytes())
        assertFalse(opened)
    }

    @Test
    fun repeatedPrepareReusesVerifiedMetadataWithoutRehashing() = runTest {
        val finalFile = File(temporaryFolder.root, "model.gguf")
        finalFile.writeBytes(validBytes)
        val hashChecks = AtomicInteger()
        val store = storeWithHooks(
            openAsset = { error("asset should not be opened") },
            calculateSha256 = { file ->
                hashChecks.incrementAndGet()
                sha256(file.readBytes())
            }
        )

        assertEquals(finalFile, store.prepare())
        assertEquals(finalFile, store.prepare())
        assertEquals(1, hashChecks.get())
    }

    @Test
    fun changedFileMetadataInvalidatesVerifiedCache() = runTest {
        val finalFile = File(temporaryFolder.root, "model.gguf")
        finalFile.writeBytes(validBytes)
        val assetOpens = AtomicInteger()
        val store = store {
            assetOpens.incrementAndGet()
            validBytes.inputStream()
        }

        store.prepare()
        finalFile.writeText("bad")
        store.prepare()

        assertEquals(1, assetOpens.get())
        assertArrayEquals(validBytes, finalFile.readBytes())
    }

    @Test
    fun corruptFinalIsRemovedBeforeAtomicPublicationAndReplaced() = runTest {
        val finalFile = File(temporaryFolder.root, "model.gguf")
        finalFile.writeText("corrupt")
        var targetWasAbsent = false
        val store = storeWithHooks(
            openAsset = { validBytes.inputStream() },
            publishAtomically = { partial, final ->
                targetWasAbsent = !final.exists()
                if (!targetWasAbsent) throw IOException("atomic target was not absent")
                Files.move(partial.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        )

        assertEquals(finalFile, store.prepare())
        assertTrue(targetWasAbsent)
        assertArrayEquals(validBytes, finalFile.readBytes())
        assertFalse(File(temporaryFolder.root, "model.gguf.partial").exists())
    }

    @Test
    fun rejectsShortAssetAndCleansPartial() = runTest {
        val store = store(expectedBytes = validBytes.size.toLong()) {
            validBytes.dropLast(1).toByteArray().inputStream()
        }

        assertPreparationFails(store)
        assertFalse(File(temporaryFolder.root, "model.gguf.partial").exists())
        assertFalse(File(temporaryFolder.root, "model.gguf").exists())
    }

    @Test
    fun rejectsLongAssetAndCleansPartial() = runTest {
        val store = store(expectedBytes = validBytes.size.toLong()) {
            (validBytes + 0).inputStream()
        }

        assertPreparationFails(store)
        assertFalse(File(temporaryFolder.root, "model.gguf.partial").exists())
    }

    @Test
    fun rejectsBadHashAndCleansPartial() = runTest {
        val store = store(expectedHash = sha256("other-model".toByteArray())) {
            validBytes.inputStream()
        }

        assertPreparationFails(store)
        assertFalse(File(temporaryFolder.root, "model.gguf.partial").exists())
        assertFalse(File(temporaryFolder.root, "model.gguf").exists())
    }

    @Test
    fun cleansPartialAfterStreamFailure() = runTest {
        val store = store {
            object : InputStream() {
                private var reads = 0

                override fun read(): Int = throw IOException("read should use the bulk method")

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ == 0) {
                        buffer[offset] = validBytes[0]
                        return 1
                    }
                    throw IOException("asset interrupted")
                }
            }
        }

        assertPreparationFails(store)
        assertFalse(File(temporaryFolder.root, "model.gguf.partial").exists())
    }

    @Test
    fun copyFailureAfterCorruptFinalRemovalLeavesFinalAbsent() = runTest {
        val finalFile = File(temporaryFolder.root, "model.gguf")
        finalFile.writeText("previous-copy")
        val store = store { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }

        assertPreparationFails(store)
        assertFalse(finalFile.exists())
        assertFalse(File(temporaryFolder.root, "model.gguf.partial").exists())
    }

    @Test
    fun publicationFailureAfterCorruptFinalRemovalLeavesFinalAbsentAndCleansPartial() = runTest {
        val finalFile = File(temporaryFolder.root, "model.gguf")
        finalFile.writeText("corrupt-before-copy")
        var targetWasAbsent = false
        val store = storeWithHooks(
            openAsset = { validBytes.inputStream() },
            publishAtomically = { _, final ->
                targetWasAbsent = !final.exists()
                throw IOException("atomic move unsupported")
            }
        )

        val failure = assertPreparationFails(store)

        assertTrue(failure.message!!.contains("atomically"))
        assertTrue(targetWasAbsent)
        assertFalse(finalFile.exists())
        assertFalse(File(temporaryFolder.root, "model.gguf.partial").exists())
    }

    @Test
    fun verifiedFinalShortCircuitsWithoutOpeningAssetOrPublishing() = runTest {
        val finalFile = File(temporaryFolder.root, "model.gguf")
        finalFile.writeBytes(validBytes)
        var opened = false
        var published = false
        val store = storeWithHooks(
            openAsset = {
                opened = true
                validBytes.inputStream()
            },
            publishAtomically = { _, _ -> published = true }
        )

        assertEquals(finalFile, store.prepare())
        assertFalse(opened)
        assertFalse(published)
    }

    @Test
    fun refusesToCopyWhenStalePartialCannotBeDeleted() = runTest {
        val partialFile = File(temporaryFolder.root, "model.gguf.partial")
        partialFile.writeText("stale")
        var opened = false
        val store = storeWithHooks(
            openAsset = {
                opened = true
                validBytes.inputStream()
            },
            deletePartial = { false }
        )

        val failure = assertPreparationFails(store)

        assertTrue(failure.message!!.contains("stale partial"))
        assertFalse(opened)
    }

    @Test
    fun reportsPartialCleanupFailureAfterCopyFailure() = runTest {
        val store = storeWithHooks(
            openAsset = {
                object : InputStream() {
                    override fun read(): Int = throw IOException("asset interrupted")
                }
            },
            deletePartial = { false }
        )

        val failure = assertPreparationFails(store)

        assertTrue(File(temporaryFolder.root, "model.gguf.partial").exists())
        assertTrue(failure.message!!.contains("delete partial"))
    }

    @Test
    fun twoInstancesSerializePreparationForTheSameFinalPath() = runTest {
        val firstAssetOpened = CountDownLatch(1)
        val secondAssetOpened = CountDownLatch(1)
        val allowCopyToFinish = CountDownLatch(1)
        val assetOpens = AtomicInteger()
        val firstStore = store {
            assetOpens.incrementAndGet()
            object : ByteArrayInputStream(validBytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    firstAssetOpened.countDown()
                    assertTrue(allowCopyToFinish.await(5, TimeUnit.SECONDS))
                    return super.read(buffer, offset, length)
                }
            }
        }
        val secondStore = store {
            assetOpens.incrementAndGet()
            secondAssetOpened.countDown()
            validBytes.inputStream()
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { firstStore.prepare() }
        try {
            assertTrue(firstAssetOpened.await(5, TimeUnit.SECONDS))
            val second = async(start = CoroutineStart.UNDISPATCHED) { secondStore.prepare() }
            assertFalse(secondAssetOpened.await(250, TimeUnit.MILLISECONDS))
            allowCopyToFinish.countDown()

            assertEquals(first.await(), second.await())
            assertEquals(1, assetOpens.get())
            assertArrayEquals(validBytes, File(temporaryFolder.root, "model.gguf").readBytes())
        } finally {
            allowCopyToFinish.countDown()
        }
    }

    private fun store(
        expectedBytes: Long = validBytes.size.toLong(),
        expectedHash: String = validHash,
        openAsset: () -> InputStream
    ) = QwenModelStore(
        modelDirectory = temporaryFolder.root,
        fileName = "model.gguf",
        expectedBytes = expectedBytes,
        expectedSha256 = expectedHash,
        openAsset = openAsset
    )

    private fun storeWithHooks(
        expectedBytes: Long = validBytes.size.toLong(),
        expectedHash: String = validHash,
        openAsset: () -> InputStream,
        calculateSha256: (File) -> String = { sha256(it.readBytes()) },
        publishAtomically: (File, File) -> Unit = { _, _ -> error("unused publication hook") },
        deletePartial: (File) -> Boolean = { file -> file.delete() }
    ) = QwenModelStore(
        modelDirectory = temporaryFolder.root,
        fileName = "model.gguf",
        expectedBytes = expectedBytes,
        expectedSha256 = expectedHash,
        openAsset = openAsset,
        calculateSha256 = calculateSha256,
        publishAtomically = publishAtomically,
        deletePartial = deletePartial
    )

    private suspend fun assertPreparationFails(store: QwenModelStore): ModelPreparationException {
        try {
            store.prepare()
        } catch (expected: ModelPreparationException) {
            return expected
        }
        throw AssertionError("Expected model preparation to fail")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
