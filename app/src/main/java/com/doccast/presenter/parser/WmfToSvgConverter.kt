package com.doccast.presenter.parser

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs

/**
 * Pure-Kotlin Windows Metafile (WMF) to inline SVG converter tailored for
 * Microsoft Word MathType equations and vector diagrams.
 * Zero external dependencies; renders crisp vector formulas directly inside WebView.
 */
object WmfToSvgConverter {

    private sealed class GdiObject {
        data class Font(
            val height: Int,
            val weight: Int,
            val italic: Boolean,
            val faceName: String
        ) : GdiObject()

        data class Pen(
            val style: Int,
            val width: Int,
            val colorHex: String
        ) : GdiObject()

        data class Brush(
            val style: Int,
            val colorHex: String
        ) : GdiObject()
    }

    fun convertToInlineSvg(
        wmfBytes: ByteArray,
        displayWidthPt: Float? = null,
        displayHeightPt: Float? = null
    ): String? {
        if (wmfBytes.size < 40) return null
        val buf = ByteBuffer.wrap(wmfBytes).order(ByteOrder.LITTLE_ENDIAN)

        var offset = 0
        var bboxLeft = 0
        var bboxTop = 0
        var bboxRight = 1000
        var bboxBottom = 400
        var inch = 1440

        // Check Placeable WMF header magic 0x9AC6CDD7
        if (buf.getInt(0) == 0x9AC6CDD7.toInt()) {
            bboxLeft = buf.getShort(6).toInt()
            bboxTop = buf.getShort(8).toInt()
            bboxRight = buf.getShort(10).toInt()
            bboxBottom = buf.getShort(12).toInt()
            inch = (buf.getShort(14).toInt() and 0xFFFF).coerceAtLeast(72)
            offset = 22
        }

        // Skip 18-byte standard WMF header
        if (offset + 18 > wmfBytes.size) return null
        offset += 18

        var winOrgX = bboxLeft
        var winOrgY = bboxTop
        var winExtW = (bboxRight - bboxLeft).takeIf { it != 0 } ?: 1000
        var winExtH = (bboxBottom - bboxTop).takeIf { it != 0 } ?: 400

        var cpX = 0
        var cpY = 0
        var textAlign = 0
        var textColor = "#000000"

        val objectTable = mutableListOf<GdiObject?>()
        var currentFont = GdiObject.Font(height = -240, weight = 400, italic = false, faceName = "Times New Roman")
        var currentPen = GdiObject.Pen(style = 0, width = 12, colorHex = "#000000")
        var currentBrush = GdiObject.Brush(style = 1, colorHex = "none")

        fun allocObject(obj: GdiObject) {
            val freeIdx = objectTable.indexOfFirst { it == null }
            if (freeIdx >= 0) objectTable[freeIdx] = obj else objectTable.add(obj)
        }

        val svgElements = StringBuilder()

        while (offset + 6 <= wmfBytes.size) {
            val sizeWords = buf.getInt(offset)
            val func = buf.getShort(offset + 4).toInt() and 0xFFFF
            if (sizeWords < 3) break
            val recBytes = sizeWords * 2
            val recEnd = offset + recBytes
            if (recEnd > wmfBytes.size) break
            if (func == 0x0000) break // META_EOF

            val p = offset + 6
            when (func) {
                0x020B -> { // META_SETWINDOWORG
                    if (p + 4 <= recEnd) {
                        winOrgY = buf.getShort(p).toInt()
                        winOrgX = buf.getShort(p + 2).toInt()
                    }
                }
                0x020C -> { // META_SETWINDOWEXT
                    if (p + 4 <= recEnd) {
                        val h = buf.getShort(p).toInt()
                        val w = buf.getShort(p + 2).toInt()
                        if (w != 0) winExtW = w
                        if (h != 0) winExtH = h
                    }
                }
                0x012E -> { // META_SETTEXTALIGN
                    if (p + 2 <= recEnd) {
                        textAlign = buf.getShort(p).toInt() and 0xFFFF
                    }
                }
                0x0209 -> { // META_SETTEXTCOLOR
                    if (p + 4 <= recEnd) {
                        textColor = readColorHex(wmfBytes, p)
                    }
                }
                0x0214 -> { // META_MOVETO
                    if (p + 4 <= recEnd) {
                        cpY = buf.getShort(p).toInt()
                        cpX = buf.getShort(p + 2).toInt()
                    }
                }
                0x0213 -> { // META_LINETO
                    if (p + 4 <= recEnd) {
                        val ny = buf.getShort(p).toInt()
                        val nx = buf.getShort(p + 2).toInt()
                        if (currentPen.style != 5) {
                            val sw = currentPen.width.coerceAtLeast(10)
                            svgElements.append(
                                "<line x1='$cpX' y1='$cpY' x2='$nx' y2='$ny' stroke='${currentPen.colorHex}' stroke-width='$sw' stroke-linecap='round'/>"
                            )
                        }
                        cpX = nx
                        cpY = ny
                    }
                }
                0x0325, 0x0324 -> { // META_POLYLINE / META_POLYGON
                    if (p + 2 <= recEnd) {
                        val count = buf.getShort(p).toInt() and 0xFFFF
                        if (count > 0 && p + 2 + count * 4 <= recEnd) {
                            val pts = StringBuilder()
                            for (i in 0 until count) {
                                val px = buf.getShort(p + 2 + i * 4).toInt()
                                val py = buf.getShort(p + 4 + i * 4).toInt()
                                pts.append("$px,$py ")
                            }
                            val stroke = if (currentPen.style == 5) "none" else currentPen.colorHex
                            val sw = currentPen.width.coerceAtLeast(10)
                            if (func == 0x0325) {
                                svgElements.append(
                                    "<polyline fill='none' stroke='$stroke' stroke-width='$sw' points='${pts.toString().trim()}'/>"
                                )
                            } else {
                                val fill = if (currentBrush.style == 1) "none" else currentBrush.colorHex
                                svgElements.append(
                                    "<polygon fill='$fill' stroke='$stroke' stroke-width='$sw' points='${pts.toString().trim()}'/>"
                                )
                            }
                        }
                    }
                }
                0x041B -> { // META_RECTANGLE
                    if (p + 8 <= recEnd) {
                        val bottom = buf.getShort(p).toInt()
                        val right = buf.getShort(p + 2).toInt()
                        val top = buf.getShort(p + 4).toInt()
                        val left = buf.getShort(p + 6).toInt()
                        val fill = if (currentBrush.style == 1) "none" else currentBrush.colorHex
                        val stroke = if (currentPen.style == 5) "none" else currentPen.colorHex
                        val sw = currentPen.width.coerceAtLeast(8)
                        svgElements.append(
                            "<rect x='$left' y='$top' width='${abs(right - left)}' height='${abs(bottom - top)}' fill='$fill' stroke='$stroke' stroke-width='$sw'/>"
                        )
                    }
                }
                0x02FA -> { // META_CREATEPENINDIRECT
                    if (p + 10 <= recEnd) {
                        val style = buf.getShort(p).toInt() and 0xFFFF
                        val width = abs(buf.getShort(p + 2).toInt())
                        val color = readColorHex(wmfBytes, p + 6)
                        allocObject(GdiObject.Pen(style, width, color))
                    }
                }
                0x02FC -> { // META_CREATEBRUSHINDIRECT
                    if (p + 6 <= recEnd) {
                        val style = buf.getShort(p).toInt() and 0xFFFF
                        val color = readColorHex(wmfBytes, p + 2)
                        allocObject(GdiObject.Brush(style, color))
                    }
                }
                0x02FB -> { // META_CREATEFONTINDIRECT
                    if (p + 18 <= recEnd) {
                        val height = buf.getShort(p).toInt()
                        val weight = buf.getShort(p + 8).toInt()
                        val italic = (wmfBytes[p + 10].toInt() and 0xFF) != 0
                        val faceStart = p + 18
                        val faceEnd = (faceStart until recEnd).firstOrNull { wmfBytes[it] == 0.toByte() } ?: recEnd
                        val faceName = String(wmfBytes, faceStart, (faceEnd - faceStart).coerceAtLeast(0), Charsets.ISO_8859_1).trim()
                        allocObject(GdiObject.Font(height, weight, italic, faceName))
                    }
                }
                0x012D -> { // META_SELECTOBJECT
                    if (p + 2 <= recEnd) {
                        val idx = buf.getShort(p).toInt() and 0xFFFF
                        when (val obj = objectTable.getOrNull(idx)) {
                            is GdiObject.Font -> currentFont = obj
                            is GdiObject.Pen -> currentPen = obj
                            is GdiObject.Brush -> currentBrush = obj
                            null -> Unit
                        }
                    }
                }
                0x01F0 -> { // META_DELETEOBJECT
                    if (p + 2 <= recEnd) {
                        val idx = buf.getShort(p).toInt() and 0xFFFF
                        if (idx in objectTable.indices) objectTable[idx] = null
                    }
                }
                0x0A32 -> { // META_EXTTEXTOUT
                    if (p + 8 <= recEnd) {
                        val recY = buf.getShort(p).toInt()
                        val recX = buf.getShort(p + 2).toInt()
                        val count = buf.getShort(p + 4).toInt() and 0xFFFF
                        val opts = buf.getShort(p + 6).toInt() and 0xFFFF
                        val txtStart = p + 8 + if ((opts and 0x06) != 0) 8 else 0
                        if (count > 0 && txtStart + count <= recEnd) {
                            val useCp = (textAlign and 0x01) != 0
                            val startX = if (useCp) cpX else recX
                            val startY = if (useCp) cpY else recY
                            val dxStart = txtStart + count + (count % 2)
                            val hasDx = dxStart + count * 2 <= recEnd

                            emitTextSvg(
                                out = svgElements,
                                bytes = wmfBytes,
                                txtStart = txtStart,
                                count = count,
                                startX = startX,
                                startY = startY,
                                dxBuf = if (hasDx) buf else null,
                                dxStart = dxStart,
                                font = currentFont,
                                textColor = textColor,
                                textAlign = textAlign
                            )
                            if (useCp && hasDx) {
                                var totalDx = 0
                                for (i in 0 until count) {
                                    totalDx += buf.getShort(dxStart + i * 2).toInt()
                                }
                                cpX += totalDx
                            }
                        }
                    }
                }
                0x0521 -> { // META_TEXTOUT
                    if (p + 2 <= recEnd) {
                        val count = buf.getShort(p).toInt() and 0xFFFF
                        val txtStart = p + 2
                        val posStart = txtStart + count + (count % 2)
                        if (count > 0 && posStart + 4 <= recEnd) {
                            val useCp = (textAlign and 0x01) != 0
                            val recY = buf.getShort(posStart).toInt()
                            val recX = buf.getShort(posStart + 2).toInt()
                            emitTextSvg(
                                out = svgElements,
                                bytes = wmfBytes,
                                txtStart = txtStart,
                                count = count,
                                startX = if (useCp) cpX else recX,
                                startY = if (useCp) cpY else recY,
                                dxBuf = null,
                                dxStart = 0,
                                font = currentFont,
                                textColor = textColor,
                                textAlign = textAlign
                            )
                        }
                    }
                }
            }
            offset = recEnd
        }

        if (svgElements.isEmpty()) return null

        val wPt = displayWidthPt ?: ((abs(bboxRight - bboxLeft).toFloat() / inch.toFloat()) * 72f).coerceIn(8f, 480f)
        val hPt = displayHeightPt ?: ((abs(bboxBottom - bboxTop).toFloat() / inch.toFloat()) * 72f).coerceIn(8f, 300f)
        // Scale slightly up (~1.18x) so math matches mobile reading body font comfortably
        val cssW = String.format(Locale.US, "%.2fpt", wPt * 1.15f)
        val cssH = String.format(Locale.US, "%.2fpt", hPt * 1.15f)

        return "<svg class='math-svg' xmlns='http://www.w3.org/2000/svg' " +
            "viewBox='$winOrgX $winOrgY $winExtW $winExtH' " +
            "style='width:$cssW;height:$cssH;vertical-align:middle;display:inline-block;overflow:visible;'>" +
            svgElements.toString() +
            "</svg>"
    }

