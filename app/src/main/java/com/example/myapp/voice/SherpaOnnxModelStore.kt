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

data class SherpaOnnxModelFiles(
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
    val bpeVocab: File,
    val hotwords: File
)

class SherpaOnnxModelStore internal constructor(
    private val modelDirectory: File,
    private val manifests: List<AssetManifest>,
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
    private val manifestsByFileName: Map<String, AssetManifest>

    init {
        require(manifests.size == REQUIRED_FILE_COUNT) { "Expected $REQUIRED_FILE_COUNT model assets" }
        val fileNames = manifests.map(AssetManifest::fileName)
        require(fileNames.all(::isPlainFileName)) { "Sherpa model asset names must be plain file names" }
        require(fileNames.toSet() == REQUIRED_FILE_NAMES) {
            "Sherpa model manifest must contain each required asset exactly once"
        }
        manifests.forEach { manifest ->
            require(resolveOutputPath(normalizedModelDirectory, manifest.fileName).parent == normalizedModelDirectory) {
                "Sherpa model asset output escapes its model directory: ${manifest.fileName}"
            }
        }
        manifestsByFileName = manifests.associateBy(AssetManifest::fileName)
    }

    @Throws(ModelPreparationException::class)
    suspend fun prepare(): SherpaOnnxModelFiles = withContext(Dispatchers.IO) {
        preparationLocks.computeIfAbsent(normalizedModelDirectory) { Mutex() }.withLock {
            prepareLocked(normalizedModelDirectory.toFile())
        }
    }

    private fun prepareLocked(directory: File): SherpaOnnxModelFiles {
        ensureDirectory(directory)
        val preparedFiles = manifestsByFileName.mapValues { (_, manifest) -> prepareAsset(directory, manifest) }
        return SherpaOnnxModelFiles(
            encoder = preparedFiles.getValue(ENCODER_FILE_NAME),
            decoder = preparedFiles.getValue(DECODER_FILE_NAME),
            joiner = preparedFiles.getValue(JOINER_FILE_NAME),
            tokens = preparedFiles.getValue(TOKENS_FILE_NAME),
            bpeVocab = preparedFiles.getValue(BPE_VOCAB_FILE_NAME),
            hotwords = preparedFiles.getValue(HOTWORDS_FILE_NAME)
        )
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw ModelPreparationException("Unable to create Sherpa model directory: ${directory.path}")
        }
        if (!directory.isDirectory) {
            throw ModelPreparationException("Sherpa model path is not a directory: ${directory.path}")
        }
    }

    private fun prepareAsset(directory: File, manifest: AssetManifest): File {
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
                ?: ModelPreparationException("Unable to prepare Sherpa model asset ${manifest.fileName}: ${error.message}", error)
            throw cleanupPartial(partialFile, failure)
        }
    }

    private fun isVerified(file: File, manifest: AssetManifest): Boolean = try {
        file.isFile && file.length() == manifest.expectedBytes &&
            calculateSha256(file).equals(manifest.sha256, ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    private fun verify(file: File, manifest: AssetManifest) {
        if (file.length() != manifest.expectedBytes) {
            throw ModelPreparationException(
                "Sherpa model asset ${manifest.fileName} length mismatch: " +
                    "expected ${manifest.expectedBytes} bytes, got ${file.length()}"
            )
        }
        if (!calculateSha256(file).equals(manifest.sha256, ignoreCase = true)) {
            throw ModelPreparationException("Sherpa model asset ${manifest.fileName} SHA-256 verification failed")
        }
    }

    private fun publish(partialFile: File, finalFile: File) {
        try {
            (publishAtomically ?: ::moveAtomically).invoke(partialFile, finalFile)
        } catch (error: Exception) {
            throw ModelPreparationException(
                "Unable to publish verified Sherpa model asset ${finalFile.name} atomically: ${error.message}",
                error
            )
        }
    }

    private fun moveAtomically(partialFile: File, finalFile: File) {
        Files.move(
            partialFile.toPath(),
            finalFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    private fun removeUnverifiedFinal(finalFile: File) {
        if (finalFile.exists() && !deleteFile(finalFile)) {
            throw ModelPreparationException("Unable to remove unverified Sherpa model asset: ${finalFile.path}")
        }
    }

    private fun removeStalePartial(partialFile: File) {
        if (partialFile.exists() && !deleteFile(partialFile)) {
            throw ModelPreparationException("Unable to remove stale Sherpa model partial: ${partialFile.path}")
        }
    }

    private fun cleanupPartial(
        partialFile: File,
        failure: ModelPreparationException
    ): ModelPreparationException {
        if (partialFile.exists() && !deleteFile(partialFile)) {
            val cleanupFailure = IOException("Unable to delete partial Sherpa model asset: ${partialFile.path}")
            failure.addSuppressed(cleanupFailure)
        }
        return failure
    }

    private fun resolveOutputPath(directory: Path, fileName: String): Path {
        val outputPath = directory.resolve(fileName).normalize()
        if (!outputPath.startsWith(directory) || outputPath.parent != directory) {
            throw IllegalArgumentException("Sherpa model asset output escapes its model directory: $fileName")
        }
        return outputPath
    }

    data class AssetManifest(
        val assetPath: String,
        val fileName: String,
        val expectedBytes: Long,
        val sha256: String
    )

    companion object {
        const val MODEL_DIRECTORY = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"

        private const val ASSET_DIRECTORY = "models/$MODEL_DIRECTORY"
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val REQUIRED_FILE_COUNT = 6
        private const val ENCODER_FILE_NAME = "encoder-epoch-99-avg-1.int8.onnx"
        private const val DECODER_FILE_NAME = "decoder-epoch-99-avg-1.int8.onnx"
        private const val JOINER_FILE_NAME = "joiner-epoch-99-avg-1.int8.onnx"
        private const val TOKENS_FILE_NAME = "tokens.txt"
        private const val BPE_VOCAB_FILE_NAME = "bpe.vocab"
        private const val HOTWORDS_FILE_NAME = "hotwords.txt"
        private val REQUIRED_FILE_NAMES = setOf(
            ENCODER_FILE_NAME,
            DECODER_FILE_NAME,
            JOINER_FILE_NAME,
            TOKENS_FILE_NAME,
            BPE_VOCAB_FILE_NAME,
            HOTWORDS_FILE_NAME
        )
        private val preparationLocks = ConcurrentHashMap<Path, Mutex>()

        val ASSET_MANIFESTS = listOf(
            AssetManifest(
                assetPath = "$ASSET_DIRECTORY/encoder-epoch-99-avg-1.int8.onnx",
                fileName = ENCODER_FILE_NAME,
                expectedBytes = 181_895_032L,
                sha256 = "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b"
            ),
            AssetManifest(
                assetPath = "$ASSET_DIRECTORY/decoder-epoch-99-avg-1.int8.onnx",
                fileName = DECODER_FILE_NAME,
                expectedBytes = 13_091_040L,
                sha256 = "1a70c593d71e53f023f5f55b0b4cfff5055abb786ee3992e5f63dc2e273cc4fa"
            ),
            AssetManifest(
                assetPath = "$ASSET_DIRECTORY/joiner-epoch-99-avg-1.int8.onnx",
                fileName = JOINER_FILE_NAME,
                expectedBytes = 3_228_404L,
                sha256 = "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0"
            ),
            AssetManifest(
                assetPath = "$ASSET_DIRECTORY/tokens.txt",
                fileName = TOKENS_FILE_NAME,
                expectedBytes = 56_317L,
                sha256 = "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"
            ),
            AssetManifest(
                assetPath = "$ASSET_DIRECTORY/bpe.vocab",
                fileName = BPE_VOCAB_FILE_NAME,
                expectedBytes = 12_564L,
                sha256 = "d0b642f3a2eacd5fadefdeff9e0e1358cab729647cbb7fe58cf738e1f7407029"
            ),
            AssetManifest(
                assetPath = "$ASSET_DIRECTORY/hotwords.txt",
                fileName = HOTWORDS_FILE_NAME,
                expectedBytes = 684L,
                sha256 = "f04216c1825c019491c29e65fdea16d03045bf43942e713e943095710bbcdb18"
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
            fileName.isNotEmpty() &&
                fileName != "." &&
                fileName != ".." &&
                '/' !in fileName &&
                '\\' !in fileName &&
                File(fileName).name == fileName
    }
}
