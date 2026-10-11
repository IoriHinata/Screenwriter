package ru.screenwriter.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Local-only GGUF runtime backed by llama.cpp. No HTTP requests or cloud fallback are made.
 * The native engine memory-maps the private model file; model weights are not loaded into a Kotlin byte array.
 */
object LocalLlamaEngine {
    @Volatile private var loadedPath: String? = null
    @Volatile private var lastError: String? = null

    init {
        System.loadLibrary("screenwriter_llama")
    }

    private external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float): String
    private external fun nativeUnloadModel()
    private external fun nativeIsLoaded(): Boolean

    @Synchronized
    fun loadModel(path: String, contextSize: Int = 4096, threads: Int = 4) {
        val file = File(path)
        require(file.isFile && file.length() > 1024L * 1024L) { "Файл модели не найден или слишком мал." }
        require(isGguf(file)) { "Файл не похож на GGUF-модель. Выбери файл с расширением .gguf." }
        val ok = nativeLoadModel(file.absolutePath, contextSize, threads)
        if (!ok) {
            loadedPath = null
            lastError = "llama.cpp не смог загрузить модель. Проверь файл и свободную память."
            throw IllegalStateException(lastError)
        }
        loadedPath = file.absolutePath
        lastError = null
    }

    @Synchronized
    fun generate(prompt: String, maxTokens: Int = 512, temperature: Float = 0.78f): String {
        check(nativeIsLoaded()) { "Локальная модель не загружена. Открой настройки и выбери GGUF-файл." }
        val result = nativeGenerate(prompt, maxTokens.coerceIn(64, 1024), temperature.coerceIn(0.1f, 1.5f))
        if (result.startsWith("ОШИБКА:")) throw IllegalStateException(result.removePrefix("ОШИБКА:").trim())
        return result.trim()
    }

    @Synchronized
    fun unload() {
        nativeUnloadModel()
        loadedPath = null
    }

    fun isLoaded(): Boolean = runCatching { nativeIsLoaded() }.getOrDefault(false)
    fun modelPath(): String? = loadedPath
    fun errorMessage(): String? = lastError

    fun importModel(context: Context, uri: Uri): File {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val destination = File(modelsDir, "writing-model.gguf")
        val partial = File(modelsDir, "writing-model.gguf.partial")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Не удалось открыть выбранный файл." }
                input.buffered().use { source ->
                    FileOutputStream(partial).buffered().use { output -> source.copyTo(output, 1024 * 1024) }
                }
            }
            require(partial.length() > 1024L * 1024L && isGguf(partial)) {
                "Выбранный файл не похож на GGUF-модель: проверь файл и повтори импорт."
            }
            // Do not destroy the last valid model until the replacement has been copied and verified.
            try {
                java.nio.file.Files.move(
                    partial.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                java.nio.file.Files.move(
                    partial.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
            return destination
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    private fun isGguf(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        }
    }.getOrDefault(false)
}
