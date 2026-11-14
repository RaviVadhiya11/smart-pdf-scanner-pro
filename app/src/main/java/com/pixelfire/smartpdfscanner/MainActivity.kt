package com.pixelfire.smartpdfscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.widget.EditText
import android.widget.FrameLayout
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


private class SignatureCanvas(context: android.content.Context) : View(context) {
    private val strokes = mutableListOf<Path>()
    private var activePath: Path? = null
    private var inkColor: Int = android.graphics.Color.BLACK
    private var inkWidth: Float = 7f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkColor
        style = Paint.Style.STROKE
        strokeWidth = inkWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    fun setInk(color: Int) { inkColor = color; invalidate() }
    fun setInkWidth(value: Float) { inkWidth = value.coerceIn(2f, 18f); invalidate() }
    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex); invalidate() }
    fun clear() { strokes.clear(); activePath = null; invalidate() }
    fun hasSignature(): Boolean = strokes.isNotEmpty()
    fun toBitmap(): Bitmap {
        val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
            val c = Canvas(bitmap)
            strokes.forEach { path ->
                paint.color = inkColor
                paint.strokeWidth = inkWidth
                c.drawPath(path, paint)
            }
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = inkColor
        paint.strokeWidth = inkWidth
        strokes.forEach { canvas.drawPath(it, paint) }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activePath = Path().apply { moveTo(event.x, event.y) }
                activePath?.let { strokes.add(it) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> { activePath?.lineTo(event.x, event.y); invalidate(); return true }
            MotionEvent.ACTION_UP -> { activePath?.lineTo(event.x, event.y); activePath = null; invalidate(); return true }
        }
        return true
    }
}

