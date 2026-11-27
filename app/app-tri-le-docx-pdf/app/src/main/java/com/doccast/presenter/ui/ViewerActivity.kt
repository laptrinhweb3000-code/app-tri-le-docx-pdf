package com.doccast.presenter.ui

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.WebSettings
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.doccast.presenter.R
import com.doccast.presenter.cast.ExternalScreenPresentation
import com.doccast.presenter.cast.QrCodeHelper
import com.doccast.presenter.cast.WifiCastServer
import com.doccast.presenter.data.AnnotationStore
import com.doccast.presenter.databinding.ActivityViewerBinding
import com.doccast.presenter.model.DocFormat
import com.doccast.presenter.model.LoadedDocument
import com.doccast.presenter.parser.DocumentParser
import com.doccast.presenter.parser.HtmlTemplates
import com.doccast.presenter.parser.PdfDocumentEngine
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.math.abs

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private lateinit var displayManager: DisplayManager
    private lateinit var annotationStore: AnnotationStore
    private lateinit var swipeDetector: GestureDetector
    private lateinit var pdfLayoutManager: LinearLayoutManager

    private var currentUri: Uri? = null
    private var currentUriString: String = ""
    private var currentDocTitle: String = "TaiLieu"
    private var currentDocFormat: DocFormat = DocFormat.UNKNOWN
    private var pdfEngine: PdfDocumentEngine? = null
    private var loadedDoc: LoadedDocument? = null
    private var currentPageIndex: Int = 0
    private var totalPages: Int = 1
    private var isBlackout: Boolean = false
    private var isPcMode: Boolean = false
    private var isVerticalScroll: Boolean = true
    private var controlsVisible: Boolean = false

    private var externalPresentation: ExternalScreenPresentation? = null
    private var wifiCastServer: WifiCastServer? = null
    private var wifiCastPort: Int = 8080

    private var contentVersion: Long = 1L
    private var overlayVersion: Long = 1L

    private val timerHandler = Handler(Looper.getMainLooper())
    private var elapsedSeconds: Int = 0
    private val timerRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++
            val mins = elapsedSeconds / 60
            val secs = elapsedSeconds % 60
            binding.tvTimer.text = String.format(Locale.US, "%02d:%02d", mins, secs)
            timerHandler.postDelayed(this, 1000L)
        }
    }

    private val createAnnotatedPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri: Uri? ->
        if (destUri != null) {
            exportAnnotatedPdfToUri(destUri)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = attachExternalDisplayIfAvailable()
        override fun onDisplayRemoved(displayId: Int) = detachExternalDisplay()
        override fun onDisplayChanged(displayId: Int) = attachExternalDisplayIfAvailable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        annotationStore = AnnotationStore(this)
        setupSwipeGesture()
        setupWebView()
        setupVerticalPdfList()
        setupControls()

        val uri = intent.data
        if (uri == null) {
            Toast.makeText(this, "Không tìm thấy đường dẫn tệp.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentUri = uri
        currentUriString = uri.toString()
        val displayName = intent.getStringExtra(EXTRA_FILE_NAME)
            ?: DocumentParser.resolveDisplayName(this, uri)
        currentDocTitle = displayName

        loadDocumentAsync(uri, displayName)
        timerHandler.postDelayed(timerRunnable, 1000L)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val isNormalMode = binding.annotationOverlay.mode == AnnotationOverlayView.Mode.NONE
        val isSingleFinger = ev.pointerCount == 1
        val pdfZoomed = loadedDoc is LoadedDocument.Pdf && (if (isVerticalScroll) binding.rvPdfVertical.isZoomed() else binding.ivPdfPage.isZoomed())
        if (isNormalMode && isSingleFinger && !pdfZoomed) {
            swipeDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        displayManager.registerDisplayListener(displayListener, null)
        attachExternalDisplayIfAvailable()
        updateCastBadge()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPageStrokes()
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
        detachExternalDisplay()
        stopWifiServer()
        pdfEngine?.close()
    }

    private fun setupSwipeGesture() {
        swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > 120 && abs(dx) > abs(dy) * 1.5f && abs(velocityX) > 200) {
                    if (dx < 0) {
                        goToNextPage()
                    } else {
                        goToPrevPage()
                    }
                    return true
                }
                return false
            }
        })
    }

    private fun setupWebView() {
        binding.webOfficeView.settings.apply {
            javaScriptEnabled = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
        }
    }

    private fun setupVerticalPdfList() {
        pdfLayoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        binding.rvPdfVertical.layoutManager = pdfLayoutManager
        binding.rvPdfVertical.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!isVerticalScroll || loadedDoc !is LoadedDocument.Pdf) return
                val firstVisible = pdfLayoutManager.findFirstVisibleItemPosition()
                if (firstVisible != RecyclerView.NO_POSITION && firstVisible != currentPageIndex) {
                    currentPageIndex = firstVisible
                    contentVersion++
                    binding.tvPageCounter.text = "${currentPageIndex + 1} / $totalPages"
                    binding.btnPrevPage.alpha = if (currentPageIndex > 0) 1f else 0.4f
                    binding.btnNextPage.alpha = if (currentPageIndex < totalPages - 1) 1f else 0.4f
                    syncExternalPdfPage(currentPageIndex)
                }
            }
        })
    }

    private fun setupControls() {
        controlsVisible = false
        binding.floatingControlsGroup.visibility = View.GONE

        binding.btnFloatingMenu.setOnClickListener {
            controlsVisible = !controlsVisible
            binding.floatingControlsGroup.visibility = if (controlsVisible) View.VISIBLE else View.GONE
            binding.btnFloatingMenu.setBackgroundResource(
                if (controlsVisible) R.drawable.bg_floating_btn_active else R.drawable.bg_floating_btn
            )
        }

        binding.btnBack.setOnClickListener {
            saveCurrentPageStrokes()
            finish()
        }

        binding.btnPrevPage.setOnClickListener { goToPrevPage() }
        binding.btnNextPage.setOnClickListener { goToNextPage() }

        binding.btnModeNormal.setOnClickListener { setToolMode(AnnotationOverlayView.Mode.NONE) }
        binding.btnModeLaser.setOnClickListener { setToolMode(AnnotationOverlayView.Mode.LASER) }
        binding.btnModePen.setOnClickListener { setToolMode(AnnotationOverlayView.Mode.PEN) }

        binding.btnVerticalScroll.setOnClickListener {
            isVerticalScroll = !isVerticalScroll
            binding.btnVerticalScroll.setBackgroundResource(
                if (isVerticalScroll) R.drawable.bg_floating_btn_active else R.drawable.bg_floating_btn
            )
            showPage(currentPageIndex)
            Toast.makeText(
                this,
                if (isVerticalScroll) "Đã bật cuộn trang theo chiều dọc" else "Đã chuyển sang chế độ xem từng trang",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnPcMode.setOnClickListener {
            isPcMode = !isPcMode
            binding.btnPcMode.setBackgroundResource(
                if (isPcMode) R.drawable.bg_floating_btn_active else R.drawable.bg_floating_btn
            )
            if (loadedDoc is LoadedDocument.Pdf) {
                if (isVerticalScroll) binding.rvPdfVertical.resetZoom() else binding.ivPdfPage.resetZoom()
            }
            showPage(currentPageIndex)
            Toast.makeText(
                this,
                if (isPcMode) "Đã bật chế độ trang chuẩn PC (hỗ trợ thu phóng)" else "Đã chuyển về chế độ đọc di động",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnClearInk.setOnClickListener {
            binding.annotationOverlay.clearAll()
            saveCurrentPageStrokes()
        }

        binding.btnSaveInk.setOnClickListener {
            saveAnnotationsAndExportFile()
        }

        binding.btnShareFile.setOnClickListener {
            shareCurrentFile()
        }

        binding.btnBlackout.setOnClickListener {
            isBlackout = !isBlackout
            binding.blackoutPreviewBanner.visibility = if (isBlackout) View.VISIBLE else View.GONE
            binding.tvBlackoutNotice.visibility = if (isBlackout) View.VISIBLE else View.GONE
            binding.btnBlackout.setBackgroundResource(
                if (isBlackout) R.drawable.bg_floating_btn_active else R.drawable.bg_floating_btn
            )
            externalPresentation?.setBlackout(isBlackout)
            overlayVersion++
        }

        binding.annotationOverlay.onAnnotationChanged = {
            overlayVersion++
            externalPresentation?.syncAnnotations(
                binding.annotationOverlay.snapshotStrokes(),
                binding.annotationOverlay.laserActive,
                binding.annotationOverlay.laserX,
                binding.annotationOverlay.laserY
            )
        }

        binding.btnDisplayCast.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
            }.onFailure {
                Toast.makeText(
                    this,
                    "Hãy cắm cáp HDMI/USB-C hoặc bật Smart View / Chiếu không dây trong Cài đặt.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.btnWifiCast.setOnClickListener {
            showWifiCastDialog()
        }

        setToolMode(AnnotationOverlayView.Mode.NONE)
    }

    private fun shareCurrentFile() {
        val sourceUri = currentUri ?: return
        lifecycleScope.launch {
            try {
                val (shareUri, mimeType) = withContext(Dispatchers.IO) {
                    val shareDir = File(cacheDir, "share_cache").apply { mkdirs() }
                    val safeName = ensureFileExtension(currentDocTitle, currentDocFormat)
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val outFile = File(shareDir, safeName)

                    contentResolver.openInputStream(sourceUri)?.use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("Không thể đọc tệp hiện tại để chia sẻ.")

                    val providerUri = FileProvider.getUriForFile(
                        this@ViewerActivity,
                        "${packageName}.fileprovider",
                        outFile
                    )
                    val mime = mimeTypeForFormat(currentDocFormat, contentResolver.getType(sourceUri))
                    providerUri to mime
                }

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    putExtra(Intent.EXTRA_SUBJECT, currentDocTitle)
                    clipData = ClipData.newRawUri(currentDocTitle, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(
                    Intent.createChooser(
                        sendIntent,
                        "Chia sẻ qua Zalo, Messenger, Drive, OneDrive…"
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this@ViewerActivity,
                    "Không thể chia sẻ tệp: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun ensureFileExtension(name: String, format: DocFormat): String {
        val ext = when (format) {
            DocFormat.PDF -> "pdf"
            DocFormat.DOCX -> "docx"
            DocFormat.DOC -> "doc"
            DocFormat.UNKNOWN -> ""
        }
        if (ext.isEmpty() || name.lowercase(Locale.US).endsWith(".$ext")) return name
        return "$name.$ext"
    }

    private fun mimeTypeForFormat(format: DocFormat, fallback: String?): String = when (format) {
        DocFormat.PDF -> "application/pdf"
        DocFormat.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        DocFormat.DOC -> "application/msword"
        DocFormat.UNKNOWN -> fallback ?: "*/*"
    }

    private fun goToPrevPage() {
        if (currentPageIndex > 0) {
            saveCurrentPageStrokes()
            if (isVerticalScroll && loadedDoc is LoadedDocument.Pdf) {
                currentPageIndex--
                binding.rvPdfVertical.smoothScrollToPosition(currentPageIndex)
                binding.tvPageCounter.text = "${currentPageIndex + 1} / $totalPages"
                syncExternalPdfPage(currentPageIndex)
            } else {
                showPage(currentPageIndex - 1)
            }
        }
    }

    private fun goToNextPage() {
        if (currentPageIndex < totalPages - 1) {
            saveCurrentPageStrokes()
            if (isVerticalScroll && loadedDoc is LoadedDocument.Pdf) {
                currentPageIndex++
                binding.rvPdfVertical.smoothScrollToPosition(currentPageIndex)
                binding.tvPageCounter.text = "${currentPageIndex + 1} / $totalPages"
                syncExternalPdfPage(currentPageIndex)
            } else {
                showPage(currentPageIndex + 1)
            }
        }
    }

    private fun saveCurrentPageStrokes() {
        if (currentUriString.isEmpty()) return
        annotationStore.savePageStrokes(
            currentUriString,
            currentPageIndex,
            binding.annotationOverlay.snapshotStrokes()
        )
    }

    private fun saveAnnotationsAndExportFile() {
        saveCurrentPageStrokes()
        val baseName = currentDocTitle.substringBeforeLast('.').ifBlank { "DocCast" }
        val isPdf = loadedDoc is LoadedDocument.Pdf

        if (isPdf) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportAnnotatedPdfToDownloads("${baseName}_ghichu.pdf")
            } else {
                createAnnotatedPdfLauncher.launch("${baseName}_ghichu.pdf")
            }
        } else {
            exportStageSnapshotPng("${baseName}_trang${currentPageIndex + 1}_ghichu.png")
        }
    }

    private fun exportAnnotatedPdfToDownloads(fileName: String) {
        lifecycleScope.launch {
            try {
                val savedPath = withContext(Dispatchers.IO) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DocCastPresenter")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Không thể tạo tệp trong Download.")
                    contentResolver.openOutputStream(uri)?.use { out ->
                        writeAnnotatedPdf(out)
                    }
                    "Download/DocCastPresenter/$fileName"
                }
                Toast.makeText(
                    this@ViewerActivity,
                    "Đã lưu nét vẽ vào $savedPath",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@ViewerActivity, "Lỗi lưu tệp: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportAnnotatedPdfToUri(destUri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(destUri)?.use { out ->
                        writeAnnotatedPdf(out)
                    }
                }
                Toast.makeText(this@ViewerActivity, "Đã lưu file PDF kèm nét vẽ!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@ViewerActivity, "Lỗi lưu tệp: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun writeAnnotatedPdf(out: OutputStream) {
        val engine = pdfEngine ?: return
        val pdfDoc = PdfDocument()
        try {
            for (i in 0 until engine.pageCount) {
                val bmp = engine.renderPage(i, 1240)
                val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                val page = pdfDoc.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawBitmap(bmp, 0f, 0f, null)

                val strokes = if (i == currentPageIndex) {
                    binding.annotationOverlay.snapshotStrokes()
                } else {
                    annotationStore.loadPageStrokes(currentUriString, i)
                }
                AnnotationOverlayView.drawStrokesOnCanvas(
                    canvas,
                    bmp.width.toFloat(),
                    bmp.height.toFloat(),
                    strokes
                )
                pdfDoc.finishPage(page)
            }
            pdfDoc.writeTo(out)
        } finally {
            pdfDoc.close()
        }
    }

    private fun exportStageSnapshotPng(fileName: String) {
        val stage = binding.stageContainer
        if (stage.width <= 0 || stage.height <= 0) return

        val bmp = Bitmap.createBitmap(stage.width, stage.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        stage.draw(canvas)

        lifecycleScope.launch {
            try {
                val savedMsg = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, "image/png")
                            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DocCastPresenter")
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("Không thể tạo ảnh trong Download.")
                        contentResolver.openOutputStream(uri)?.use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        "Download/DocCastPresenter/$fileName"
                    } else {
                        val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName)
                        file.outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        file.absolutePath
                    }
                }
                Toast.makeText(this@ViewerActivity, "Đã lưu nét vẽ vào $savedMsg", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@ViewerActivity, "Lỗi lưu ảnh: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setToolMode(mode: AnnotationOverlayView.Mode) {
        binding.annotationOverlay.mode = mode
        binding.btnModeNormal.setBackgroundResource(
            if (mode == AnnotationOverlayView.Mode.NONE) R.drawable.bg_floating_btn_active else R.drawable.bg_floating_btn
        )
        binding.btnModeLaser.setBackgroundResource(
            if (mode == AnnotationOverlayView.Mode.LASER) R.drawable.bg_floating_btn_active else R.drawable.bg_floating_btn
        )
        binding.btnModePen.setBackgroundResource(
            if (mode == AnnotationOverlayView.Mode.PEN) R.drawable.bg_floating_btn_active else R.drawable.bg_floating_btn
        )
    }

    private fun loadDocumentAsync(uri: Uri, displayName: String) {
        binding.progressLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val format = DocumentParser.detectFormat(this@ViewerActivity, uri, displayName)
                currentDocFormat = format
                val result = withContext(Dispatchers.IO) {
                    if (format == DocFormat.PDF) {
                        val engine = PdfDocumentEngine.open(this@ViewerActivity, uri)
                        pdfEngine = engine
                        LoadedDocument.Pdf(displayName, engine.pageCount)
                    } else {
                        DocumentParser.loadOfficeDocument(this@ViewerActivity, uri, displayName, format)
                    }
                }

                loadedDoc = result
                totalPages = when (result) {
                    is LoadedDocument.Pdf -> result.pageCount.coerceAtLeast(1)
                    is LoadedDocument.HtmlPages -> result.pages.size.coerceAtLeast(1)
                }

                if (result is LoadedDocument.Pdf && pdfEngine != null) {
                    val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1080)
                    binding.rvPdfVertical.adapter = PdfVerticalAdapter(pdfEngine!!, targetWidth, lifecycleScope)
                }

                binding.progressLoading.visibility = View.GONE
                showPage(0)
            } catch (e: Exception) {
                binding.progressLoading.visibility = View.GONE
                Toast.makeText(
                    this@ViewerActivity,
                    "Lỗi mở tài liệu: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun syncExternalPdfPage(pageIdx: Int) {
        val engine = pdfEngine ?: return
        if (externalPresentation?.isShowing != true) return
        val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1200)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                engine.renderPage(pageIdx, targetWidth)
            }
            externalPresentation?.showPdfBitmap(bmp)
        }
    }

    private fun renderedHtmlForPage(idx: Int): String? {
        val doc = loadedDoc as? LoadedDocument.HtmlPages ?: return null
        val raw = doc.pages.getOrNull(idx)?.html ?: return null
        return HtmlTemplates.applyPcMode(raw, isPcMode)
    }

    private fun showPage(index: Int) {
        val doc = loadedDoc ?: return
        currentPageIndex = index.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        contentVersion++

        val savedStrokes = annotationStore.loadPageStrokes(currentUriString, currentPageIndex)
        binding.annotationOverlay.loadStrokes(savedStrokes)

        when (doc) {
            is LoadedDocument.Pdf -> {
                binding.webOfficeView.visibility = View.GONE
                binding.tvPageCounter.text = "${currentPageIndex + 1} / $totalPages"

                val engine = pdfEngine ?: return
                if (isVerticalScroll) {
                    binding.ivPdfPage.visibility = View.GONE
                    binding.rvPdfVertical.visibility = View.VISIBLE
                    binding.rvPdfVertical.scrollToPosition(currentPageIndex)
                    syncExternalPdfPage(currentPageIndex)
                } else {
                    binding.rvPdfVertical.visibility = View.GONE
                    binding.ivPdfPage.visibility = View.VISIBLE
                    val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1200)
                    lifecycleScope.launch {
                        val bmp = withContext(Dispatchers.IO) {
                            engine.renderPage(currentPageIndex, targetWidth)
                        }
                        binding.ivPdfPage.setImageBitmap(bmp)
                        externalPresentation?.showPdfBitmap(bmp)
                    }
                }
            }
            is LoadedDocument.HtmlPages -> {
                binding.ivPdfPage.visibility = View.GONE
                binding.rvPdfVertical.visibility = View.GONE
                binding.webOfficeView.visibility = View.VISIBLE

                val page = doc.pages.getOrNull(currentPageIndex) ?: return
                val singlePageHtml = HtmlTemplates.applyPcMode(page.html, isPcMode)
                val displayHtml = if (isVerticalScroll && doc.pages.size > 1) {
                    HtmlTemplates.combinePagesVertically(doc.title, doc.pages, isPcMode)
                } else {
                    singlePageHtml
                }

                binding.tvPageCounter.text = if (isVerticalScroll && doc.pages.size > 1) {
                    "Cuộn dọc ($totalPages trang)"
                } else {
                    "${page.label} (${currentPageIndex + 1}/$totalPages)"
                }
                binding.webOfficeView.settings.layoutAlgorithm = if (isPcMode) {
                    WebSettings.LayoutAlgorithm.NORMAL
                } else {
                    WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                }
                binding.webOfficeView.loadDataWithBaseURL(null, displayHtml, "text/html", "UTF-8", null)
                externalPresentation?.showHtmlContent(singlePageHtml)
            }
        }

        binding.btnPrevPage.alpha = if (currentPageIndex > 0) 1f else 0.4f
        binding.btnNextPage.alpha = if (currentPageIndex < totalPages - 1) 1f else 0.4f
        updateCastBadge()
    }

    private fun attachExternalDisplayIfAvailable() {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val targetDisplay: Display? = displays.firstOrNull()
        if (targetDisplay == null) {
            detachExternalDisplay()
            return
        }

        if (externalPresentation?.display?.displayId == targetDisplay.displayId && externalPresentation?.isShowing == true) {
            return
        }

        detachExternalDisplay()
        runCatching {
            val presentation = ExternalScreenPresentation(this, targetDisplay)
            presentation.show()
            presentation.setBlackout(isBlackout)
            externalPresentation = presentation
            showPage(currentPageIndex)
        }
        updateCastBadge()
    }

    private fun detachExternalDisplay() {
        runCatching { externalPresentation?.dismiss() }
        externalPresentation = null
        updateCastBadge()
    }

    private fun ensureWifiServerStarted(): Boolean {
        if (wifiCastServer != null) return true
        for (port in 8080..8090) {
            try {
                val server = WifiCastServer(
                    port = port,
                    stateProvider = {
                        val doc = loadedDoc
                        WifiCastServer.CastSnapshot(
                            contentVersion = contentVersion,
                            overlayVersion = overlayVersion,
                            isPdf = doc is LoadedDocument.Pdf,
                            title = doc?.title ?: "Tài liệu",
                            pageIndex = currentPageIndex,
                            totalPages = totalPages,
                            blackout = isBlackout,
                            overlaySvg = binding.annotationOverlay.toSvgMarkup()
                        )
                    },
                    pdfJpegProvider = { idx ->
                        pdfEngine?.renderPageAsJpeg(idx, 1600, 88)
                    },
                    htmlPageProvider = { idx ->
                        renderedHtmlForPage(idx)
                    }
                )
                server.start()
                wifiCastServer = server
                wifiCastPort = port
                updateCastBadge()
                return true
            } catch (_: Exception) {
                // Try next port
            }
        }
        return false
    }

    private fun stopWifiServer() {
        runCatching { wifiCastServer?.stop() }
        wifiCastServer = null
        updateCastBadge()
    }

    private fun showWifiCastDialog() {
        if (wifiCastServer == null) {
            val started = ensureWifiServerStarted()
            if (!started) {
                Toast.makeText(this, "Không thể khởi động máy chủ phát Wi-Fi.", Toast.LENGTH_LONG).show()
                return
            }
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_wifi_cast, null)
        val ivQr = dialogView.findViewById<ImageView>(R.id.ivQrCode)
        val tvUrl = dialogView.findViewById<TextView>(R.id.tvCastUrl)
        val btnToggle = dialogView.findViewById<MaterialButton>(R.id.btnToggleWifiServer)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btnCloseDialog)

        val ip = QrCodeHelper.findLocalWifiIpAddress()
        val castUrl = "http://$ip:$wifiCastPort"
        tvUrl.text = castUrl
        ivQr.setImageBitmap(QrCodeHelper.generateQrBitmap(castUrl, 480))

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnToggle.setOnClickListener {
            if (wifiCastServer != null) {
                stopWifiServer()
                Toast.makeText(this, "Đã tắt phát trình chiếu Wi-Fi.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun updateCastBadge() {
        val parts = mutableListOf<String>()
        if (isVerticalScroll) parts.add("Cuộn dọc")
        if (isPcMode) parts.add("PC")
        if (externalPresentation?.isShowing == true) {
            parts.add("HDMI/TV: Đang chiếu")
        }
        if (wifiCastServer != null) {
            val ip = QrCodeHelper.findLocalWifiIpAddress()
            parts.add("Wi-Fi: http://$ip:$wifiCastPort")
        }
        if (parts.isEmpty()) {
            binding.tvCastBadge.text = "$currentDocTitle • Chế độ xem"
            binding.tvCastBadge.setTextColor(Color.parseColor("#FF334155"))
        } else {
            binding.tvCastBadge.text = parts.joinToString(" | ")
            binding.tvCastBadge.setTextColor(Color.parseColor("#FF15803D"))
        }
    }

    companion object {
        const val EXTRA_FILE_NAME = "extra_file_name"
    }
}