    private fun emitTextSvg(
        out: StringBuilder,
        bytes: ByteArray,
        txtStart: Int,
        count: Int,
        startX: Int,
        startY: Int,
        dxBuf: ByteBuffer?,
        dxStart: Int,
        font: GdiObject.Font,
        textColor: String,
        textAlign: Int
    ) {
        val fontSize = abs(font.height).coerceAtLeast(16)
        val isBaseline = (textAlign and 0x18) == 0x18
        val isBottom = (textAlign and 0x18) == 0x08
        val yPos = when {
            isBaseline -> startY
            isBottom -> startY - (fontSize * 0.15f).toInt()
            else -> startY + (fontSize * 0.82f).toInt()
        }

        val anchor = when (textAlign and 0x06) {
            0x06 -> "middle"
            0x02 -> "end"
            else -> "start"
        }

        val fontWeight = if (font.weight >= 600) "bold" else "normal"
        val fontStyle = if (font.italic) "italic" else "normal"
        val family = when {
            font.faceName.equals("Symbol", true) || font.faceName.equals("MT Extra", true) ->
                "\"Times New Roman\", Georgia, serif"
            font.faceName.isBlank() || font.faceName.equals("System", true) ->
                "\"Times New Roman\", Georgia, serif"
            else -> "\"${font.faceName}\", \"Times New Roman\", Georgia, serif"
        }

        if (dxBuf != null) {
            var curX = startX
            for (i in 0 until count) {
                val b = bytes[txtStart + i].toInt() and 0xFF
                val ch = mapGlyph(b, font.faceName)
                if (ch.isNotEmpty() && ch != " ") {
                    out.append(
                        "<text x='$curX' y='$yPos' font-family='$family' font-size='$fontSize' " +
                            "font-weight='$fontWeight' font-style='$fontStyle' fill='$textColor' text-anchor='$anchor'>" +
                            OoxmlParser.escapeHtml(ch) +
                            "</text>"
                    )
                }
                curX += dxBuf.getShort(dxStart + i * 2).toInt()
            }
        } else {
            val sb = StringBuilder()
            for (i in 0 until count) {
                sb.append(mapGlyph(bytes[txtStart + i].toInt() and 0xFF, font.faceName))
            }
            val text = sb.toString()
            if (text.isNotEmpty()) {
                out.append(
                    "<text x='$startX' y='$yPos' font-family='$family' font-size='$fontSize' " +
                        "font-weight='$fontWeight' font-style='$fontStyle' fill='$textColor' text-anchor='$anchor'>" +
                        OoxmlParser.escapeHtml(text) +
                        "</text>"
                )
            }
        }
    }

