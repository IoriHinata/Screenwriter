package ru.screenwriter.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Offline text extraction for common writer document formats. */
object DocumentImporter {
    fun readText(context: Context, uri: Uri): Pair<String, String> {
        val name = queryName(context, uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        val text = when (extension) {
            "pdf" -> readPdf(context, uri)
            "docx" -> readZipXml(context, uri, "word/document.xml", true)
            "odt" -> readZipXml(context, uri, "content.xml", true)
            "epub" -> readEpub(context, uri)
            "rtf" -> readRtf(context, uri)
            "txt", "md", "markdown", "text", "html", "htm", "xml", "json", "csv", "log" ->
                context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("Не удалось открыть документ")
            else -> {
                val stream = context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть документ")
                stream.use { input ->
                    val prefix = ByteArray(4)
                    val count = input.read(prefix)
                    require(count >= 4 && prefix[0] == 'P'.code.toByte() && prefix[1] == 'K'.code.toByte()) {
                        "Формат .$extension пока не поддерживается. Поддерживаются TXT, MD, HTML, RTF, DOCX, PDF, ODT и EPUB."
                    }
                }
                when (extension) {
                    "docx" -> readZipXml(context, uri, "word/document.xml", true)
                    "epub" -> readEpub(context, uri)
                    else -> error("Неподдерживаемый формат .$extension")
                }
            }
        }
        val cleaned = cleanText(text, extension)
        require(cleaned.isNotBlank()) { "В документе не найден текст" }
        return name to cleaned
    }

    private fun queryName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Импортированный документ.txt"
    }

    private fun readPdf(context: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(context)
        val input = context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть PDF")
        input.use { stream ->
            PDDocument.load(stream).use { document -> return PDFTextStripper().getText(document) }
        }
    }

    private fun readZipXml(context: Context, uri: Uri, wantedEntry: String, wordXml: Boolean): String {
        val input = context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть архив документа")
        input.use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == wantedEntry) {
                        val bytes = ByteArrayOutputStream()
                        zip.copyTo(bytes)
                        var xml = bytes.toString(Charsets.UTF_8.name())
                        if (wordXml) xml = xml.replace(Regex("</w:p>"), "\n").replace(Regex("<w:tab[^>]*/>"), "\t")
                        return xml.replace(Regex("<[^>]+>"), " ")
                    }
                }
            }
        }
        error("В архиве не найден текст документа ($wantedEntry)")
    }

    private fun readEpub(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть EPUB")
        val chapters = mutableListOf<Pair<String, String>>()
        input.use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = entry.name.lowercase()
                    if (!entry.isDirectory && (path.endsWith(".xhtml") || path.endsWith(".html") || path.endsWith(".htm")) &&
                        !path.contains("nav") && !path.contains("toc")) {
                        val bytes = ByteArrayOutputStream()
                        zip.copyTo(bytes)
                        chapters.add(entry.name to bytes.toString(Charsets.UTF_8.name()))
                    }
                }
            }
        }
        require(chapters.isNotEmpty()) { "В EPUB не найдены главы в XHTML/HTML" }
        return chapters.sortedBy { it.first }.joinToString("\n\n") { it.second }
    }

    private fun readRtf(context: Context, uri: Uri): String {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Не удалось открыть RTF")
        return raw.replace(Regex("\\{\\\\fonttbl.*?\\}"), "")
            .replace(Regex("\\{\\\\colortbl.*?\\}"), "")
            .replace(Regex("\\'[0-9a-fA-F]{2}"), " ")
            .replace(Regex("\\\\u(-?\\d+)\\??")) { match ->
                val signedCode = match.groupValues[1].toIntOrNull() ?: 32
                ((signedCode + 65536) % 65536).toChar().toString()
            }
            .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), " ")
            .replace("}", " ").replace("{", " ")
    }

    private fun cleanText(value: String, extension: String): String {
        var text = value
        if (extension in setOf("html", "htm", "xhtml", "epub", "docx", "odt")) {
            text = text.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?i)</(p|div|h[1-6]|li|tr|br|section|article)[^>]*>"), "\n")
                .replace(Regex("<[^>]+>"), " ")
            text = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
        }
        return text.replace("\u0000", "").replace(Regex("[\\t ]+"), " ")
            .replace(Regex(" *\\n *"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}
