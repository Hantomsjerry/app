package com.example.myapp.voice

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ModelPreparationException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 将 APK 内置的 Qwen GGUF 模型安全发布到应用私有目录。
 * 文件只有在长度与 SHA-256 均匹配后才从 `.partial` 原子移动到正式路径。
 */
class QwenModelStore internal constructor(
    private val modelDirectory: File,
    private val fileName: String,
    private val expectedBytes: Long,
    private val expectedSha256: String,
    private val openAsset: () -> InputStream,
    private val calculateSha256: (File) -> String = ::sha256,
    private val publishAtomically: ((File, File) -> Unit)? = null,
    private val deletePartial: (File) -> Boolean = { file -> file.delete() }
) {
    constructor(context: Context) : this(
        modelDirectory = File(context.filesDir, MODELS_DIRECTORY),
        fileName = FILE_NAME,
        expectedBytes = EXPECTED_BYTES,
        expectedSha256 = EXPECTED_SHA256,
        openAsset = { context.assets.open(ASSET_PATH) }
    )

    @Volatile
    private var verifiedSnapshot: VerifiedSnapshot? = null

    @Throws(ModelPreparationException::class)
    suspend fun prepare(): File = withContext(Dispatchers.IO) {
        val finalPath = File(modelDirectory, fileName).toPath().toAbsolutePath().normalize()
        // 锁按规范化最终路径共享，多个 Store 实例也不会并行复制同一模型。
        preparationLocks.computeIfAbsent(finalPath) { Mutex() }.withLock {
            prepareLocked(finalPath.toFile())
        }
    }

    private fun prepareLocked(finalFile: File): File {
        if (isVerified(finalFile)) {
            return finalFile
        }

        val partialFile = File(finalFile.parentFile, "${finalFile.name}.partial")
        try {
            if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
                throw ModelPreparationException("Unable to create model directory: ${modelDirectory.path}")
            }
            if (!modelDirectory.isDirectory) {
                throw ModelPreparationException("Model path is not a directory: ${modelDirectory.path}")
            }

            removeUnverifiedFinal(finalFile)
            removeStalePartial(partialFile)
            openAsset().use { input ->
                partialFile.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }

            verify(partialFile)
            publish(partialFile, finalFile)
            verifiedSnapshot = VerifiedSnapshot.from(finalFile)
            return finalFile
        } catch (error: Exception) {
            verifiedSnapshot = null
            val failure = error as? ModelPreparationException
                ?: ModelPreparationException("Unable to prepare Qwen model: ${error.message}", error)
            throw cleanupPartial(partialFile, failure)
        }
    }

    private fun isVerified(file: File): Boolean = try {
        // 元数据未变化时复用已校验快照，避免每次推理前重新读取 1GB 模型计算哈希。
        if (verifiedSnapshot?.matches(file) == true) {
            true
        } else {
            val verified = file.isFile && file.length() == expectedBytes &&
                calculateSha256(file).equals(expectedSha256, ignoreCase = true)
            if (verified) verifiedSnapshot = VerifiedSnapshot.from(file)
            verified
        }
    } catch (_: Exception) {
        false
    }

    private fun verify(file: File) {
        if (file.length() != expectedBytes) {
            throw ModelPreparationException(
                "Qwen model length mismatch: expected $expectedBytes bytes, got ${file.length()}"
            )
        }
        if (!calculateSha256(file).equals(expectedSha256, ignoreCase = true)) {
            throw ModelPreparationException("Qwen model SHA-256 verification failed")
        }
    }

    private fun publish(partialFile: File, finalFile: File) {
        try {
            (publishAtomically ?: ::moveAtomically).invoke(partialFile, finalFile)
        } catch (error: Exception) {
            throw ModelPreparationException(
                "Unable to publish verified Qwen model atomically: ${error.message}",
                error
            )
        }
    }

    private fun moveAtomically(partialFile: File, finalFile: File) {
        // 原子移动保证进程崩溃时不会留下“看似正式、实际只复制一部分”的模型。
        Files.move(
            partialFile.toPath(),
            finalFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    private fun removeUnverifiedFinal(finalFile: File) {
        verifiedSnapshot = null
        if (finalFile.exists() && !finalFile.delete()) {
            throw ModelPreparationException("Unable to remove unverified Qwen model: ${finalFile.path}")
        }
    }

    private fun removeStalePartial(partialFile: File) {
        if (partialFile.exists() && !deletePartial(partialFile)) {
            throw ModelPreparationException("Unable to remove stale partial Qwen model: ${partialFile.path}")
        }
    }

    private fun cleanupPartial(
        partialFile: File,
        failure: ModelPreparationException
    ): ModelPreparationException {
        if (partialFile.exists() && !deletePartial(partialFile)) {
            val cleanupFailure = IOException("Unable to delete partial Qwen model: ${partialFile.path}")
            failure.addSuppressed(cleanupFailure)
            return ModelPreparationException("${failure.message}; ${cleanupFailure.message}", failure)
        }
        return failure
    }

    private data class VerifiedSnapshot(
        val normalizedPath: String,
        val length: Long,
        val lastModified: Long
    ) {
        fun matches(file: File): Boolean =
            file.isFile &&
                file.toPath().toAbsolutePath().normalize().toString() == normalizedPath &&
                file.length() == length &&
                file.lastModified() == lastModified

        companion object {
            fun from(file: File) = VerifiedSnapshot(
                normalizedPath = file.toPath().toAbsolutePath().normalize().toString(),
                length = file.length(),
                lastModified = file.lastModified()
            )
        }
    }

    companion object {
        const val FILE_NAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        const val ASSET_PATH = "models/$FILE_NAME"
        const val EXPECTED_SHA256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
        const val EXPECTED_BYTES = 1_117_320_736L

        private const val MODELS_DIRECTORY = "models"
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private val preparationLocks = ConcurrentHashMap<Path, Mutex>()

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
    }
}
