package com.doccast.presenter.parser

import android.util.Base64
import android.util.Xml
import com.doccast.presenter.model.DocFormat
import com.doccast.presenter.model.HtmlPage
import com.doccast.presenter.model.LoadedDocument
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Lightweight, zero-dependency pure-Kotlin parser for .docx (Word)
 * with MathType WMF->SVG, full OMML Word Equations, and Word page-break pagination.
 */
object OoxmlParser {

    private val WIDTH_PT_REGEX = Regex("width\\s*:\\s*([0-9.]+)\\s*pt", RegexOption.IGNORE_CASE)
    private val HEIGHT_PT_REGEX = Regex("height\\s*:\\s*([0-9.]+)\\s*pt", RegexOption.IGNORE_CASE)

    fun parseDocx(title: String, inputStream: InputStream): LoadedDocument.HtmlPages {
        val entries = readZipEntries(inputStream)
        val docXml = entries["word/document.xml"]
            ?: return singleErrorPage(title, DocFormat.DOCX, "Không tìm thấy nội dung word/document.xml.")
        val relsMap = parseRelationships(entries["word/_rels/document.xml.rels"], "word/")

        val pageBodies = mutableListOf<String>()
        val bodyHtml = StringBuilder()
        val parser = newXmlParser(docXml)
        var eventType = parser.eventType

        var inParagraph = false
        var inTable = false
        var inRow = false
        var inCell = false
        var inRun = false
        var runBold = false
        var runItalic = false
        var headingLevel = 0
        var currentShapeWidthPt: Float? = null
        var currentShapeHeightPt: Float? = null
        val paragraphBuf = StringBuilder()

        fun flushPage(reopenTable: Boolean) {
            if (reopenTable) {
                if (inCell) bodyHtml.append("</td>")
                if (inRow) bodyHtml.append("</tr>")
                if (inTable) bodyHtml.append("</table></div>")
            }
            val chunk = bodyHtml.toString().trim()
            if (chunk.isNotEmpty()) {
                pageBodies.add(chunk)
            }
            bodyHtml.setLength(0)
            if (reopenTable) {
                if (inTable) bodyHtml.append("<div class='table-wrap'><table>")
                if (inRow) bodyHtml.append("<tr>")
                if (inCell) bodyHtml.append("<td>")
            }
        }

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (eventType) {
                XmlPullParser.START_TAG -> when (tag) {
                    "tbl" -> {
                        inTable = true
                        bodyHtml.append("<div class='table-wrap'><table>")
                    }
                    "tr" -> if (inTable) {
                        inRow = true
                        bodyHtml.append("<tr>")
                    }
                    "tc" -> if (inRow) {
                        inCell = true
                        bodyHtml.append("<td>")
                    }
                    "p" -> {
                        inParagraph = true
                        headingLevel = 0
                        paragraphBuf.setLength(0)
                    }
                    "pStyle" -> if (inParagraph) {
                        val styleVal = getAttr(parser, "val").lowercase()
                        headingLevel = when {
                            styleVal.contains("heading1") || styleVal == "title" -> 1
                            styleVal.contains("heading2") || styleVal == "subtitle" -> 2
                            styleVal.contains("heading3") -> 3
                            styleVal.contains("heading4") -> 4
                            else -> 0
                        }
                    }
                    "pageBreakBefore" -> if (inParagraph) {
                        val v = getAttr(parser, "val")
                        if (v.isEmpty() || (v != "0" && !v.equals("false", true))) {
                            flushPage(reopenTable = inTable)
                        }
                    }
                    "lastRenderedPageBreak" -> {
                        if (paragraphBuf.isNotEmpty()) {
                            bodyHtml.append("<p>${paragraphBuf}</p>")
                            paragraphBuf.setLength(0)
                        }
                        flushPage(reopenTable = inTable)
                    }
                    "br" -> if (inParagraph) {
                        val brType = getAttr(parser, "type")
                        if (brType.equals("page", true)) {
                            if (paragraphBuf.isNotEmpty()) {
                                bodyHtml.append("<p>${paragraphBuf}</p>")
                                paragraphBuf.setLength(0)
                            }
                            flushPage(reopenTable = inTable)
                        } else {
                            paragraphBuf.append("<br/>")
                        }
                    }
                    "oMath", "oMathPara" -> {
                        val mathHtml = OmmlToHtmlConverter.parseMathElement(parser, tag)
                        if (inParagraph) {
                            paragraphBuf.append(mathHtml)
                        } else {
                            bodyHtml.append("<p>$mathHtml</p>")
                        }
                    }
                    "r" -> {
                        inRun = true
                        runBold = false
                        runItalic = false
                    }
                    "b" -> if (inRun) {
                        val v = getAttr(parser, "val")
                        runBold = v.isEmpty() || v == "1" || v.equals("true", true)
                    }
                    "i" -> if (inRun) {
                        val v = getAttr(parser, "val")
                        runItalic = v.isEmpty() || v == "1" || v.equals("true", true)
                    }
                    "t" -> if (inParagraph) {
                        val text = escapeHtml(parser.nextText())
                        if (text.isNotEmpty()) {
                            var formatted = text
                            if (runBold) formatted = "<strong>$formatted</strong>"
                            if (runItalic) formatted = "<em>$formatted</em>"
                            paragraphBuf.append(formatted)
                        }
                    }
                    "tab" -> if (inParagraph) paragraphBuf.append("&emsp;")
                    "shape" -> {
                        val style = getAttr(parser, "style")
                        currentShapeWidthPt = WIDTH_PT_REGEX.find(style)?.groupValues?.get(1)?.toFloatOrNull()
                        currentShapeHeightPt = HEIGHT_PT_REGEX.find(style)?.groupValues?.get(1)?.toFloatOrNull()
                    }
                    "imagedata" -> if (inParagraph) {
                        val rId = getAttr(parser, "id")
                        val mediaPath = relsMap[rId]
                        if (mediaPath != null) {
                            appendMediaToParagraph(
                                paragraphBuf = paragraphBuf,
                                mediaPath = mediaPath,
                                bytes = entries[mediaPath],
                                widthPt = currentShapeWidthPt,
                                heightPt = currentShapeHeightPt,
                                inlineMath = true
                            )
                        }
                    }
                    "blip" -> if (inParagraph) {
                        val rId = getAttr(parser, "embed")
                        val mediaPath = relsMap[rId]
                        if (mediaPath != null) {
                            appendMediaToParagraph(
                                paragraphBuf = paragraphBuf,
                                mediaPath = mediaPath,
                                bytes = entries[mediaPath],
                                widthPt = null,
                                heightPt = null,
                                inlineMath = false
                            )
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (tag) {
                    "shape" -> {
                        currentShapeWidthPt = null
                        currentShapeHeightPt = null
                    }
                    "r" -> inRun = false
                    "p" -> {
                        inParagraph = false
                        val content = paragraphBuf.toString().trim()
                        if (content.isNotEmpty()) {
                            val htmlBlock = when (headingLevel) {
                                1 -> "<h1>$content</h1>"
                                2 -> "<h2>$content</h2>"
                                3 -> "<h3>$content</h3>"
                                4 -> "<h4>$content</h4>"
                                else -> "<p>$content</p>"
                            }
                            bodyHtml.append(htmlBlock)
                        }
                    }
                    "tc" -> if (inCell) {
                        inCell = false
                        bodyHtml.append("</td>")
                    }
                    "tr" -> if (inRow) {
                        inRow = false
                        bodyHtml.append("</tr>")
                    }
                    "tbl" -> if (inTable) {
                        inTable = false
                        bodyHtml.append("</table></div>")
                    }
                }
            }
            eventType = parser.next()
        }

        val remaining = bodyHtml.toString().trim()
        if (remaining.isNotEmpty()) {
            pageBodies.add(remaining)
        }

        val pages = if (pageBodies.isEmpty()) {
            listOf(
                HtmlPage(
                    "Trang 1",
                    HtmlTemplates.wrapDocument(title, "<p class='muted'>Tài liệu trống hoặc không có văn bản đọc được.</p>")
                )
            )
        } else {
            pageBodies.mapIndexed { idx, chunk ->
                HtmlPage(
                    label = "Trang ${idx + 1}",
                    html = HtmlTemplates.wrapDocument(title, chunk, idx + 1, pageBodies.size)
                )
            }
        }
        return LoadedDocument.HtmlPages(title, DocFormat.DOCX, pages)
    }

    private fun appendMediaToParagraph(
        paragraphBuf: StringBuilder,
        mediaPath: String,
        bytes: ByteArray?,
        widthPt: Float?,
        heightPt: Float?,
        inlineMath: Boolean
    ) {
        if (bytes == null) return
        val ext = mediaPath.substringAfterLast('.', "").lowercase()
        if (ext == "wmf") {
            val svg = WmfToSvgConverter.convertToInlineSvg(bytes, widthPt, heightPt)
            if (svg != null) {
                paragraphBuf.append("<span class='math-wrap'>$svg</span>")
            }
            return
        }
        val mime = guessImageMime(mediaPath)
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        if (inlineMath) {
            paragraphBuf.append("<img class='inline-img' src='data:$mime;base64,$b64'/>")
        } else {
            paragraphBuf.append("<div class='img-box'><img src='data:$mime;base64,$b64'/></div>")
        }
    }

    private fun parseRelationships(relsBytes: ByteArray?, baseFolder: String): Map<String, String> {
        if (relsBytes == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        val parser = newXmlParser(relsBytes)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && localName(parser.name) == "Relationship") {
                val id = getAttr(parser, "Id")
                val target = getAttr(parser, "Target")
                if (id.isNotEmpty() && target.isNotEmpty()) {
                    map[id] = normalizeZipPath(baseFolder, target)
                }
            }
            eventType = parser.next()
        }
        return map
    }

    private fun normalizeZipPath(baseFolder: String, target: String): String {
        val combined = if (target.startsWith("/")) target.drop(1) else "$baseFolder$target"
        val parts = mutableListOf<String>()
        for (segment in combined.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun readZipEntries(inputStream: InputStream): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    map[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return map
    }

    private fun newXmlParser(bytes: ByteArray): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        return parser
    }

    private fun localName(rawName: String?): String = rawName?.substringAfter(':') ?: ""

    private fun getAttr(parser: XmlPullParser, name: String): String {
        for (i in 0 until parser.attributeCount) {
            val attrName = parser.getAttributeName(i)
            if (attrName == name || attrName.substringAfter(':') == name) {
                return parser.getAttributeValue(i) ?: ""
            }
        }
        return ""
    }

    private fun guessImageMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun singleErrorPage(title: String, format: DocFormat, msg: String): LoadedDocument.HtmlPages {
        return LoadedDocument.HtmlPages(
            title,
            format,
            listOf(HtmlPage("Thông báo", HtmlTemplates.wrapDocument(title, "<p class='muted'>$msg</p>")))
        )
    }

    fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
