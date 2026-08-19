package com.example.myapp.voice

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SenseVoiceModelFiles(
    val model: File,
    val tokens: File
)

class SenseVoiceModelStore internal constructor(
    private val modelDirectory: File,
    private val manifests: List<SherpaOnnxModelStore.AssetManifest>,
    private val openAsset: (String) -> InputStream,
    private val calculateSha256: (File) -> String = ::sha256,
    private val publishAtomically: ((File, File) -> Unit)? = null,
    private val deleteFile: (File) -> Boolean = { file -> file.delete() }
) {
    constructor(context: Context) : this(
        modelDirectory = File(context.filesDir, "models/$MODEL_DIRECTORY"),
        manifests = ASSET_MANIFESTS,
        openAsset = context.assets::open
    )

    private val normalizedModelDirectory = modelDirectory.toPath().toAbsolutePath().normalize()
    private val manifestsByFileName: Map<String, SherpaOnnxModelStore.AssetManifest>

    init {
        require(manifests.size == REQUIRED_FILE_COUNT) { "Expected $REQUIRED_FILE_COUNT SenseVoice model assets" }
        val fileNames = manifests.map(SherpaOnnxModelStore.AssetManifest::fileName)
        require(fileNames.all(::isPlainFileName)) { "SenseVoice model asset names must be plain file names" }
        require(fileNames.toSet() == REQUIRED_FILE_NAMES) {
            "SenseVoice model manifest must contain each required asset exactly once"
        }
        manifests.forEach { manifest ->
            require(resolveOutputPath(normalizedModelDirectory, manifest.fileName).parent == normalizedModelDirectory) {
                "SenseVoice model asset output escapes its model directory: ${manifest.fileName}"
            }
        }
        manifestsByFileName = manifests.associateBy(SherpaOnnxModelStore.AssetManifest::fileName)
    }

    @Throws(ModelPreparationException::class)
    suspend fun prepare(): SenseVoiceModelFiles = withContext(Dispatchers.IO) {
        preparationLocks.computeIfAbsent(normalizedModelDirectory) { Mutex() }.withLock {
            prepareLocked(normalizedModelDirectory.toFile())
        }
    }

    private fun prepareLocked(directory: File): SenseVoiceModelFiles {
        ensureDirectory(directory)
        val preparedFiles = manifestsByFileName.mapValues { (_, manifest) -> prepareAsset(directory, manifest) }
        return SenseVoiceModelFiles(
            model = preparedFiles.getValue(MODEL_FILE_NAME),
            tokens = preparedFiles.getValue(TOKENS_FILE_NAME)
        )
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw ModelPreparationException("Unable to create SenseVoice model directory: ${directory.path}")
        }
        if (!directory.isDirectory) {
            throw ModelPreparationException("SenseVoice model path is not a directory: ${directory.path}")
        }
    }

    private fun prepareAsset(directory: File, manifest: SherpaOnnxModelStore.AssetManifest): File {
        val finalFile = resolveOutputPath(directory.toPath().toAbsolutePath().normalize(), manifest.fileName).toFile()
        val partialFile = File(directory, "${manifest.fileName}.partial")
        if (isVerified(finalFile, manifest)) {
            removeStalePartial(partialFile)
            return finalFile
        }

        try {
            removeUnverifiedFinal(finalFile)
            removeStalePartial(partialFile)
            openAsset(manifest.assetPath).use { input ->
                partialFile.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            verify(partialFile, manifest)
            publish(partialFile, finalFile)
            return finalFile
        } catch (error: Exception) {
            val failure = error as? ModelPreparationException
                ?: ModelPreparationException("Unable to prepare SenseVoice model asset ${manifest.fileName}: ${error.message}", error)
            throw cleanupPartial(partialFile, failure)
        }
    }

    private fun isVerified(file: File, manifest: SherpaOnnxModelStore.AssetManifest): Boolean = try {
        file.isFile && file.length() == manifest.expectedBytes &&
            calculateSha256(file).equals(manifest.sha256, ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    private fun verify(file: File, manifest: SherpaOnnxModelStore.AssetManifest) {
        if (file.length() != manifest.expectedBytes) {
            throw ModelPreparationException(
                "SenseVoice model asset ${manifest.fileName} length mismatch: expected " +
                    "${manifest.expectedBytes} bytes, got ${file.length()}"
            )
        }
        if (!calculateSha256(file).equals(manifest.sha256, ignoreCase = true)) {
            throw ModelPreparationException("SenseVoice model asset ${manifest.fileName} SHA-256 verification failed")
        }
    }

    private fun publish(partialFile: File, finalFile: File) {
        try {
            (publishAtomically ?: ::moveAtomically).invoke(partialFile, finalFile)
        } catch (error: Exception) {
            throw ModelPreparationException(
                "Unable to publish verified SenseVoice model asset ${finalFile.name} atomically: ${error.message}",
                error
            )
        }
    }

    private fun moveAtomically(partialFile: File, finalFile: File) {
        Files.move(partialFile.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun removeUnverifiedFinal(finalFile: File) {
        if (finalFile.exists() && !deleteFile(finalFile)) {
            throw ModelPreparationException("Unable to remove unverified SenseVoice model asset: ${finalFile.path}")
        }
    }

    private fun removeStalePartial(partialFile: File) {
        if (partialFile.exists() && !deleteFile(partialFile)) {
            throw ModelPreparationException("Unable to remove stale SenseVoice model partial: ${partialFile.path}")
        }
    }

    private fun cleanupPartial(partialFile: File, failure: ModelPreparationException): ModelPreparationException {
        if (partialFile.exists() && !deleteFile(partialFile)) {
            failure.addSuppressed(IOException("Unable to delete partial SenseVoice model asset: ${partialFile.path}"))
        }
        return failure
    }

    private fun resolveOutputPath(directory: Path, fileName: String): Path {
        val outputPath = directory.resolve(fileName).normalize()
        if (!outputPath.startsWith(directory) || outputPath.parent != directory) {
            throw IllegalArgumentException("SenseVoice model asset output escapes its model directory: $fileName")
        }
        return outputPath
    }

    companion object {
        const val MODEL_DIRECTORY = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        const val MODEL_EXPECTED_BYTES = 239_233_841L
        const val MODEL_SHA256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"

        private const val ASSET_DIRECTORY = "models/$MODEL_DIRECTORY"
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val REQUIRED_FILE_COUNT = 2
        private const val MODEL_FILE_NAME = "model.int8.onnx"
        private const val TOKENS_FILE_NAME = "tokens.txt"
        private val REQUIRED_FILE_NAMES = setOf(MODEL_FILE_NAME, TOKENS_FILE_NAME)
        private val preparationLocks = ConcurrentHashMap<Path, Mutex>()

        val ASSET_MANIFESTS = listOf(
            SherpaOnnxModelStore.AssetManifest(
                assetPath = "$ASSET_DIRECTORY/$MODEL_FILE_NAME",
                fileName = MODEL_FILE_NAME,
                expectedBytes = MODEL_EXPECTED_BYTES,
                sha256 = MODEL_SHA256
            ),
            SherpaOnnxModelStore.AssetManifest(
                assetPath = "$ASSET_DIRECTORY/$TOKENS_FILE_NAME",
                fileName = TOKENS_FILE_NAME,
                expectedBytes = 315_894L,
                sha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
            )
        )

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun isPlainFileName(fileName: String): Boolean =
            fileName.isNotEmpty() && fileName != "." && fileName != ".." &&
                '/' !in fileName && '\\' !in fileName && File(fileName).name == fileName
    }
}
