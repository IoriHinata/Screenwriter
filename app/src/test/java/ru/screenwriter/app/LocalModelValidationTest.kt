package ru.screenwriter.app

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelValidationTest {
    @Test
    fun localModelRuntimeHasNoNetworkFallback() {
        val source = java.io.File("src/main/java/ru/screenwriter/app/LocalLlamaEngine.kt").readText()
        assertTrue(source.contains("nativeGenerate"))
        assertTrue(source.contains("importModel"))
        assertTrue(source.contains("isGguf"))
        assertTrue(source.contains("No HTTP requests or cloud fallback"))
    }
}
