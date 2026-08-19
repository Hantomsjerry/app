package com.example.myapp.voice

import java.io.ByteArrayInputStream
import java.io.File
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

class SherpaOnnxModelStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val assets = linkedMapOf(
        "encoder-epoch-99-avg-1.int8.onnx" to "encoder".toByteArray(),
        "decoder-epoch-99-avg-1.int8.onnx" to "decoder".toByteArray(),
        "joiner-epoch-99-avg-1.int8.onnx" to "joiner".toByteArray(),
        "tokens.txt" to "tokens".toByteArray(),
        "bpe.vocab" to "vocab".toByteArray(),
        "hotwords.txt" to "hotwords".toByteArray()
    )

    @Test
    fun prepareCopiesAndReturnsAllSixVerifiedFiles() = runTest {
        val store = store()

        val files = store.prepare()

        assertArrayEquals(assets.getValue("encoder-epoch-99-avg-1.int8.onnx"), files.encoder.readBytes())
        assertArrayEquals(assets.getValue("decoder-epoch-99-avg-1.int8.onnx"), files.decoder.readBytes())
        assertArrayEquals(assets.getValue("joiner-epoch-99-avg-1.int8.onnx"), files.joiner.readBytes())
        assertArrayEquals(assets.getValue("tokens.txt"), files.tokens.readBytes())
        assertArrayEquals(assets.getValue("bpe.vocab"), files.bpeVocab.readBytes())
        assertArrayEquals(assets.getValue("hotwords.txt"), files.hotwords.readBytes())
    }

    @Test
    fun prepareReusesFilesWhenLengthAndHashMatch() = runTest {
        val opensByAsset = mutableMapOf<String, AtomicInteger>()
        val store = store(openAsset = { assetPath ->
            opensByAsset.getOrPut(assetPath) { AtomicInteger() }.incrementAndGet()
            assetBytes(assetPath).inputStream()
        })

        store.prepare()
        store.prepare()

        assertEquals(assetPaths().toSet(), opensByAsset.keys)
        assertTrue(opensByAsset.values.all { it.get() == 1 })
    }

    @Test
    fun truncatedFileIsReplacedAtomically() = runTest {
        val modelDirectory = File(temporaryFolder.root, "sherpa")
        assertTrue(modelDirectory.mkdirs())
        val target = File(modelDirectory, "encoder-epoch-99-avg-1.int8.onnx")
        target.writeText("bad")
        val stalePartial = File(modelDirectory, "encoder-epoch-99-avg-1.int8.onnx.partial")
        stalePartial.writeText("stale")
        var targetWasAbsent = false
        val store = store(
            modelDirectory = modelDirectory,
            publishAtomically = { partial, final ->
                if (final.name == target.name) targetWasAbsent = !final.exists()
                Files.move(partial.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        )

        val files = store.prepare()

        assertTrue(targetWasAbsent)
        assertArrayEquals(assets.getValue("encoder-epoch-99-avg-1.int8.onnx"), files.encoder.readBytes())
        assertFalse(stalePartial.exists())
    }

    @Test
    fun hashMismatchDeletesPartialAndThrowsModelPreparationException() = runTest {
        val badManifests = manifests().mapIndexed { index, manifest ->
            if (index == 0) manifest.copy(sha256 = sha256("different".toByteArray())) else manifest
        }
        val store = store(manifests = badManifests)

        val failure = assertPreparationFails(store)

        assertTrue(failure.message!!.contains("SHA-256"))
        assertFalse(File(temporaryFolder.root, "sherpa/encoder-epoch-99-avg-1.int8.onnx.partial").exists())
        assertFalse(File(temporaryFolder.root, "sherpa/encoder-epoch-99-avg-1.int8.onnx").exists())
    }

    @Test
    fun sameLengthCorruptionWithRestoredTimestampIsReplacedFromAsset() = runTest {
        val store = store()
        val first = store.prepare()
        val originalBytes = assets.getValue("encoder-epoch-99-avg-1.int8.onnx")
        val originalLastModified = first.encoder.lastModified()
        val corruptedBytes = ByteArray(originalBytes.size) { index ->
            (originalBytes[index].toInt() xor 0x5a).toByte()
        }

        first.encoder.writeBytes(corruptedBytes)
        assertTrue(first.encoder.setLastModified(originalLastModified))
        val repaired = store.prepare()

        assertArrayEquals(originalBytes, repaired.encoder.readBytes())
    }

    @Test
    fun reorderedManifestReturnsFilesByTheirSemanticNames() = runTest {
        val files = store(manifests = manifests().reversed()).prepare()

        assertEquals("encoder-epoch-99-avg-1.int8.onnx", files.encoder.name)
        assertEquals("decoder-epoch-99-avg-1.int8.onnx", files.decoder.name)
        assertEquals("joiner-epoch-99-avg-1.int8.onnx", files.joiner.name)
        assertEquals("tokens.txt", files.tokens.name)
        assertEquals("bpe.vocab", files.bpeVocab.name)
        assertEquals("hotwords.txt", files.hotwords.name)
    }

    @Test
    fun traversalAndUnknownManifestNamesAreRejectedWithoutCreatingOutsideFiles() = runTest {
        val outsideFile = File(temporaryFolder.root, "escaped.onnx")
        val traversal = manifests().toMutableList().apply {
            this[0] = this[0].copy(fileName = "../escaped.onnx")
        }
        val unknown = manifests().toMutableList().apply {
            this[0] = this[0].copy(fileName = "unknown.onnx")
        }

        assertManifestIsRejected(traversal)
        assertManifestIsRejected(unknown)

        assertFalse(outsideFile.exists())
        assertFalse(File(temporaryFolder.root, "sherpa/unknown.onnx").exists())
    }

    @Test
    fun duplicateRequiredManifestNameIsRejected() = runTest {
        val duplicate = manifests().toMutableList().apply {
            this[0] = this[0].copy(fileName = "decoder-epoch-99-avg-1.int8.onnx")
        }

        assertManifestIsRejected(duplicate)
    }

    @Test
    fun concurrentPrepareForSameDirectoryCopiesEachAssetOnce() = runTest {
        val firstAssetOpened = CountDownLatch(1)
        val secondAssetOpened = CountDownLatch(1)
        val allowCopyToFinish = CountDownLatch(1)
        val assetOpens = AtomicInteger()
        val firstStore = store(openAsset = { assetPath ->
            assetOpens.incrementAndGet()
            object : ByteArrayInputStream(assetBytes(assetPath)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (assetPath.endsWith("encoder-epoch-99-avg-1.int8.onnx")) {
                        firstAssetOpened.countDown()
                        assertTrue(allowCopyToFinish.await(5, TimeUnit.SECONDS))
                    }
                    return super.read(buffer, offset, length)
                }
            }
        })
        val secondStore = store(openAsset = { assetPath ->
            assetOpens.incrementAndGet()
            secondAssetOpened.countDown()
            assetBytes(assetPath).inputStream()
        })

        val first = async(start = CoroutineStart.UNDISPATCHED) { firstStore.prepare() }
        try {
            assertTrue(firstAssetOpened.await(5, TimeUnit.SECONDS))
            val second = async(start = CoroutineStart.UNDISPATCHED) { secondStore.prepare() }
            assertFalse(secondAssetOpened.await(250, TimeUnit.MILLISECONDS))
            allowCopyToFinish.countDown()

            assertEquals(first.await(), second.await())
            assertEquals(assets.size, assetOpens.get())
        } finally {
            allowCopyToFinish.countDown()
        }
    }

    private fun store(
        modelDirectory: File = File(temporaryFolder.root, "sherpa"),
        manifests: List<SherpaOnnxModelStore.AssetManifest> = manifests(),
        openAsset: (String) -> InputStream = { assetBytes(it).inputStream() },
        publishAtomically: ((File, File) -> Unit)? = null
    ) = SherpaOnnxModelStore(
        modelDirectory = modelDirectory,
        manifests = manifests,
        openAsset = openAsset,
        publishAtomically = publishAtomically
    )

    private fun manifests() = assets.map { (name, bytes) ->
        SherpaOnnxModelStore.AssetManifest(
            assetPath = "test/$name",
            fileName = name,
            expectedBytes = bytes.size.toLong(),
            sha256 = sha256(bytes)
        )
    }

    private fun assetPaths() = assets.keys.map { "test/$it" }

    private fun assetBytes(assetPath: String): ByteArray = assets.getValue(assetPath.substringAfterLast('/'))

    private suspend fun assertManifestIsRejected(
        invalidManifests: List<SherpaOnnxModelStore.AssetManifest>
    ) {
        try {
            store(manifests = invalidManifests).prepare()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected invalid manifest to be rejected")
    }

    private suspend fun assertPreparationFails(store: SherpaOnnxModelStore): ModelPreparationException {
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
