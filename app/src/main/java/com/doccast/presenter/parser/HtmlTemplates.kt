package com.doccast.presenter.parser

import com.doccast.presenter.model.HtmlPage

/**
 * Responsive, projector-friendly HTML/CSS templates for Word (.docx/.doc) views,
 * supporting OMML Word Equations, MathType SVG, seamless non-paginated vs paginated vertical flow,
 * and automatic Fit-Width / Fit-Whole-Page scaling.
 */
object HtmlTemplates {

    private val BODY_CONTENT_REGEX = Regex("<body[^>]*>(.*)</body>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val STYLE_BLOCK_REGEX = Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private const val OMML_MATH_CSS = """
      .omml-math { font-family: "Cambria Math", "Times New Roman", Georgia, serif; display: inline; white-space: nowrap; }
      .omml-block { font-family: "Cambria Math", "Times New Roman", Georgia, serif; text-align: center; margin: 10px 0; overflow-x: auto; }
      .omml-math var, .omml-block var { font-style: italic; }
      .omml-nor { font-style: normal !important; }
      .omml-frac { display: inline-flex; flex-direction: column; align-items: center; vertical-align: middle; margin: 0 3px; line-height: 1.15; font-size: 0.92em; }
      .omml-num { display: block; padding: 0 4px 2px 4px; border-bottom: 1.25px solid #0f172a; text-align: center; }
      .omml-den { display: block; padding: 2px 4px 0 4px; text-align: center; }
      .omml-script sub, .omml-script sup { font-size: 0.72em; line-height: 0; }
      .omml-subsup { display: inline-flex; align-items: center; vertical-align: baseline; }
      .omml-scripts { display: inline-flex; flex-direction: column; font-size: 0.68em; line-height: 1.05; margin-left: 1px; vertical-align: middle; }
      .omml-rad { display: inline-flex; align-items: stretch; vertical-align: middle; margin: 0 2px; }
      .omml-rad-deg { font-size: 0.65em; margin-right: -2px; }
      .omml-rad-sign { font-size: 1.15em; line-height: 1.05; padding-right: 1px; }
      .omml-rad-body { border-top: 1.25px solid #0f172a; padding: 1px 3px 0 3px; display: inline-block; }
      .omml-acc { display: inline-flex; flex-direction: column; align-items: center; vertical-align: bottom; line-height: 1.0; margin: 0 1px; }
      .omml-acc-chr { font-size: 0.72em; margin-bottom: -4px; }
      .omml-overline { text-decoration: overline; }
      .omml-group { display: inline-flex; flex-direction: column; align-items: center; vertical-align: middle; margin: 0 4px; line-height: 1.0; }
      .omml-group-top { font-size: 0.78em; }
      .omml-group-chr { font-size: 1.1em; margin-top: -3px; }
      .omml-nary { display: inline-flex; align-items: center; margin: 0 2px; }
      .omml-nary-op { font-size: 1.35em; margin-right: 2px; }
      .omml-lim { display: inline-flex; flex-direction: column; align-items: center; vertical-align: middle; line-height: 1.05; }
      .omml-lim-sub { font-size: 0.7em; }
      .omml-matrix { display: inline-table; vertical-align: middle; border-collapse: collapse; }
      .omml-mr { display: table-row; }
      .omml-mc { display: table-cell; padding: 1px 6px; text-align: center; }
    """

    /**
     * Combines multiple Word pages into one continuous vertically-scrollable HTML document.
     * - isPaginated = false: seamless vertical flow (no page gaps, no footers).
     * - isPaginated = true: distinct separated page cards with shadow & footer.
     */
    fun combinePagesVertically(
        title: String,
        pages: List<HtmlPage>,
        isPcMode: Boolean,
        isPaginated: Boolean,
        fitMode: String = "width"
    ): String {
        if (pages.isEmpty()) return ""
        if (pages.size == 1) return applyViewOptions(pages.first().html, isPcMode, isPaginated, fitMode)

        val firstHtml = pages.first().html
        val baseStyles = STYLE_BLOCK_REGEX.findAll(firstHtml).joinToString("\n") { it.value }
        val combinedBody = StringBuilder()

        pages.forEachIndexed { idx, page ->
            val inner = BODY_CONTENT_REGEX.find(page.html)?.groupValues?.get(1) ?: page.html
            combinedBody.append("<section class='vpage-item' data-page='$idx'>$inner</section>")
            if (isPaginated && idx < pages.lastIndex) {
                combinedBody.append("<div class='vpage-divider'></div>")
            }
        }

        val bg = if (isPaginated) "#cbd5e1" else "#ffffff"
        val footerDisplay = if (isPaginated) "block" else "none"
        val doc = """
        <!DOCTYPE html>
        <html lang="vi">
        <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.35, maximum-scale=5.0, user-scalable=yes" />
        $baseStyles
        <style>
          html, body {
            display: block !important; height: auto !important;
            background: $bg; margin: 0; padding: 0;
          }
          .vpage-item { display: block; width: 100%; margin: 0 auto; }
          .vpage-divider { height: 14px; background: #cbd5e1; }
          .doc-page-footer { display: $footerDisplay !important; }
        </style>
        <title>${OoxmlParser.escapeHtml(title)}</title>
        </head>
        <body><div id="fit-root">$combinedBody</div></body>
        </html>
        """.trimIndent()

        return applyViewOptions(doc, isPcMode, isPaginated, fitMode)
    }

    fun applyViewOptions(
        html: String,
        isPcMode: Boolean,
        isPaginated: Boolean,
        fitMode: String
    ): String {
        var out = html
        if (isPcMode || fitMode == "page") {
            val pcStyle = """
                <style id="pc-mode-style">
                  html, body { background: ${if (isPaginated || fitMode == "page") "#cbd5e1" else "#ffffff"} !important; margin: 0 !important; padding: 0 !important; }
                  .doc-page-sheet {
                    width: 794px !important;
                    min-height: ${if (isPaginated || fitMode == "page") "1080px" else "auto"} !important;
                    margin: 0 auto !important;
                    padding: 48px 56px !important;
                    background: #ffffff !important;
                    box-shadow: ${if (isPaginated || fitMode == "page") "0 6px 22px rgba(15,23,42,0.22)" else "none"} !important;
                    box-sizing: border-box !important;
                  }
                </style>
            """.trimIndent()
            out = out.replace("</head>", "$pcStyle\n</head>", ignoreCase = true)
        }

        val fitScript = """
            <script>
              function applyDocFit(mode) {
                var body = document.body;
                if (!body) return;
                body.style.transformOrigin = 'top left';
                body.style.transform = 'none';
                var vw = Math.max(window.innerWidth, 240);
                var vh = Math.max(window.innerHeight, 240);
                var cw = Math.max(body.scrollWidth, 1);
                var ch = Math.max(body.scrollHeight, 1);
                var scale = 1.0;
                if (mode === 'width' && cw > vw) {
                  scale = vw / cw;
                } else if (mode === 'page') {
                  scale = Math.min(vw / cw, vh / Math.min(ch, 1140));
                }
                scale = Math.max(0.25, Math.min(1.0, scale));
                if (scale < 0.995) {
                  body.style.transform = 'scale(' + scale.toFixed(4) + ')';
                }
              }
              window.addEventListener('load', function() { applyDocFit('$fitMode'); });
              window.addEventListener('resize', function() { applyDocFit('$fitMode'); });
            </script>
        """.trimIndent()
        return out.replace("</body>", "$fitScript\n</body>", ignoreCase = true)
    }

    fun applyPcMode(html: String, isPcMode: Boolean): String =
        applyViewOptions(html, isPcMode, isPaginated = true, fitMode = "width")

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
        <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.35, maximum-scale=5.0, user-scalable=yes" />
        <style>
          body {
            font-family: "Times New Roman", Georgia, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, serif;
            background: #ffffff;
            color: #0f172a;
            margin: 0;
            padding: 0;
            line-height: 1.62;
            font-size: 16.5px;
          }
          .doc-page-sheet { padding: 16px 18px 28px 18px; background: #ffffff; box-sizing: border-box; }
          h1, h2, h3, h4 { color: #1e3a8a; margin-top: 0.9em; margin-bottom: 0.4em; }
          h1 { font-size: 1.45em; border-bottom: 2px solid #e2e8f0; padding-bottom: 6px; }
          h2 { font-size: 1.25em; }
          p { margin: 0.45em 0; }
          .math-wrap { display: inline-block; vertical-align: middle; margin: 0 2px; }
          .math-svg { max-width: 100%; height: auto; }
          .inline-img { max-height: 2.2em; vertical-align: middle; }
          .table-wrap { overflow-x: auto; margin: 10px 0; }
          table { border-collapse: collapse; width: 100%; font-size: 15px; }
          td, th { border: 1px solid #94a3b8; padding: 7px 9px; text-align: left; vertical-align: top; }
          th { background: #eff6ff; color: #1e3a8a; font-weight: 600; }
          .img-box { text-align: center; margin: 10px 0; }
          .img-box img { max-width: 100%; height: auto; border-radius: 6px; }
          .doc-page-footer {
            margin-top: 20px; padding-top: 8px; border-top: 1px dashed #cbd5e1;
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
}
