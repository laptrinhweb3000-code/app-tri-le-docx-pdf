package com.doccast.presenter.parser

import org.xmlpull.v1.XmlPullParser

/**
 * Recursive pure-Kotlin Office Math Markup Language (OMML / Word Equation)
 * to crisp HTML+CSS converter.
 * Supports fractions, sub/superscripts, stacked sub-sup, radicals/roots,
 * delimiters, accents/vectors, groupChr, functions, n-ary operators, limits, and matrices.
 */
object OmmlToHtmlConverter {

    /**
     * Parses from the START_TAG of <m:oMath> or <m:oMathPara> through its matching END_TAG,
     * returning formatted HTML markup.
     */
    fun parseMathElement(parser: XmlPullParser, rootTag: String): String {
        val inner = parseChildrenUntilEnd(parser, rootTag)
        return if (rootTag == "oMathPara") {
            "<div class='omml-block'>$inner</div>"
        } else {
            "<span class='omml-math'>$inner</span>"
        }
    }

    private fun parseChildrenUntilEnd(parser: XmlPullParser, endTagName: String): String {
        val sb = StringBuilder()
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> {
                    sb.append(parseNode(parser, tag))
                }
                XmlPullParser.END_TAG -> {
                    if (tag == endTagName) return sb.toString()
                }
            }
            event = parser.next()
        }
        return sb.toString()
    }

    private fun parseNode(parser: XmlPullParser, tag: String): String = when (tag) {
        "r" -> parseRun(parser)
        "f" -> parseFraction(parser)
        "sSub" -> parseSub(parser)
        "sSup" -> parseSup(parser)
        "sSubSup" -> parseSubSup(parser)
        "sPre" -> parsePre(parser)
        "rad" -> parseRadical(parser)
        "d" -> parseDelimiter(parser)
        "acc" -> parseAccent(parser)
        "bar" -> parseBar(parser)
        "groupChr" -> parseGroupChr(parser)
        "func" -> parseFunc(parser)
        "nary" -> parseNary(parser)
        "limLow" -> parseLimit(parser, isLow = true)
        "limUpp" -> parseLimit(parser, isLow = false)
        "m" -> parseMatrix(parser)
        "oMath" -> "<span class='omml-math'>${parseChildrenUntilEnd(parser, "oMath")}</span>"
        "box", "e", "num", "den", "sub", "sup", "deg", "fName", "lim" ->
            parseChildrenUntilEnd(parser, tag)
        else -> {
            skipElement(parser, tag)
            ""
        }
    }

    private fun parseRun(parser: XmlPullParser): String {
        var isNormal = false
        val textBuf = StringBuilder()
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "nor" -> isNormal = true
                    "sty" -> {
                        val v = getAttr(parser, "val")
                        if (v == "p" || v == "b") isNormal = true
                    }
                    "t" -> textBuf.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> {
                    if (tag == "r") break
                }
            }
            event = parser.next()
        }
        val raw = textBuf.toString()
        if (raw.isEmpty()) return ""
        val escaped = OoxmlParser.escapeHtml(raw)
        if (isNormal) return "<span class='omml-nor'>$escaped</span>"

        // Italicize Latin letters, keep digits and math operators upright
        val styled = StringBuilder()
        for (ch in raw) {
            val s = OoxmlParser.escapeHtml(ch.toString())
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                styled.append("<var>$s</var>")
            } else {
                styled.append(s)
            }
        }
        return styled.toString()
    }

    private fun parseFraction(parser: XmlPullParser): String {
        var num = ""
        var den = ""
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "num" -> num = parseChildrenUntilEnd(parser, "num")
                    "den" -> den = parseChildrenUntilEnd(parser, "den")
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == "f") break
            }
            event = parser.next()
        }
        return "<span class='omml-frac'><span class='omml-num'>$num</span><span class='omml-den'>$den</span></span>"
    }

    private fun parseSub(parser: XmlPullParser): String {
        var base = ""
        var sub = ""
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "e" -> base = parseChildrenUntilEnd(parser, "e")
                    "sub" -> sub = parseChildrenUntilEnd(parser, "sub")
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == "sSub") break
            }
            event = parser.next()
        }
        return "<span class='omml-script'>$base<sub>$sub</sub></span>"
    }

    private fun parseSup(parser: XmlPullParser): String {
        var base = ""
        var sup = ""
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "e" -> base = parseChildrenUntilEnd(parser, "e")
                    "sup" -> sup = parseChildrenUntilEnd(parser, "sup")
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == "sSup") break
            }
            event = parser.next()
        }
        return "<span class='omml-script'>$base<sup>$sup</sup></span>"
    }

    private fun parseSubSup(parser: XmlPullParser): String {
        var base = ""
        var sub = ""
        var sup = ""
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "e" -> base = parseChildrenUntilEnd(parser, "e")
                    "sub" -> sub = parseChildrenUntilEnd(parser, "sub")
                    "sup" -> sup = parseChildrenUntilEnd(parser, "sup")
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == "sSubSup") break
            }
            event = parser.next()
        }
        return "<span class='omml-subsup'>$base<span class='omml-scripts'><span class='omml-sup'>$sup</span><span class='omml-sub'>$sub</span></span></span>"
    }

    private fun parsePre(parser: XmlPullParser): String {
        var base = ""
        var sub = ""
        var sup = ""
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "e" -> base = parseChildrenUntilEnd(parser, "e")
                    "sub" -> sub = parseChildrenUntilEnd(parser, "sub")
                    "sup" -> sup = parseChildrenUntilEnd(parser, "sup")
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == "sPre") break
            }
            event = parser.next()
        }
        return "<span class='omml-subsup'><span class='omml-scripts'><span class='omml-sup'>$sup</span><span class='omml-sub'>$sub</span></span>$base</span>"
    }

    private fun parseRadical(parser: XmlPullParser): String {
        var deg = ""
        var body = ""
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "deg" -> deg = parseChildrenUntilEnd(parser, "deg").trim()
                    "e" -> body = parseChildrenUntilEnd(parser, "e")
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == "rad") break
            }
            event = parser.next()
        }
        val degHtml = if (deg.isNotEmpty()) "<sup class='omml-rad-deg'>$deg</sup>" else ""
        return "<span class='omml-rad'>$degHtml<span class='omml-rad-sign'>√</span><span class='omml-rad-body'>$body</span></span>"
    }

    private fun parseDelimiter(parser: XmlPullParser): String {
        var begChr = "("
        var endChr = ")"
        var sepChr = ","
        val parts = mutableListOf<String>()
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "dPr" -> {
                        var prEvent = parser.next()
                        while (prEvent != XmlPullParser.END_DOCUMENT) {
                            val prTag = localName(parser.name)
                            if (prEvent == XmlPullParser.START_TAG) {
                                when (prTag) {
                                    "begChr" -> begChr = getAttr(parser, "val")
                                    "endChr" -> endChr = getAttr(parser, "val")
                                    "sepChr" -> sepChr = getAttr(parser, "val")
                                }
                            } else if (prEvent == XmlPullParser.END_TAG && prTag == "dPr") {
                                break
                            }
                            prEvent = parser.next()
                        }
                    }
                    "e" -> parts.add(parseChildrenUntilEnd(parser, "e"))
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == "d") break
            }
            event = parser.next()
        }
        val joined = parts.joinToString(OoxmlParser.escapeHtml(sepChr))
        return "<span class='omml-delim'>${OoxmlParser.escapeHtml(begChr)}$joined${OoxmlParser.escapeHtml(endChr)}</span>"
    }

    private fun parseAccent(parser: XmlPullParser): String {
        var chr = "→"
        var body = ""
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "accPr" -> {
                        var prEvent = parser.next()
                        while (prEvent != XmlPullParser.END_DOCUMENT) {
                            val prTag = localName(parser.name)
                            if (prEvent == XmlPullParser.START_TAG && prTag == "chr") {
                                val v = getAttr(parser, "val")
                                if (v.isNotEmpty()) chr = v
                            } else if (prEvent == XmlPullParser.END_TAG && prTag == "accPr") {
                                break
                            }
                            prEvent = parser.next()
                        }
                    }
                    "e" -> body = parseChildrenUntilEnd(parser, "e")
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == "acc") break
            }
            event = parser.next()
        }
        val sym = when (chr) {
            "\u20D7", "→" -> "→"
            "\u0304", "¯" -> "¯"
            "\u0302", "^" -> "ˆ"
            else -> chr
        }
        return "<span class='omml-acc'><span class='omml-acc-chr'>${OoxmlParser.escapeHtml(sym)}</span><span class='omml-acc-base'>$body</span></span>"
    }

    private fun parseBar(parser: XmlPullParser): String {
        var body = ""
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "e" -> body = parseChildrenUntilEnd(parser, "e")
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == "bar") break
            }
            event = parser.next()
        }
        return "<span class='omml-overline'>$body</span>"
    }

    private fun parseGroupChr(parser: XmlPullParser): String {
        var chr = "→"
        var body = ""
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "groupChrPr" -> {
                        var prEvent = parser.next()
                        while (prEvent != XmlPullParser.END_DOCUMENT) {
                            val prTag = localName(parser.name)
                            if (prEvent == XmlPullParser.START_TAG && prTag == "chr") {
                                val v = getAttr(parser, "val")
                                if (v.isNotEmpty()) chr = v
                            } else if (prEvent == XmlPullParser.END_TAG && prTag == "groupChrPr") {
                                break
                            }
                            prEvent = parser.next()
                        }
                    }
                    "e" -> body = parseChildrenUntilEnd(parser, "e")
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == "groupChr") break
            }
            event = parser.next()
        }
        return "<span class='omml-group'><span class='omml-group-top'>$body</span><span class='omml-group-chr'>${OoxmlParser.escapeHtml(chr)}</span></span>"
    }

    private fun parseFunc(parser: XmlPullParser): String {
        var fname = ""
        var body = ""
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "fName" -> fname = parseChildrenUntilEnd(parser, "fName")
                    "e" -> body = parseChildrenUntilEnd(parser, "e")
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == "func") break
            }
            event = parser.next()
        }
        return "<span class='omml-func'><span class='omml-nor'>$fname</span>&#8201;$body</span>"
    }

    private fun parseNary(parser: XmlPullParser): String {
        var chr = "∑"
        var sub = ""
        var sup = ""
        var body = ""
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "naryPr" -> {
                        var prEvent = parser.next()
                        while (prEvent != XmlPullParser.END_DOCUMENT) {
                            val prTag = localName(parser.name)
                            if (prEvent == XmlPullParser.START_TAG && prTag == "chr") {
                                val v = getAttr(parser, "val")
                                if (v.isNotEmpty()) chr = v
                            } else if (prEvent == XmlPullParser.END_TAG && prTag == "naryPr") {
                                break
                            }
                            prEvent = parser.next()
                        }
                    }
                    "sub" -> sub = parseChildrenUntilEnd(parser, "sub")
                    "sup" -> sup = parseChildrenUntilEnd(parser, "sup")
                    "e" -> body = parseChildrenUntilEnd(parser, "e")
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == "nary") break
            }
            event = parser.next()
        }
        return "<span class='omml-nary'><span class='omml-nary-op'>${OoxmlParser.escapeHtml(chr)}</span>" +
            "<span class='omml-scripts'><span class='omml-sup'>$sup</span><span class='omml-sub'>$sub</span></span>$body</span>"
    }

    private fun parseLimit(parser: XmlPullParser, isLow: Boolean): String {
        var base = ""
        var lim = ""
        val endName = if (isLow) "limLow" else "limUpp"
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "e" -> base = parseChildrenUntilEnd(parser, "e")
                    "lim" -> lim = parseChildrenUntilEnd(parser, "lim")
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == endName) break
            }
            event = parser.next()
        }
        return if (isLow) {
            "<span class='omml-lim'><span>$base</span><span class='omml-lim-sub'>$lim</span></span>"
        } else {
            "<span class='omml-lim'><span class='omml-lim-sub'>$lim</span><span>$base</span></span>"
        }
    }

    private fun parseMatrix(parser: XmlPullParser): String {
        val sb = StringBuilder("<span class='omml-matrix'>")
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "mr" -> {
                        sb.append("<span class='omml-mr'>")
                        var rEvent = parser.next()
                        while (rEvent != XmlPullParser.END_DOCUMENT) {
                            val rTag = localName(parser.name)
                            if (rEvent == XmlPullParser.START_TAG && rTag == "e") {
                                sb.append("<span class='omml-mc'>${parseChildrenUntilEnd(parser, "e")}</span>")
                            } else if (rEvent == XmlPullParser.END_TAG && rTag == "mr") {
                                break
                            }
                            rEvent = parser.next()
                        }
                        sb.append("</span>")
                    }
                    else -> skipElement(parser, tag)
                }
                XmlPullParser.END_TAG -> if (tag == "m") break
            }
            event = parser.next()
        }
        sb.append("</span>")
        return sb.toString()
    }

    private fun skipElement(parser: XmlPullParser, tagName: String) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
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
}