private class ImageCropView(context: android.content.Context, private var source: Bitmap) : View(context) {
    private val imageRect = android.graphics.RectF()
    private val cropRect = android.graphics.RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF21B7E8.toInt(); style = Paint.Style.FILL }
    private var activeCorner = -1
    private val handleRadius = 26f

    init { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF111827.toInt())
        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val w = source.width * scale
        val h = source.height * scale
        imageRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        if (cropRect.isEmpty) {
            cropRect.set(imageRect.left + w * 0.08f, imageRect.top + h * 0.08f, imageRect.right - w * 0.08f, imageRect.bottom - h * 0.08f)
        }
        canvas.drawBitmap(source, null, imageRect, imagePaint)
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropRect.top, shadePaint)
        canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, shadePaint)
        canvas.drawRect(imageRect.left, cropRect.top, cropRect.left, cropRect.bottom, shadePaint)
        canvas.drawRect(cropRect.right, cropRect.top, imageRect.right, cropRect.bottom, shadePaint)
        canvas.drawRect(cropRect, borderPaint)
        val corners = floatArrayOf(cropRect.left, cropRect.top, cropRect.right, cropRect.top, cropRect.left, cropRect.bottom, cropRect.right, cropRect.bottom)
        for (i in corners.indices step 2) canvas.drawCircle(corners[i], corners[i + 1], handleRadius / 2f, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val corners = arrayOf(
                    cropRect.left to cropRect.top, cropRect.right to cropRect.top,
                    cropRect.left to cropRect.bottom, cropRect.right to cropRect.bottom
                )
                activeCorner = corners.indices.minByOrNull { i ->
                    val dx = event.x - corners[i].first
                    val dy = event.y - corners[i].second
                    dx * dx + dy * dy
                } ?: -1
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val minSize = 48f
                when (activeCorner) {
                    0 -> { cropRect.left = event.x.coerceIn(imageRect.left, cropRect.right - minSize); cropRect.top = event.y.coerceIn(imageRect.top, cropRect.bottom - minSize) }
                    1 -> { cropRect.right = event.x.coerceIn(cropRect.left + minSize, imageRect.right); cropRect.top = event.y.coerceIn(imageRect.top, cropRect.bottom - minSize) }
                    2 -> { cropRect.left = event.x.coerceIn(imageRect.left, cropRect.right - minSize); cropRect.bottom = event.y.coerceIn(cropRect.top + minSize, imageRect.bottom) }
                    3 -> { cropRect.right = event.x.coerceIn(cropRect.left + minSize, imageRect.right); cropRect.bottom = event.y.coerceIn(cropRect.top + minSize, imageRect.bottom) }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { activeCorner = -1; return true }
        }
        return true
    }

    fun resetCrop() {
        cropRect.setEmpty()
        invalidate()
    }

    fun rotateImage() {
        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
        source = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        cropRect.setEmpty()
        invalidate()
    }

    fun getCroppedBitmap(): Bitmap {
        val sx = source.width / imageRect.width()
        val sy = source.height / imageRect.height()
        val left = ((cropRect.left - imageRect.left) * sx).toInt().coerceIn(0, source.width - 1)
        val top = ((cropRect.top - imageRect.top) * sy).toInt().coerceIn(0, source.height - 1)
        val right = ((cropRect.right - imageRect.left) * sx).toInt().coerceIn(left + 1, source.width)
        val bottom = ((cropRect.bottom - imageRect.top) * sy).toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var recent: TextView
    private var lastPdf: Uri? = null
    private var pendingImagePdfSaveAs: Uri? = null
    private var signaturePng: ByteArray? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val qrAlreadyRead = AtomicBoolean(false)
    private var qrCameraProvider: ProcessCameraProvider? = null
    private val qrCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) openLiveQrCamera() else Toast.makeText(this, "Camera permission is required for live QR scanning.", Toast.LENGTH_LONG).show() }

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pdf = scan?.pdf
            if (pdf != null) {
                lastPdf = copyPdfToDownloads(pdf.uri)
                if (lastPdf != null) {
                    prefs.edit().putBoolean("has_completed_first_scan", true).apply()
                    Toast.makeText(this, "Saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
                    // Rebuild the home screen immediately so the new PDF appears without restarting.
                    buildScreen()
                } else {
                    Toast.makeText(this, "PDF could not be saved. Please scan again.", Toast.LENGTH_LONG).show()
                }
            } else status.text = "No PDF was returned."
        } else status.text = "Scan cancelled."
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) showImageImportPreview(uris)
    }

    private val imagePdfSaveAsPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { destination ->
        val source = pendingImagePdfSaveAs
        pendingImagePdfSaveAs = null
        if (destination != null && source != null) {
            Thread {
                try {
                    contentResolver.openInputStream(source).use { input ->
                        requireNotNull(input) { "Could not read generated PDF" }
                        contentResolver.openOutputStream(destination, "w").use { output ->
                            requireNotNull(output) { "Could not write to selected location" }
                            input.copyTo(output)
                        }
                    }
                    runOnUiThread { Toast.makeText(this, "PDF saved to your selected folder", Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Save As failed", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
    }


    private val idCardPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) showIdCardImportPreview(uris.take(2))
    }

    private val passportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showPassportPhotoEditor(uri)
    }

    private val ocrPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runOcr(uri)
    }

    private val mergePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size >= 2) mergePdfFiles(uris)
        else if (uris.isNotEmpty()) Toast.makeText(this, "Select at least two PDF files", Toast.LENGTH_LONG).show()
    }

    private val splitPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) splitPdfFile(uri)
    }

    private val importPdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lastPdf = uri
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            openPdf(uri)
        }
    }

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val signature = signaturePng
            if (signature != null) applySignatureToPdf(uri, signature)
            else { lastPdf = uri; openPdf(uri) }
        }
    }
    private val pdfTextPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) extractPdfText(uri) }
    private val detailsPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) showPdfDetails(uri) }
    private val rotatePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) rotatePdfPages(uri) }
    private val pdfEditorPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) showPdfEditor(uri) }
    private val compressPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) showCompressionOptions(uri) }
    private val pdfToWordPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) exportPdfAsWord(uri) }
    private val qrImagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) readQrFromImage(uri) }
    private val pdfToImagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) convertPdfToImages(uri) }
    private val passwordPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) passwordDialog(uri, false) }
    private val removePasswordPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) passwordDialog(uri, true) }
    private val zipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) zipPdf(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        buildScreen()
    }

    private fun setScreenContent(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun showPremiumDialog(builder: AlertDialog.Builder): AlertDialog {
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(0xFFFFFFFF.toInt(), 24, 0xFFE1E9F5.toInt()))
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
                dialog.getButton(which)?.apply {
                    isAllCaps = false
                    textSize = 14f
                    setTextColor(0xFF1764C0.toInt())
                }
            }
        }
        dialog.show()
        return dialog
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun gradient(start: Int, end: Int, radius: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end)).apply {
            cornerRadius = dp(radius).toFloat()
        }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            includeFontPadding = true
        }

    private fun gap(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun toolCard(title: String, subtitle: String, symbol: String, accent: Int, action: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(12), dp(13))
            background = rounded(0xFFFFFFFF.toInt(), 18, 0xFFE5EAF2.toInt())
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        val icon = TextView(this).apply {
            text = symbol
            textSize = 22f
            setTextColor(accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(0xFFEAF2FF.toInt(), 13)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(44))
        }
        card.addView(icon)
        card.addView(gap(11))
        card.addView(label(title, 14f, 0xFF17243A.toInt(), true))
        card.addView(gap(4))
        card.addView(label(subtitle, 10.5f, 0xFF69778D.toInt()))
        return card
    }

    private val prefs by lazy { getSharedPreferences("scanner_data", MODE_PRIVATE) }
    private val homeFolders = mutableListOf<String>()
    private var selectedFolder: String = "New Folder"
    private val folderAssignments by lazy { prefs.getString("folder_assignments", "")?.split("\\n")?.filter { it.contains("|") }?.associate { it.substringBefore("|") to it.substringAfter("|") }?.toMutableMap() ?: mutableMapOf() }

    private fun loadFolders() {
        homeFolders.clear()
        homeFolders.addAll(prefs.getStringSet("folders", setOf("New Folder", "ID Cards", "Bills")) ?: setOf("New Folder", "ID Cards", "Bills"))
        if (homeFolders.isEmpty()) homeFolders.add("New Folder")
    }
    private fun saveFolders() { prefs.edit().putStringSet("folders", homeFolders.toSet()).apply() }
    private fun assignPdf(uri: Uri) {
        folderAssignments[uri.toString()] = selectedFolder
        prefs.edit().putString("folder_assignments", folderAssignments.entries.joinToString("\\n") { "${it.key}|${it.value}" }).apply()
    }

    private fun buildScreen() {
        loadFolders()
        selectedFolder = "New Folder"
        val bg = 0xFFF5F8FF.toInt()
        val navy = 0xFF172B4D.toInt()
        val blue = 0xFF3478F6.toInt()
        val paleBlue = 0xFFEAF2FF.toInt()
        val muted = 0xFF718096.toInt()

        window.statusBarColor = 0xFF082D72.toInt()
        window.navigationBarColor = 0xFFFFFFFF.toInt()
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
        }

        // Premium blue header.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = gradient(0xFF073B91.toInt(), 0xFF06245F.toInt(), 20)
            elevation = dp(3).toFloat()
        }
        header.addView(TextView(this).apply {
            text = "☰"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, dp(10), 0)
            setOnClickListener { showAppDrawer() }
        })
        header.addView(object : View(this) {
            private val scannerInk = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1557A8.toInt()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(3).toFloat()
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            private val scanBeam = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF18BFEA.toInt()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(3).toFloat()
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                val left = w * 0.25f
                val top = h * 0.20f
                val right = w * 0.75f
                val bottom = h * 0.80f
                val corner = dp(8).toFloat()
                // Four viewfinder corners make the mark read as a document scanner.
                val path = android.graphics.Path().apply {
                    moveTo(left + corner, top); lineTo(left, top); lineTo(left, top + corner)
                    moveTo(right - corner, top); lineTo(right, top); lineTo(right, top + corner)
                    moveTo(left, bottom - corner); lineTo(left, bottom); lineTo(left + corner, bottom)
                    moveTo(right - corner, bottom); lineTo(right, bottom); lineTo(right, bottom - corner)
                }
                canvas.drawPath(path, scannerInk)
                val doc = android.graphics.RectF(w * 0.34f, h * 0.31f, w * 0.66f, h * 0.69f)
                scannerInk.style = android.graphics.Paint.Style.FILL
                scannerInk.alpha = 30
                canvas.drawRoundRect(doc, dp(3).toFloat(), dp(3).toFloat(), scannerInk)
                scannerInk.alpha = 255
                scannerInk.style = android.graphics.Paint.Style.STROKE
                canvas.drawLine(doc.left + dp(3), doc.top + dp(7), doc.right - dp(3), doc.top + dp(7), scannerInk)
                canvas.drawLine(doc.left + dp(3), doc.centerY(), doc.right - dp(3), doc.centerY(), scannerInk)
                canvas.drawLine(doc.left + dp(3), doc.bottom - dp(7), doc.right - dp(3), doc.bottom - dp(7), scannerInk)
                canvas.drawLine(w * 0.18f, h * 0.50f, w * 0.82f, h * 0.50f, scanBeam)
            }
        }.apply {
            background = rounded(0xFFFFFFFF.toInt(), 15, 0xFFDCE8FF.toInt())
            contentDescription = "DocNexa document scanner icon"
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        })
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brand.addView(label("DocNexa", 20f, 0xFFFFFFFF.toInt(), true))
        brand.addView(label("PDF Scanner & Editor", 11f, 0xFFDCE8FF.toInt()))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "★"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFB82E.toInt())
            contentDescription = "Favourite documents"
            setOnClickListener { showFavouriteDocs() }
        })
        header.addView(TextView(this).apply {
            text = "⌕"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), 0, dp(4), 0)
            setOnClickListener { showDocumentSearch() }
        })
        header.addView(TextView(this).apply {
            text = "⋮"
            textSize = 32f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            minWidth = dp(42)
            minHeight = dp(48)
            setPadding(dp(8), 0, dp(4), 0)
            contentDescription = "More options"
            isClickable = true
            isFocusable = true
            setOnClickListener { showHomeOverflowMenu(this) }
        })
        // Keep the brand header outside the ScrollView so it stays fixed while content moves.
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(18)
            rightMargin = dp(18)
            topMargin = dp(12)
            bottomMargin = dp(12)
        })

        // Functional search field styled after the DocNexa reference screen.
        val searchEntry = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = rounded(0xFFFFFFFF.toInt(), 28, 0xFFB8D0FF.toInt())
            elevation = dp(2).toFloat()
            isClickable = true
            setOnClickListener { showDocumentSearch() }
        }
        searchEntry.addView(label("⌕", 25f, 0xFF073B91.toInt(), true))
        searchEntry.addView(label("  Search documents", 14f, 0xFF65738A.toInt()))
        searchEntry.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        searchEntry.addView(label("×", 21f, 0xFF65738A.toInt()))
        content.addView(searchEntry)
        content.addView(gap(12))

        // One compact horizontal quick-access strip combines navigation and top tools.
        val quickAccessScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            background = rounded(0xFFFFFFFF.toInt(), 14)
            elevation = dp(1).toFloat()
            setPadding(dp(5), dp(4), dp(5), dp(4))
        }
        val quickAccess = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun quickItem(title: String, selected: Boolean = false, action: () -> Unit) {
            val chip = TextView(this).apply {
                text = title
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                setTextColor(if (selected) blue else muted)
                background = if (selected) rounded(0xFFEAF2FF.toInt(), 10) else null
                setPadding(dp(11), dp(12), dp(11), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            quickAccess.addView(chip, LinearLayout.LayoutParams(-2, -2))
        }
        quickItem("⌂ Home", true) { buildScreen() }
        quickItem("▤ PDF Tools") { showPdfTools() }
        quickItem("▣ ID Photo") { showIdPhotoMenu() }
        quickItem("PDF Editor") { pdfEditorPicker.launch(arrayOf("application/pdf")) }
        quickItem("✍ Signature") { openSignatureEditor() }
        quickItem("OCR Image") { ocrPicker.launch("image/*") }
        quickItem("▤ Trash") { showTrashScreen() }
        quickAccessScroll.addView(quickAccess)
        content.addView(quickAccessScroll)
        content.addView(gap(16))

        // Premium scan hero: compact copy, custom scanner artwork and clear primary action.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = gradient(0xFFEAF2FF.toInt(), 0xFFDCEAFF.toInt(), 24)
            elevation = dp(2).toFloat()
        }
        val heroTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heroCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        heroCopy.addView(label("DOCNEXA • SMART SCAN", 10f, blue, true))
        heroCopy.addView(gap(8))
        heroCopy.addView(label("Your documents.\nClear, secure,\norganized.", 22f, navy, true))
        heroCopy.addView(gap(7))
        heroCopy.addView(label("Scan • Enhance • Save as PDF", 10.5f, muted))
        heroTop.addView(heroCopy, LinearLayout.LayoutParams(0, -2, 1f))
        val heroArt = object : View(this) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                val cx = w * .50f
                val cy = h * .50f
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = 0xFFBBD5FF.toInt()
                canvas.drawCircle(cx, cy, minOf(w, h) * .48f, paint)
                val rect = android.graphics.RectF(w*.25f, h*.13f, w*.75f, h*.87f)
                paint.color = 0xFFFFFFFF.toInt()
                canvas.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), paint)
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = dp(3).toFloat()
                paint.strokeCap = android.graphics.Paint.Cap.ROUND
                paint.color = 0xFF2164C5.toInt()
                val l=rect.left-dp(5); val t=rect.top+dp(8); val r=rect.right+dp(5); val b=rect.bottom-dp(8); val k=dp(11).toFloat()
                canvas.drawLine(l+k,t,l,t,paint); canvas.drawLine(l,t,l,t+k,paint)
                canvas.drawLine(r-k,t,r,t,paint); canvas.drawLine(r,t,r,t+k,paint)
                canvas.drawLine(l,b-k,l,b,paint); canvas.drawLine(l,b,l+k,b,paint)
                canvas.drawLine(r-k,b,r,b,paint); canvas.drawLine(r,b,r,b-k,paint)
                paint.color = 0xFF18A9D5.toInt()
                paint.strokeWidth = dp(4).toFloat()
                canvas.drawLine(w*.17f,cy,w*.83f,cy,paint)
                paint.color = 0xFF2164C5.toInt()
                paint.strokeWidth = dp(2).toFloat()
                canvas.drawLine(rect.left+dp(8),rect.top+dp(22),rect.right-dp(8),rect.top+dp(22),paint)
                canvas.drawLine(rect.left+dp(8),rect.bottom-dp(22),rect.right-dp(8),rect.bottom-dp(22),paint)
            }
        }.apply { contentDescription = "Document scanner illustration" }
        heroTop.addView(heroArt, LinearLayout.LayoutParams(dp(94), dp(112)).apply { leftMargin = dp(8) })
        hero.addView(heroTop)
        hero.addView(gap(14))
        val scanButton = Button(this).apply {
            text = "▣   Scan a Document"
            isAllCaps = false
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            background = gradient(0xFF4285F4.toInt(), 0xFF1764DE.toInt(), 16)
            backgroundTintList = null
            minHeight = dp(54)
            setOnClickListener { startDocumentScan() }
        }
        hero.addView(scanButton, LinearLayout.LayoutParams(-1, dp(54)))
        content.addView(hero)
        content.addView(gap(20))

        // Tool section title.
        val quickTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        quickTitle.addView(label("QUICK TOOLS", 15f, navy, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        quickTitle.addView(TextView(this).apply {
            text = "VIEW ALL  ›"
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(blue)
            setOnClickListener { showPdfTools() }
        })
        content.addView(quickTitle)
        content.addView(gap(10))

        val tools = listOf(
            Triple("Smart Scan", "Camera scanner", "▣"),
            Triple("PDF Tools", "Merge • Split • Edit", "PDF"),
            Triple("Import Picture", "Images to PDF", "▧"),
            Triple("Import File", "Open PDF files", "⇧"),
            Triple("Compress PDF", "Reduce file size", "⌁"),
            Triple("Image to Text", "Extract OCR text", "T"),
            Triple("PDF to Word", "Convert document", "W"),
            Triple("QR Scanner", "Scan QR codes", "⌗")
        )
        tools.chunked(2).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { index, item ->
                val accent = listOf(
                    0xFF3478F6.toInt(), 0xFFE66AA6.toInt(),
                    0xFF42B96B.toInt(), 0xFFFFC247.toInt(),
                    0xFFE66AA6.toInt(), 0xFFFFC247.toInt(),
                    0xFF3478F6.toInt(), 0xFF42B96B.toInt()
                )[rowIndex * 2 + index]
                val action: () -> Unit = when (rowIndex * 2 + index) {
                    0 -> ({ startDocumentScan() })
                    1 -> ({ showPdfTools() })
                    2 -> ({ imagePicker.launch(arrayOf("image/*")) })
                    3 -> ({ showImportFileHub() })
                    4 -> ({ compressPicker.launch(arrayOf("application/pdf")) })
                    5 -> ({ ocrPicker.launch("image/*") })
                    6 -> ({ pdfToWordPicker.launch(arrayOf("application/pdf")) })
                    7 -> ({ showQrScanner() })
                    else -> ({ showPdfTools() })
                }
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(13), dp(10), dp(12))
                    background = rounded(0xFFFFFFFF.toInt(), 17, 0xFFE8EDF5.toInt())
                    elevation = dp(1).toFloat()
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { action() }
                }
                card.addView(TextView(this).apply {
                    text = item.third
                    textSize = 20f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(accent)
                    background = rounded(paleBlue, 13)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(42))
                })
                card.addView(gap(8))
                card.addView(label(item.first, 13f, navy, true))
                card.addView(gap(3))
                card.addView(label(item.second, 10f, muted))
                val params = LinearLayout.LayoutParams(0, dp(116), 1f)
                if (index == 0) params.rightMargin = dp(8) else params.leftMargin = dp(8)
                row.addView(card, params)
            }
            content.addView(row)
            content.addView(gap(10))
        }

        // Before first successful scan show onboarding; afterwards reserve this area for ads.
        val hasScannedOnce = prefs.getBoolean("has_completed_first_scan", false)
        status = label(
            if (hasScannedOnce) "Your scanner is ready" else "Ready to scan your first document",
            14f, navy, true
        )
        recent = label("Your PDFs are saved in Downloads/DocumentScanner", 11f, muted)
        if (!hasScannedOnce) {
            val recentCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(0xFFFFFFFF.toInt(), 17, 0xFFE8EDF5.toInt())
                elevation = dp(1).toFloat()
            }
            val recentHead = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            recentHead.addView(label("RECENT ACTIVITY", 12f, navy, true),
                LinearLayout.LayoutParams(0, -2, 1f))
            recentHead.addView(label("● READY", 10f, 0xFF24966B.toInt(), true))
            recentCard.addView(recentHead)
            recentCard.addView(gap(9))
            recentCard.addView(status)
            recentCard.addView(gap(4))
            recentCard.addView(recent)
            content.addView(recentCard)
        } else {
            content.addView(status)
            content.addView(recent)
        }
        content.addView(gap(16))
        content.addView(label("MY DOCUMENTS", 15f, navy, true))
        content.addView(gap(8))
        homeFolders.forEach { folder -> content.addView(folderCard(folder)) }
        val savedDocs = getSavedPdfs()
        content.addView(label("Recent Documents (" + savedDocs.size + ")", 16f, navy, true))
        content.addView(gap(8))
        if (savedDocs.isEmpty()) content.addView(label("Your scanned PDFs will appear here automatically.", 11f, muted))
        else savedDocs.take(20).forEach { item -> content.addView(documentCard(item.first, item.second)) }
        content.addView(gap(16))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        addBottomAdAndActions(root)
        setScreenContent(root)
    }

    private fun showDocumentSearch() {
        val field = EditText(this).apply { hint = "Enter document name"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Search documents").setView(field)
            .setPositiveButton("Search") { _, _ ->
                val query = field.text.toString().trim()
                val matches = getSavedPdfs().filter { it.first.contains(query, ignoreCase = true) }
                if (query.isBlank()) Toast.makeText(this, "Type a document name to search", Toast.LENGTH_SHORT).show()
                else if (matches.isEmpty()) AlertDialog.Builder(this).setTitle("No documents found")
                    .setMessage("No saved PDF matches: " + query).setPositiveButton("OK", null).show()
                else AlertDialog.Builder(this).setTitle("Search results • " + matches.size)
                    .setItems(matches.map { it.first }.toTypedArray()) { _, index -> openPdf(matches[index].second) }
                    .setNegativeButton("Close", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showHomeOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("PDF / JPG").setOnMenuItemClickListener {
            AlertDialog.Builder(this)
                .setTitle("PDF / JPG")
                .setItems(arrayOf("Create PDF from images", "Open PDF file")) { _, which ->
                    if (which == 0) imagePicker.launch(arrayOf("image/*"))
                    else pdfPicker.launch(arrayOf("application/pdf"))
                }.show()
            true
        }
        val importMenu = popup.menu.addSubMenu("Import")
        importMenu.add("Images / JPG").setOnMenuItemClickListener {
            imagePicker.launch(arrayOf("image/*")); true
        }
        importMenu.add("PDF file").setOnMenuItemClickListener {
            pdfPicker.launch(arrayOf("application/pdf")); true
        }
        popup.menu.add("PDF Tools").setOnMenuItemClickListener { showPdfTools(); true }
        popup.menu.add("Sort By").setOnMenuItemClickListener {
            AlertDialog.Builder(this).setTitle("Sort documents")
                .setItems(arrayOf("Name (A–Z)", "Name (Z–A)", "Newest first", "Oldest first")) { _, which ->
                    prefs.edit().putInt("document_sort_order", which).apply()
                    buildScreen()
                }.show()
            true
        }
        popup.menu.add("Select").setOnMenuItemClickListener {
            val docs = getSavedPdfs()
            if (docs.isEmpty()) Toast.makeText(this, "No documents to select yet", Toast.LENGTH_SHORT).show()
            else AlertDialog.Builder(this).setTitle("Select documents")
                .setMultiChoiceItems(docs.map { it.first }.toTypedArray(), BooleanArray(docs.size)) { _, _, _ -> }
                .setPositiveButton("Done", null).show()
            true
        }
        popup.menu.add("Create Folder").setOnMenuItemClickListener { createFolderDialog(); true }
        popup.menu.add("Account / Cloud Sync").setOnMenuItemClickListener {
            if (cloudToken().isBlank()) showCloudLoginDialog() else cloudAccountMenu()
            true
        }
        popup.menu.add("View Mode").setOnMenuItemClickListener {
            AlertDialog.Builder(this).setTitle("View mode")
                .setSingleChoiceItems(arrayOf("List view", "Grid view"), prefs.getInt("document_view_mode", 0)) { dialog, which ->
                    prefs.edit().putInt("document_view_mode", which).apply()
                    dialog.dismiss()
                    buildScreen()
                }.setNegativeButton("Cancel", null).show()
            true
        }
        popup.show()
    }

    private fun addBottomAdAndActions(root: LinearLayout) {
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = dp(5).toFloat()
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val camera = Button(this).apply {
            text = "▣  Scan"
            isAllCaps = false
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            background = gradient(0xFF3478F6.toInt(), 0xFF1764DE.toInt(), 28)
            backgroundTintList = null
            setOnClickListener { startDocumentScan() }
        }
        val import = Button(this).apply {
            text = "+  Import"
            isAllCaps = false
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF168BC5.toInt(), 28)
            backgroundTintList = null
            setOnClickListener { imagePicker.launch(arrayOf("image/*")) }
        }
        bar.addView(camera, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) })
        bar.addView(import, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(5) })
        footer.addView(bar)
        root.addView(footer)
    }

    private fun moveToTrash(uri: Uri) {
        val set = (prefs.getStringSet("trash_uris", emptySet()) ?: emptySet()).toMutableSet()
        set.add(uri.toString())
        prefs.edit().putStringSet("trash_uris", set).apply()
        Toast.makeText(this, "Moved to Trash", Toast.LENGTH_SHORT).show()
        buildScreen()
    }

    private fun showTrashScreen() {
        val trashed = (prefs.getStringSet("trash_uris", emptySet()) ?: emptySet()).toList()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            background = rounded(0xFFF5F8FF.toInt(), 20)
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(documentIconTile(54))
        val headingText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        headingText.addView(label("Recently deleted", 17f, 0xFF172B4D.toInt(), true))
        headingText.addView(gap(3))
        headingText.addView(label("${trashed.size} document(s) in Trash", 11f, 0xFF718096.toInt()))
        heading.addView(headingText)
        panel.addView(heading)
        panel.addView(gap(14))

        if (trashed.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(28), dp(18), dp(28))
                background = rounded(0xFFFFFFFF.toInt(), 16, 0xFFE2EAF5.toInt())
            }
            empty.addView(documentIconTile(76))
            empty.addView(gap(12))
            empty.addView(label("Trash is empty", 17f, 0xFF263957.toInt(), true))
            empty.addView(gap(5))
            empty.addView(label("Deleted PDFs will appear here. You can restore them or delete them permanently.", 11f, 0xFF718096.toInt()).apply {
                gravity = Gravity.CENTER
            })
            panel.addView(empty)
            showPremiumDialog(AlertDialog.Builder(this).setTitle("Trash").setView(panel)
                .setPositiveButton("OK", null))
            return
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        trashed.forEach { raw ->
            val target = Uri.parse(raw)
            val name = target.lastPathSegment ?: "Deleted PDF"
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(0xFFFFFFFF.toInt(), 14, 0xFFE2EAF5.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, dp(82)).apply { bottomMargin = dp(9) }
            }
            // Render the first PDF page as a real thumbnail; fall back to the PDF tile
            // when the original content URI is no longer readable.
            val thumbnail = createPdfThumbnail(target)
            if (thumbnail != null) {
                card.addView(android.widget.ImageView(this).apply {
                    setImageBitmap(thumbnail)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    background = rounded(0xFFFFFFFF.toInt(), 8, 0xFFE2EAF5.toInt())
                    contentDescription = "Preview of $name"
                    layoutParams = LinearLayout.LayoutParams(dp(58), dp(68))
                })
            } else {
                card.addView(documentIconTile(58))
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, dp(4), 0)
            }
            info.addView(label(name, 13f, 0xFF263957.toInt(), true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            info.addView(gap(4))
            info.addView(label("PDF document  •  In Trash", 10.5f, 0xFF718096.toInt()))
            card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(this).apply {
                text = "•••"
                textSize = 19f
                setTextColor(0xFF64748B.toInt())
                setPadding(dp(10), dp(8), dp(4), dp(8))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle(name)
                        .setItems(arrayOf("Restore document", "Delete permanently")) { _, action ->
                            val set = (prefs.getStringSet("trash_uris", emptySet()) ?: emptySet()).toMutableSet()
                            if (action == 0) {
                                set.remove(target.toString())
                                prefs.edit().putStringSet("trash_uris", set).apply()
                                Toast.makeText(this@MainActivity, "PDF restored", Toast.LENGTH_SHORT).show()
                                showTrashScreen()
                            } else {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Delete permanently?")
                                    .setMessage("This PDF will be removed from your device.")
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Delete") { _, _ ->
                                        try { contentResolver.delete(target, null, null) } catch (_: Exception) {}
                                        val updated = (prefs.getStringSet("trash_uris", emptySet()) ?: emptySet()).toMutableSet()
                                        updated.remove(target.toString())
                                        prefs.edit().putStringSet("trash_uris", updated).apply()
                                        Toast.makeText(this@MainActivity, "Deleted permanently", Toast.LENGTH_SHORT).show()
                                        showTrashScreen()
                                    }.show()
                            }
                        }.setNegativeButton("Cancel", null).show()
                }
            })
            list.addView(card)
        }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(300)))
        showPremiumDialog(AlertDialog.Builder(this).setTitle("Trash • ${trashed.size} item(s)")
            .setView(panel).setPositiveButton("Close", null))
    }

    private fun showAppDrawer() {
        val drawer = android.app.Dialog(this)
        val screenWidth = resources.displayMetrics.widthPixels
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(20))
        }
        val account = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(28), dp(16), dp(24))
            setBackgroundColor(0xFF1D3B4A.toInt())
        }
        val accountAvatar = FrameLayout(this).apply {
            background = rounded(0xFF3478F6.toInt(), 22, 0xFF75B8FF.toInt())
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(62))
        }
        accountAvatar.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_myplaces)
            setColorFilter(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "DocNexa account profile"
            layoutParams = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
        })
        account.addView(accountAvatar)
        val accountText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        accountText.addView(label("Sign In (Cloud)", 18f, 0xFFFFFFFF.toInt(), true))
        accountText.addView(gap(5))
        accountText.addView(label("Sign in to sync documents", 13f, 0xFFE5EDF3.toInt()))
        accountText.removeAllViews()
        val signedInEmail = prefs.getString("cloud_email", null)
        accountText.addView(label(if (signedInEmail.isNullOrBlank()) "DocNexa Account" else signedInEmail, 18f, 0xFFFFFFFF.toInt(), true))
        accountText.addView(gap(5))
        accountText.addView(label(if (signedInEmail.isNullOrBlank()) "Backup and sync your PDFs" else "Cloud account connected", 13f, 0xFFE5EDF3.toInt()))
        account.addView(accountText, LinearLayout.LayoutParams(0, -2, 1f))
        menu.addView(account)
        val onlineState = isInternetAvailable()
        menu.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(10), dp(16), dp(10))
            setBackgroundColor(0xFFF4F7FC.toInt())
            addView(TextView(this@MainActivity).apply {
                text = if (onlineState) "●" else "●"
                textSize = 15f
                setTextColor(if (onlineState) 0xFF159B68.toInt() else 0xFFD65A5A.toInt())
            })
            addView(TextView(this@MainActivity).apply {
                text = if (onlineState) "ONLINE MODE  •  Internet connected" else "OFFLINE MODE  •  No internet"
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFF34445E.toInt())
                setPadding(dp(8), 0, 0, 0)
            })
        })

        // Clearly visible Login and Signup actions in the drawer header area.
        val authActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(0xFF1D3B4A.toInt())
        }
        val loginButton = TextView(this).apply {
            text = if (signedInEmail.isNullOrBlank()) "LOGIN" else "ACCOUNT"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF3478F6.toInt(), 10)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                drawer.dismiss()
                if (cloudToken().isBlank()) showCloudLoginDialog() else cloudAccountMenu()
            }
        }
        val signupButton = TextView(this).apply {
            text = if (signedInEmail.isNullOrBlank()) "SIGN UP" else "SIGN OUT"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (signedInEmail.isNullOrBlank()) 0xFF17324D.toInt() else 0xFFFFFFFF.toInt())
            background = rounded(if (signedInEmail.isNullOrBlank()) 0xFFFFFFFF.toInt() else 0xFFD94B55.toInt(), 10)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                drawer.dismiss()
                if (cloudToken().isBlank()) showCloudLoginDialog(startWithSignup = true) else confirmCloudSignOut()
            }
        }
        authActions.addView(loginButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        authActions.addView(signupButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6) })
        menu.addView(authActions)

        fun section(title: String) {
            menu.addView(TextView(this).apply {
                text = title; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFF333333.toInt()); setPadding(dp(20), dp(22), dp(16), dp(12))
            })
        }
        fun item(icon: String, title: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(28), dp(12), dp(16), dp(12)); minimumHeight = dp(54)
                isClickable = true; isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { drawer.dismiss(); action() }
            }
            row.addView(TextView(this).apply {
                text = icon; textSize = 19f; gravity = Gravity.CENTER
                setTextColor(0xFF168BC5.toInt()); background = rounded(0xFFEAF2FF.toInt(), 24)
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            row.addView(label(title, 15f, 0xFF161616.toInt(), true).apply { setPadding(dp(22), 0, 0, 0) })
            menu.addView(row)
        }
        fun separator() { menu.addView(View(this).apply { setBackgroundColor(0xFFE6E6E6.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10) }) }

        section("General")
        item("♡", "Favourite") { showFavouriteDocs() }
        item("◇", "Manage tags") { manageTagsDialog() }
        item("▤", "Trash") { showTrashScreen() }
        item("⚙", "Settings") { showSettingsDialog() }
        separator()
        section("Tools")
        item("⚒", "PDF Tools") { showPdfTools() }
        item("▣", "Edit/Create PDF") { imagePicker.launch(arrayOf("image/*")) }
        item("✍", "Signature") { openSignatureEditor() }
        item("▦", "QR Scanner") { showQrScanner() }
        item("▦", "QR Generator") { showQrGenerator() }
        separator()
        section("Other")
        item("?", "Help and FAQ") { showHelpAndFaq() }
        item("✉", "Contact us") { openFeedbackEmail(false) }
        item("⚑", "Report a bug") { openFeedbackEmail(true) }
        item("ⓘ", "About us") { showAboutDocNexa() }
        separator()
        section("Privacy & Legal")
        item("▣", "Privacy Policy") { showPrivacyPolicy() }
        item("§", "Terms of Use") { showTermsOfUse() }
        item("⌑", "Data and account help") { showDataHelp() }
        section("More")
        item("↗", "Share with friends") { shareApp() }
        item("★", "Rate us") { openRateUs() }

        scroll.addView(menu)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        drawer.setContentView(panel)
        drawer.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.45f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout((screenWidth * 0.86f).toInt(), -1)
            setGravity(Gravity.START)
            navigationBarColor = 0xFFFFFFFF.toInt()
        }
        drawer.show()
        drawer.window?.setLayout((screenWidth * 0.86f).toInt(), -1)
    }

    private fun showHelpAndFaq() {
        val topics = arrayOf(
            "How do I scan a document?",
            "How do I import photos?",
            "Where are my PDFs saved?",
            "How do I use cloud sync?",
            "How do I reset my password?",
            "How do I restore a deleted PDF?"
        )
        val answers = arrayOf(
            "Tap Scan on the home screen, allow camera access if asked, capture your pages and save the PDF.",
            "Tap Import and select one or more images. DocNexa will create a PDF from the selected images.",
            "Scanned PDFs are saved in Downloads/DocumentScanner on your device.",
            "Open the app drawer, choose Login or Create Account, verify your email if requested, then use Account / Cloud Sync. Cloud sync requires an internet connection.",
            "Open Login, enter your account email and tap Forgot password. Follow the reset link sent by the authentication service. Check spam if you do not see it.",
            "Open the app drawer, choose Trash, select a document and choose Restore. Permanent deletion cannot be undone."
        )
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(6))
            background = rounded(0xFFF5F8FF.toInt(), 16)
        }
        topics.forEachIndexed { index, topic ->
            val row = TextView(this).apply {
                text = "ⓘ   $topic     ›"
                textSize = 12.5f
                setTextColor(0xFF263957.toInt())
                setPadding(dp(12), dp(13), dp(10), dp(13))
                background = rounded(0xFFFFFFFF.toInt(), 12, 0xFFE1EAF7.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
                isClickable = true
                setOnClickListener {
                    showPremiumDialog(AlertDialog.Builder(this@MainActivity).setTitle(topic)
                        .setMessage(answers[index]).setPositiveButton("Got it", null)
                        .setNeutralButton("Contact support") { _, _ -> openFeedbackEmail(false) })
                }
            }
            panel.addView(row)
        }
        showPremiumDialog(AlertDialog.Builder(this).setTitle("DocNexa • Help & FAQ")
            .setView(panel).setNegativeButton("Close", null))
    }

    private fun openFeedbackEmail(reportBug: Boolean) {
        val subject = if (reportBug) "DocNexa bug report" else "DocNexa support request"
        val appVersion = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0" } catch (_: Exception) { "1.0" }
        val body = if (reportBug) {
            "Please describe the problem below:\n\nApp version: ${appVersion}\nAndroid: ${Build.VERSION.RELEASE}\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\n\nSteps to reproduce:\n\nExpected result:\n\nActual result:"
        } else {
            "Hello DocNexa team,\n\nI need help with:\n\nApp version: ${appVersion}"
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(Intent.createChooser(intent, if (reportBug) "Send bug report" else "Contact DocNexa"))
        } catch (_: Exception) {
            AlertDialog.Builder(this).setTitle("Email app not found")
                .setMessage("Please install or set up an email app on your phone, then try again.")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun showAboutDocNexa() {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0" } catch (_: Exception) { "1.0" }
        AlertDialog.Builder(this).setTitle("About DocNexa")
            .setMessage("DOCNEXA\nPDF Scanner & Editor\nVersion $version\n\nScan, organise and manage PDF documents from your Android device. Save PDFs locally and optionally use an account for cloud backup and sync.\n\nDocNexa is provided as a document productivity tool. Always keep a separate backup of important files.")
            .setPositiveButton("OK", null).show()
    }

    private fun showPrivacyPolicy() {
        val policy = """
            DOCNEXA – PRIVACY POLICY
            Last updated: 13 November 2026

            1. Information you provide
            If you create an account, your email address and the name you enter may be processed by the app's authentication service (Supabase). Your password is handled by that authentication service; do not share it with anyone.

            2. Documents and files
            PDFs created or imported in the app are saved on your device in Downloads/DocumentScanner. If you choose Cloud Sync while signed in, document files may be uploaded to the configured Supabase storage service so they can be restored on another device. Do not upload documents unless you are comfortable storing them with that service.

            3. Camera, photos and files
            Camera access is used for document scanning. Photo/file access is used only when you choose to import or open documents. The app processes the selected content to provide its scanner and PDF features.

            4. Internet and service providers
            Sign-in, email verification, password recovery and optional cloud storage require an internet connection and are provided through Supabase. Those services process data under their own terms and privacy policies.

            5. Local storage and security
            Local PDFs and app account session information may be stored on your device. Protect your device with a screen lock. No internet service can be guaranteed completely secure.

            6. Sharing and deletion
            The app does not send a document to another person unless you choose a share/export action or enable cloud sync. You can delete local PDFs from your device. Removing the app or signing out may not delete files already uploaded to cloud storage; cloud copies may need to be deleted separately.

            7. Children
            DocNexa is not designed to knowingly collect personal information from children.

            8. Changes and contact
            This policy may be updated as app features change. For privacy questions, use Contact us in the app and include “Privacy request” in your message.

            Important: This in-app policy describes the current intended app behaviour. Before public release, the developer should publish a permanent public policy URL and verify the actual Supabase storage and retention settings.
        """.trimIndent()
        val view = TextView(this).apply {
            text = policy
            textSize = 13f
            setTextColor(0xFF263957.toInt())
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this).setTitle("Privacy Policy")
            .setView(scroll).setPositiveButton("Close", null)
            .setNeutralButton("Privacy question") { _, _ -> openFeedbackEmail(false) }.show()
    }

    private fun showTermsOfUse() {
        val terms = """
            DOCNEXA – TERMS OF USE

            • Use DocNexa only for documents and content you are authorised to access.
            • You are responsible for checking the accuracy, readability and completeness of every scanned or generated PDF.
            • Keep backups of important documents. The app and optional cloud services may be unavailable or interrupted.
            • You are responsible for protecting your account credentials and your device.
            • Do not use the app to store or share unlawful, harmful or unauthorised material.
            • PDF conversion, OCR and scanning results may contain errors; review important information before relying on it.
            • Optional cloud features depend on the configured third-party service and its availability.
            • Features may change as the app is updated.

            By using DocNexa, you agree to use it responsibly and understand these limitations.
        """.trimIndent()
        val view = TextView(this).apply {
            text = terms; textSize = 13f; setTextColor(0xFF263957.toInt())
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(this).setTitle("Terms of Use")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Close", null).show()
    }

    private fun showDataHelp() {
        AlertDialog.Builder(this).setTitle("Your data and account")
            .setMessage("Local PDFs are stored in Downloads/DocumentScanner. Cloud sync is optional and requires you to sign in. Signing out only removes the account session from this device; it does not automatically erase PDFs saved locally or cloud copies. To request help with account or privacy data, use Contact us and describe what you want removed. Never send your password.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Contact support") { _, _ -> openFeedbackEmail(false) }.show()
    }

    private fun openRateUs() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
        try { startActivity(market) } catch (_: Exception) {
            try { startActivity(web) } catch (_: Exception) {
                Toast.makeText(this, "Thank you for supporting DocNexa!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cloudToken(): String = prefs.getString("cloud_access_token", "") ?: ""

    private fun showCloudLoginDialog(startWithSignup: Boolean = false) {
        if (!requireInternet("Login and account creation")) return
        val dialog = android.app.Dialog(this)
        var signupMode = startWithSignup
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = rounded(0xFFFFFFFF.toInt(), 24)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val logo = TextView(this).apply {
            text = "▤"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(0xFF315CF5.toInt())
            background = rounded(0xFFEAF0FF.toInt(), 18)
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(62))
        }
        content.addView(logo)
        content.addView(gap(12))
        content.addView(label("DOCNEXA", 22f, 0xFF1B2B4B.toInt(), true))
        content.addView(gap(4))
        val subtitle = label("Your documents. Safe, synced and organised.", 12f, 0xFF77839A.toInt())
        content.addView(subtitle)
        content.addView(gap(20))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(0xFFF0F3FA.toInt(), 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun tabButton(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        val loginTab = tabButton("LOGIN")
        val signupTab = tabButton("CREATE ACCOUNT")
        tabs.addView(loginTab, LinearLayout.LayoutParams(0, dp(44), 1f))
        tabs.addView(signupTab, LinearLayout.LayoutParams(0, dp(44), 1f))
        content.addView(tabs)
        content.addView(gap(18))

        val name = EditText(this).apply {
            hint = "Full name"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(0xFFF7F9FD.toInt(), 12, 0xFFE0E6F0.toInt())
        }
        val email = EditText(this).apply {
            hint = "Email address"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(0xFFF7F9FD.toInt(), 12, 0xFFE0E6F0.toInt())
        }
        val password = EditText(this).apply {
            hint = "Password (8+ characters)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(0xFFF7F9FD.toInt(), 12, 0xFFE0E6F0.toInt())
        }
        fun addField(caption: String, field: EditText) {
            content.addView(label(caption, 11f, 0xFF53627A.toInt(), true).apply {
                gravity = Gravity.START
                setPadding(dp(2), 0, 0, dp(6))
            })
            content.addView(field, LinearLayout.LayoutParams(-1, dp(52)))
            content.addView(gap(13))
        }
        addField("FULL NAME", name)
        addField("EMAIL ADDRESS", email)
        addField("PASSWORD", password)

        val recovery = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val forgot = TextView(this).apply {
            text = "Forgot password?"
            textSize = 12f
            setTextColor(0xFF315CF5.toInt())
            setPadding(0, dp(6), dp(8), dp(6))
            setOnClickListener { sendCloudPasswordReset(email.text.toString().trim()) }
        }
        val resend = TextView(this).apply {
            text = "Resend verification"
            textSize = 12f
            setTextColor(0xFF315CF5.toInt())
            gravity = Gravity.END
            setPadding(dp(8), dp(6), 0, dp(6))
            setOnClickListener { resendCloudConfirmation(email.text.toString().trim()) }
        }
        recovery.addView(forgot, LinearLayout.LayoutParams(0, -2, 1f))
        recovery.addView(resend, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(recovery)
        content.addView(gap(14))

        val submit = Button(this).apply {
            text = "SIGN IN  →"
            isAllCaps = false
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            background = gradient(0xFF5367F6.toInt(), 0xFF315CF5.toInt(), 14)
            backgroundTintList = null
            layoutParams = LinearLayout.LayoutParams(-1, dp(52))
        }
        content.addView(submit)
        content.addView(gap(10))
        val guest = TextView(this).apply {
            text = "Continue as Guest"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF52627A.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(guest)
        content.addView(gap(8))
        content.addView(label("By continuing, you agree to use DocNexa responsibly.", 10f, 0xFF9AA4B4.toInt()))
        scroll.addView(content)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        dialog.setContentView(panel)

        fun renderMode() {
            name.visibility = if (signupMode) View.VISIBLE else View.GONE
            forgot.visibility = if (signupMode) View.GONE else View.VISIBLE
            loginTab.setTextColor(if (signupMode) 0xFF758198.toInt() else 0xFF315CF5.toInt())
            loginTab.background = if (signupMode) null else rounded(0xFFFFFFFF.toInt(), 11)
            signupTab.setTextColor(if (signupMode) 0xFF315CF5.toInt() else 0xFF758198.toInt())
            signupTab.background = if (signupMode) rounded(0xFFFFFFFF.toInt(), 11) else null
            submit.text = if (signupMode) "CREATE ACCOUNT  →" else "SIGN IN  →"
            subtitle.text = if (signupMode) "Create an account and keep your PDFs backed up." else "Sign in to access your secure cloud documents."
        }
        loginTab.setOnClickListener { signupMode = false; renderMode() }
        signupTab.setOnClickListener { signupMode = true; renderMode() }
        submit.setOnClickListener {
            cloudAuthenticate(
                email.text.toString().trim(), password.text.toString(),
                signupMode, dialog, submit, name.text.toString().trim()
            )
        }
        renderMode()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (resources.displayMetrics.heightPixels * 0.82f).toInt()
        )
    }

    private fun resendCloudConfirmation(email: String, dialog: AlertDialog? = null) {
        if (email.isBlank() || !email.contains("@")) {
            Toast.makeText(this, "Enter your email address first.", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                SupabaseCloud.resendConfirmation(email)
                runOnUiThread { Toast.makeText(this, "Confirmation email sent. Check your inbox and spam folder.", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Could not resend confirmation email.", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun sendCloudPasswordReset(email: String, dialog: AlertDialog? = null) {
        if (email.isBlank() || !email.contains("@")) {
            Toast.makeText(this, "Enter your email address first.", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                SupabaseCloud.sendPasswordReset(email)
                runOnUiThread { Toast.makeText(this, "Password reset email sent. Check your inbox.", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Could not send reset email.", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun cloudAuthenticate(
        email: String, password: String, signUp: Boolean,
        dialog: android.app.Dialog, submit: Button, fullName: String = ""
    ) {
        if (signUp && fullName.isBlank()) {
            Toast.makeText(this, "Enter your full name.", Toast.LENGTH_LONG).show()
            return
        }
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() || password.length < 8) {
            Toast.makeText(this, "Enter a valid email and a password with at least 8 characters.", Toast.LENGTH_LONG).show()
            return
        }
        submit.isEnabled = false
        submit.text = "PLEASE WAIT…"
        Thread {
            try {
                val result = SupabaseCloud.authenticate(email, password, signUp, fullName)
                runOnUiThread {
                    submit.isEnabled = true
                    submit.text = if (signUp) "CREATE ACCOUNT  →" else "SIGN IN  →"
                    if (result.accessToken.isNotBlank()) {
                        prefs.edit().putString("cloud_access_token", result.accessToken)
                            .putString("cloud_user_id", result.userId)
                            .putString("cloud_email", email).apply()
                        dialog.dismiss()
                        Toast.makeText(this, "Welcome to DocNexa Cloud.", Toast.LENGTH_LONG).show()
                        syncCloudNow()
                    } else if (signUp) {
                        AlertDialog.Builder(this)
                            .setTitle("Verify your email")
                            .setMessage("We sent a confirmation link to $email. Open the email, tap Confirm, then return to DocNexa and sign in. Check Spam/Junk too.")
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Resend email") { _, _ -> resendCloudConfirmation(email) }
                            .show()
                    } else {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    submit.isEnabled = true
                    submit.text = if (signUp) "CREATE ACCOUNT  →" else "SIGN IN  →"
                    Toast.makeText(this, e.localizedMessage ?: "Cloud authentication failed.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun isInternetAvailable(): Boolean {
        val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            manager.activeNetworkInfo?.isConnected == true
        }
    }

    private fun requireInternet(feature: String): Boolean {
        if (isInternetAvailable()) return true
        AlertDialog.Builder(this)
            .setTitle("Internet connection required")
            .setMessage("$feature is available in Online Mode. Connect to Wi-Fi or mobile data and try again. Your offline scan and PDF files remain available.")
            .setPositiveButton("OK", null).show()
        return false
    }

    private fun cloudAccountMenu() {
        if (!requireInternet("DocNexa Cloud account")) return
        AlertDialog.Builder(this).setTitle("DocNexa Cloud")
            .setMessage("Connected: ${prefs.getString("cloud_email", "")}")
            .setItems(arrayOf("Sync now", "Sign out")) { _, which ->
                if (which == 0) syncCloudNow() else confirmCloudSignOut()
            }.setNegativeButton("Close", null).show()
    }

    private fun confirmCloudSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out of DocNexa?")
            .setMessage("Your cloud account will be disconnected on this device. Your locally saved PDFs will not be deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign out") { _, _ ->
                prefs.edit().remove("cloud_access_token")
                    .remove("cloud_user_id")
                    .remove("cloud_email")
                    .apply()
                Toast.makeText(this, "Signed out. Local PDFs are unchanged.", Toast.LENGTH_LONG).show()
                buildScreen()
            }
            .show()
    }

    private fun syncCloudNow() {
        if (!requireInternet("Manual Cloud Sync")) return
        val token = cloudToken()
        val userId = prefs.getString("cloud_user_id", "") ?: ""
        if (token.isBlank() || userId.isBlank()) { showCloudLoginDialog(); return }
        val localDocs = getSavedPdfs()
        status.text = "Syncing with DocNexa Cloud…"
        Thread {
            try {
                var uploaded = 0
                for ((name, uri) in localDocs) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Could not read $name")
                    SupabaseCloud.upload(token, userId, name, bytes)
                    uploaded++
                }
                val remoteFiles = SupabaseCloud.list(token, userId)
                var downloaded = 0
                val localNames = getSavedPdfs().map { it.first }.toMutableSet()
                for (name in remoteFiles) {
                    if (name !in localNames && name.endsWith(".pdf", true)) {
                        val bytes = SupabaseCloud.download(token, userId, name)
                        val saved = saveCloudPdf(name, bytes)
                        if (saved != null) { downloaded++; localNames.add(name) }
                    }
                }
                runOnUiThread {
                    status.text = "Cloud sync complete"
                    recent.text = "Cloud: $uploaded uploaded • $downloaded restored"
                    Toast.makeText(this, "Sync complete: $uploaded uploaded, $downloaded downloaded.", Toast.LENGTH_LONG).show()
                    buildScreen()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Cloud sync failed"
                    Toast.makeText(this, e.localizedMessage ?: "Cloud sync failed. Check internet and Storage policies.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun manageTagsDialog() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            background = rounded(0xFFF5F8FF.toInt(), 16)
        }
        panel.addView(label("ORGANIZE YOUR DOCUMENTS", 10f, 0xFF3478F6.toInt(), true))
        panel.addView(gap(8))
        val field = android.widget.EditText(this).apply {
            hint = "Enter a tag name"
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFFFFFFFF.toInt(), 12, 0xFFD8E4F4.toInt())
        }
        panel.addView(field)
        showPremiumDialog(AlertDialog.Builder(this).setTitle("DocNexa • Manage Tags").setView(panel)
            .setPositiveButton("Save tag") { _, _ ->
                val tag = field.text.toString().trim()
                if (tag.isNotEmpty()) Toast.makeText(this, "Tag saved: $tag", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null))
    }

    private fun showSettingsDialog() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(0xFFF3F7FF.toInt(), 18)
        }
        val currentSort = prefs.getInt("document_sort_order", 2).coerceIn(0, 3)
        val sortNames = arrayOf("Name (A–Z)", "Name (Z–A)", "Newest first", "Oldest first")
        val docs = getSavedPdfs()
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }

        fun section(title: String) {
            panel.addView(label(title, 12f, 0xFF3478D8.toInt(), true).apply {
                setPadding(dp(5), dp(12), 0, dp(7))
            })
        }
        fun setting(icon: String, title: String, subtitle: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rounded(0xFFFFFFFF.toInt(), 14, 0xFFE0E8F4.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            row.addView(label(icon, 20f, 0xFF2878C8.toInt(), true).apply {
                gravity = Gravity.CENTER
                background = rounded(0xFFEAF2FF.toInt(), 12)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            })
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(6), 0)
            }
            info.addView(label(title, 13f, 0xFF1D3557.toInt(), true))
            info.addView(gap(3))
            info.addView(label(subtitle, 10.5f, 0xFF718096.toInt()))
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("›", 23f, 0xFF718096.toInt()))
            panel.addView(row)
        }

        section("DOCUMENT PREFERENCES")
        setting("⇅", "Default sort order", sortNames[currentSort]) {
            AlertDialog.Builder(this).setTitle("Sort documents")
                .setSingleChoiceItems(sortNames, currentSort) { dialog, which ->
                    prefs.edit().putInt("document_sort_order", which).apply()
                    dialog.dismiss()
                    showDocsScreen()
                }.setNegativeButton("Cancel", null).show()
        }
        setting("▤", "Storage location", "Downloads/DocumentScanner • ${docs.size} saved PDF(s)") {
            showPremiumDialog(AlertDialog.Builder(this)
                .setTitle("DocNexa storage")
                .setMessage("Your scanned and created PDFs are saved in the Documents folder:\n\nInternal storage / Download / DocumentScanner\n\nCurrently indexed: ${docs.size} PDF(s).\n\nYou can open this folder from your phone's Files app.")
                .setPositiveButton("OK", null))
        }

        section("APP & SUPPORT")
        setting("ⓘ", "App information", "DocNexa • Version $appVersion") {
            showPremiumDialog(AlertDialog.Builder(this)
                .setTitle("About DocNexa")
                .setMessage("DocNexa\nPDF Scanner & Editor\nVersion $appVersion\n\nScan, create, organize and manage PDF documents on your device.")
                .setPositiveButton("Done", null))
        }
        setting("↗", "Share DocNexa", "Invite friends to try the app") { shareApp() }
        setting("✉", "Contact support", "Send feedback or report a problem") { openFeedbackEmail(false) }

        section("MAINTENANCE")
        setting("♲", "Clear temporary files", "Remove temporary app cache; saved PDFs are not deleted") {
            AlertDialog.Builder(this).setTitle("Clear temporary files?")
                .setMessage("This removes temporary cache files only. Your saved PDFs, folders and account data will remain untouched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    val cleared = try { cacheDir.deleteRecursively() } catch (_: Exception) { false }
                    Toast.makeText(this, if (cleared) "Temporary cache cleared" else "Cache could not be fully cleared", Toast.LENGTH_LONG).show()
                }.show()
        }
        setting("↻", "Refresh document list", "Rescan the saved PDF folder") {
            showDocsScreen()
            Toast.makeText(this, "Document list refreshed", Toast.LENGTH_SHORT).show()
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(panel)
        }
        showPremiumDialog(AlertDialog.Builder(this).setTitle("Settings • DocNexa")
            .setView(scroll).setNegativeButton("Close", null))
    }

    private fun shareApp() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Try DocNexa")
            .putExtra(Intent.EXTRA_TEXT, "Try DocNexa – PDF Scanner & Editor.")
        startActivity(Intent.createChooser(send, "Share DocNexa"))
    }

    private fun showDocsOptions(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add("Search documents").setOnMenuItemClickListener {
            showDocumentSearch(); true
        }
        menu.menu.add("Sort documents").setOnMenuItemClickListener {
            AlertDialog.Builder(this).setTitle("Sort documents")
                .setItems(arrayOf("Name (A–Z)", "Name (Z–A)", "Newest first", "Oldest first")) { _, which ->
                    prefs.edit().putInt("document_sort_order", which).apply()
                    showDocsScreen()
                }.show()
            true
        }
        menu.menu.add("Import images").setOnMenuItemClickListener {
            imagePicker.launch(arrayOf("image/*")); true
        }
        menu.menu.add("Open PDF").setOnMenuItemClickListener {
            pdfPicker.launch(arrayOf("application/pdf")); true
        }
        menu.menu.add("Create folder").setOnMenuItemClickListener {
            createFolderDialog(); true
        }
        menu.menu.add("PDF tools").setOnMenuItemClickListener {
            showPdfTools(); true
        }
        menu.menu.add("Account / Cloud Sync").setOnMenuItemClickListener {
            if (cloudToken().isBlank()) showCloudLoginDialog() else cloudAccountMenu()
            true
        }
        menu.show()
    }

    private fun showDocsScreen() {
        val bg = 0xFFF5F8FF.toInt()
        val blue = 0xFF3478F6.toInt()
        val navy = 0xFF172B4D.toInt()
        val muted = 0xFF718096.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = 0xFFFFFFFF.toInt()
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(104))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = rounded(0xFF1D3B4A.toInt(), 16)
        }
        toolbar.addView(TextView(this).apply {
            text = "☰"
            textSize = 23f
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { buildScreen() }
        })
        toolbar.addView(label("  Doc Scanner", 20f, 0xFFFFFFFF.toInt(), true),
            LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(TextView(this).apply {
            text = "⌕   ⋮"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(6), 0, dp(6))
            contentDescription = "Document options"
            setOnClickListener { showDocsOptions(this) }
        })
        body.addView(toolbar)
        body.addView(gap(10))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0xFFFFFFFF.toInt(), 12)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun tab(text: String, selected: Boolean, action: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(if (selected) blue else muted)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(5), dp(13), dp(5), dp(13))
            if (selected) background = rounded(0xFFEAF2FF.toInt(), 9)
            setOnClickListener { action() }
        }
        tabs.addView(tab("⌂  Home", false) { buildScreen() }, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tab("▤  PDF Tools", false) { showPdfTools() }, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tab("▣  Import ID Photo", false) { showIdPhotoMenu() }, LinearLayout.LayoutParams(0, -2, 1.25f))
        body.addView(tabs)
        body.addView(gap(14))

        val allDocuments = getSavedPdfs()
        val favouriteUris = prefs.getStringSet("favourite_uris", emptySet()) ?: emptySet()
        val selectedFilter = prefs.getString("docs_filter", "all") ?: "all"
        val documents = when (selectedFilter) {
            "recent" -> allDocuments.take(10)
            "favourites" -> allDocuments.filter { favouriteUris.contains(it.second.toString()) }
            else -> allDocuments
        }
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun filterChip(title: String, key: String) = TextView(this).apply {
            text = title
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(9), dp(13), dp(9))
            setTextColor(if (selectedFilter == key) blue else muted)
            background = rounded(if (selectedFilter == key) 0xFFEAF2FF.toInt() else 0xFFFFFFFF.toInt(), 18)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                prefs.edit().putString("docs_filter", key).apply()
                showDocsScreen()
            }
        }
        filters.addView(filterChip("All docs", "all"))
        filters.addView(gap(7))
        filters.addView(filterChip("◷ Recent", "recent"))
        filters.addView(gap(7))
        filters.addView(filterChip("★ Favourites", "favourites"))
        body.addView(filters)
        body.addView(gap(14))

        val folderHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        folderHead.addView(label("Folders (${homeFolders.size})", 15f, navy, true), LinearLayout.LayoutParams(0, -2, 1f))
        folderHead.addView(TextView(this).apply {
            text = "▣＋"
            textSize = 22f
            setTextColor(muted)
            setOnClickListener { createFolderDialog() }
        })
        body.addView(folderHead)
        body.addView(gap(8))
        homeFolders.forEach { body.addView(folderCard(it)) }
        body.addView(gap(8))
        body.addView(label("Documents (${documents.size})", 15f, navy, true))
        body.addView(gap(8))
        if (documents.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(30), dp(20), dp(30))
                background = rounded(0xFFFFFFFF.toInt(), 16, 0xFFE5EAF2.toInt())
            }
            empty.addView(label("No documents yet", 16f, navy, true))
            empty.addView(gap(5))
            empty.addView(label("Tap the camera button to scan your first document.", 11f, muted))
            body.addView(empty)
        } else documents.forEach { item -> body.addView(documentCard(item.first, item.second)) }

        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Floating camera and add controls, positioned above Android navigation area.
        val overlay = android.widget.FrameLayout(this)
        val fab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(0xFF078BCB.toInt(), 40)
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val camera = TextView(this).apply {
            text = "📷"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), 0, dp(12), 0)
            setOnClickListener { startDocumentScan() }
        }
        val separator = View(this).apply { setBackgroundColor(0x66FFFFFF) }
        fab.addView(camera)
        fab.addView(separator, LinearLayout.LayoutParams(dp(1), dp(30)))
        fab.addView(TextView(this).apply {
            text = "+"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), 0, dp(8), 0)
            setOnClickListener { imagePicker.launch(arrayOf("image/*")) }
        })
        overlay.addView(fab, android.widget.FrameLayout.LayoutParams(-2, dp(58), Gravity.RIGHT or Gravity.BOTTOM).apply {
            rightMargin = dp(22)
            bottomMargin = dp(16)
        })
        root.addView(overlay, LinearLayout.LayoutParams(-1, dp(78)))

        addBottomAdAndActions(root)
        setScreenContent(root)
    }

    private fun folderCard(name: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = rounded(0xFFFFFFFF.toInt(), 13, 0xFFE5E7EB.toInt())
            elevation = dp(2).toFloat()
            val params = LinearLayout.LayoutParams(-1, dp(82))
            params.bottomMargin = dp(9)
            layoutParams = params
            setOnClickListener { showFolderContents(name) }
        }
        card.addView(TextView(this).apply {
            text = "📁"
            textSize = 35f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(70), -1)
        })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(label(name, 16f, 0xFF666666.toInt()))
        info.addView(gap(3))
        val count = getSavedPdfs().count { ( _, uri) -> folderAssignments[uri.toString()] == name }
        info.addView(label("Saved folder   •   $count file(s)", 11f, 0xFF888888.toInt()))
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(TextView(this).apply {
            text = "•••"
            textSize = 18f
            setTextColor(0xFF777777.toInt())
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setItems(arrayOf("Open", "Rename", "Delete")) { _, which ->
                    when (which) {
                        0 -> showFolderContents(name)
                        1 -> renameFolder(name)
                        2 -> deleteFolder(name)
                    }
                }.show()
            }
        })
        return card
    }

    private fun documentCard(name: String, uri: Uri): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = rounded(0xFFFFFFFF.toInt(), 13, 0xFFE5E7EB.toInt())
            elevation = dp(2).toFloat()
            val params = LinearLayout.LayoutParams(-1, dp(86))
            params.bottomMargin = dp(9)
            layoutParams = params
            setOnClickListener { openPdf(uri) }
        }
        val thumbnail = createPdfThumbnail(uri)
        if (thumbnail != null) {
            card.addView(ImageView(this).apply {
                setImageBitmap(thumbnail)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(0xFFEAF2FF.toInt(), 13, 0xFFDCE7FA.toInt())
                contentDescription = "Preview of $name"
                layoutParams = LinearLayout.LayoutParams(dp(62), dp(62))
            })
        } else {
            card.addView(documentIconTile(62))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        info.addView(label(name, 14f, 0xFF666666.toInt(), true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        info.addView(gap(4))
        info.addView(label("Saved on this device  •  PDF", 11f, 0xFF718096.toInt()))
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(TextView(this).apply {
            text = "•••"
            textSize = 18f
            setTextColor(0xFF777777.toInt())
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { showDocumentActions(name, uri) }
        })
        return card
    }

    private fun showDocumentActions(name: String, uri: Uri) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(8))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(panel)
        }
        fun section(title: String) {
            panel.addView(label(title.uppercase(Locale.US), 10f, 0xFF3478F6.toInt(), true).apply {
                setPadding(dp(4), dp(12), dp(4), dp(6))
            })
        }
        fun action(title: String, detail: String, symbol: String, run: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(8), dp(9))
                background = rounded(0xFFF7F9FD.toInt(), 12, 0xFFE6ECF5.toInt())
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                setOnClickListener { run() }
            }
            row.addView(label(symbol, 20f, 0xFF3478F6.toInt(), true), LinearLayout.LayoutParams(dp(38), -2))
            val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textBox.addView(label(title, 13f, 0xFF263957.toInt(), true))
            textBox.addView(gap(2))
            textBox.addView(label(detail, 10f, 0xFF718096.toInt()))
            row.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("›", 22f, 0xFF718096.toInt()))
            panel.addView(row)
        }

        section("Open and share")
        action("Open PDF", "View this document", "▤") { openPdf(uri) }
        action("Share PDF", "Send to another app", "↗") { sharePdf(uri) }
        action("Send to Email", "Attach this PDF to an email", "✉") { emailPdf(uri) }
        action("Open with Google Docs", "Open using a compatible app", "G") { openPdfWithGoogleDocs(uri) }

        section("Convert and export")
        action("Export first page as JPG", "Save only page 1 to Gallery", "▧") { savePdfPageAsJpg(uri) }
        action("Export all pages as images", "Save every page as a separate image", "▧") { convertPdfToImages(uri) }
        action("Create ZIP archive", "Package this PDF into a ZIP file", "ZIP") { zipPdf(uri) }
        action("Extract PDF text", "Read and copy selectable text", "T") { extractPdfText(uri) }
        action("PDF details", "View page count, size and metadata", "ⓘ") { showPdfDetails(uri) }

        section("Edit and organize")
        action("Edit PDF", "Add text, signature and annotations", "✎") { showPdfEditor(uri) }
        action("Compress PDF", "Reduce file size with quality options", "⇩") { showCompressionOptions(uri) }
        action("Split PDF", "Extract selected pages into a new PDF", "↔") { splitPdfFile(uri) }
        action("Rotate pages", "Rotate all or selected pages", "⟳") { rotatePdfPages(uri) }
        action("Move to Folder", "Organize this document", "▰") { movePdfToFolder(uri) }
        action("Rename PDF", "Change the document filename", "Aa") { renamePdfDocument(uri, name) }
        action("Copy PDF", "Create another copy in Downloads", "▣") { copyPdfDocument(uri) }
        action("Tags and notes", "Add searchable labels or a date note", "⌑") {
            AlertDialog.Builder(this).setTitle("Document details")
                .setItems(arrayOf("Edit tags", "Add date / time note")) { _, which ->
                    if (which == 0) documentTagDialog(uri) else documentDateTimeDialog(uri)
                }.show()
        }

        section("Security and document management")
        val favourites = prefs.getStringSet("favourite_uris", emptySet()) ?: emptySet()
        val isFavourite = favourites.contains(uri.toString())
        action(if (isFavourite) "Remove Favourite" else "Add to Favourite", "Keep this PDF in your favourites", "★") { toggleFavourite(uri) }
        action("Lock PDF", "Protect this PDF with a password", "▣") { passwordDialog(uri, false) }
        action("Remove PDF password", "Unlock a PDF when you know its password", "◉") { passwordDialog(uri, true) }
        action("Save to Cloud", "Upload using your connected cloud account", "☁") { sharePdfToGoogleDrive(uri) }
        action("Move to Trash", "Delete later or restore from Trash", "⌫") {
            AlertDialog.Builder(this).setTitle("Move to Trash?")
                .setMessage("This PDF will be moved to DocNexa Trash. You can restore it later.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Move to Trash") { _, _ -> moveToTrash(uri) }.show()
        }

        showPremiumDialog(AlertDialog.Builder(this).setTitle(name).setView(scroll)
            .setNegativeButton("Close", null))
    }

    private fun showMoreDocumentActions(name: String, uri: Uri) {
        val more = arrayOf(
            "Favourite / Unfavourite", "Pin Document", "OCR Text",
            "Copy PDF", "Split PDF", "Rename", "Tags",
            "Date and Time setting", "Open with Google Docs", "MultiSide Doc / Collage"
        )
        AlertDialog.Builder(this).setTitle("More • $name").setItems(more) { _, which ->
            when (which) {
                0 -> toggleFavourite(uri)
                1 -> {
                    toggleFavourite(uri)
                    Toast.makeText(this, "Pinned in DocNexa Favourite documents", Toast.LENGTH_LONG).show()
                }
                2 -> extractPdfText(uri)
                3 -> copyPdfDocument(uri)
                4 -> splitPdfFile(uri)
                5 -> renamePdfDocument(uri, name)
                6 -> documentTagDialog(uri)
                7 -> documentDateTimeDialog(uri)
                8 -> openPdfWithGoogleDocs(uri)
                9 -> Toast.makeText(this, "MultiSide Doc / Collage is not available yet.", Toast.LENGTH_LONG).show()
            }
        }.show()
    }

    private fun emailPdf(uri: Uri) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Send PDF by email"))
        } catch (e: Exception) {
            Toast.makeText(this, e.localizedMessage ?: "No email app available", Toast.LENGTH_LONG).show()
        }
    }

    private fun savePdfPageAsJpg(uri: Uri) {
        Thread {
            try {
                val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open PDF")
                val bitmap = descriptor.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (renderer.pageCount == 0) error("PDF has no pages")
                        renderer.openPage(0).use { page ->
                            val scale = 2f
                            Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888).also {
                                Canvas(it).apply { drawColor(android.graphics.Color.WHITE) }
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "DocNexa_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DocNexa")
                }
                val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create Gallery image")
                contentResolver.openOutputStream(imageUri).use { output ->
                    if (output == null) error("Could not save JPG")
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }
                bitmap.recycle()
                runOnUiThread { Toast.makeText(this, "First PDF page saved as JPG in Gallery", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "PDF to JPG failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun copyPdfDocument(uri: Uri) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read PDF")
                val copied = savePdfBytes(bytes, "Copy")
                runOnUiThread { Toast.makeText(this, if (copied != null) "PDF copy saved in Downloads/DocumentScanner" else "Copy failed", Toast.LENGTH_LONG).show(); buildScreen() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Copy failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun renamePdfDocument(uri: Uri, oldName: String) {
        val field = EditText(this).apply { setText(oldName.removeSuffix(".pdf")); selectAll() }
        AlertDialog.Builder(this).setTitle("Rename PDF").setView(field)
            .setPositiveButton("Save") { _, _ ->
                val value = field.text.toString().trim()
                if (value.isBlank()) {
                    Toast.makeText(this, "Enter a file name", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, if (value.endsWith(".pdf", true)) value else "$value.pdf") }
                        val changed = contentResolver.update(uri, values, null, null)
                        if (changed > 0) { Toast.makeText(this, "PDF renamed", Toast.LENGTH_SHORT).show(); buildScreen() }
                        else Toast.makeText(this, "Rename is not supported for this file", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, e.localizedMessage ?: "Rename failed", Toast.LENGTH_LONG).show()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun movePdfToFolder(uri: Uri) {
        if (homeFolders.isEmpty()) loadFolders()
        val folders = homeFolders.toTypedArray()
        AlertDialog.Builder(this).setTitle("Move to Folder").setItems(folders) { _, which ->
            folderAssignments[uri.toString()] = folders[which]
            prefs.edit().putString("folder_assignments", folderAssignments.entries.joinToString("\\n") { "${it.key}|${it.value}" }).apply()
            Toast.makeText(this, "Moved to ${folders[which]}", Toast.LENGTH_SHORT).show()
            buildScreen()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun documentTagDialog(uri: Uri) {
        val key = "document_tag_" + uri.toString().hashCode()
        val field = EditText(this).apply { hint = "Enter tags separated by commas"; setText(prefs.getString(key, "")) }
        AlertDialog.Builder(this).setTitle("Document Tags").setView(field)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString(key, field.text.toString().trim()).apply()
                Toast.makeText(this, "Tags saved", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun documentDateTimeDialog(uri: Uri) {
        val field = EditText(this).apply {
            hint = "Document title"
            setText(prefs.getString("document_datetime_" + uri.toString().hashCode(), ""))
        }
        AlertDialog.Builder(this).setTitle("Document date / time label").setMessage("Add a custom date or time note for this document.")
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("document_datetime_" + uri.toString().hashCode(), field.text.toString().trim()).apply()
                Toast.makeText(this, "Date/time note saved", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun createPdfThumbnail(uri: Uri): Bitmap? {
        return try {
            val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: return null
            descriptor.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount <= 0) return null
                    renderer.openPage(0).use { page ->
                        val maxSide = dp(124)
                        val scale = minOf(
                            maxSide.toFloat() / page.width.toFloat(),
                            maxSide.toFloat() / page.height.toFloat()
                        )
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun documentIconTile(sizeDp: Int = 62): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(0xFFEAF2FF.toInt(), 13, 0xFFDCE7FA.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
        tile.addView(TextView(this).apply {
            text = "▤"
            textSize = if (sizeDp >= 60) 29f else 24f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setTextColor(0xFF167FBD.toInt())
            gravity = Gravity.CENTER
        })
        tile.addView(TextView(this).apply {
            text = "PDF"
            textSize = 8f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(0xFF167FBD.toInt())
            gravity = Gravity.CENTER
        })
        return tile
    }

    private fun blueForDocs() = 0xFF078BCB.toInt()

    private fun getSavedPdfs(): List<Pair<String, Uri>> {
        val trashed = prefs.getStringSet("trash_uris", emptySet()) ?: emptySet()
        return try {
            val results = mutableListOf<Pair<String, Uri>>()
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?" else "${MediaStore.MediaColumns.MIME_TYPE} = ?"
            val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf("Download/DocumentScanner/", "application/pdf") else arrayOf("application/pdf")
            val sortOrder = when (prefs.getInt("document_sort_order", 2)) {
                0 -> "${MediaStore.MediaColumns.DISPLAY_NAME} ASC"
                1 -> "${MediaStore.MediaColumns.DISPLAY_NAME} DESC"
                3 -> "${MediaStore.MediaColumns.DATE_ADDED} ASC"
                else -> "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            }
            contentResolver.query(collection, projection, selection, args, sortOrder)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext() && results.size < 100) {
                    val id = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex) ?: "Document.pdf"
                    val itemUri = Uri.withAppendedPath(collection, id.toString())
                    if (!trashed.contains(itemUri.toString())) results.add(name to itemUri)
                }
            }
            results
        } catch (_: Exception) { emptyList() }
    }
    private fun createFolderDialog() {
        val field = android.widget.EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this).setTitle("Create folder").setView(field)
            .setPositiveButton("Create") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty() && !homeFolders.contains(name)) {
                    homeFolders.add(name)
                    saveFolders()
                    buildScreen()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun renameFolder(oldName: String) {
        val field = android.widget.EditText(this).apply { setText(oldName) }
        AlertDialog.Builder(this).setTitle("Rename folder").setView(field)
            .setPositiveButton("Save") { _, _ ->
                val name = field.text.toString().trim()
                val index = homeFolders.indexOf(oldName)
                if (name.isNotEmpty() && index >= 0) {
                    homeFolders[index] = name
                    saveFolders()
                    folderAssignments.entries.filter { it.value == oldName }.forEach { folderAssignments[it.key] = name }
                    prefs.edit().putString("folder_assignments", folderAssignments.entries.joinToString("\\n") { "${it.key}|${it.value}" }).apply()
                    buildScreen()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deleteFolder(name: String) {
        AlertDialog.Builder(this).setMessage("Delete folder '$name'?")
            .setPositiveButton("Delete") { _, _ -> homeFolders.remove(name); saveFolders(); buildScreen() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showFolderContents(folder: String) {
        selectedFolder = folder
        val items = getSavedPdfs().filter { folderAssignments[it.second.toString()] == folder }
        val names = items.map { it.first }.toTypedArray()
        if (items.isEmpty()) {
            AlertDialog.Builder(this).setTitle(folder).setMessage("This folder is empty. New PDFs created while this folder is selected will be saved here.").setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle(folder).setItems(names) { _, which -> openPdf(items[which].second) }
            .setPositiveButton("Close", null).show()
    }

    private fun shareLatestPdf() {
        val uri = lastPdf
        if (uri == null) {
            pdfPicker.launch(arrayOf("application/pdf"))
            return
        }
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share PDF"))
    }

    private fun addToolRow(parent: LinearLayout, first: View, second: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(first, LinearLayout.LayoutParams(0, dp(148), 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        row.addView(second, LinearLayout.LayoutParams(0, dp(148), 1f))
        parent.addView(row)
    }


    private fun showPdfTools() {
        val bg = 0xFFF3F7FF.toInt()
        val ink = 0xFF172B4D.toInt()
        val muted = 0xFF718096.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = gradient(0xFF073B91.toInt(), 0xFF06245F.toInt(), 18)
            elevation = dp(3).toFloat()
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(46))
            setOnClickListener { buildScreen() }
        }
        toolbar.addView(back)
        val fileIcon = TextView(this).apply {
            text = "PDF"
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = rounded(0xFF2C8DEB.toInt(), 12)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }
        toolbar.addView(fileIcon)
        val heading = label("PDF Tools", 20f, 0xFFFFFFFF.toInt(), true)
        heading.setPadding(dp(18), 0, 0, 0)
        toolbar.addView(heading)
        root.addView(toolbar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(28))
        }
        addPdfSectionTitle(body, "Quick Access")
        addPdfToolHorizontal(body, listOf(
            PdfTool("Open PDF", "▣", 0xFFE9433A.toInt()) { pdfPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Page Details", "↔", 0xFF4CAF50.toInt()) { detailsPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Signature", "S", 0xFFF6B34C.toInt()) { openSignatureEditor() },
            PdfTool("PDF to Image", "▧", 0xFF82C58A.toInt()) { pdfToImagePicker.launch(arrayOf("application/pdf")) },
            PdfTool("Image to PDF", "▧", 0xFF795548.toInt()) { imagePicker.launch(arrayOf("image/*")) },
            PdfTool("Share PDF", "↗", 0xFFFFB900.toInt()) {
                lastPdf?.let { uri ->
                    val share = Intent(Intent.ACTION_SEND).setType("application/pdf")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(share, "Share PDF"))
                } ?: pdfPicker.launch(arrayOf("application/pdf"))
            }
        ))

        addPdfSectionTitle(body, "PDF Tools")
        addPdfToolGrid(body, listOf(
            PdfTool("Create PDF", "＋", 0xFF2563EB.toInt()) { showCreatePdfHub() },
            PdfTool("Add/Merge PDF Files", "⇲", 0xFFF06A36.toInt()) { mergePicker.launch(arrayOf("application/pdf")) },
            PdfTool("Add Page from Gallery", "▧+", 0xFFB8D52B.toInt()) { imagePicker.launch(arrayOf("image/*")) },
            PdfTool("Split", "↔", 0xFF078F83.toInt()) { splitPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Rotate all pages", "✎", 0xFF13A6D8.toInt()) { rotatePicker.launch(arrayOf("application/pdf")) },
            PdfTool("Add Password", "▣", 0xFFED78A5.toInt()) { passwordPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Remove Password", "◉", 0xFF80516E.toInt()) { removePasswordPicker.launch(arrayOf("application/pdf")) },
            PdfTool("PDF To Text", "T", 0xFF35B5E5.toInt()) { pdfTextPicker.launch(arrayOf("application/pdf")) },
            PdfTool("PDF To ZIP", "▣", 0xFFE91E63.toInt()) { zipPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Text To PDF", "▤", 0xFF9826AC.toInt()) { createTextPdfDialog() },
            PdfTool("PDF Details", "ⓘ", 0xFF673AB7.toInt()) { detailsPicker.launch(arrayOf("application/pdf")) }
        ), ink, muted)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setScreenContent(root)
    }

    private fun showCreatePdfHub() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        fun option(title: String, subtitle: String, icon: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(10), dp(12))
                background = rounded(0xFFF5F8FF.toInt(), 14, 0xFFE0E9F7.toInt())
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                setOnClickListener { action() }
            }
            row.addView(label(icon, 22f, 0xFF2563EB.toInt(), true), LinearLayout.LayoutParams(dp(42), -2))
            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(label(title, 13f, 0xFF172B4D.toInt(), true))
            copy.addView(gap(3))
            copy.addView(label(subtitle, 10.5f, 0xFF718096.toInt()))
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("›", 22f, 0xFF718096.toInt()))
            panel.addView(row)
        }
        option("Images to PDF", "Select, preview and arrange photos", "▧") {
            imagePicker.launch(arrayOf("image/*"))
        }
        option("Text to PDF", "Write notes and choose page formatting", "T") {
            createTextPdfDialog()
        }
        option("Blank PDF", "Create blank pages with custom size", "□") {
            showBlankPdfDialog()
        }
        option("Advanced PDF Design", "Create a formatted document with header and footer", "✎") {
            showAdvancedPdfDesignDialog()
        }
        showPremiumDialog(AlertDialog.Builder(this).setTitle("Create PDF • DocNexa")
            .setMessage("Choose how you want to create your PDF.")
            .setView(panel).setNegativeButton("Close", null))
    }

    private fun showBlankPdfDialog() {
        val fileName = EditText(this).apply { setSingleLine(true); setText("Blank_Document"); hint = "PDF file name" }
        val pages = EditText(this).apply {
            setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1"); hint = "Number of pages (1–50)"
        }
        val size = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("A4", "Letter", "A5"))
        }
        val orientationSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Portrait", "Landscape"))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(label("File name", 11f, 0xFF263957.toInt(), true)); addView(fileName)
            addView(gap(7)); addView(label("Number of pages", 11f, 0xFF263957.toInt(), true)); addView(pages)
            addView(gap(7)); addView(label("Page size", 11f, 0xFF263957.toInt(), true)); addView(size)
            addView(gap(7)); addView(label("Orientation", 11f, 0xFF263957.toInt(), true)); addView(orientationSpinner)
        }
        showPremiumDialog(AlertDialog.Builder(this).setTitle("Blank PDF • Settings").setView(panel)
            .setNegativeButton("Cancel", null).setPositiveButton("Create PDF") { _, _ ->
                val count = pages.text.toString().toIntOrNull()
                if (count == null || count !in 1..50) {
                    Toast.makeText(this, "Enter between 1 and 50 pages", Toast.LENGTH_LONG).show()
                } else {
                    val dimensions = listOf(595 to 842, 612 to 792, 420 to 595)[size.selectedItemPosition.coerceIn(0, 2)]
                    val landscape = orientationSpinner.selectedItemPosition == 1
                    val width = if (landscape) dimensions.second else dimensions.first
                    val height = if (landscape) dimensions.first else dimensions.second
                    val name = fileName.text.toString().trim().replace(Regex("[^A-Za-z0-9_-]"), "_").take(60).ifBlank { "Blank_Document" }
                    createBlankPdf(count, width, height, name)
                }
            })
    }

    private fun createBlankPdf(count: Int, width: Int, height: Int, name: String) {
        status.text = "Creating blank PDF…"
        Thread {
            try {
                val doc = PdfDocument()
                repeat(count) { index ->
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                    page.canvas.drawColor(android.graphics.Color.WHITE)
                    doc.finishPage(page)
                }
                val bytes = ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
                doc.close()
                val saved = savePdfBytes(bytes, name)
                runOnUiThread {
                    if (saved != null) {
                        lastPdf = saved; status.text = "Blank PDF created"; recent.text = "Latest: ${saved.lastPathSegment}"
                        buildScreen()
                        AlertDialog.Builder(this).setTitle("Blank PDF created")
                            .setMessage("${count} blank page(s) saved as ${saved.lastPathSegment}")
                            .setPositiveButton("Open PDF") { _, _ -> openPdf(saved) }
                            .setNeutralButton("Share") { _, _ -> sharePdf(saved) }.setNegativeButton("Done", null).show()
                    } else Toast.makeText(this, "Could not save blank PDF", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Blank PDF failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showAdvancedPdfDesignDialog() {
        val titleField = EditText(this).apply { setSingleLine(true); setText("My Document"); hint = "Document title" }
        val bodyField = EditText(this).apply {
            hint = "Write or paste document content"
            minLines = 6; maxLines = 10; gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val header = EditText(this).apply { setSingleLine(true); hint = "Header (optional)" }
        val footer = EditText(this).apply { setSingleLine(true); hint = "Footer (optional)" }
        val size = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("A4", "Letter", "A5"))
        }
        val font = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("11 pt", "12 pt", "14 pt", "16 pt"))
            setSelection(1)
        }
        val pageNumbers = android.widget.CheckBox(this).apply { text = "Show page numbers"; isChecked = true }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(label("Document title", 11f, 0xFF263957.toInt(), true)); addView(titleField)
            addView(gap(6)); addView(label("Content", 11f, 0xFF263957.toInt(), true)); addView(bodyField, LinearLayout.LayoutParams(-1, dp(130)))
            addView(gap(6)); addView(label("Header", 11f, 0xFF263957.toInt(), true)); addView(header)
            addView(gap(6)); addView(label("Footer", 11f, 0xFF263957.toInt(), true)); addView(footer)
            addView(gap(6)); addView(label("Page size", 11f, 0xFF263957.toInt(), true)); addView(size)
            addView(gap(6)); addView(label("Body font", 11f, 0xFF263957.toInt(), true)); addView(font)
            addView(pageNumbers)
        }
        showPremiumDialog(AlertDialog.Builder(this).setTitle("Advanced PDF Design").setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("Cancel", null).setPositiveButton("Create PDF") { _, _ ->
                val content = bodyField.text.toString().trim()
                if (content.isBlank()) Toast.makeText(this, "Enter document content first", Toast.LENGTH_LONG).show()
                else {
                    val dims = listOf(595 to 842, 612 to 792, 420 to 595)[size.selectedItemPosition.coerceIn(0, 2)]
                    val fontSize = listOf(11f, 12f, 14f, 16f)[font.selectedItemPosition.coerceIn(0, 3)]
                    createDesignedPdf(titleField.text.toString().ifBlank { "My Document" }, content, header.text.toString(), footer.text.toString(), dims.first, dims.second, fontSize, pageNumbers.isChecked)
                }
            })
    }

    private fun createDesignedPdf(title: String, content: String, header: String, footer: String, pageWidth: Int, pageHeight: Int, fontSize: Float, showPageNumbers: Boolean) {
        status.text = "Designing PDF…"
        Thread {
            try {
                val doc = PdfDocument()
                val margin = 48f
                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; color = 0xFF123B78.toInt(); typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD) }
                val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = fontSize; color = 0xFF263447.toInt() }
                val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = 0xFF718096.toInt() }
                val lineHeight = fontSize * 1.5f
                val maxWidth = pageWidth - margin * 2
                val lines = mutableListOf<String>()
                content.replace("\r", "").split("\n").forEach { paragraph ->
                    if (paragraph.isBlank()) { lines.add(""); return@forEach }
                    var current = ""
                    paragraph.split(Regex("\\s+")).forEach { word ->
                        val candidate = if (current.isEmpty()) word else "$current $word"
                        if (bodyPaint.measureText(candidate) > maxWidth && current.isNotEmpty()) { lines.add(current); current = word } else current = candidate
                    }
                    if (current.isNotEmpty()) lines.add(current)
                }
                var pageNumber = 1
                var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                var y = margin
                fun drawHeaderFooter(canvas: android.graphics.Canvas, number: Int) {
                    if (header.isNotBlank()) canvas.drawText(header.take(90), margin, 25f, smallPaint)
                    canvas.drawLine(margin, 34f, pageWidth - margin, 34f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9E3F1.toInt(); strokeWidth = 1f })
                    if (footer.isNotBlank()) canvas.drawText(footer.take(90), margin, pageHeight - 22f, smallPaint)
                    if (showPageNumbers) canvas.drawText("Page $number", pageWidth - margin - 42f, pageHeight - 22f, smallPaint)
                }
                drawHeaderFooter(page.canvas, pageNumber)
                page.canvas.drawText(title.take(90), margin, y + 18f, titlePaint)
                y += 48f
                for (line in lines) {
                    if (y + lineHeight > pageHeight - margin - 12f) {
                        doc.finishPage(page); pageNumber++
                        page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                        drawHeaderFooter(page.canvas, pageNumber); y = margin + 12f
                    }
                    if (line.isNotEmpty()) page.canvas.drawText(line, margin, y, bodyPaint)
                    y += lineHeight
                }
                doc.finishPage(page)
                val bytes = ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray(); doc.close()
                val saved = savePdfBytes(bytes, title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(50).ifBlank { "Designed_Document" })
                runOnUiThread {
                    if (saved != null) {
                        lastPdf = saved; status.text = "Designed PDF created"; recent.text = "Latest: ${saved.lastPathSegment}"
                        buildScreen()
                        AlertDialog.Builder(this).setTitle("Designed PDF created")
                            .setMessage("Saved as ${saved.lastPathSegment}")
                            .setPositiveButton("Open PDF") { _, _ -> openPdf(saved) }
                            .setNeutralButton("Share") { _, _ -> sharePdf(saved) }.setNegativeButton("Done", null).show()
                    } else Toast.makeText(this, "Could not save designed PDF", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "PDF design failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private data class PdfTool(
        val title: String,
        val symbol: String,
        val color: Int,
        val action: () -> Unit
    )

    private fun addPdfToolHorizontal(parent: LinearLayout, tools: List<PdfTool>) {
        val horizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(4))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        tools.forEachIndexed { index, tool ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(8), dp(4), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { tool.action() }
            }
            val circle = TextView(this).apply {
                text = tool.symbol
                textSize = 19f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(tool.color)
                }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                elevation = dp(2).toFloat()
            }
            item.addView(circle)
            item.addView(gap(5))
            val caption = label(tool.title, 9.5f, 0xFF777777.toInt())
            caption.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            caption.textAlignment = View.TEXT_ALIGNMENT_CENTER
            caption.maxLines = 2
            item.addView(caption)
            row.addView(item, LinearLayout.LayoutParams(dp(82), dp(82)))
        }
        horizontal.addView(row)
        parent.addView(horizontal)
    }

    private fun addPdfToolGrid(parent: LinearLayout, tools: List<PdfTool>, titleColor: Int, subtitleColor: Int) {
        tools.chunked(2).forEach { rowTools ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            rowTools.forEach { tool ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(12), dp(10), dp(12))
                    background = rounded(0xFFFFFFFF.toInt(), 16, 0xFFE1EAF7.toInt())
                    elevation = dp(1).toFloat()
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { tool.action() }
                }
                val icon = TextView(this).apply {
                    text = tool.symbol
                    textSize = 21f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(tool.color)
                    gravity = Gravity.CENTER
                    background = rounded(0xFFEAF2FF.toInt(), 14)
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(46))
                }
                item.addView(icon)
                item.addView(gap(8))
                val caption = label(tool.title, 12f, 0xFF263957.toInt(), true)
                caption.gravity = Gravity.CENTER
                caption.textAlignment = View.TEXT_ALIGNMENT_CENTER
                caption.maxLines = 2
                item.addView(caption)
                item.addView(gap(4))
                val descriptions = mapOf(
                    "Add/Merge PDF Files" to "Combine multiple PDFs",
                    "Add Page from Gallery" to "Insert photos as pages",
                    "Split" to "Extract custom page ranges",
                    "Rotate all pages" to "Rotate selected pages",
                    "Add Password" to "Protect with a password",
                    "Remove Password" to "Unlock an accessible PDF",
                    "PDF To Text" to "Extract and copy text",
                    "PDF To ZIP" to "Package PDF for sharing",
                    "Text To PDF" to "Create a PDF from notes",
                    "PDF Details" to "View pages and metadata",
                    "Create PDF" to "Images • Text • Blank • Design"
                )
                val sub = label(descriptions[tool.title] ?: "Open PDF utility", 9.5f, 0xFF718096.toInt())
                sub.gravity = Gravity.CENTER
                sub.textAlignment = View.TEXT_ALIGNMENT_CENTER
                sub.maxLines = 2
                item.addView(sub)
                row.addView(item, LinearLayout.LayoutParams(0, dp(124), 1f).apply {
                    leftMargin = dp(4); rightMargin = dp(4); bottomMargin = dp(9)
                })
            }
            if (rowTools.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            parent.addView(row)
        }
    }

    private fun addPdfSectionTitle(parent: LinearLayout, title: String) {
        val lineColor = 0xFFD6E3F5.toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(View(this).apply { setBackgroundColor(lineColor) },
            LinearLayout.LayoutParams(0, dp(1), 1f))
        val text = label(title.uppercase(), 11f, 0xFF3478F6.toInt(), true)
        text.setPadding(dp(10), 0, dp(10), 0)
        row.addView(text)
        row.addView(View(this).apply { setBackgroundColor(lineColor) },
            LinearLayout.LayoutParams(0, dp(1), 1f))
        parent.addView(row)
    }

    private fun showLongTextReport(title: String, body: String, copyLabel: String) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(4))
        }
        panel.addView(label("DOCUMENT REPORT", 10f, 0xFF3478F6.toInt(), true))
        panel.addView(gap(6))
        val content = TextView(this).apply {
            text = body
            textSize = 12f
            setTextColor(0xFF263957.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        panel.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(-1, dp(390)))
        showPremiumDialog(AlertDialog.Builder(this).setTitle(title).setView(panel)
            .setNeutralButton(copyLabel) { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(title, body))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Close", null))
    }

    private fun extractPdfText(uri: Uri) {
        status.text = "Extracting PDF text…"
        Thread {
            try {
                val text = contentResolver.openInputStream(uri)?.use { stream ->
                    PDDocument.load(stream).use { doc -> PDFTextStripper().getText(doc) }
                } ?: error("Cannot open PDF")
                runOnUiThread {
                    status.text = "PDF text extracted"
                    if (text.isBlank()) {
                        showPremiumDialog(AlertDialog.Builder(this).setTitle("PDF To Text")
                            .setMessage("No selectable text was found. If this is a scanned PDF, use the Image To Text (OCR) tool.")
                            .setPositiveButton("Close", null))
                    } else {
                        showLongTextReport("DocNexa • Extracted Text", text, "Copy Text")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "PDF text extraction failed"
                    Toast.makeText(this, e.localizedMessage ?: "Could not read PDF", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showPdfDetails(uri: Uri) {
        status.text = "Reading PDF information…"
        Thread {
            try {
                val fileName = try {
                    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                } catch (_: Exception) { null } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Selected PDF"
                val fileSize = try {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                } catch (_: Exception) { -1L }
                val details = contentResolver.openInputStream(uri)?.use { stream ->
                    PDDocument.load(stream).use { doc ->
                        val info = doc.documentInformation
                        val pages = doc.numberOfPages
                        val sizes = linkedMapOf<String, Int>()
                        var portrait = 0
                        var landscape = 0
                        var square = 0
                        val pageReport = StringBuilder()
                        for (index in 0 until pages) {
                            val page = doc.getPage(index)
                            val box = page.mediaBox
                            val size = String.format(Locale.US, "%.1f × %.1f pt", box.width, box.height)
                            sizes[size] = (sizes[size] ?: 0) + 1
                            when {
                                box.height > box.width -> portrait++
                                box.width > box.height -> landscape++
                                else -> square++
                            }
                            if (index < 10) pageReport.append("Page ${index + 1}: $size • Rotation ${page.rotation}°\\n")
                        }
                        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                        fun dateText(value: java.util.Calendar?): String =
                            value?.time?.let { dateFormat.format(it) } ?: "—"
                        val sizeText = when {
                            fileSize < 0 -> "Unavailable"
                            fileSize >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", fileSize / (1024.0 * 1024.0))
                            fileSize >= 1024L -> String.format(Locale.US, "%.1f KB", fileSize / 1024.0)
                            else -> "$fileSize bytes"
                        }
                        buildString {
                            append("FILE INFORMATION\\nName: $fileName\\nSize: $sizeText\\n\\n")
                            append("DOCUMENT\\nPages: $pages\\n")
                            append("Layout: $portrait portrait • $landscape landscape • $square square\\n")
                            append("Page sizes: ")
                            append(sizes.entries.joinToString("; ") { entry -> "${entry.key} (${entry.value})" })
                            append("\\nPDF version: ${doc.version}\\nEncrypted: ${if (doc.isEncrypted) "Yes" else "No"}\\n\\n")
                            append("METADATA\\n")
                            append("Title: ${info.title?.takeIf { it.isNotBlank() } ?: "—"}\\n")
                            append("Author: ${info.author?.takeIf { it.isNotBlank() } ?: "—"}\\n")
                            append("Subject: ${info.subject?.takeIf { it.isNotBlank() } ?: "—"}\\n")
                            append("Creator: ${info.creator?.takeIf { it.isNotBlank() } ?: "—"}\\n")
                            append("Producer: ${info.producer?.takeIf { it.isNotBlank() } ?: "—"}\\n")
                            append("Keywords: ${info.keywords?.takeIf { it.isNotBlank() } ?: "—"}\\n")
                            append("Created: ${dateText(info.creationDate)}\\nModified: ${dateText(info.modificationDate)}\\n\\n")
                            append("PAGE BREAKDOWN\\n$pageReport")
                            if (pages > 10) append("…and ${pages - 10} more page(s)\\n")
                        }
                    }
                } ?: error("Cannot open selected PDF")
                runOnUiThread {
                    status.text = "PDF information ready"
                    showLongTextReport("DocNexa • PDF Details", details, "Copy Report")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Could not read PDF information"
                    Toast.makeText(this, e.localizedMessage ?: "Could not read PDF details", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun rotatePdfPages(uri: Uri) {
        Thread {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("Cannot open PDF")
                val count = input.use { PDDocument.load(it).use { doc -> doc.numberOfPages } }
                runOnUiThread {
                    val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
                    panel.addView(label("TOTAL PAGES: $count", 12f, 0xFF3478F6.toInt(), true))
                    val range = EditText(this).apply { hint = "All pages or range: 1-3, 5"; setSingleLine(true) }
                    panel.addView(range)
                    panel.addView(label("Leave blank to rotate every page. Rotation: 90° clockwise.", 11f, 0xFF718096.toInt()))
                    showPremiumDialog(AlertDialog.Builder(this).setTitle("DocNexa • Rotate Pages")
                        .setView(panel).setNegativeButton("Cancel", null)
                        .setPositiveButton("Rotate & Save") { _, _ ->
                            val pages = if (range.text.isNullOrBlank()) (0 until count).toList()
                                else parsePageRanges(range.text.toString(), count)
                            if (pages.isEmpty()) Toast.makeText(this, "Please enter a valid page range", Toast.LENGTH_LONG).show()
                            else rotateSelectedPdfPages(uri, pages)
                        })
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Could not inspect PDF", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun rotateSelectedPdfPages(uri: Uri, pages: List<Int>) {
        status.text = "Rotating selected pages…"
        Thread {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("Cannot open PDF")
                val bytes = input.use { stream -> PDDocument.load(stream).use { doc ->
                    pages.forEach { index -> doc.getPage(index).rotation = (doc.getPage(index).rotation + 90) % 360 }
                    ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                } }
                val saved = savePdfBytes(bytes, "Rotated")
                runOnUiThread {
                    if (saved != null) {
                        lastPdf = saved; buildScreen()
                        status.text = "Selected pages rotated"
                        recent.text = "Latest: ${saved.lastPathSegment}"
                        Toast.makeText(this, "Rotated PDF saved", Toast.LENGTH_LONG).show()
                    } else status.text = "Could not save rotated PDF"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Rotate failed"; Toast.makeText(this, e.localizedMessage ?: "Could not rotate PDF", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun createTextPdfDialog() {
        val titleField = EditText(this).apply { hint = "Document title"; setSingleLine(true); setText("My Document") }
        val bodyField = EditText(this).apply {
            hint = "Type or paste your text here"
            minLines = 7
            maxLines = 12
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val pageSize = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("A4", "Letter", "A5"))
        }
        val fontSize = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("10 pt", "11 pt", "12 pt", "14 pt", "16 pt", "18 pt"))
            setSelection(2)
        }
        val margins = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Narrow", "Normal", "Wide"))
            setSelection(1)
        }
        val spacing = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Compact", "Normal", "Relaxed"))
            setSelection(1)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(label("Create a clean, multi-page PDF from your notes or pasted text.", 11f, 0xFF718096.toInt()))
            addView(gap(8))
            addView(titleField)
            addView(gap(6))
            addView(bodyField, LinearLayout.LayoutParams(-1, dp(150)))
            addView(gap(8))
            addView(label("Page size", 11f, 0xFF263957.toInt(), true)); addView(pageSize)
            addView(gap(5))
            addView(label("Font size", 11f, 0xFF263957.toInt(), true)); addView(fontSize)
            addView(gap(5))
            addView(label("Page margins", 11f, 0xFF263957.toInt(), true)); addView(margins)
            addView(gap(5))
            addView(label("Line spacing", 11f, 0xFF263957.toInt(), true)); addView(spacing)
        }
        val scroll = ScrollView(this).apply { addView(panel) }
        showPremiumDialog(AlertDialog.Builder(this).setTitle("Text To PDF • Advanced")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create PDF") { _, _ ->
                val textValue = bodyField.text.toString().trim()
                if (textValue.isBlank()) {
                    Toast.makeText(this, "Please enter or paste some text", Toast.LENGTH_LONG).show()
                } else {
                    val sizes = listOf(595 to 842, 612 to 792, 420 to 595)
                    val page = sizes[pageSize.selectedItemPosition.coerceIn(0, 2)]
                    val font = listOf(10f, 11f, 12f, 14f, 16f, 18f)[fontSize.selectedItemPosition.coerceIn(0, 5)]
                    val margin = listOf(28f, 48f, 68f)[margins.selectedItemPosition.coerceIn(0, 2)]
                    val lineGap = listOf(1.15f, 1.45f, 1.8f)[spacing.selectedItemPosition.coerceIn(0, 2)]
                    createPdfFromText(textValue, titleField.text.toString().trim().ifBlank { "My Document" }, page.first, page.second, font, margin, lineGap)
                }
            })
    }

    private fun createPdfFromText(value: String, title: String = "My Document", pageWidth: Int = 595, pageHeight: Int = 842, fontSize: Float = 12f, margin: Float = 48f, lineSpacing: Float = 1.45f) {
        status.text = "Creating PDF…"
        Thread {
            try {
                val doc = PdfDocument()
                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = fontSize + 5f
                    color = android.graphics.Color.rgb(25, 61, 112)
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = fontSize; color = android.graphics.Color.BLACK }
                val maxWidth = pageWidth - margin * 2
                val lineHeight = fontSize * lineSpacing
                val lines = mutableListOf<String>()
                value.replace("\r", "").split("\n").forEach { paragraph ->
                    if (paragraph.isBlank()) { lines.add(""); return@forEach }
                    var current = ""
                    paragraph.split(Regex("\\s+")).forEach { word ->
                        val candidate = if (current.isEmpty()) word else "$current $word"
                        if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                            lines.add(current); current = word
                        } else current = candidate
                    }
                    if (current.isNotEmpty()) lines.add(current)
                }
                var pageNo = 1
                var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
                var y = margin
                page.canvas.drawText(title.take(80), margin, y + titlePaint.textSize, titlePaint)
                y += titlePaint.textSize + lineHeight
                page.canvas.drawLine(margin, y - lineHeight / 3f, pageWidth - margin, y - lineHeight / 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD8E2F0.toInt(); strokeWidth = 1f })
                for (line in lines) {
                    if (y + lineHeight > pageHeight - margin) {
                        doc.finishPage(page)
                        pageNo++
                        page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
                        y = margin
                    }
                    if (line.isNotEmpty()) page.canvas.drawText(line, margin, y, paint)
                    y += lineHeight
                }
                doc.finishPage(page)
                val saved = savePdf(doc); doc.close()
                runOnUiThread {
                    if (saved != null) {
                        lastPdf = saved; buildScreen(); status.text = "Text PDF created"; recent.text = "Latest: ${saved.lastPathSegment}"
                        Toast.makeText(this, "PDF created with $pageNo page(s) and saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
                    } else Toast.makeText(this, "Could not save PDF", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "PDF creation failed"; Toast.makeText(this, e.localizedMessage ?: "Text PDF failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun openSignatureEditor(targetPdf: Uri? = null) {
        val canvas = SignatureCanvas(this)
        val typed = EditText(this).apply { hint = "Type your name / signature"; textSize = 23f }
        var selectedSignatureFont = "Cursive"
        val fontButton = Button(this).apply { text = "Font: $selectedSignatureFont" }
        fontButton.setOnClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            listOf("Cursive", "Serif", "Sans Serif", "Monospace", "Elegant", "Bold", "Italic").forEach { menu.menu.add(it) }
            menu.setOnMenuItemClickListener { item ->
                selectedSignatureFont = item.title.toString()
                fontButton.text = "Font: $selectedSignatureFont"
                typed.typeface = signatureTypeface(selectedSignatureFont)
                true
            }
            menu.show()
        }
        val preview = TextView(this).apply {
            text = "Draw with your finger • Transparent background"
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
        }
        val drawArea = FrameLayout(this).apply {
            background = rounded(0xFFFFFFFF.toInt(), 16, 0xFFB8D0F5.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(canvas, FrameLayout.LayoutParams(-1, dp(190)))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(label("CREATE YOUR SIGNATURE", 12f, 0xFF2865B5.toInt(), true))
            addView(gap(8))
            addView(drawArea)
            addView(preview)
            val colors = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            listOf(0xFF111827.toInt(), 0xFF1457C5.toInt(), 0xFFB42318.toInt(), 0xFF16845B.toInt()).forEach { color ->
                val swatch = TextView(this@MainActivity).apply {
                    text = "●"; textSize = 26f; gravity = Gravity.CENTER
                    setTextColor(color); setPadding(dp(8), 0, dp(8), 0)
                    setOnClickListener { canvas.setInk(color); preview.text = "Ink color changed" }
                }
                colors.addView(swatch)
            }
            addView(colors)
            addView(label("Pen thickness", 12f, 0xFF64748B.toInt()))
            addView(SeekBar(this@MainActivity).apply {
                max = 16; progress = 5
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { canvas.setInkWidth((progress + 2).toFloat()) }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            })
            addView(label("TYPE SIGNATURE • CHOOSE A FONT", 12f, 0xFF2865B5.toInt(), true))
            addView(fontButton, LinearLayout.LayoutParams(-1, -2))
            addView(typed, LinearLayout.LayoutParams(-1, -2))
            typed.typeface = signatureTypeface(selectedSignatureFont)
            addView(label("Tip: Use Undo to remove the last stroke, or Clear to start again.", 11f, 0xFF64748B.toInt()))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("DocNexa • Signature Studio")
            .setView(ScrollView(this).apply { addView(layout) })
            .setNeutralButton("Save Signature PDF", null)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (targetPdf != null) "Apply to PDF" else "Sign a PDF", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val bitmap = buildSignatureBitmap(canvas, typed.text.toString(), selectedSignatureFont)
                if (bitmap == null) {
                    Toast.makeText(this, "Please draw or type a signature first.", Toast.LENGTH_LONG).show()
                } else {
                    saveSignatureOnlyPdf(bitmap)
                    bitmap.recycle()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val bitmap = buildSignatureBitmap(canvas, typed.text.toString(), selectedSignatureFont)
                if (bitmap == null) {
                    Toast.makeText(this, "Please draw or type a signature first.", Toast.LENGTH_LONG).show()
                } else {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    signaturePng = out.toByteArray()
                    bitmap.recycle()
                    dialog.dismiss()
                    if (targetPdf != null) applySignatureToPdf(targetPdf, signaturePng!!)
                    else pdfPicker.launch(arrayOf("application/pdf"))
                }
            }
        }
        // Small quick actions below the dialog are exposed through a compact toolbar.
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(Button(this@MainActivity).apply { text = "Undo"; setOnClickListener { canvas.undo() } })
            addView(Button(this@MainActivity).apply { text = "Clear"; setOnClickListener { canvas.clear(); typed.setText("") } })
        }
        layout.addView(toolbar)
        dialog.show()
    }

    private fun buildSignatureBitmap(canvas: SignatureCanvas, text: String, fontName: String): Bitmap? {
        val value = text.trim()
        return if (value.isNotEmpty()) renderTypedSignature(value, fontName)
        else if (canvas.hasSignature()) canvas.toBitmap()
        else null
    }

    private fun saveSignatureOnlyPdf(signature: Bitmap) {
        try {
            val document = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = document.startPage(pageInfo)
            val pageCanvas = page.canvas
            pageCanvas.drawColor(android.graphics.Color.WHITE)
            val maxW = 440f
            val maxH = 170f
            val scale = minOf(maxW / signature.width.coerceAtLeast(1), maxH / signature.height.coerceAtLeast(1))
            val w = signature.width * scale
            val h = signature.height * scale
            val left = (595f - w) / 2f
            val top = (842f - h) / 2f
            pageCanvas.drawBitmap(signature, null, Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt()), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            document.finishPage(page)
            val output = ByteArrayOutputStream()
            document.writeTo(output)
            document.close()
            val saved = savePdfBytes(output.toByteArray(), "Signature")
            Toast.makeText(this, if (saved != null) "Signature-only PDF saved in Downloads/DocumentScanner" else "Could not save signature PDF", Toast.LENGTH_LONG).show()
            buildScreen()
        } catch (e: Exception) {
            Toast.makeText(this, e.localizedMessage ?: "Signature PDF creation failed", Toast.LENGTH_LONG).show()
        }
    }

    private fun signatureTypeface(fontName: String): android.graphics.Typeface {
        val style = when (fontName) {
            "Bold" -> android.graphics.Typeface.BOLD
            "Italic" -> android.graphics.Typeface.ITALIC
            else -> android.graphics.Typeface.NORMAL
        }
        val family = when (fontName) {
            "Serif", "Elegant" -> "serif"
            "Sans Serif", "Bold", "Italic" -> "sans-serif"
            "Monospace" -> "monospace"
            else -> "cursive"
        }
        return android.graphics.Typeface.create(family, style)
    }

    private fun renderTypedSignature(value: String, fontName: String = "Cursive"): Bitmap {
        val bitmap = Bitmap.createBitmap(1000, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 112f
            typeface = signatureTypeface(fontName)
        }
        canvas.drawText(value, 30f, 190f, paint)
        return bitmap
    }

    private fun applySignatureToPdf(uri: Uri, png: ByteArray) {
        Thread {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("Cannot open PDF")
                val bytes = input.use { stream ->
                    PDDocument.load(stream).use { doc ->
                        val page = doc.getPage(0)
                        val image = PDImageXObject.createFromByteArray(doc, png, "signature")
                        val box = page.mediaBox
                        val width = minOf(box.width * 0.35f, 180f)
                        val height = width * image.height.toFloat() / image.width.toFloat()
                        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            cs.drawImage(image, box.width - width - 36f, 36f, width, height)
                        }
                        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                    }
                }
                val saved = savePdfBytes(bytes, "Signed")
                runOnUiThread {
                    lastPdf = saved
                    status.text = "Signature added to PDF"
                    recent.text = "Latest: ${saved?.lastPathSegment ?: "Signed PDF"}"
                    Toast.makeText(this, "Signed PDF saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
                    buildScreen()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Could not sign PDF", Toast.LENGTH_LONG).show() }
            } finally { signaturePng = null }
        }.start()
    }


    private fun showPdfEditor(uri: Uri) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
            setBackgroundColor(0xFF101216.toInt())
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 34f; setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { buildScreen() }
        })
        header.addView(label("  PDF Editor", 20f, 0xFFFFFFFF.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "💾"; textSize = 23f; setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { Toast.makeText(this@MainActivity, "Choose a tool to apply changes and save a new PDF.", Toast.LENGTH_LONG).show() }
        })
        root.addView(header); root.addView(gap(12))
        root.addView(label("EDIT PDF", 12f, 0xFF55A7FF.toInt(), true)); root.addView(gap(10))
        val scroller = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun editorAction(title: String, detail: String, symbol: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(15), dp(13), dp(15), dp(13))
                background = rounded(0xFF20242B.toInt(), 13); isClickable = true
                setOnClickListener { action() }
            }
            row.addView(label(symbol, 23f, 0xFF55A7FF.toInt(), true), LinearLayout.LayoutParams(dp(42), -2))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(title, 15f, 0xFFFFFFFF.toInt(), true))
            texts.addView(label(detail, 11f, 0xFFB8C0CC.toInt()))
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("›", 24f, 0xFFB8C0CC.toInt()))
            list.addView(row); list.addView(gap(8))
        }
        editorAction("Add Text", "Place text on the first page", "T") { addTextToPdfDialog(uri) }
        editorAction("Signature", "Draw and apply your signature", "✍") { openSignatureEditor(uri) }
        editorAction("Watermark", "Add a text watermark to every page", "▧") { addWatermarkDialog(uri) }
        editorAction("Whitener Tool", "Cover the lower area with white", "□") { addWhitenerDialog(uri) }
        editorAction("Add Blank Page", "Append a blank page to this PDF", "＋") { addBlankPage(uri) }
        editorAction("Add Page from Gallery", "Choose an image to convert to PDF", "▣") { addPageImageToPdf(uri) }
        editorAction("Line and Shapes", "Add a line, rectangle or circle", "◇") { addShapeDialog(uri) }
        editorAction("Rotate Pages", "Rotate all pages by 90 degrees", "↻") { rotatePdfPages(uri) }
        editorAction("Split PDF", "Split this PDF into two files", "⇄") { splitPdfFile(uri) }
        editorAction("Extract Text", "Copy searchable PDF text", "Aa") { extractPdfText(uri) }
        editorAction("PDF Details", "View page count and file information", "ⓘ") { showPdfDetails(uri) }
        editorAction("Share PDF", "Share the original PDF", "↗") { sharePdf(uri) }
        scroller.addView(list); root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        setScreenContent(root)
    }

    private fun sharePdf(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(send, "Share PDF"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app available to share this PDF", Toast.LENGTH_LONG).show()
        }
    }

    // Google Drive receives the PDF through Android's secure share sheet.
    // This does not store Google passwords or request broad Drive access.
    private fun sharePdfToGoogleDrive(uri: Uri) {
        if (!requireInternet("Google Drive upload")) return
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, "Save PDF to Google Drive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(send, "Save PDF using Google Drive"))
        } catch (_: Exception) {
            Toast.makeText(this, "Install or update Google Drive, then try again", Toast.LENGTH_LONG).show()
        }
    }

    // Google Docs import/conversion support depends on the installed Google app version.
    private fun openPdfWithGoogleDocs(uri: Uri) {
        if (!requireInternet("Google Docs online features")) return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(view, "Open PDF with Google Docs or another app"))
        } catch (_: Exception) {
            Toast.makeText(this, "No compatible document app found", Toast.LENGTH_LONG).show()
        }
    }

    private fun addWatermarkDialog(uri: Uri) {
        val field = EditText(this).apply { hint = "Watermark text" }
        AlertDialog.Builder(this).setTitle("Watermark").setView(field)
            .setPositiveButton("Apply") { _, _ -> if (field.text.isNotBlank()) addWatermark(uri, field.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addWatermark(uri: Uri, value: String) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                    PDDocument.load(input).use { doc ->
                        for (page in doc.pages) PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 28f)
                            cs.setNonStrokingColor(170, 170, 170); cs.newLineAtOffset(55f, 100f)
                            cs.showText(value.take(60).filter { it.code in 32..126 }); cs.endText()
                        }
                        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                    }
                }
                val saved = savePdfBytes(bytes, "Watermarked")
                runOnUiThread { if (saved != null) { lastPdf = saved; Toast.makeText(this, "Watermarked PDF saved", Toast.LENGTH_LONG).show(); buildScreen() } }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Watermark failed", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun addWhitenerDialog(uri: Uri) {
        AlertDialog.Builder(this).setTitle("Whitener Tool")
            .setMessage("This adds a white cover to the lower area of every page. The original remains unchanged.")
            .setPositiveButton("Add Whitener") { _, _ -> applyWhiteout(uri) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun applyWhiteout(uri: Uri) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                    PDDocument.load(input).use { doc ->
                        for (page in doc.pages) PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            cs.setNonStrokingColor(255, 255, 255)
                            cs.addRect(36f, 36f, page.mediaBox.width - 72f, 42f); cs.fill()
                        }
                        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                    }
                }
                val saved = savePdfBytes(bytes, "Whitened")
                runOnUiThread { if (saved != null) { lastPdf = saved; Toast.makeText(this, "New whitened PDF saved", Toast.LENGTH_LONG).show(); buildScreen() } }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Whitener failed", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun addBlankPage(uri: Uri) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                    PDDocument.load(input).use { doc ->
                        doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
                        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                    }
                }
                val saved = savePdfBytes(bytes, "Page_Added")
                runOnUiThread { if (saved != null) { lastPdf = saved; Toast.makeText(this, "Blank page added and saved", Toast.LENGTH_LONG).show(); buildScreen() } }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Add page failed", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun addPageImageToPdf(uri: Uri) {
        Toast.makeText(this, "Select the image; it will be converted to a separate PDF. Merge it with this PDF from PDF Tools.", Toast.LENGTH_LONG).show()
        imagePicker.launch(arrayOf("image/*"))
    }

    private fun addShapeDialog(uri: Uri) {
        AlertDialog.Builder(this).setTitle("Line and Shapes")
            .setItems(arrayOf("Add rectangle", "Add line", "Add circle")) { _, which -> addShapeToPdf(uri, which) }.show()
    }

    private fun addShapeToPdf(uri: Uri, shape: Int) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                    PDDocument.load(input).use { doc ->
                        val page = doc.getPage(0)
                        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            cs.setStrokingColor(40, 120, 230); cs.setLineWidth(2f)
                            when (shape) {
                                0 -> { cs.addRect(55f, 80f, 180f, 90f); cs.stroke() }
                                1 -> { cs.moveTo(55f, 100f); cs.lineTo(260f, 100f); cs.stroke() }
                                else -> { cs.moveTo(195f, 150f); cs.curveTo(195f, 174.85f, 174.85f, 195f, 150f, 195f); cs.curveTo(125.15f, 195f, 105f, 174.85f, 105f, 150f); cs.curveTo(105f, 125.15f, 125.15f, 105f, 150f, 105f); cs.curveTo(174.85f, 105f, 195f, 125.15f, 195f, 150f); cs.stroke() }
                            }
                        }
                        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                    }
                }
                val saved = savePdfBytes(bytes, "Shapes")
                runOnUiThread { if (saved != null) { lastPdf = saved; Toast.makeText(this, "Shape added to PDF", Toast.LENGTH_LONG).show(); buildScreen() } }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Shape tool failed", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun addTextToPdfDialog(uri: Uri) {
        val field = EditText(this).apply { hint = "Enter text to place on first page"; minLines = 2 }
        AlertDialog.Builder(this).setTitle("Add text to PDF").setView(field)
            .setPositiveButton("Save edited PDF") { _, _ ->
                val value = field.text.toString().trim()
                if (value.isNotEmpty()) addTextToPdf(uri, value)
                else Toast.makeText(this, "Enter some text first", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addTextToPdf(uri: Uri, value: String) {
        status.text = "Editing PDF…"
        Thread {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("Cannot open PDF")
                val bytes = input.use { stream ->
                    PDDocument.load(stream).use { doc ->
                        val page = doc.getPage(0)
                        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            cs.beginText()
                            cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                            cs.setNonStrokingColor(20, 45, 90)
                            cs.newLineAtOffset(48f, 48f)
                            cs.showText(value.take(180).filter { it.code in 32..126 })
                            cs.endText()
                        }
                        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                    }
                }
                val saved = savePdfBytes(bytes, "Edited")
                runOnUiThread {
                    if (saved != null) {
                        lastPdf = saved
                        status.text = "Edited PDF saved"
                        recent.text = "Latest: ${saved.lastPathSegment}"
                        Toast.makeText(this, "Edited PDF saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
                        openPdf(saved)
                    } else Toast.makeText(this, "Could not save edited PDF", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "PDF editing failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private data class CompressionPreset(val title: String, val dpi: Int, val quality: Int, val detail: String)

    private val compressionPresets = listOf(
        CompressionPreset("Light compression", 150, 82, "Good quality • 150 DPI • JPEG 82%"),
        CompressionPreset("Recommended", 120, 70, "Balanced size and clarity • 120 DPI • JPEG 70%"),
        CompressionPreset("Strong compression", 96, 55, "Smaller file • 96 DPI • JPEG 55%"),
        CompressionPreset("Maximum compression", 72, 40, "Smallest output • 72 DPI • JPEG 40%")
    )

    private fun showCompressionOptions(uri: Uri) {
        Thread {
            try {
                val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                val pfd = contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open selected PDF")
                val pages = PdfRenderer(pfd).use { it.pageCount }
                runOnUiThread {
                    var selected = 1
                    val panel = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                        background = rounded(0xFFF5F8FF.toInt(), 18)
                    }
                    val summary = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        background = rounded(0xFFEAF2FF.toInt(), 14)
                    }
                    summary.addView(label("SOURCE PDF", 10f, 0xFF3478F6.toInt(), true))
                    summary.addView(gap(5))
                    summary.addView(label("\$pages page(s)   •   \$size bytes", 15f, 0xFF172B4D.toInt(), true))
                    summary.addView(gap(3))
                    summary.addView(label("Choose how much you want to reduce the file size.", 11f, 0xFF64748B.toInt()))
                    panel.addView(summary)
                    panel.addView(gap(12))
                    panel.addView(label("COMPRESSION PRESETS", 10f, 0xFF64748B.toInt(), true))
                    panel.addView(gap(7))
                    val presetRows = mutableListOf<LinearLayout>()
                    compressionPresets.forEachIndexed { index, preset ->
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(12), dp(10), dp(12), dp(10))
                            background = rounded(if (index == selected) 0xFFEAF2FF.toInt() else 0xFFFFFFFF.toInt(), 13,
                                if (index == selected) 0xFF3478F6.toInt() else 0xFFE2EAF5.toInt())
                            layoutParams = LinearLayout.LayoutParams(-1, dp(62)).apply { bottomMargin = dp(7) }
                            setOnClickListener {
                                selected = index
                                presetRows.forEachIndexed { rowIndex, item ->
                                    item.background = rounded(if (rowIndex == selected) 0xFFEAF2FF.toInt() else 0xFFFFFFFF.toInt(), 13,
                                        if (rowIndex == selected) 0xFF3478F6.toInt() else 0xFFE2EAF5.toInt())
                                }
                            }
                        }
                        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                        info.addView(label(preset.title, 13f, 0xFF172B4D.toInt(), true))
                        info.addView(gap(3))
                        info.addView(label(preset.detail, 10f, 0xFF718096.toInt()))
                        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                        row.addView(label(if (index == selected) "●" else "○", 18f, 0xFF3478F6.toInt(), true))
                        presetRows.add(row)
                        panel.addView(row)
                    }
                    panel.addView(label("Note: Pages are converted to images. Searchable text, links, forms and annotations may not be preserved.", 10.5f, 0xFF718096.toInt()))
                    val scroll = ScrollView(this).apply { isFillViewport = true; addView(panel) }
                    showPremiumDialog(AlertDialog.Builder(this)
                        .setTitle("DocNexa • Compression Studio")
                        .setView(scroll)
                        .setPositiveButton("Compress PDF") { _, _ -> runPdfCompression(uri, compressionPresets[selected], size) }
                        .setNeutralButton("Custom settings", null)
                        .setNegativeButton("Cancel", null)
                    ).apply {
                        getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            dismiss()
                            showCustomCompressionDialog(uri, size, compressionPresets[selected])
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Could not read PDF", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showCustomCompressionDialog(uri: Uri, originalSize: Long, base: CompressionPreset) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
            background = rounded(0xFFF5F8FF.toInt(), 16)
        }
        var dpi = base.dpi
        var quality = base.quality
        val dpiLabel = label("Resolution: \$dpi DPI", 14f, 0xFF17243A.toInt(), true)
        val qualityLabel = label("Image quality: \$quality%", 14f, 0xFF17243A.toInt(), true)
        panel.addView(dpiLabel)
        val dpiSeek = SeekBar(this).apply { max = 228; progress = (dpi - 72).coerceIn(0, 228) }
        panel.addView(dpiSeek)
        panel.addView(gap(12))
        panel.addView(qualityLabel)
        val qualitySeek = SeekBar(this).apply { max = 65; progress = (quality - 35).coerceIn(0, 65) }
        panel.addView(qualitySeek)
        panel.addView(gap(8))
        panel.addView(label("Lower values create smaller files but may reduce text clarity.", 11f, 0xFF64748B.toInt()))
        dpiSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { dpi = 72 + progress; dpiLabel.text = "Resolution: \$dpi DPI" }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        qualitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { quality = 35 + progress; qualityLabel.text = "Image quality: \$quality%" }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        showPremiumDialog(AlertDialog.Builder(this).setTitle("Custom compression")
            .setView(panel)
            .setPositiveButton("Compress with custom settings") { _, _ ->
                runPdfCompression(uri, CompressionPreset("Custom", dpi, quality, "\$dpi DPI • JPEG \$quality%"), originalSize)
            }
            .setNegativeButton("Cancel", null))
    }

    private fun runPdfCompression(uri: Uri, preset: CompressionPreset, originalSize: Long) {
        status.text = "Compressing PDF…"
        Thread {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open selected PDF")
                val renderer = PdfRenderer(pfd)
                val output = PdfDocument()
                try {
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { sourcePage ->
                            val width = (sourcePage.width * preset.dpi / 72f).toInt().coerceAtLeast(1)
                            val height = (sourcePage.height * preset.dpi / 72f).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                sourcePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val imageBytes = ByteArrayOutputStream().also { stream ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, preset.quality, stream)
                                }.toByteArray()
                                val compressedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                                    ?: error("Could not encode page ${index + 1}")
                                try {
                                    val page = output.startPage(PdfDocument.PageInfo.Builder(sourcePage.width, sourcePage.height, index + 1).create())
                                    page.canvas.drawBitmap(compressedBitmap, null, android.graphics.Rect(0, 0, sourcePage.width, sourcePage.height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                                    output.finishPage(page)
                                } finally {
                                    compressedBitmap.recycle()
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    val compressedBytes = ByteArrayOutputStream().also { output.writeTo(it) }.toByteArray()
                    val reduced = originalSize > 0 && compressedBytes.size < originalSize
                    runOnUiThread {
                        status.text = "Compression preview ready"
                        val savedPercent = if (originalSize > 0) (((originalSize - compressedBytes.size).toDouble() / originalSize) * 100).toInt() else 0
                        val summary = "Preset: ${preset.title}\nOriginal: ${formatFileSize(originalSize)}\nCompressed: ${formatFileSize(compressedBytes.size.toLong())}\n" +
                            if (originalSize > 0) (if (reduced) "Size reduced by $savedPercent%" else "This PDF did not become smaller with this preset.") else "Compression complete."
                        AlertDialog.Builder(this)
                            .setTitle("Compression Preview")
                            .setMessage(summary + "\n\nNote: This image-based compression reduces resolution and removes selectable/searchable text and form layers.")
                            .setPositiveButton("Save PDF") { _, _ ->
                                val saved = savePdfBytes(compressedBytes, "Compressed")
                                if (saved != null) {
                                    lastPdf = saved
                                    status.text = "Compressed PDF saved"
                                    recent.text = "Latest: ${saved.lastPathSegment}"
                                    Toast.makeText(this, "Compressed PDF saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
                                } else Toast.makeText(this, "Could not save compressed PDF", Toast.LENGTH_LONG).show()
                            }
                            .setNeutralButton("Share") { _, _ ->
                                val saved = savePdfBytes(compressedBytes, "Compressed")
                                if (saved != null) {
                                    lastPdf = saved
                                    sharePdf(saved)
                                } else Toast.makeText(this, "Could not prepare PDF to share", Toast.LENGTH_LONG).show()
                            }
                            .setNegativeButton("Discard", null)
                            .show()
                    }
                } finally {
                    output.close()
                    renderer.close()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Compression failed"
                    Toast.makeText(this, e.localizedMessage ?: "PDF compression failed", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 0) return "Unknown"
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    private fun exportPdfAsWord(uri: Uri) {
        val nameField = EditText(this).apply {
            hint = "Word file name"
            setSingleLine(true)
            setText("DocNexa_Converted")
        }
        val fontSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Arial", "Calibri", "Times New Roman"))
        }
        val sizeSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("10 pt", "11 pt", "12 pt", "14 pt"))
            setSelection(2)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(label("Convert selectable PDF text into an editable Microsoft Word DOCX file.", 12f, 0xFF64748B.toInt()))
            addView(gap(10))
            addView(label("File name", 12f, 0xFF263957.toInt(), true))
            addView(nameField)
            addView(gap(8))
            addView(label("Document font", 12f, 0xFF263957.toInt(), true))
            addView(fontSpinner)
            addView(gap(8))
            addView(label("Font size", 12f, 0xFF263957.toInt(), true))
            addView(sizeSpinner)
            addView(gap(8))
            addView(label("Note: scanned/image-only PDFs need OCR before Word conversion. The original PDF will not be changed.", 11f, 0xFF718096.toInt()))
        }
        showPremiumDialog(AlertDialog.Builder(this)
            .setTitle("PDF to Word • Advanced Export")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Convert to DOCX") { _, _ ->
                val base = nameField.text.toString().trim()
                    .replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').take(60)
                    .ifBlank { "DocNexa_Converted" }
                val font = fontSpinner.selectedItem.toString()
                val fontSize = listOf(10, 11, 12, 14)[sizeSpinner.selectedItemPosition.coerceIn(0, 3)]
                convertPdfToDocx(uri, base, font, fontSize)
            })
    }

    private fun convertPdfToDocx(uri: Uri, fileName: String, fontName: String, fontSize: Int) {
        status.text = "Converting PDF to editable Word…"
        Thread {
            try {
                val text = contentResolver.openInputStream(uri)?.use { stream ->
                    PDDocument.load(stream).use { PDFTextStripper().getText(it) }
                } ?: error("Cannot open PDF")
                if (text.isBlank()) error("No selectable text found. This may be a scanned PDF; run Image to Text (OCR) first.")
                val docxBytes = createDocxBytes(text, fontName, fontSize)
                val saved = saveDocumentBytes(
                    docxBytes, fileName,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"
                )
                runOnUiThread {
                    if (saved != null) {
                        status.text = "Word DOCX document saved"
                        recent.text = "Latest: ${saved.lastPathSegment ?: fileName}"
                        buildScreen()
                        AlertDialog.Builder(this)
                            .setTitle("Word file created")
                            .setMessage("Editable DOCX saved in Downloads/DocumentScanner. The original PDF is unchanged.")
                            .setPositiveButton("Open") { _, _ -> openWordDocument(saved) }
                            .setNeutralButton("Share") { _, _ -> shareWordDocument(saved) }
                            .setNegativeButton("Done", null)
                            .show()
                    } else {
                        status.text = "Word export failed"
                        Toast.makeText(this, "Could not save DOCX file", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Word conversion failed"
                    Toast.makeText(this, e.localizedMessage ?: "PDF conversion failed", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun createDocxBytes(text: String, fontName: String, fontSize: Int): ByteArray {
        val safeFont = fontName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val paragraphs = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(text.trim()) }
        fun xml(value: String): String = value.replace("&", "&amp;")
            .replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        val body = paragraphs.joinToString("") { paragraph ->
            val runs = paragraph.split("\t").mapIndexed { index, part ->
                val tab = if (index > 0) "<w:r><w:tab/></w:r>" else ""
                tab + "<w:r><w:rPr><w:rFonts w:ascii=\"$safeFont\" w:hAnsi=\"$safeFont\"/><w:sz w:val=\"${fontSize * 2}\"/></w:rPr><w:t xml:space=\"preserve\">${xml(part)}</w:t></w:r>"
            }.joinToString("")
            "<w:p><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr>$runs</w:p>"
        }
        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
        return ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                fun entry(name: String, value: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("[Content_Types].xml", contentTypes)
                entry("_rels/.rels", rels)
                entry("word/document.xml", documentXml)
            }
            bytes.toByteArray()
        }
    }

    private fun openWordDocument(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(intent) }
        catch (_: Exception) { Toast.makeText(this, "No DOCX app found. The file is saved in Downloads/DocumentScanner.", Toast.LENGTH_LONG).show() }
    }

    private fun shareWordDocument(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Word document"))
        } catch (e: Exception) {
            Toast.makeText(this, e.localizedMessage ?: "Could not share DOCX", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveDocumentBytes(bytes: ByteArray, prefix: String, mime: String, extension: String): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "${prefix}_${stamp}.${extension}")
            put(MediaStore.Downloads.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/DocumentScanner")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri).use { output ->
            if (output == null) throw IllegalStateException("Unable to save document")
            output.write(bytes)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    private fun passwordDialog(uri: Uri, remove: Boolean) {
        val password = EditText(this).apply {
            hint = if (remove) "Enter current PDF password" else "Create password (min. 6 characters)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val confirm = EditText(this).apply {
            hint = "Confirm new password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val showPassword = android.widget.CheckBox(this).apply { text = "Show password" }
        showPassword.setOnCheckedChangeListener { _, checked ->
            val type = if (checked) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            password.inputType = type
            confirm.inputType = type
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(label(if (remove) "Enter the password currently protecting this PDF. The original file will remain unchanged." else "Create a password to encrypt a new copy. Keep it safe; you will need it to open the protected PDF.", 11f, 0xFF718096.toInt()))
            addView(gap(10))
            addView(password)
            if (!remove) {
                addView(gap(8))
                addView(confirm)
            }
            addView(showPassword)
        }
        showPremiumDialog(AlertDialog.Builder(this)
            .setTitle(if (remove) "Remove PDF Password" else "Protect PDF • Security")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (remove) "Unlock & Save Copy" else "Encrypt & Save Copy") { _, _ ->
                val value = password.text.toString()
                val confirmation = confirm.text.toString()
                when {
                    value.isBlank() -> Toast.makeText(this, "Enter the PDF password", Toast.LENGTH_LONG).show()
                    !remove && value.length < 6 -> Toast.makeText(this, "Use at least 6 characters for a stronger password", Toast.LENGTH_LONG).show()
                    !remove && value != confirmation -> Toast.makeText(this, "Passwords do not match", Toast.LENGTH_LONG).show()
                    else -> processPdfPassword(uri, value, remove)
                }
            })
    }

    private fun processPdfPassword(uri: Uri, password: String, remove: Boolean) {
        status.text = if (remove) "Unlocking PDF…" else "Encrypting PDF…"
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val doc = if (remove) PDDocument.load(input, password) else PDDocument.load(input)
                    doc.use {
                        if (!remove) {
                            val ownerPassword = java.util.UUID.randomUUID().toString().replace("-", "") + password
                            val policy = StandardProtectionPolicy(ownerPassword, password, AccessPermission())
                            policy.encryptionKeyLength = 256
                            it.protect(policy)
                        }
                        ByteArrayOutputStream().also { out -> it.save(out) }.toByteArray()
                    }
                } ?: error("Could not read selected PDF")
                val saved = savePdfBytes(bytes, if (remove) "Unlocked" else "Protected")
                runOnUiThread {
                    if (saved != null) {
                        lastPdf = saved
                        status.text = if (remove) "PDF unlocked" else "PDF protected"
                        Toast.makeText(this, if (remove) "Unlocked PDF copy saved. Original file is unchanged." else "Encrypted PDF copy saved. Remember your password.", Toast.LENGTH_LONG).show()
                        buildScreen()
                    } else Toast.makeText(this, "Could not save the PDF copy", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                val raw = e.localizedMessage ?: ""
                val message = if (remove && (raw.contains("password", true) || raw.contains("decrypt", true) || raw.contains("invalid", true))) {
                    "Could not unlock PDF. Check that the password is correct."
                } else raw.ifBlank { if (remove) "PDF unlock failed" else "PDF protection failed" }
                runOnUiThread { status.text = "PDF security operation failed"; Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun convertPdfToImages(uri: Uri) {
        Thread {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open PDF")
                val renderer = PdfRenderer(pfd)
                try {
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val bitmap = Bitmap.createBitmap((page.width * 2).coerceAtLeast(1), (page.height * 2).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val out = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                            bitmap.recycle()
                            saveDocumentBytes(out.toByteArray(), "PDF_Page_${index + 1}", "image/jpeg", "jpg")
                        }
                    }
                } finally { renderer.close(); pfd.close() }
                runOnUiThread { Toast.makeText(this, "PDF pages exported as images to Downloads/DocumentScanner", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "PDF to Image failed", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun zipPdf(uri: Uri) {
        val nameField = EditText(this).apply {
            hint = "Archive name (e.g. My_Documents)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText("DocNexa_PDF_Archive")
        }
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(label("Create a ZIP archive containing your selected PDF. The original PDF will remain unchanged.", 12f, 0xFF718096.toInt()))
            addView(gap(8))
            addView(nameField)
        }
        showPremiumDialog(AlertDialog.Builder(this).setTitle("PDF To ZIP • Create Archive")
            .setView(options)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create ZIP") { _, _ ->
                val archiveName = nameField.text.toString().trim()
                    .replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').take(60)
                    .ifBlank { "DocNexa_PDF_Archive" }
                Thread {
                    try {
                        val bytes = ByteArrayOutputStream()
                        ZipOutputStream(bytes).use { zip ->
                            zip.putNextEntry(ZipEntry("document.pdf"))
                            val input = contentResolver.openInputStream(uri) ?: error("Could not open selected PDF")
                            input.use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                        val saved = saveDocumentBytes(bytes.toByteArray(), archiveName, "application/zip", "zip")
                        runOnUiThread {
                            if (saved != null) {
                                Toast.makeText(this, "$archiveName.zip saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, saved)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try { startActivity(Intent.createChooser(share, "Share ZIP archive")) }
                                catch (_: Exception) { }
                            } else Toast.makeText(this, "Could not save ZIP archive", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "PDF to ZIP failed", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            })
    }

    private fun showQrScanner() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            background = rounded(0xFFF5F8FF.toInt(), 18)
        }
        val choices = listOf(
            Triple("▣", "Live camera scanner", "Scan a QR code using your camera"),
            Triple("▧", "Scan from gallery", "Choose an image containing a QR code")
        )
        choices.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(0xFFFFFFFF.toInt(), 14, 0xFFE2EAF5.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(9) }
                setOnClickListener {
                    when(index) {
                        0 -> if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openLiveQrCamera() else qrCameraPermission.launch(Manifest.permission.CAMERA)
                        1 -> qrImagePicker.launch("image/*")
                    }
                }
            }
            row.addView(label(item.first, 24f, 0xFF168BC5.toInt(), true), LinearLayout.LayoutParams(dp(44), -2))
            val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textBox.addView(label(item.second, 14f, 0xFF172B4D.toInt(), true))
            textBox.addView(gap(3))
            textBox.addView(label(item.third, 10.5f, 0xFF718096.toInt()))
            row.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("›", 23f, 0xFF718096.toInt()))
            panel.addView(row)
        }
        showPremiumDialog(AlertDialog.Builder(this).setTitle("QR Scanner").setView(panel).setNegativeButton("Close", null))
    }

    private fun openLiveQrCamera() {
        qrAlreadyRead.set(false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(0xFF101216.toInt()) }
        val preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("Keep the QR code inside the camera frame. It scans automatically.", 13f, 0xFFFFFFFF.toInt()).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
        val close = Button(this).apply { text = "Close scanner"; setOnClickListener { qrCameraProvider?.unbindAll(); buildScreen() } }
        root.addView(close, LinearLayout.LayoutParams(-1, dp(48)))
        setScreenContent(root)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                qrCameraProvider = provider
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val scanner = BarcodeScanning.getClient()
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(cameraExecutor) { proxy -> analyzeQrFrame(proxy, scanner) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, e.localizedMessage ?: "Could not start camera", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeQrFrame(proxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner) {
        val media = proxy.image
        if (media == null || qrAlreadyRead.get()) { proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                val value = codes.mapNotNull { it.rawValue }.distinct().joinToString("\n\n")
                if (value.isNotBlank() && qrAlreadyRead.compareAndSet(false, true)) {
                    runOnUiThread {
                        qrCameraProvider?.unbindAll()
                        showQrResult(value)
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun showQrResult(value: String) {
        AlertDialog.Builder(this).setTitle("QR Scanner • Result")
            .setMessage(value.ifBlank { "No QR code found. Try a clearer image." })
            .setPositiveButton("Copy") { _, _ ->
                if (value.isNotBlank()) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QR content", value))
                    Toast.makeText(this, "QR content copied", Toast.LENGTH_SHORT).show()
                }
            }.setNeutralButton("Open link") { _, _ ->
                val link = value.trim()
                if (link.startsWith("https://") || link.startsWith("http://")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                    catch (_: Exception) { Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show() }
                } else Toast.makeText(this, "This QR does not contain a web link", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Close") { _, _ -> buildScreen() }.show()
    }

    private fun readQrFromImage(uri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, uri)
            BarcodeScanning.getClient().process(image)
                .addOnSuccessListener { codes ->
                    val result = codes.mapNotNull { it.rawValue }.distinct().joinToString("\n\n")
                    showQrResult(result)
                }
                .addOnFailureListener { error -> Toast.makeText(this, error.localizedMessage ?: "QR scan failed", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            Toast.makeText(this, e.localizedMessage ?: "Cannot read selected image", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleFavourite(uri: Uri) {
        val set = (prefs.getStringSet("favourite_uris", emptySet()) ?: emptySet()).toMutableSet()
        if (!set.add(uri.toString())) set.remove(uri.toString())
        prefs.edit().putStringSet("favourite_uris", set).apply()
        Toast.makeText(this, if (set.contains(uri.toString())) "Added to Favourite" else "Removed from Favourite", Toast.LENGTH_SHORT).show()
        buildScreen()
    }

    private fun showFavouriteDocs() {
        val favourites = prefs.getStringSet("favourite_uris", emptySet()) ?: emptySet()
        val docs = getSavedPdfs().filter { favourites.contains(it.second.toString()) }
        if (docs.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Favourite").setMessage("No favourite PDFs yet. Open a document menu and choose Add/Remove Favourite.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Favourite documents")
            .setItems(docs.map { it.first }.toTypedArray()) { _, index -> openPdf(docs[index].second) }
            .setNegativeButton("Close", null).show()
    }

    private fun showQrGenerator() {
        val field = EditText(this).apply {
            hint = "Enter text, phone number or website URL"
            minLines = 2
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        field.background = rounded(0xFFFFFFFF.toInt(), 12, 0xFFD8E4F2.toInt())
        field.setTextColor(0xFF172B4D.toInt())
        field.setHintTextColor(0xFF8994A5.toInt())
        val inputPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)); addView(label("Enter the content you want to share", 12f, 0xFF64748B.toInt(), true)); addView(gap(8)); addView(field) }
        AlertDialog.Builder(this).setTitle("Create QR Code").setView(inputPanel)
            .setPositiveButton("Generate") { _, _ ->
                val value = field.text.toString().trim()
                if (value.isBlank()) {
                    Toast.makeText(this, "Enter text or a URL first", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 900, 900)
                        val bitmap = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
                        for (x in 0 until 900) for (y in 0 until 900) {
                            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                        }
                        val bytes = ByteArrayOutputStream().also { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }.toByteArray()
                        val preview = ImageView(this).apply {
                            setImageBitmap(bitmap)
                            adjustViewBounds = true
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                        }
                        AlertDialog.Builder(this).setTitle("QR Code Ready").setView(preview)
                            .setMessage("Your QR code is ready. Download it to your phone or share it directly.")
                            .setPositiveButton("Download") { _, _ ->
                                val saved = saveDocumentBytes(bytes, "DocNexa_QR", "image/png", "png")
                                Toast.makeText(
                                    this,
                                    if (saved != null) "QR PNG downloaded to Downloads/DocumentScanner" else "Could not save QR image",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            .setNeutralButton("Share") { _, _ ->
                                try {
                                    val saved = saveDocumentBytes(bytes, "DocNexa_QR", "image/png", "png")
                                    if (saved == null) {
                                        Toast.makeText(this, "Could not prepare QR image for sharing", Toast.LENGTH_LONG).show()
                                    } else {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "image/png"
                                            putExtra(Intent.EXTRA_STREAM, saved)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        startActivity(Intent.createChooser(shareIntent, "Share DocNexa QR Code"))
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(this, e.localizedMessage ?: "Unable to share QR image", Toast.LENGTH_LONG).show()
                                }
                            }
                            .setNegativeButton("Done", null)
                            .let { showPremiumDialog(it) }
                    } catch (e: Exception) {
                        Toast.makeText(this, e.localizedMessage ?: "QR generation failed", Toast.LENGTH_LONG).show()
                    }
                }
            }.setNegativeButton("Cancel", null).let { showPremiumDialog(it) }
    }

    private fun showComingSoon(feature: String) {
        AlertDialog.Builder(this)
            .setTitle(feature)
            .setMessage("This tool is planned for a future update. The available PDF tools are Open PDF, Merge, Split, Image to PDF and Share PDF.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun mergePdfFiles(uris: List<Uri>) {
        if (uris.size < 2) {
            Toast.makeText(this, "Select at least two PDF files", Toast.LENGTH_LONG).show()
            return
        }
        val orderedUris = uris.toMutableList()
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nameField = EditText(this).apply {
            hint = "Merged PDF file name"
            setSingleLine(true)
            setText("DocNexa_Merged")
        }
        fun displayName(uri: Uri, index: Int): String = try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "PDF ${index + 1}"
        } catch (_: Exception) { "PDF ${index + 1}" }

        fun refreshRows() {
            rows.removeAllViews()
            orderedUris.forEachIndexed { index, uri ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(9), dp(8), dp(7), dp(8))
                    background = rounded(0xFFF5F8FF.toInt(), 13, 0xFFDCE6F4.toInt())
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                }
                val thumb = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = rounded(0xFFE8EFFB.toInt(), 8)
                    layoutParams = LinearLayout.LayoutParams(dp(62), dp(78))
                    setImageResource(android.R.drawable.ic_menu_gallery)
                    contentDescription = "First page preview"
                }
                row.addView(thumb)
                val details = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(9), 0, dp(4), 0)
                }
                details.addView(label("PDF ${index + 1}", 10f, 0xFF3478F6.toInt(), true))
                details.addView(gap(3))
                details.addView(label(displayName(uri, index).take(32), 11f, 0xFF263957.toInt(), true))
                details.addView(gap(3))
                details.addView(label("Position ${index + 1} of ${orderedUris.size}", 10f, 0xFF718096.toInt()))
                row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
                val up = android.widget.Button(this).apply {
                    text = "↑"; isAllCaps = false; textSize = 15f; minWidth = dp(38); minHeight = dp(36)
                    isEnabled = index > 0
                    setOnClickListener {
                        java.util.Collections.swap(orderedUris, index, index - 1)
                        refreshRows()
                    }
                }
                val down = android.widget.Button(this).apply {
                    text = "↓"; isAllCaps = false; textSize = 15f; minWidth = dp(38); minHeight = dp(36)
                    isEnabled = index < orderedUris.lastIndex
                    setOnClickListener {
                        java.util.Collections.swap(orderedUris, index, index + 1)
                        refreshRows()
                    }
                }
                controls.addView(up); controls.addView(down); row.addView(controls)
                rows.addView(row)
                Thread {
                    try {
                        val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: return@Thread
                        val bitmap = descriptor.use { pfd ->
                            android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                                if (renderer.pageCount == 0) null else renderer.openPage(0).use { page ->
                                    Bitmap.createBitmap(180, 230, Bitmap.Config.ARGB_8888).also { bmp ->
                                        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
                                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }
                        }
                        if (bitmap != null) runOnUiThread { if (!isFinishing && thumb.parent != null) thumb.setImageBitmap(bitmap) }
                    } catch (_: Exception) { }
                }.start()
            }
        }
        refreshRows()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(4))
            addView(label("PREVIEW & REORDER", 11f, 0xFF3478F6.toInt(), true))
            addView(gap(4))
            addView(label("Use the up/down arrows to choose the exact page order in the merged PDF.", 10.5f, 0xFF718096.toInt()))
            addView(gap(9))
            addView(rows)
            addView(gap(8))
            addView(label("Output PDF name", 11f, 0xFF263957.toInt(), true))
            addView(nameField)
        }
        showPremiumDialog(AlertDialog.Builder(this).setTitle("Merge PDFs • Preview")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Merge in this order") { _, _ ->
                val outputName = nameField.text.toString().trim()
                    .replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').take(60)
                    .ifBlank { "DocNexa_Merged" }
                val mergeOrder = orderedUris.toList()
                status.text = "Merging ${mergeOrder.size} PDF files…"
                Thread {
                    try {
                        val merger = PDFMergerUtility()
                        val streams = mutableListOf<java.io.InputStream>()
                        try {
                            mergeOrder.forEach { uri ->
                                val stream = contentResolver.openInputStream(uri)
                                    ?: throw IllegalStateException("Could not open selected PDF")
                                streams.add(stream)
                                merger.addSource(stream)
                            }
                            val output = ByteArrayOutputStream()
                            merger.destinationStream = output
                            merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
                            val saved = savePdfBytes(output.toByteArray(), outputName)
                            runOnUiThread {
                                lastPdf = saved
                                status.text = if (saved != null) "PDF merge complete" else "PDF merge failed"
                                recent.text = "Latest: ${saved?.lastPathSegment ?: "Merged PDF"}"
                                if (saved != null) {
                                    buildScreen()
                                    AlertDialog.Builder(this)
                                        .setTitle("PDFs merged successfully")
                                        .setMessage("${mergeOrder.size} files combined in your selected order into ${saved.lastPathSegment}")
                                        .setPositiveButton("Open PDF") { _, _ -> openPdf(saved) }
                                        .setNeutralButton("Share PDF") { _, _ -> sharePdf(saved) }
                                        .setNegativeButton("Done", null)
                                        .show()
                                } else Toast.makeText(this, "Could not save merged PDF", Toast.LENGTH_LONG).show()
                            }
                        } finally {
                            streams.forEach { try { it.close() } catch (_: Exception) {} }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            status.text = "PDF merge failed"
                            Toast.makeText(this, e.localizedMessage ?: "Could not merge PDFs", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            })
    }

    private fun splitPdfFile(uri: Uri) {
        Thread {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("Could not open selected PDF")
                val pageCount = input.use { PDDocument.load(it).use { doc -> doc.numberOfPages } }
                runOnUiThread {
                    if (pageCount < 2) {
                        showPremiumDialog(AlertDialog.Builder(this)
                            .setTitle("Cannot split a one-page PDF")
                            .setMessage("This file contains only 1 page. Splitting needs at least 2 pages. Choose a multi-page PDF to extract page ranges, or use PDF To ZIP to package this file.")
                            .setPositiveButton("Choose another PDF") { _, _ -> splitPicker.launch(arrayOf("application/pdf")) }
                            .setNegativeButton("Close", null))
                        return@runOnUiThread
                    }
                    val panel = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(8), dp(16), dp(4))
                    }
                    panel.addView(label("TOTAL PAGES: $pageCount", 12f, 0xFF3478F6.toInt(), true))
                    panel.addView(gap(8))
                    val range = EditText(this).apply {
                        hint = "Example: 1-3, 5, 7-9"
                        setSingleLine(true)
                        inputType = android.text.InputType.TYPE_CLASS_TEXT
                    }
                    panel.addView(range)
                    panel.addView(gap(5))
                    panel.addView(label("Enter pages to extract. The remaining pages are saved as a second PDF.", 11f, 0xFF718096.toInt()))
                    showPremiumDialog(AlertDialog.Builder(this).setTitle("DocNexa • Smart PDF Split")
                        .setView(panel)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Split & Save") { _, _ ->
                            val selected = parsePageRanges(range.text.toString(), pageCount)
                            if (selected.isEmpty()) {
                                Toast.makeText(this, "Enter a valid page range from 1 to $pageCount", Toast.LENGTH_LONG).show()
                            } else splitPdfPages(uri, selected)
                        })
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Could not inspect PDF", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun parsePageRanges(value: String, total: Int): List<Int> {
        val pages = sortedSetOf<Int>()
        try {
            value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                val bounds = part.split("-").map { it.trim() }
                require(bounds.size in 1..2)
                val start = bounds[0].toInt()
                val end = if (bounds.size == 2) bounds[1].toInt() else start
                require(start >= 1 && end >= start && end <= total)
                for (page in start..end) pages.add(page - 1)
            }
        } catch (_: Exception) { return emptyList() }
        return pages.toList()
    }

    private fun splitPdfPages(uri: Uri, selectedPages: List<Int>) {
        status.text = "Splitting selected pages…"
        Thread {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("Could not open PDF")
                val source = input.use { PDDocument.load(it) }
                val picked = PDDocument()
                val remaining = PDDocument()
                try {
                    for (i in 0 until source.numberOfPages) {
                        if (i in selectedPages) picked.importPage(source.getPage(i))
                        else remaining.importPage(source.getPage(i))
                    }
                    val firstBytes = ByteArrayOutputStream().also { picked.save(it) }.toByteArray()
                    val savedA = savePdfBytes(firstBytes, "Selected_Pages")
                    val savedB = if (remaining.numberOfPages > 0) {
                        val secondBytes = ByteArrayOutputStream().also { remaining.save(it) }.toByteArray()
                        savePdfBytes(secondBytes, "Remaining_Pages")
                    } else null
                    runOnUiThread {
                        status.text = "Custom page split complete"
                        recent.text = if (savedB != null) "Saved selected pages and remaining pages" else "Saved extracted pages as a separate PDF"
                        val message = if (savedB != null) "Selected pages and remaining pages saved in Downloads/DocumentScanner" else "All selected pages saved as a PDF in Downloads/DocumentScanner"
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                } finally { picked.close(); remaining.close(); source.close() }
            } catch (e: Exception) {
                runOnUiThread { status.text = "PDF split failed"; Toast.makeText(this, e.localizedMessage ?: "Could not split PDF", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }


    private fun saveCloudPdf(fileName: String, bytes: ByteArray): Uri? {
        val safeName = fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100)
            .ifBlank { "Cloud_Document.pdf" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/DocumentScanner")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri).use { output ->
            if (output == null) throw IllegalStateException("Unable to save downloaded PDF")
            output.write(bytes)
        }
        assignPdf(uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
        }
        return uri
    }

    private fun savePdfBytes(bytes: ByteArray, prefix: String): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "${prefix}_${stamp}.pdf"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/DocumentScanner")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri).use { output ->
            if (output == null) throw IllegalStateException("Unable to save PDF")
            output.write(bytes)
        }
        assignPdf(uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
        }
        return uri
    }

    private fun startDocumentScan() {
        status.text = "Opening scanner…"
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { error ->
                status.text = "Scanner unavailable. Check Google Play services and internet."
                Toast.makeText(this, error.localizedMessage ?: "Scanner error", Toast.LENGTH_LONG).show()
            }
    }

    private fun showIdPhotoMenu() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            background = rounded(0xFFF5F8FF.toInt(), 16)
        }
        listOf(
            Triple("▣", "ID Card • Front & Back", "Import, crop and create an ID PDF"),
            Triple("▤", "Passport Photo Maker", "Crop a photo and create a print sheet")
        ).forEachIndexed { index, option ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = rounded(0xFFFFFFFF.toInt(), 14, 0xFFE1EAF7.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
                isClickable = true
                setOnClickListener {
                    if (index == 0) idCardPicker.launch(arrayOf("image/*"))
                    else passportPicker.launch(arrayOf("image/*"))
                }
            }
            row.addView(label(option.first, 24f, 0xFF3478F6.toInt(), true), LinearLayout.LayoutParams(dp(42), -2))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(label(option.second, 13f, 0xFF172B4D.toInt(), true))
            info.addView(gap(4))
            info.addView(label(option.third, 10.5f, 0xFF718096.toInt()))
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("›", 22f, 0xFF3478F6.toInt()))
            panel.addView(row)
        }
        showPremiumDialog(AlertDialog.Builder(this).setTitle("DocNexa • ID Photo Studio")
            .setView(panel).setNegativeButton("Cancel", null))
    }

    private fun showIdCardImportPreview(uris: List<Uri>) {
        val bitmaps = uris.map { uri ->
            contentResolver.openInputStream(uri).use { input ->
                if (input != null) BitmapFactory.decodeStream(input) else null
            }
        }.toMutableList()
        if (bitmaps.any { it == null }) {
            Toast.makeText(this, "Could not read one of the selected images", Toast.LENGTH_LONG).show()
            return
        }
        val originals = bitmaps.filterNotNull().toMutableList()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        panel.addView(label("Select front and back images. Crop each side if needed.", 13f, 0xFF263957.toInt(), true))
        originals.forEachIndexed { index, bitmap ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val preview = ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(112), dp(82))
            }
            row.addView(preview)
            row.addView(label(if (index == 0) "  Front side" else "  Back side", 14f, 0xFF263957.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "Crop"
                isAllCaps = false
                setOnClickListener {
                    val cropView = ImageCropView(this@MainActivity, originals[index])
                    val cropPanel = FrameLayout(this@MainActivity).apply {
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        addView(cropView, FrameLayout.LayoutParams(-1, dp(380)))
                    }
                    val dialog = AlertDialog.Builder(this@MainActivity).setTitle("Crop ${if (index == 0) "Front" else "Back"}")
                        .setView(cropPanel).setPositiveButton("Apply Crop", null)
                        .setNeutralButton("Rotate", null).setNegativeButton("Reset", null).create()
                    dialog.setOnShowListener {
                        dialog.window?.setLayout(-1, dp(560))
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            originals[index] = cropView.getCroppedBitmap()
                            preview.setImageBitmap(originals[index])
                            dialog.dismiss()
                        }
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { cropView.rotateImage() }
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { cropView.resetCrop() }
                    }
                    dialog.show()
                }
            })
            panel.addView(row)
            panel.addView(gap(6))
        }
        val scroll = ScrollView(this).apply { addView(panel) }
        AlertDialog.Builder(this).setTitle("ID Card Preview").setView(scroll)
            .setPositiveButton("Create ID PDF") { _, _ -> createPdfFromBitmaps(originals, "ID_Card") }
            .setNeutralButton("Share Images") { _, _ ->
                val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Share ID images"))
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showPassportPhotoEditor(uri: Uri) {
        try {
            val source = contentResolver.openInputStream(uri).use { input ->
                if (input != null) BitmapFactory.decodeStream(input) else null
            } ?: throw IllegalStateException("Could not open selected photo")
            val cropView = ImageCropView(this, source)
            val sizeOptions = arrayOf("35 × 45 mm (Passport)", "2 × 2 inch (Square)", "25 × 35 mm (Wallet)")
            var selectedSize = 0
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                addView(cropView, LinearLayout.LayoutParams(-1, dp(390)))
                addView(label("Drag crop handles. Choose a print size below.", 12f, 0xFF53627A.toInt()))
            }
            AlertDialog.Builder(this).setTitle("Passport Photo Maker")
                .setView(panel)
                .setSingleChoiceItems(sizeOptions, 0) { _, which -> selectedSize = which }
                .setPositiveButton("Create Photo Sheet") { _, _ ->
                    val cropped = cropView.getCroppedBitmap()
                    createPassportPhotoSheet(cropped, selectedSize)
                }
                .setNeutralButton("Rotate", null)
                .setNegativeButton("Cancel", null)
                .create().also { dialog ->
                    dialog.setOnShowListener {
                        dialog.window?.setLayout(-1, dp(650))
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { cropView.rotateImage() }
                    }
                }.show()
        } catch (e: Exception) {
            Toast.makeText(this, e.localizedMessage ?: "Could not open photo", Toast.LENGTH_LONG).show()
        }
    }

    private fun createPdfFromBitmaps(bitmaps: List<Bitmap>, prefix: String) {
        Thread {
            try {
                val pdf = PdfDocument()
                bitmaps.forEachIndexed { index, bitmap ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    page.canvas.drawColor(android.graphics.Color.WHITE)
                    val rect = fitRect(bitmap.width, bitmap.height, 515, 762, 40, 40)
                    page.canvas.drawBitmap(bitmap, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    pdf.finishPage(page)
                }
                val bytes = ByteArrayOutputStream().also { pdf.writeTo(it) }.toByteArray()
                pdf.close()
                val saved = savePdfBytes(bytes, prefix)
                runOnUiThread {
                    if (saved != null) {
                        lastPdf = saved
                        buildScreen()
                        AlertDialog.Builder(this).setTitle("ID PDF Created")
                            .setMessage("Your ID card PDF is saved in Downloads/DocumentScanner.")
                            .setPositiveButton("Open PDF") { _, _ -> openPdf(saved) }
                            .setNeutralButton("Share PDF") { _, _ -> sharePdf(saved) }
                            .setNegativeButton("Done", null).show()
                    } else Toast.makeText(this, "Could not save ID PDF", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "ID PDF creation failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun createPassportPhotoSheet(photo: Bitmap, sizeChoice: Int) {
        Thread {
            try {
                val mmWidth = when (sizeChoice) { 1 -> 50f; 2 -> 25f; else -> 35f }
                val mmHeight = when (sizeChoice) { 1 -> 50f; 2 -> 35f; else -> 45f }
                val targetAspect = mmWidth / mmHeight
                val passportPhoto = if (photo.width.toFloat() / photo.height.toFloat() > targetAspect) {
                    val cropWidth = (photo.height * targetAspect).toInt().coerceIn(1, photo.width)
                    Bitmap.createBitmap(photo, (photo.width - cropWidth) / 2, 0, cropWidth, photo.height)
                } else {
                    val cropHeight = (photo.width / targetAspect).toInt().coerceIn(1, photo.height)
                    Bitmap.createBitmap(photo, 0, (photo.height - cropHeight) / 2, photo.width, cropHeight)
                }
                val pageWidth = 595
                val pageHeight = 842
                val margin = 24f
                val gap = 10f
                val photoW = mmWidth * 72f / 25.4f
                val photoH = mmHeight * 72f / 25.4f
                val cols = ((pageWidth - 2 * margin + gap) / (photoW + gap)).toInt().coerceAtLeast(1)
                val rows = ((pageHeight - 2 * margin + gap) / (photoH + gap)).toInt().coerceAtLeast(1)
                val sheet = Bitmap.createBitmap(pageWidth * 2, pageHeight * 2, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(sheet)
                canvas.drawColor(android.graphics.Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val w = photoW * 2; val h = photoH * 2; val g = gap * 2; val m = margin * 2
                for (row in 0 until rows) for (col in 0 until cols) {
                    val left = m + col * (w + g)
                    val top = m + row * (h + g)
                    canvas.drawBitmap(passportPhoto, null, android.graphics.RectF(left, top, left + w, top + h), paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1f
                    paint.color = 0xFF999999.toInt()
                    canvas.drawRect(left, top, left + w, top + h, paint)
                    paint.style = Paint.Style.FILL
                    paint.color = android.graphics.Color.BLACK
                }
                val jpgValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "DocNexa_Passport_Photos_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DocNexa")
                }
                val jpgUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, jpgValues) ?: error("Could not create photo sheet")
                contentResolver.openOutputStream(jpgUri).use { output ->
                    if (output == null) error("Could not save photo sheet")
                    sheet.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }
                val doc = PdfDocument()
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
                page.canvas.drawColor(android.graphics.Color.WHITE)
                val printBitmap = Bitmap.createScaledBitmap(sheet, pageWidth, pageHeight, true)
                page.canvas.drawBitmap(printBitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
                val pdfBytes = ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
                doc.close()
                val pdfUri = savePdfBytes(pdfBytes, "Passport_Photo_Sheet")
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Passport Photo Sheet Ready")
                        .setMessage("Photo sheet saved to Gallery and PDF saved to Downloads/DocumentScanner. Check print scaling before using for official documents.")
                        .setPositiveButton("Open PDF") { _, _ -> if (pdfUri != null) openPdf(pdfUri) }
                        .setNeutralButton("Share JPG") { _, _ ->
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, jpgUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(send, "Share passport photo sheet"))
                        }.setNegativeButton("Done", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Passport photo sheet failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showImageImportPreview(uris: List<Uri>) {
        val orderedUris = uris.toMutableList()
        val croppedBitmaps = MutableList<Bitmap?>(uris.size) { null }
        val previewList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val header = label("", 13f, 0xFF263957.toInt(), true)
        previewList.addView(header)
        previewList.addView(gap(10))
        val rows = mutableListOf<View>()
        fun refreshHeader() { header.text = "${orderedUris.size} image(s) selected • Arrange pages before export" }
        fun refreshRows() {
            rows.forEachIndexed { index, view ->
                val row = view as LinearLayout
                (row.getChildAt(1) as TextView).text = "Page ${index + 1}"
                val actions = row.getChildAt(2) as LinearLayout
                actions.getChildAt(0).isEnabled = index > 0
                actions.getChildAt(1).isEnabled = index < rows.lastIndex
            }
            refreshHeader()
        }
        fun movePage(from: Int, to: Int) {
            if (from !in orderedUris.indices || to !in orderedUris.indices) return
            val uri = orderedUris.removeAt(from); orderedUris.add(to, uri)
            val bitmap = croppedBitmaps.removeAt(from); croppedBitmaps.add(to, bitmap)
            val view = rows.removeAt(from); rows.add(to, view)
            previewList.removeView(view)
            previewList.addView(view, to + 2)
            refreshRows()
        }
        refreshHeader()
        uris.forEachIndexed { index, uri ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(0xFFF7F9FD.toInt(), 14, 0xFFE2EAF5.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, dp(278)).apply { bottomMargin = dp(12) }
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(0xFFFFFFFF.toInt(), 10, 0xFFDCE7FA.toInt())
                clipToOutline = true
                try { contentResolver.openInputStream(uri).use { input -> setImageBitmap(if (input != null) BitmapFactory.decodeStream(input) else null) } }
                catch (_: Exception) { setImageResource(android.R.drawable.ic_menu_report_image) }
                layoutParams = LinearLayout.LayoutParams(dp(170), dp(164))
            }
            row.addView(thumb)
            row.addView(label("Page ${index + 1}", 13f, 0xFF263957.toInt(), true))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            fun action(caption: String, callback: () -> Unit) = Button(this@MainActivity).apply {
                text = caption; isAllCaps = false; textSize = 10f; setPadding(dp(3), 0, dp(3), 0)
                setOnClickListener { callback() }
            }
            actions.addView(action("↑") { val p = rows.indexOf(row); movePage(p, p - 1) })
            actions.addView(action("↓") { val p = rows.indexOf(row); movePage(p, p + 1) })
            actions.addView(action("Rotate") {
                val p = rows.indexOf(row)
                try {
                    val source = croppedBitmaps[p] ?: contentResolver.openInputStream(orderedUris[p]).use { input -> if (input != null) BitmapFactory.decodeStream(input) else null }
                        ?: throw IllegalStateException("Could not read image")
                    val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                    if (croppedBitmaps[p] != null && croppedBitmaps[p] !== rotated) source.recycle()
                    croppedBitmaps[p] = rotated
                    thumb.setImageBitmap(rotated)
                } catch (e: Exception) { Toast.makeText(this@MainActivity, e.localizedMessage ?: "Rotate failed", Toast.LENGTH_LONG).show() }
            })
            actions.addView(action("Crop") {
                val p = rows.indexOf(row)
                try {
                    val original = croppedBitmaps[p] ?: contentResolver.openInputStream(orderedUris[p]).use { input -> if (input != null) BitmapFactory.decodeStream(input) else null }
                        ?: throw IllegalStateException("Could not open image")
                    val cropView = ImageCropView(this@MainActivity, original)
                    val panel = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
                        addView(cropView, LinearLayout.LayoutParams(-1, dp(330)))
                        addView(label("Drag the corner handles to select the crop area.", 12f, 0xFF53627A.toInt()))
                    }
                    val cropDialog = AlertDialog.Builder(this@MainActivity).setTitle("Crop page ${p + 1}").setView(panel)
                        .setPositiveButton("Apply crop", null).setNeutralButton("Rotate", null).setNegativeButton("Reset", null).create()
                    cropDialog.setOnShowListener {
                        cropDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            croppedBitmaps[p] = cropView.getCroppedBitmap()
                            thumb.setImageBitmap(croppedBitmaps[p])
                            cropDialog.dismiss()
                        }
                        cropDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { cropView.rotateImage() }
                        cropDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { cropView.resetCrop() }
                    }
                    cropDialog.show()
                } catch (e: Exception) { Toast.makeText(this@MainActivity, e.localizedMessage ?: "Crop failed", Toast.LENGTH_LONG).show() }
            })
            actions.addView(action("Remove") {
                val p = rows.indexOf(row)
                if (p >= 0) {
                    orderedUris.removeAt(p)
                    croppedBitmaps.removeAt(p)
                    rows.removeAt(p)
                    previewList.removeView(row)
                    refreshRows()
                }
            })
            row.addView(actions)
            rows.add(row)
            previewList.addView(row)
        }
        previewList.addView(label("Use ↑ / ↓ to change page order. Crop, rotate or remove any image before making your PDF.", 11f, 0xFF718096.toInt()))
        val scroll = ScrollView(this).apply { addView(previewList); isFillViewport = false }
        val dialog = AlertDialog.Builder(this).setTitle("Image to PDF • Page manager").setView(scroll)
            .setPositiveButton("Next: PDF settings", null)
            .setNeutralButton("Share images") { _, _ -> shareSelectedImages(orderedUris) }
            .setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (orderedUris.isEmpty()) Toast.makeText(this, "Select at least one image.", Toast.LENGTH_LONG).show()
                else { dialog.dismiss(); showImagePdfSettings(orderedUris.toList(), croppedBitmaps.toList()) }
            }
        }
        dialog.show()
    }

    private fun showImagePdfSettings(uris: List<Uri>, croppedBitmaps: List<Bitmap?>) {
        val fileName = EditText(this).apply { hint = "PDF file name"; setSingleLine(true); setText("Images_to_PDF") }
        val pageSize = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("A4", "Letter", "A5"))
        }
        val orientationSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Portrait", "Landscape", "Auto fit image"))
        }
        val fit = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Fit entire image", "Fill page (may crop edges)"))
        }
        val margin = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Small", "Normal", "Large"))
            setSelection(1)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(label("${uris.size} image(s) • one image per PDF page", 11f, 0xFF718096.toInt()))
            addView(gap(8)); addView(label("File name", 11f, 0xFF263957.toInt(), true)); addView(fileName)
            addView(gap(7)); addView(label("Page size", 11f, 0xFF263957.toInt(), true)); addView(pageSize)
            addView(gap(7)); addView(label("Orientation", 11f, 0xFF263957.toInt(), true)); addView(orientationSpinner)
            addView(gap(7)); addView(label("Image placement", 11f, 0xFF263957.toInt(), true)); addView(fit)
            addView(gap(7)); addView(label("Page margins", 11f, 0xFF263957.toInt(), true)); addView(margin)
        }
        showPremiumDialog(AlertDialog.Builder(this).setTitle("Image to PDF • Export settings")
            .setView(panel)
            .setNegativeButton("Back", null)
            .setPositiveButton("Create PDF") { _, _ ->
                val base = fileName.text.toString().trim().replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').take(60).ifBlank { "Images_to_PDF" }
                val sizes = listOf(595 to 842, 612 to 792, 420 to 595)
                val selected = sizes[pageSize.selectedItemPosition.coerceIn(0, 2)]
                val landscape = orientationSpinner.selectedItemPosition == 1
                val autoFit = orientationSpinner.selectedItemPosition == 2
                val width = if (landscape) selected.second else selected.first
                val height = if (landscape) selected.first else selected.second
                val margins = listOf(20, 40, 64)[margin.selectedItemPosition.coerceIn(0, 2)]
                createPdfFromImages(uris, croppedBitmaps, base, width, height, margins, fit.selectedItemPosition == 1, autoFit)
            })
    }

    private fun shareSelectedImages(uris: List<Uri>) {
        try {
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share selected images"))
        } catch (e: Exception) {
            Toast.makeText(this, e.localizedMessage ?: "Unable to share selected images", Toast.LENGTH_LONG).show()
        }
    }

    private fun createPdfFromImages(
        uris: List<Uri>,
        croppedBitmaps: List<Bitmap?> = emptyList(),
        fileName: String = "Images_to_PDF",
        pageWidth: Int = 595,
        pageHeight: Int = 842,
        margin: Int = 40,
        fillPage: Boolean = false,
        autoFit: Boolean = false
    ) {
        status.text = "Creating PDF from ${uris.size} image(s)…"
        Thread {
            var document: PdfDocument? = null
            try {
                document = PdfDocument()
                uris.forEachIndexed { index, uri ->
                    val bitmap = croppedBitmaps.getOrNull(index) ?: (contentResolver.openInputStream(uri).use { input ->
                        if (input == null) null else BitmapFactory.decodeStream(input)
                    }) ?: throw IllegalStateException("Could not read selected image")
                    val w = if (autoFit && bitmap.width > bitmap.height) pageHeight else pageWidth
                    val h = if (autoFit && bitmap.width > bitmap.height) pageWidth else pageHeight
                    val pageInfo = PdfDocument.PageInfo.Builder(w, h, index + 1).create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawColor(android.graphics.Color.WHITE)
                    val availableW = (w - margin * 2).coerceAtLeast(1)
                    val availableH = (h - margin * 2).coerceAtLeast(1)
                    val bounds = if (fillPage) {
                        val scale = maxOf(availableW.toFloat() / bitmap.width, availableH.toFloat() / bitmap.height)
                        val outW = (bitmap.width * scale).toInt()
                        val outH = (bitmap.height * scale).toInt()
                        val left = (w - outW) / 2
                        val top = (h - outH) / 2
                        Rect(left, top, left + outW, top + outH)
                    } else fitRect(bitmap.width, bitmap.height, availableW, availableH, margin, margin)
                    page.canvas.drawBitmap(bitmap, null, bounds, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    document.finishPage(page)
                    bitmap.recycle()
                }
                val output = savePdfBytes(ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray(), fileName)
                runOnUiThread {
                    lastPdf = output
                    status.text = if (output != null) "PDF created • ${uris.size} page(s)" else "PDF save failed"
                    recent.text = "Latest: ${output?.lastPathSegment ?: "PDF"}"
                    if (output != null) {
                        buildScreen()
                        AlertDialog.Builder(this)
                            .setTitle("PDF created successfully")
                            .setMessage("${uris.size} image page(s) saved as ${output.lastPathSegment}.")
                            .setPositiveButton("Open PDF") { _, _ -> openPdf(output) }
                            .setNeutralButton("Share PDF") { _, _ -> sharePdf(output) }
                            .setNegativeButton("Save As") { _, _ ->
                                pendingImagePdfSaveAs = output
                                imagePdfSaveAsPicker.launch(output.lastPathSegment ?: "Images_to_PDF.pdf")
                            }
                            .show()
                    } else Toast.makeText(this, "Could not save PDF. Please try again.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "PDF creation failed"
                    Toast.makeText(this, e.localizedMessage ?: "Unable to create PDF", Toast.LENGTH_LONG).show()
                }
            } finally {
                document?.close()
            }
        }.start()
    }

    private fun fitRect(w: Int, h: Int, maxW: Int, maxH: Int, left: Int, top: Int): Rect {
        val scale = minOf(maxW.toFloat() / w, maxH.toFloat() / h)
        val outW = (w * scale).toInt()
        val outH = (h * scale).toInt()
        val x = left + (maxW - outW) / 2
        val y = top + (maxH - outH) / 2
        return Rect(x, y, x + outW, y + outH)
    }

    private fun savePdf(document: PdfDocument): Uri? {
        val name = "Document_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/DocumentScanner")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri).use { out ->
            if (out == null) throw IllegalStateException("Unable to save PDF")
            document.writeTo(out)
        }
        assignPdf(uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
        }
        return uri
    }

    private fun copyPdfToDownloads(source: Uri): Uri? {
        return try {
            val name = "Document_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/DocumentScanner")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            contentResolver.openInputStream(source).use { input ->
                contentResolver.openOutputStream(dest).use { output ->
                    if (input == null || output == null) throw IllegalStateException("Unable to copy PDF")
                    input.copyTo(output)
                }
            }
            assignPdf(dest)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                contentResolver.update(dest, done, null, null)
            }
            dest
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun runOcr(uri: Uri) {
        status.text = "Recognizing text…"
        try {
            val image = InputImage.fromFilePath(this, uri)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(image)
                .addOnSuccessListener { result ->
                    status.text = "OCR complete"
                    val extracted = result.text.ifBlank { "No readable text found." }
                    val textPanel = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        background = rounded(0xFFF5F8FF.toInt(), 16)
                    }
                    textPanel.addView(label("TEXT RECOGNITION RESULT", 10f, 0xFF3478F6.toInt(), true))
                    textPanel.addView(gap(8))
                    val resultView = TextView(this).apply {
                        text = extracted
                        textSize = 14f
                        setTextColor(0xFF172B4D.toInt())
                        setTextIsSelectable(true)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = rounded(0xFFFFFFFF.toInt(), 12, 0xFFE2EAF5.toInt())
                    }
                    val resultScroll = ScrollView(this).apply {
                        addView(resultView)
                        layoutParams = LinearLayout.LayoutParams(-1, dp(330))
                    }
                    textPanel.addView(resultScroll)
                    showPremiumDialog(AlertDialog.Builder(this)
                        .setTitle("DocNexa • Extracted Text")
                        .setView(textPanel)
                        .setPositiveButton("Copy text") { _, _ ->
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DocNexa OCR text", extracted))
                            Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show()
                        }
                        .setNeutralButton("Share text", null)
                        .setNegativeButton("Close", null)
                    ).apply {
                        getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "Text extracted with DocNexa")
                                .putExtra(Intent.EXTRA_TEXT, extracted)
                            startActivity(Intent.createChooser(send, "Share extracted text"))
                        }
                    }
                }
                .addOnFailureListener { e ->
                    status.text = "OCR failed"
                    Toast.makeText(this, e.localizedMessage ?: "OCR error", Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            status.text = "Could not read selected image"
            Toast.makeText(this, e.localizedMessage ?: "Image error", Toast.LENGTH_LONG).show()
        }
    }

    private fun showImportFileHub() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(4))
            background = rounded(0xFFF5F8FF.toInt(), 18)
        }
        panel.addView(label("Choose what you want to bring into DocNexa. Your selected source file is not modified.", 12f, 0xFF64748B.toInt()))
        panel.addView(gap(12))
        fun option(title: String, detail: String, symbol: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rounded(0xFFFFFFFF.toInt(), 14, 0xFFE0E8F5.toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            row.addView(label(symbol, 20f, 0xFF2674D9.toInt(), true).apply {
                gravity = Gravity.CENTER
                background = rounded(0xFFEAF2FF.toInt(), 12)
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            })
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            texts.addView(label(title, 14f, 0xFF1D3557.toInt(), true))
            texts.addView(gap(2))
            texts.addView(label(detail, 10.5f, 0xFF718096.toInt()))
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("›", 23f, 0xFF718096.toInt()))
            panel.addView(row)
            panel.addView(gap(8))
        }
        val dialog = showPremiumDialog(AlertDialog.Builder(this)
            .setTitle("Import File • DocNexa")
            .setView(panel)
            .setNegativeButton("Close", null))
        option("Open a PDF", "Browse phone storage, Downloads or cloud files", "PDF") {
            dialog.dismiss()
            importPdfPicker.launch(arrayOf("application/pdf"))
        }
        option("Merge multiple PDFs", "Select two or more PDFs and arrange them in one file", "⇄") {
            dialog.dismiss()
            mergePicker.launch(arrayOf("application/pdf"))
        }
        option("Compress a PDF", "Choose a PDF and select compression quality", "↓") {
            dialog.dismiss()
            compressPicker.launch(arrayOf("application/pdf"))
        }
        option("Convert PDF to Word", "Create an editable DOCX copy", "W") {
            dialog.dismiss()
            pdfToWordPicker.launch(arrayOf("application/pdf"))
        }
        option("View PDF details", "Check pages, file size and document metadata", "ⓘ") {
            dialog.dismiss()
            detailsPicker.launch(arrayOf("application/pdf"))
        }
        option("Split PDF", "Extract selected page ranges into a new PDF", "✂") {
            dialog.dismiss()
            splitPicker.launch(arrayOf("application/pdf"))
        }
    }

    private fun openPdf(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(intent) }
        catch (_: Exception) {
            Toast.makeText(this, "No PDF viewer found. Your PDF is still saved.", Toast.LENGTH_LONG).show()
        }
    }
}
