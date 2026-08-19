package com.example.myapp.voice

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SenseVoiceModelStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val assets = linkedMapOf(
        "model.int8.onnx" to "sense-model".toByteArray(),
        "tokens.txt" to "<blank> 0".toByteArray()
    )

    @Test
    fun preparesExactlyModelAndTokens() = runTest {
        val files = testStore(assets).prepare()

        assertArrayEquals(assets.getValue("model.int8.onnx"), files.model.readBytes())
        assertArrayEquals(assets.getValue("tokens.txt"), files.tokens.readBytes())
    }

    @Test
    fun reusesVerifiedFilesWithoutOpeningAssets() = runTest {
        val store = testStore(assets)
        store.prepare()
        var opened = false
        val reused = testStore(assets, openAsset = {
            opened = true
            error("verified assets should be reused")
        }).prepare()

        assertFalse(opened)
        assertTrue(reused.model.exists())
        assertTrue(reused.tokens.exists())
    }

    @Test
    fun removesStalePartialsWhenFilesAreVerified() = runTest {
        val store = testStore(assets)
        val files = store.prepare()
        File(files.model.parentFile, "model.int8.onnx.partial").writeText("stale")
        File(files.tokens.parentFile, "tokens.txt.partial").writeText("stale")

        store.prepare()

        assertFalse(File(files.model.parentFile, "model.int8.onnx.partial").exists())
        assertFalse(File(files.tokens.parentFile, "tokens.txt.partial").exists())
    }

    @Test
    fun rejectsWrongLengthAndCleansPartial() = runTest {
        val store = testStore(assets, manifests = manifests().mapIndexed { index, manifest ->
            if (index == 0) manifest.copy(expectedBytes = manifest.expectedBytes + 1) else manifest
        })

        assertPreparationFails(store)
        assertFalse(File(temporaryFolder.root, "sense/model.int8.onnx.partial").exists())
        assertFalse(File(temporaryFolder.root, "sense/model.int8.onnx").exists())
    }

    @Test
    fun rejectsWrongHashAndCleansPartial() = runTest {
        val store = testStore(assets, manifests = manifests().mapIndexed { index, manifest ->
            if (index == 0) manifest.copy(sha256 = sha256("wrong".toByteArray())) else manifest
        })

        val failure = assertPreparationFails(store)

        assertTrue(failure.message!!.contains("SHA-256"))
        assertFalse(File(temporaryFolder.root, "sense/model.int8.onnx.partial").exists())
        assertFalse(File(temporaryFolder.root, "sense/model.int8.onnx").exists())
    }

    @Test
    fun failedAtomicPublicationCleansPartialAndLeavesFinalAbsent() = runTest {
        val store = testStore(
            assets,
            publishAtomically = { _, _ -> throw IOException("atomic move unsupported") }
        )

        val failure = assertPreparationFails(store)

        assertTrue(failure.message!!.contains("atomically"))
        assertFalse(File(temporaryFolder.root, "sense/model.int8.onnx.partial").exists())
        assertFalse(File(temporaryFolder.root, "sense/model.int8.onnx").exists())
    }

    @Test
    fun plainFileAndPathConfinementAreEnforced() = runTest {
        val duplicate = manifests().toMutableList().apply {
            this[0] = this[0].copy(fileName = "tokens.txt")
        }
        val traversal = manifests().toMutableList().apply {
            this[0] = this[0].copy(fileName = "../escaped.onnx")
        }

        assertManifestRejected(duplicate)
        assertManifestRejected(traversal)
        assertFalse(File(temporaryFolder.root, "escaped.onnx").exists())
    }

    private fun testStore(
        sourceAssets: Map<String, ByteArray>,
        modelDirectory: File = File(temporaryFolder.root, "sense"),
        manifests: List<SenseVoiceModelStore.AssetManifest> = manifests(sourceAssets),
        openAsset: (String) -> InputStream = { path ->
            sourceAssets.getValue(path.substringAfterLast('/')).inputStream()
        },
        publishAtomically: ((File, File) -> Unit)? = null
    ) = SenseVoiceModelStore(
        modelDirectory = modelDirectory,
        manifests = manifests,
        openAsset = openAsset,
        publishAtomically = publishAtomically
    )

    private fun manifests(sourceAssets: Map<String, ByteArray> = assets) = sourceAssets.map { (name, bytes) ->
        SenseVoiceModelStore.AssetManifest(
            assetPath = "test/$name",
            fileName = name,
            expectedBytes = bytes.size.toLong(),
            sha256 = sha256(bytes)
        )
    }

    private suspend fun assertPreparationFails(store: SenseVoiceModelStore): ModelPreparationException = try {
        store.prepare()
        throw AssertionError("Expected model preparation to fail")
    } catch (expected: ModelPreparationException) {
        expected
    }

    private suspend fun assertManifestRejected(manifests: List<SenseVoiceModelStore.AssetManifest>) {
        try {
            testStore(assets, manifests = manifests).prepare()
            throw AssertionError("Expected invalid manifest to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
