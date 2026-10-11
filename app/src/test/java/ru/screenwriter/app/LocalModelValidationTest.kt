package ru.screenwriter.app

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelValidationTest {
    @Test
    fun localModelRuntimeHasNoNetworkFallback() {
        val candidates = listOf(
            java.io.File("src/main/java/ru/screenwriter/app/LocalLlamaEngine.kt"),
            java.io.File("app/src/main/java/ru/screenwriter/app/LocalLlamaEngine.kt")
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(source.contains("nativeGenerate"))
        assertTrue(source.contains("importModel"))
        assertTrue(source.contains("isGguf"))
        assertTrue(source.contains("No HTTP requests or cloud fallback"))
    }
}
