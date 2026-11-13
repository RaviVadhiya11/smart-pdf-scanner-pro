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
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TextView
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
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    fun clear() { path.reset(); invalidate() }
    fun toBitmap(): Bitmap {
        val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it); c.drawColor(android.graphics.Color.WHITE); c.drawPath(path, paint)
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawPath(path, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { path.moveTo(event.x, event.y); invalidate(); return true }
            MotionEvent.ACTION_MOVE -> { path.lineTo(event.x, event.y); invalidate(); return true }
            MotionEvent.ACTION_UP -> { path.lineTo(event.x, event.y); invalidate(); return true }
        }
        return true
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var recent: TextView
    private var lastPdf: Uri? = null
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
        if (uris.isNotEmpty()) createPdfFromImages(uris)
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
    private val compressPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) compressPdf(uri) }
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

        window.statusBarColor = bg
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
            background = rounded(0xFFFFFFFF.toInt(), 20)
            elevation = dp(2).toFloat()
        }
        header.addView(TextView(this).apply {
            text = "☰"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(navy)
            setPadding(0, 0, dp(10), 0)
            setOnClickListener { showAppDrawer() }
        })
        header.addView(TextView(this).apply {
            text = "▣"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(blue)
            background = rounded(paleBlue, 14)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        })
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brand.addView(label("Document Scanner", 19f, navy, true))
        brand.addView(label("Scan • Convert • Edit • Share", 11f, muted))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "♛"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFB82E.toInt())
            setOnClickListener { showComingSoon("Premium") }
        })
        header.addView(TextView(this).apply {
            text = "⌕"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(navy)
            setPadding(dp(12), 0, dp(4), 0)
            setOnClickListener { showComingSoon("Search documents") }
        })
        // Keep the brand header outside the ScrollView so it stays fixed while content moves.
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(18)
            rightMargin = dp(18)
            topMargin = dp(12)
            bottomMargin = dp(12)
        })

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
        quickItem("▣ ID Photo") { imagePicker.launch(arrayOf("image/*")) }
        quickItem("PDF Editor") { pdfEditorPicker.launch(arrayOf("application/pdf")) }
        quickItem("✍ Signature") { openSignatureEditor() }
        quickItem("OCR Image") { ocrPicker.launch("image/*") }
        quickItem("▤ Trash") { showTrashScreen() }
        quickAccessScroll.addView(quickAccess)
        content.addView(quickAccessScroll)
        content.addView(gap(16))

        // Large scan hero card.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(0xFFE5EFFF.toInt(), 22)
        }
        hero.addView(label("SMART DOCUMENT WORKSPACE", 11f, blue, true))
        hero.addView(gap(10))
        hero.addView(label("Scan documents.\nKeep everything organized.", 25f, navy, true))
        hero.addView(gap(8))
        hero.addView(label("Auto capture • Crop • Enhance • PDF export", 12f, muted))
        hero.addView(gap(16))
        val scanButton = Button(this).apply {
            text = "▣   Scan Document"
            isAllCaps = false
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            background = gradient(0xFF4285F4.toInt(), 0xFF1764DE.toInt(), 16)
            backgroundTintList = null
            setOnClickListener { startDocumentScan() }
        }
        hero.addView(scanButton, LinearLayout.LayoutParams(-1, dp(56)))
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
                    3 -> ({ pdfPicker.launch(arrayOf("application/pdf")) })
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
            val adSlot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(0xFFFFFFFF.toInt(), 14, 0xFFE8EDF5.toInt())
                minimumHeight = dp(92)
            }
            adSlot.addView(label("ADVERTISEMENT  •  Ad space", 11f, muted, true))
            adSlot.addView(gap(5))
            adSlot.addView(label("Banner ad placement", 10f, muted))
            content.addView(adSlot)
        }
        content.addView(gap(16))
        content.addView(label("MY DOCUMENTS", 15f, navy, true))
        content.addView(gap(8))
        homeFolders.forEach { folder -> content.addView(folderCard(folder)) }
        val savedDocs = getSavedPdfs()
        content.addView(label("Documents (" + savedDocs.size + ")", 14f, navy, true))
        content.addView(gap(8))
        if (savedDocs.isEmpty()) content.addView(label("Your scanned PDFs will appear here automatically.", 11f, muted))
        else savedDocs.take(20).forEach { item -> content.addView(documentCard(item.first, item.second)) }
        content.addView(gap(16))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        addBottomAdAndActions(root)
        setScreenContent(root)
    }

    private fun addBottomAdAndActions(root: LinearLayout) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setBackgroundColor(0xFFF5F8FF.toInt())
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
        bar.addView(camera, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(6) })
        bar.addView(import, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) })
        root.addView(bar)
        root.addView(TextView(this).apply {
            text = "ADVERTISEMENT  •  Ad space"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(0xFF8994A5.toInt())
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, dp(48))
        })
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
        if (trashed.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Trash").setMessage("Trash is empty.")
                .setPositiveButton("OK", null).show()
            return
        }
        val names = trashed.map { Uri.parse(it).lastPathSegment ?: "Deleted PDF" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Trash • " + trashed.size + " item(s)")
            .setItems(names) { _, index ->
                val target = Uri.parse(trashed[index])
                AlertDialog.Builder(this).setTitle("Trash item")
                    .setItems(arrayOf("Restore", "Delete permanently")) { _, action ->
                        val set = (prefs.getStringSet("trash_uris", emptySet()) ?: emptySet()).toMutableSet()
                        if (action == 0) {
                            set.remove(target.toString())
                            prefs.edit().putStringSet("trash_uris", set).apply()
                            Toast.makeText(this, "PDF restored", Toast.LENGTH_SHORT).show()
                        } else {
                            try { contentResolver.delete(target, null, null) } catch (_: Exception) {}
                            set.remove(target.toString())
                            prefs.edit().putStringSet("trash_uris", set).apply()
                            Toast.makeText(this, "Deleted permanently", Toast.LENGTH_SHORT).show()
                        }
                        showTrashScreen()
                    }.setNegativeButton("Cancel", null).show()
            }.setPositiveButton("Close", null).show()
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
        account.addView(TextView(this).apply {
            text = "◉"; textSize = 42f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(62), dp(62)))
        val accountText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        accountText.addView(label("Sign In (Cloud)", 18f, 0xFFFFFFFF.toInt(), true))
        accountText.addView(gap(5))
        accountText.addView(label("Sign in to sync documents", 13f, 0xFFE5EDF3.toInt()))
        account.addView(accountText)
        menu.addView(account)

        val premium = TextView(this).apply {
            text = "🏅   Upgrade to Premium\n       Unlock Full Features and Go Ads Free!"
            textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(17), dp(12), dp(17))
            background = gradient(0xFF08A96B.toInt(), 0xFF31B88D.toInt(), 0)
            setOnClickListener { drawer.dismiss(); showComingSoon("Premium") }
        }
        menu.addView(premium)

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
        item("♡", "Favourite") { showComingSoon("Favourite") }
        item("◇", "Manage tags") { manageTagsDialog() }
        item("▤", "Trash") { showTrashScreen() }
        item("⚙", "Settings") { showSettingsDialog() }
        separator()
        section("Tools")
        item("⚒", "PDF Tools") { showPdfTools() }
        item("▣", "Edit/Create PDF") { imagePicker.launch(arrayOf("image/*")) }
        item("✍", "Signature") { openSignatureEditor() }
        item("▦", "QR Scanner") { showQrScanner() }
        item("▦", "QR Generator") { showComingSoon("QR Generator") }
        separator()
        section("Other")
        item("?", "Help and FAQ") {
            AlertDialog.Builder(this).setTitle("Help and FAQ")
                .setMessage("Scan documents, import pictures, create PDFs, manage files and use PDF tools.")
                .setPositiveButton("OK", null).show()
        }
        item("✉", "Contact us / Report a bug") { shareApp() }
        item("ⓘ", "About us") {
            AlertDialog.Builder(this).setTitle("About DocNexa")
                .setMessage("DocNexa – PDF Scanner & Editor").setPositiveButton("OK", null).show()
        }
        section("More")
        item("↗", "Share with friends") { shareApp() }
        item("★", "Rate us") { Toast.makeText(this, "Thank you for supporting DocNexa!", Toast.LENGTH_SHORT).show() }

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
    private fun manageTagsDialog() {
        val field = android.widget.EditText(this).apply { hint = "Enter a tag name" }
        AlertDialog.Builder(this).setTitle("Manage tags").setView(field)
            .setPositiveButton("Save") { _, _ ->
                val tag = field.text.toString().trim()
                if (tag.isNotEmpty()) Toast.makeText(this, "Tag saved: $tag", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this).setTitle("Settings")
            .setItems(arrayOf("App information", "Storage location", "Share app")) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this).setMessage("Document Scanner Pro").setPositiveButton("OK", null).show()
                    1 -> Toast.makeText(this, "PDFs are saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
                    2 -> shareApp()
                }
            }.show()
    }

    private fun shareApp() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Try Document Scanner Pro.")
        startActivity(Intent.createChooser(send, "Share app"))
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
            text = "♛   ⌕   ⋮"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { showComingSoon("Document options") }
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
        tabs.addView(tab("▣  Import ID Photo", false) { imagePicker.launch(arrayOf("image/*")) }, LinearLayout.LayoutParams(0, -2, 1.25f))
        body.addView(tabs)
        body.addView(gap(14))

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filters.addView(label("All docs", 12f, blue, true), LinearLayout.LayoutParams(0, -2, 1f))
        filters.addView(label("◷  Recent     ★  Favourite", 11f, muted))
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
        val documents = getSavedPdfs()
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
        card.addView(TextView(this).apply {
            text = "▤"
            textSize = 32f
            setTextColor(blueForDocs())
            gravity = Gravity.CENTER
            background = rounded(0xFFF0F4F8.toInt(), 8)
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(62))
        })
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        info.addView(label(name, 14f, 0xFF666666.toInt(), true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        info.addView(gap(4))
        info.addView(label("PDF document", 11f, 0xFF888888.toInt()))
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(TextView(this).apply {
            text = "•••"
            textSize = 18f
            setTextColor(0xFF777777.toInt())
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle(name)
                    .setItems(arrayOf("Open PDF", "Edit PDF", "Share PDF", "Move to Trash")) { _, which ->
                        when (which) {
                            0 -> openPdf(uri)
                            1 -> showPdfEditor(uri)
                            2 -> sharePdf(uri)
                            3 -> moveToTrash(uri)
                        }
                    }.show()
            }
        })
        return card
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
            contentResolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
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
        val bg = 0xFFF7F8FA.toInt()
        val ink = 0xFF343A40.toInt()
        val muted = 0xFF777777.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = rounded(0xFFFFFFFF.toInt(), 0)
            elevation = dp(2).toFloat()
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(0xFF1686C8.toInt())
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
            background = rounded(0xFF168BC5.toInt(), 5)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }
        toolbar.addView(fileIcon)
        val heading = label("PDF Tools", 20f, 0xFF666666.toInt(), true)
        heading.setPadding(dp(18), 0, 0, 0)
        toolbar.addView(heading)
        root.addView(toolbar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(18), dp(10), dp(28))
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

    private fun addPdfToolGrid(
        parent: LinearLayout,
        tools: List<PdfTool>,
        titleColor: Int,
        subtitleColor: Int
    ) {
        tools.chunked(4).forEach { rowTools ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            rowTools.forEach { tool ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    setPadding(dp(3), dp(8), dp(3), dp(13))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { tool.action() }
                }
                val circle = TextView(this).apply {
                    text = tool.symbol
                    textSize = 18f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    background = rounded(tool.color, 50)
                    elevation = dp(1).toFloat()
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                }
                item.addView(circle)
                item.addView(gap(5))
                val caption = label(tool.title, 10.5f, subtitleColor)
                caption.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                caption.textAlignment = View.TEXT_ALIGNMENT_CENTER
                item.addView(caption)
                row.addView(item, LinearLayout.LayoutParams(0, dp(86), 1f))
            }
            repeat(4 - rowTools.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            parent.addView(row)
        }
    }

    private fun addPdfSectionTitle(parent: LinearLayout, title: String) {
        val lineColor = 0xFFDADADA.toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(View(this).apply { setBackgroundColor(lineColor) },
            LinearLayout.LayoutParams(0, dp(1), 1f))
        val text = label(title, 15f, 0xFF777777.toInt())
        text.setPadding(dp(10), 0, dp(10), 0)
        row.addView(text)
        row.addView(View(this).apply { setBackgroundColor(lineColor) },
            LinearLayout.LayoutParams(0, dp(1), 1f))
        parent.addView(row)
    }

    private fun extractPdfText(uri: Uri) {
        status.text = "Extracting PDF text…"
        Thread {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("Cannot open PDF")
                val text = input.use { stream -> PDDocument.load(stream).use { doc -> PDFTextStripper().getText(doc) } }
                runOnUiThread {
                    status.text = "PDF text extracted"
                    AlertDialog.Builder(this).setTitle("PDF To Text")
                        .setMessage(text.ifBlank { "No selectable text found. For scanned image PDFs, use Image To Text (OCR)." })
                        .setPositiveButton("Copy") { _, _ ->
                            val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("PDF text", text))
                            Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show()
                        }.setNegativeButton("Close", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "PDF text extraction failed"; Toast.makeText(this, e.localizedMessage ?: "Could not read PDF", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showPdfDetails(uri: Uri) {
        Thread {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("Cannot open PDF")
                val details = input.use { stream -> PDDocument.load(stream).use { doc ->
                    val box = doc.getPage(0).mediaBox
                    "Pages: ${doc.numberOfPages}\nPage 1 size: ${"%.1f".format(box.width)} × ${"%.1f".format(box.height)} pt\nTitle: ${doc.documentInformation.title ?: "—"}\nAuthor: ${doc.documentInformation.author ?: "—"}"
                } }
                runOnUiThread { AlertDialog.Builder(this).setTitle("PDF Details").setMessage(details).setPositiveButton("OK", null).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Could not read PDF details", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun rotatePdfPages(uri: Uri) {
        status.text = "Rotating PDF pages…"
        Thread {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("Cannot open PDF")
                val bytes = input.use { stream -> PDDocument.load(stream).use { doc ->
                    for (page in doc.pages) page.rotation = (page.rotation + 90) % 360
                    ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                } }
                val saved = savePdfBytes(bytes, "Rotated")
                runOnUiThread {
                    if (saved != null) {
                        lastPdf = saved
                        buildScreen()
                        status.text = "All pages rotated 90°"
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
        val field = android.widget.EditText(this).apply { hint = "Type or paste text"; minLines = 5; gravity = Gravity.TOP }
        AlertDialog.Builder(this).setTitle("Text To PDF").setView(field)
            .setPositiveButton("Create PDF") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let { createPdfFromText(it) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun createPdfFromText(value: String) {
        Thread {
            try {
                val doc = PdfDocument()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = android.graphics.Color.BLACK }
                val lines = value.lines().flatMap { line ->
                    val out = mutableListOf<String>(); var current = ""
                    for (word in line.split(" ")) {
                        val next = if (current.isEmpty()) word else "$current $word"
                        if (paint.measureText(next) > 500 && current.isNotEmpty()) { out.add(current); current = word } else current = next
                    }
                    out.add(current); out
                }
                var pageNo = 1
                var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create())
                var y = 55f
                for (line in lines) {
                    if (y > 790f) { doc.finishPage(page); pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create()); y = 55f }
                    page.canvas.drawText(line, 48f, y, paint); y += 19f
                }
                doc.finishPage(page)
                val saved = savePdf(doc); doc.close()
                runOnUiThread {
                    if (saved != null) {
                        lastPdf = saved; buildScreen(); status.text = "Text PDF created"; recent.text = "Latest: ${saved.lastPathSegment}"
                        Toast.makeText(this, "PDF saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
                    } else Toast.makeText(this, "Could not save PDF", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Text PDF failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun openSignatureEditor(targetPdf: Uri? = null) {
        val canvas = SignatureCanvas(this)
        val typed = EditText(this).apply { hint = "Enter signature text"; textSize = 24f }
        val preview = TextView(this).apply {
            text = "Draw your signature in the box"
            textSize = 13f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
        }
        val drawArea = FrameLayout(this).apply {
            setBackgroundColor(0xFFF1F3F5.toInt())
            addView(canvas, FrameLayout.LayoutParams(-1, dp(210)))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(drawArea)
            addView(preview)
            addView(typed, LinearLayout.LayoutParams(-1, -2))
        }
        val tabs = arrayOf("Draw", "Text")
        var textMode = false
        AlertDialog.Builder(this)
            .setTitle("Create Signature")
            .setView(layout)
            .setNeutralButton("Clear") { _, _ -> canvas.clear(); typed.setText("") }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use Signature") { _, _ ->
                val bitmap = if (textMode || typed.text.toString().isNotBlank()) {
                    val value = typed.text.toString().trim()
                    if (value.isBlank()) null else renderTypedSignature(value)
                } else canvas.toBitmap()
                if (bitmap == null) {
                    Toast.makeText(this, "Please draw or type your signature", Toast.LENGTH_LONG).show()
                } else {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    signaturePng = out.toByteArray()
                    bitmap.recycle()
                    if (targetPdf != null) {
                        val png = signaturePng
                        if (png != null) applySignatureToPdf(targetPdf, png)
                    } else pdfPicker.launch(arrayOf("application/pdf"))
                }
            }
            .show()
        // The user can draw directly; entering text switches to a typed signature.
        typed.setOnFocusChangeListener { _, hasFocus -> textMode = hasFocus }
    }

    private fun renderTypedSignature(value: String): Bitmap {
        val bitmap = Bitmap.createBitmap(1000, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 112f
            typeface = android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL)
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
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share PDF"))
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

    private fun compressPdf(uri: Uri) {
        status.text = "Optimizing PDF…"
        Thread {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("Cannot open PDF")
                val bytes = input.use { stream ->
                    PDDocument.load(stream).use { document ->
                        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
                    }
                }
                val saved = savePdfBytes(bytes, "Compressed")
                runOnUiThread {
                    if (saved != null) {
                        lastPdf = saved
                        status.text = "PDF optimized and saved"
                        recent.text = "Latest: ${saved.lastPathSegment}"
                        Toast.makeText(this, "Optimized PDF saved. Size reduction depends on the source PDF.", Toast.LENGTH_LONG).show()
                    } else Toast.makeText(this, "Could not save optimized PDF", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "PDF optimization failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun exportPdfAsWord(uri: Uri) {
        status.text = "Converting PDF text…"
        Thread {
            try {
                val input = contentResolver.openInputStream(uri) ?: error("Cannot open PDF")
                val text = input.use { stream -> PDDocument.load(stream).use { PDFTextStripper().getText(it) } }
                if (text.isBlank()) error("No selectable text found in this PDF")
                val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\n", "<br/>")
                val html = "<html><head><meta charset=\"utf-8\"></head><body style=\"font-family:Arial\">$escaped</body></html>"
                val saved = saveDocumentBytes(html.toByteArray(Charsets.UTF_8), "Converted", "application/msword", "doc")
                runOnUiThread {
                    if (saved != null) {
                        status.text = "Word-compatible document saved"
                        Toast.makeText(this, "Word document saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
                    } else Toast.makeText(this, "Could not save Word document", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "PDF conversion failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
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
        val field = EditText(this).apply { hint = if (remove) "Current PDF password" else "Set PDF password"; inputType = 129 }
        AlertDialog.Builder(this).setTitle(if (remove) "Remove PDF password" else "Protect PDF")
            .setView(field).setPositiveButton(if (remove) "Unlock and save" else "Protect and save") { _, _ ->
                val password = field.text.toString()
                if (password.isBlank()) Toast.makeText(this, "Enter a password", Toast.LENGTH_SHORT).show()
                else processPdfPassword(uri, password, remove)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun processPdfPassword(uri: Uri, password: String, remove: Boolean) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                    val doc = if (remove) PDDocument.load(input, password) else PDDocument.load(input)
                    doc.use {
                        if (!remove) {
                            val policy = StandardProtectionPolicy(password, password, AccessPermission())
                            policy.encryptionKeyLength = 128
                            it.protect(policy)
                        }
                        ByteArrayOutputStream().also { out -> it.save(out) }.toByteArray()
                    }
                }
                val saved = savePdfBytes(bytes, if (remove) "Unlocked" else "Protected")
                runOnUiThread { if (saved != null) { lastPdf = saved; Toast.makeText(this, "PDF saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show(); buildScreen() } }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Password operation failed", Toast.LENGTH_LONG).show() } }
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
        Thread {
            try {
                val bytes = ByteArrayOutputStream()
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("document.pdf"))
                    contentResolver.openInputStream(uri)!!.use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                val saved = saveDocumentBytes(bytes.toByteArray(), "PDF_Archive", "application/zip", "zip")
                runOnUiThread { if (saved != null) Toast.makeText(this, "ZIP saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "PDF to ZIP failed", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun showQrScanner() {
        AlertDialog.Builder(this).setTitle("QR Scanner")
            .setItems(arrayOf("Live automatic camera scan", "Scan QR from gallery image")) { _, which ->
                if (which == 0) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openLiveQrCamera()
                    else qrCameraPermission.launch(Manifest.permission.CAMERA)
                } else qrImagePicker.launch("image/*")
            }.show()
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

    private fun showComingSoon(feature: String) {
        AlertDialog.Builder(this)
            .setTitle(feature)
            .setMessage("This tool is planned for a future update. The available PDF tools are Open PDF, Merge, Split, Image to PDF and Share PDF.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun mergePdfFiles(uris: List<Uri>) {
        status.text = "Merging ${uris.size} PDF files…"
        Thread {
            try {
                val merger = PDFMergerUtility()
                val streams = mutableListOf<java.io.InputStream>()
                try {
                    uris.forEach { uri ->
                        val stream = contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Could not open selected PDF")
                        streams.add(stream)
                        merger.addSource(stream)
                    }
                    val output = ByteArrayOutputStream()
                    merger.destinationStream = output
                    merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
                    val saved = savePdfBytes(output.toByteArray(), "Merged")
                    runOnUiThread {
                        lastPdf = saved
                        status.text = "PDF merge complete"
                        recent.text = "Latest: ${saved?.lastPathSegment ?: "Merged PDF"}"
                        Toast.makeText(this, "Merged PDF saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
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
    }

    private fun splitPdfFile(uri: Uri) {
        status.text = "Splitting PDF…"
        Thread {
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open selected PDF")
                val source = input.use { PDDocument.load(it) }
                val total = source.numberOfPages
                if (total < 2) {
                    source.close()
                    throw IllegalArgumentException("PDF needs at least two pages to split")
                }
                val middle = (total + 1) / 2
                val first = PDDocument()
                val second = PDDocument()
                try {
                    for (i in 0 until middle) first.importPage(source.getPage(i))
                    for (i in middle until total) second.importPage(source.getPage(i))
                    val firstBytes = ByteArrayOutputStream().also { first.save(it) }.toByteArray()
                    val secondBytes = ByteArrayOutputStream().also { second.save(it) }.toByteArray()
                    val savedA = savePdfBytes(firstBytes, "Split_Part_1")
                    val savedB = savePdfBytes(secondBytes, "Split_Part_2")
                    runOnUiThread {
                        status.text = "PDF split into two files"
                        recent.text = "Saved: ${savedA?.lastPathSegment ?: "Part 1"} and ${savedB?.lastPathSegment ?: "Part 2"}"
                        Toast.makeText(this, "Both parts saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    first.close()
                    second.close()
                    source.close()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "PDF split failed"
                    Toast.makeText(this, e.localizedMessage ?: "Could not split PDF", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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

    private fun createPdfFromImages(uris: List<Uri>) {
        status.text = "Creating PDF from ${uris.size} image(s)…"
        Thread {
            var document: PdfDocument? = null
            try {
                document = PdfDocument()
                uris.forEachIndexed { index, uri ->
                    val bitmap = contentResolver.openInputStream(uri).use { input ->
                        if (input == null) null else BitmapFactory.decodeStream(input)
                    } ?: throw IllegalStateException("Could not read selected image")
                    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
                    val page = document.startPage(pageInfo)
                    val bounds = fitRect(bitmap.width, bitmap.height, 515, 762, 40, 40)
                    page.canvas.drawColor(android.graphics.Color.WHITE)
                    page.canvas.drawBitmap(bitmap, null, bounds, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    document.finishPage(page)
                    bitmap.recycle()
                }
                val output = savePdf(document)
                runOnUiThread {
                    lastPdf = output
                    status.text = "PDF created • ${uris.size} page(s)"
                    recent.text = "Latest: ${output?.lastPathSegment ?: "PDF"}"
                    if (output != null) buildScreen()
                    Toast.makeText(this, "PDF saved to Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
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
                    AlertDialog.Builder(this)
                        .setTitle("Extracted text")
                        .setMessage(extracted)
                        .setPositiveButton("Copy") { _, _ ->
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OCR text", extracted))
                            Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Close", null)
                        .show()
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

    private fun openPdf(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(intent) }
        catch (_: Exception) {
            Toast.makeText(this, "No PDF viewer found. Your PDF is still saved.", Toast.LENGTH_LONG).show()
        }
    }
}
