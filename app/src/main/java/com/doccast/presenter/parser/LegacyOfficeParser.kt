package com.doccast.presenter.parser

import com.doccast.presenter.model.DocFormat
import com.doccast.presenter.model.HtmlPage
import com.doccast.presenter.model.LoadedDocument
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import java.io.InputStream

/**
 * Lightweight legacy binary Word (.doc) parser using Apache POI 4.1.2.
 */
object LegacyOfficeParser {

    fun parseDoc(title: String, inputStream: InputStream): LoadedDocument.HtmlPages {
        return try {
            HWPFDocument(inputStream).use { doc ->
                val extractor = WordExtractor(doc)
                val paragraphs = extractor.paragraphText
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val body = if (paragraphs.isEmpty()) {
                    "<p class='muted'>Tài liệu trống.</p>"
                } else {
                    paragraphs.joinToString("\n") { "<p>${OoxmlParser.escapeHtml(it)}</p>" }
                }

                LoadedDocument.HtmlPages(
                    title = title,
                    format = DocFormat.DOC,
                    pages = listOf(HtmlPage("Trang 1", HtmlTemplates.wrapDocument(title, body)))
                )
            }
        } catch (e: Exception) {
            LoadedDocument.HtmlPages(
                title,
                DocFormat.DOC,
                listOf(
                    HtmlPage(
                        "Lỗi",
                        HtmlTemplates.wrapDocument(
                            title,
                            "<p class='muted'>${OoxmlParser.escapeHtml("Không thể đọc tệp .doc: ${e.message}")}</p>"
                        )
                    )
                )
            )
        }
    }
}
