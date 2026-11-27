package com.doccast.presenter.parser

import com.doccast.presenter.model.HtmlPage

/**
 * Responsive, projector-friendly HTML/CSS templates for Word, PowerPoint, and Excel views,
 * including crisp OMML Word Equation styling, continuous vertical multi-page view,
 * and Desktop PC Page View mode.
 */
object HtmlTemplates {

    private val BODY_CONTENT_REGEX = Regex("<body[^>]*>(.*)</body>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val STYLE_BLOCK_REGEX = Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private const val OMML_MATH_CSS = """
      .omml-math {
        font-family: "Cambria Math", "Times New Roman", Georgia, serif;
        display: inline;
        white-space: nowrap;
      }
      .omml-block {
        font-family: "Cambria Math", "Times New Roman", Georgia, serif;
        text-align: center;
        margin: 10px 0;
        overflow-x: auto;
      }
      .omml-math var, .omml-block var { font-style: italic; }
      .omml-nor { font-style: normal !important; }
      .omml-frac {
        display: inline-flex;
        flex-direction: column;
        align-items: center;
        vertical-align: middle;
        margin: 0 3px;
        line-height: 1.15;
        font-size: 0.92em;
      }
      .omml-num {
        display: block;
        padding: 0 4px 2px 4px;
        border-bottom: 1.25px solid #0f172a;
        text-align: center;
      }
      .omml-den {
        display: block;
        padding: 2px 4px 0 4px;
        text-align: center;
      }
      .omml-script sub, .omml-script sup {
        font-size: 0.72em;
        line-height: 0;
      }
      .omml-subsup {
        display: inline-flex;
        align-items: center;
        vertical-align: baseline;
      }
      .omml-scripts {
        display: inline-flex;
        flex-direction: column;
        font-size: 0.68em;
        line-height: 1.05;
        margin-left: 1px;
        vertical-align: middle;
      }
      .omml-rad {
        display: inline-flex;
        align-items: stretch;
        vertical-align: middle;
        margin: 0 2px;
      }
      .omml-rad-deg { font-size: 0.65em; margin-right: -2px; }
      .omml-rad-sign { font-size: 1.15em; line-height: 1.05; padding-right: 1px; }
      .omml-rad-body {
        border-top: 1.25px solid #0f172a;
        padding: 1px 3px 0 3px;
        display: inline-block;
      }
      .omml-acc {
        display: inline-flex;
        flex-direction: column;
        align-items: center;
        vertical-align: bottom;
        line-height: 1.0;
        margin: 0 1px;
      }
      .omml-acc-chr { font-size: 0.72em; margin-bottom: -4px; }
      .omml-overline { text-decoration: overline; }
      .omml-group {
        display: inline-flex;
        flex-direction: column;
        align-items: center;
        vertical-align: middle;
        margin: 0 4px;
        line-height: 1.0;
      }
      .omml-group-top { font-size: 0.78em; }
      .omml-group-chr { font-size: 1.1em; margin-top: -3px; }
      .omml-nary { display: inline-flex; align-items: center; margin: 0 2px; }
      .omml-nary-op { font-size: 1.35em; margin-right: 2px; }
      .omml-lim {
        display: inline-flex;
        flex-direction: column;
        align-items: center;
        vertical-align: middle;
        line-height: 1.05;
      }
      .omml-lim-sub { font-size: 0.7em; }
      .omml-matrix {
        display: inline-table;
        vertical-align: middle;
        border-collapse: collapse;
      }
      .omml-mr { display: table-row; }
      .omml-mc { display: table-cell; padding: 1px 6px; text-align: center; }
    """

    /**
     * Combines multiple HtmlPages (Word pages, PPT slides, or Excel sheets) into one
     * continuous vertically-scrollable HTML document with crisp visual page separators.
     */
    fun combinePagesVertically(title: String, pages: List<HtmlPage>, isPcMode: Boolean): String {
        if (pages.isEmpty()) return ""
        if (pages.size == 1) return applyPcMode(pages.first().html, isPcMode)

        val firstHtml = pages.first().html
        val baseStyles = STYLE_BLOCK_REGEX.findAll(firstHtml).joinToString("\n") { it.value }
        val combinedBody = StringBuilder()

        pages.forEachIndexed { idx, page ->
            val inner = BODY_CONTENT_REGEX.find(page.html)?.groupValues?.get(1) ?: page.html
            combinedBody.append("<section class='vpage-item' data-page='$idx'>$inner</section>")
            if (idx < pages.lastIndex) {
                combinedBody.append("<div class='vpage-divider'></div>")
            }
        }

        val doc = """
        <!DOCTYPE html>
        <html lang="vi">
        <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=5.0, user-scalable=yes" />
        $baseStyles
        <style>
          html, body {
            display: block !important;
            height: auto !important;
            min-height: 100%;
            background: #e2e8f0;
            margin: 0;
            padding: 0;
          }
          .vpage-item {
            display: block;
            width: 100%;
            margin: 0 auto;
          }
          .vpage-divider {
            height: 12px;
            background: #cbd5e1;
          }
        </style>
        <title>${OoxmlParser.escapeHtml(title)}</title>
        </head>
        <body>$combinedBody</body>
        </html>
        """.trimIndent()

        return applyPcMode(doc, isPcMode)
    }

    /**
     * Switches any generated HTML page dynamically between Mobile Reading mode
     * and Desktop PC Page View mode (A4 sheet for Word, 16:9 canvas for PPT, wide grid for Excel).
     */
    fun applyPcMode(html: String, isPcMode: Boolean): String {
        if (!isPcMode) return html
        val pcViewport = "<meta name=\"viewport\" content=\"width=920, initial-scale=0.45, minimum-scale=0.25, maximum-scale=5.0, user-scalable=yes\" />"
        val pcStyle = """
            <style id="pc-mode-style">
              html, body {
                background: #cbd5e1 !important;
                margin: 0 !important;
                padding: 18px 0 36px 0 !important;
                min-width: 860px !important;
              }
              .vpage-divider {
                height: 22px !important;
                background: transparent !important;
              }
              .doc-page-sheet {
                width: 794px !important;
                min-height: 1080px !important;
                margin: 0 auto !important;
                padding: 58px 66px !important;
                background: #ffffff !important;
                box-shadow: 0 6px 24px rgba(15, 23, 42, 0.25) !important;
                box-sizing: border-box !important;
                font-size: 15.5px !important;
              }
              .slide-canvas {
                width: 1120px !important;
                min-height: 630px !important;
                margin: 24px auto !important;
              }
              .sheet-container {
                width: 1040px !important;
                margin: 0 auto !important;
                background: #ffffff !important;
                padding: 18px !important;
                box-shadow: 0 4px 18px rgba(15, 23, 42, 0.2) !important;
              }
            </style>
        """.trimIndent()

        val withViewport = html.replace(
            Regex("<meta\\s+name=\"viewport\"[^>]*>", RegexOption.IGNORE_CASE),
            pcViewport
        )
        return withViewport.replace("</head>", "$pcStyle\n</head>", ignoreCase = true)
    }

    fun wrapDocument(
        title: String,
        bodyHtml: String,
        pageNumber: Int? = null,
        totalPages: Int? = null
    ): String {
        val pageFooter = if (pageNumber != null && totalPages != null && totalPages > 1) {
            "<div class='doc-page-footer'>Trang $pageNumber / $totalPages</div>"
        } else {
            ""
        }
        return """
        <!DOCTYPE html>
        <html lang="vi">
        <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=5.0, user-scalable=yes" />
        <style>
          body {
            font-family: "Times New Roman", Georgia, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, serif;
            background: #ffffff;
            color: #0f172a;
            margin: 0;
            padding: 0;
            line-height: 1.65;
            font-size: 16.5px;
          }
          .doc-page-sheet {
            padding: 18px 20px 44px 20px;
            background: #ffffff;
          }
          h1, h2, h3, h4 { color: #1e3a8a; margin-top: 1em; margin-bottom: 0.45em; }
          h1 { font-size: 1.45em; border-bottom: 2px solid #e2e8f0; padding-bottom: 6px; }
          h2 { font-size: 1.25em; }
          p { margin: 0.5em 0; }
          .math-wrap { display: inline-block; vertical-align: middle; margin: 0 2px; }
          .math-svg { max-width: 100%; height: auto; }
          .inline-img { max-height: 2.2em; vertical-align: middle; }
          .table-wrap { overflow-x: auto; margin: 12px 0; }
          table { border-collapse: collapse; width: 100%; font-size: 15px; }
          td, th { border: 1px solid #94a3b8; padding: 7px 9px; text-align: left; vertical-align: top; }
          th { background: #eff6ff; color: #1e3a8a; font-weight: 600; }
          .img-box { text-align: center; margin: 12px 0; }
          .img-box img { max-width: 100%; height: auto; border-radius: 6px; }
          .doc-page-footer {
            margin-top: 24px; padding-top: 8px; border-top: 1px dashed #cbd5e1;
            text-align: center; font-size: 12px; color: #64748b; font-family: sans-serif;
          }
          .muted { color: #64748b; font-style: italic; }
          $OMML_MATH_CSS
        </style>
        <title>${OoxmlParser.escapeHtml(title)}</title>
        </head>
        <body><div class="doc-page-sheet">$bodyHtml$pageFooter</div></body>
        </html>
        """.trimIndent()
    }

    fun wrapSlide(slideNumber: Int, totalSlides: Int, contentHtml: String): String = """
        <!DOCTYPE html>
        <html lang="vi">
        <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=5.0, user-scalable=yes" />
        <style>
          html, body {
            margin: 0; padding: 0; width: 100%; min-height: 100%;
            background: #0f172a; color: #0f172a;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            display: flex; align-items: center; justify-content: center;
          }
          .slide-canvas {
            width: 94vw; max-width: 1200px; min-height: 76vh;
            background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
            border-radius: 14px;
            box-shadow: 0 18px 40px rgba(0,0,0,0.45);
            padding: 36px 42px;
            box-sizing: border-box;
            position: relative;
            display: flex; flex-direction: column; justify-content: flex-start;
          }
          .slide-title {
            color: #1e40af; font-size: clamp(22px, 3.2vw, 38px);
            margin: 0 0 20px 0; padding-bottom: 12px;
            border-bottom: 3px solid #2563eb;
          }
          .slide-bullets {
            font-size: clamp(16px, 2.1vw, 26px);
            line-height: 1.55; padding-left: 26px; margin: 0;
          }
          .slide-bullets li { margin-bottom: 12px; }
          .slide-gallery {
            display: flex; flex-wrap: wrap; gap: 14px;
            justify-content: center; margin-top: 20px;
          }
          .slide-img { max-width: 100%; max-height: 46vh; object-fit: contain; border-radius: 8px; }
          .slide-footer {
            position: absolute; right: 24px; bottom: 14px;
            font-size: 13px; color: #64748b; font-weight: 600;
          }
          .muted { color: #64748b; font-size: 18px; }
          $OMML_MATH_CSS
        </style>
        </head>
        <body>
          <div class="slide-canvas">
            $contentHtml
            <div class="slide-footer">Slide $slideNumber / $totalSlides</div>
          </div>
        </body>
        </html>
    """.trimIndent()

    fun wrapSpreadsheet(title: String, tableHtml: String): String = """
        <!DOCTYPE html>
        <html lang="vi">
        <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=5.0, user-scalable=yes" />
        <style>
          body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background: #ffffff; color: #0f172a; margin: 0; padding: 12px;
          }
          .sheet-header {
            font-size: 15px; font-weight: 700; color: #166534;
            margin-bottom: 10px; padding: 6px 10px;
            background: #dcfce7; border-radius: 8px;
          }
          .grid-wrap { overflow: auto; }
          table { border-collapse: collapse; min-width: 100%; font-size: 14px; }
          th, td {
            border: 1px solid #cbd5e1; padding: 7px 10px;
            white-space: nowrap; text-align: left;
          }
          th {
            background: #16a34a; color: #ffffff;
            position: sticky; top: 0; font-weight: 600;
          }
          tr:nth-child(even) td { background: #f8fafc; }
          .muted { color: #64748b; padding: 16px; }
        </style>
        </head>
        <body>
          <div class="sheet-container">
            <div class="sheet-header">${OoxmlParser.escapeHtml(title)}</div>
            <div class="grid-wrap">$tableHtml</div>
          </div>
        </body>
        </html>
    """.trimIndent()
}