    private fun readColorHex(bytes: ByteArray, offset: Int): String {
        val r = bytes[offset].toInt() and 0xFF
        val g = bytes[offset + 1].toInt() and 0xFF
        val b = bytes[offset + 2].toInt() and 0xFF
        return String.format(Locale.US, "#%02x%02x%02x", r, g, b)
    }

    private fun mapGlyph(code: Int, faceName: String): String {
        if (faceName.equals("Symbol", true)) {
            return SYMBOL_MAP[code] ?: code.toChar().toString()
        }
        if (faceName.equals("MT Extra", true)) {
            return MT_EXTRA_MAP[code] ?: code.toChar().toString()
        }
        // Windows-1252 / Latin-1 common math punctuation
        return when (code) {
            0x96 -> "–"
            0x97 -> "—"
            0xB0 -> "°"
            0xB1 -> "±"
            0xB2 -> "²"
            0xB3 -> "³"
            0xB7 -> "·"
            0xD7 -> "×"
            0xF7 -> "÷"
            else -> code.toChar().toString()
        }
    }

    private val SYMBOL_MAP: Map<Int, String> = mapOf(
        0x22 to "∀", 0x24 to "∃", 0x27 to "∋", 0x2A to "∗", 0x2D to "−",
        0x40 to "≅", 0x41 to "Α", 0x42 to "Β", 0x43 to "Χ", 0x44 to "Δ",
        0x45 to "Ε", 0x46 to "Φ", 0x47 to "Γ", 0x48 to "Η", 0x49 to "Ι",
        0x4B to "Κ", 0x4C to "Λ", 0x4D to "Μ", 0x4E to "Ν", 0x4F to "Ο",
        0x50 to "Π", 0x51 to "Θ", 0x52 to "Ρ", 0x53 to "Σ", 0x54 to "Τ",
        0x55 to "Υ", 0x57 to "Ω", 0x58 to "Ξ", 0x59 to "Ψ", 0x5A to "Ζ",
        0x5C to "∴", 0x5E to "⊥",
        0x61 to "α", 0x62 to "β", 0x63 to "χ", 0x64 to "δ", 0x65 to "ε",
        0x66 to "φ", 0x67 to "γ", 0x68 to "η", 0x69 to "ι", 0x6B to "κ",
        0x6C to "λ", 0x6D to "μ", 0x6E to "ν", 0x6F to "ο", 0x70 to "π",
        0x71 to "θ", 0x72 to "ρ", 0x73 to "σ", 0x74 to "τ", 0x75 to "υ",
        0x76 to "ϖ", 0x77 to "ω", 0x78 to "ξ", 0x79 to "ψ", 0x7A to "ζ",
        0x7E to "∼",
        0xA2 to "′", 0xA3 to "≤", 0xA4 to "⁄", 0xA5 to "∞", 0xAB to "↔",
        0xAC to "←", 0xAD to "↑", 0xAE to "→", 0xAF to "↓", 0xB0 to "°",
        0xB1 to "±", 0xB2 to "″", 0xB3 to "≥", 0xB4 to "×", 0xB5 to "∝",
        0xB6 to "∂", 0xB7 to "•", 0xB8 to "÷", 0xB9 to "≠", 0xBA to "≡",
        0xBB to "≈", 0xBC to "…", 0xBD to "│", 0xBE to "─", 0xC4 to "⊗",
        0xC5 to "⊕", 0xC6 to "∅", 0xC7 to "∩", 0xC8 to "∪", 0xC9 to "⊃",
        0xCA to "⊇", 0xCB to "⊄", 0xCC to "⊂", 0xCD to "⊆", 0xCE to "∈",
        0xCF to "∉", 0xD0 to "∠", 0xD1 to "∇", 0xD5 to "∏", 0xD6 to "√",
        0xD7 to "·", 0xD8 to "¬", 0xD9 to "∧", 0xDA to "∨", 0xDB to "⇔",
        0xDC to "⇐", 0xDD to "⇑", 0xDE to "⇒", 0xDF to "⇓", 0xE0 to "◊",
        0xE1 to "⟨", 0xE5 to "∑", 0xE6 to "⎛", 0xE7 to "⎜", 0xE8 to "⎝",
        0xE9 to "⎡", 0xEA to "⎢", 0xEB to "⎣", 0xEC to "⎧", 0xED to "⎨",
        0xEE to "⎩", 0xEF to "⎪", 0xF1 to "⟩", 0xF2 to "∫", 0xF3 to "⌠",
        0xF4 to "⎮", 0xF5 to "⌡", 0xF6 to "⎞", 0xF7 to "⎟", 0xF8 to "⎠",
        0xF9 to "⎤", 0xFA to "⎥", 0xFB to "⎦", 0xFC to "⎫", 0xFD to "⎬",
        0xFE to "⎭"
    )

    private val MT_EXTRA_MAP: Map<Int, String> = mapOf(
        0x43 to "ℂ", 0x46 to "⌢", 0x49 to "∩", 0x4B to "…", 0x4C to "…",
        0x4D to "⋯", 0x4E to "ℕ", 0x4F to "⋮", 0x50 to "⋱", 0x51 to "ℚ",
        0x52 to "ℝ", 0x55 to "∪", 0x5A to "ℤ", 0x62 to "′", 0x63 to "″",
        0x68 to "ℏ", 0x6C to "ℓ", 0x70 to "∥", 0x72 to "→", 0x73 to "←",
        0x74 to "↔", 0x77 to "∠"
    )
}
