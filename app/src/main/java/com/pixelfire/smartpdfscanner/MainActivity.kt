package com.pixelfire.smartpdfscanner

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var recent: TextView
    private var lastPdf: Uri? = null

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pdf = scan?.pdf
            if (pdf != null) {
                lastPdf = copyPdfToDownloads(pdf.uri)
                status.text = "Scan complete • ${pdf.pageCount} page(s)"
                recent.text = "Latest: ${lastPdf?.lastPathSegment ?: "PDF"}"
                Toast.makeText(this, "Saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
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
            lastPdf = uri
            openPdf(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        buildScreen()
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

    private val homeFolders = mutableListOf("New Folder", "ID Cards", "Bills")

    private fun buildScreen() {
        val bg = 0xFFF7F8FA.toInt()
        val ink = 0xFF1E3B49.toInt()
        val blue = 0xFF078BCB.toInt()
        val muted = 0xFF777777.toInt()

        window.statusBarColor = ink
        window.navigationBarColor = 0xFFFFFFFF.toInt()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // Compact dark-teal app bar, matching the supplied reference.
        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(ink)
        }
        fun topAction(text: String, width: Int = 42, action: (() -> Unit)? = null) =
            TextView(this).apply {
                this.text = text
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(width), dp(48))
                if (action != null) setOnClickListener { action() }
            }
        appBar.addView(topAction("☰", 42) {
            AlertDialog.Builder(this).setTitle("Document Scanner")
                .setItems(arrayOf("Home", "PDF Tools", "Import ID Photo", "About")) { _, which ->
                    when (which) {
                        0 -> buildScreen()
                        1 -> showPdfTools()
                        2 -> imagePicker.launch(arrayOf("image/*"))
                        else -> showComingSoon("About")
                    }
                }.show()
        })
        appBar.addView(label("Doc Scanner", 21f, 0xFFFFFFFF.toInt(), true).apply {
            setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        appBar.addView(topAction("♛", 42) { showComingSoon("Premium") }.apply {
            setTextColor(0xFFFFC928.toInt())
        })
        appBar.addView(topAction("⌕", 42) { showComingSoon("Search documents") })
        appBar.addView(topAction("⋮", 32) {
            AlertDialog.Builder(this).setItems(arrayOf("Refresh", "Settings", "About")) { _, which ->
                if (which == 0) buildScreen() else showComingSoon(if (which == 1) "Settings" else "About")
            }.show()
        })
        root.addView(appBar)

        // Main navigation tabs.
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        fun tab(text: String, selected: Boolean, action: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(17), dp(7), dp(15))
            setTextColor(if (selected) blue else muted)
            if (selected) typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener { action() }
        }
        tabs.addView(tab("⌂  Home", true) { buildScreen() }, LinearLayout.LayoutParams(0, dp(54), 1f))
        tabs.addView(tab("▣  PDF Tools", false) { showPdfTools() }, LinearLayout.LayoutParams(0, dp(54), 1.15f))
        tabs.addView(tab("▤  Import ID Photo", false) {
            imagePicker.launch(arrayOf("image/*"))
        }, LinearLayout.LayoutParams(0, dp(54), 1.45f))
        root.addView(tabs)
        root.addView(View(this).apply { setBackgroundColor(blue) }, LinearLayout.LayoutParams(-1, dp(2)))

        // Scrollable filters row.
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        fun chip(text: String, selected: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else muted)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(if (selected) blue else 0xFFFFFFFF.toInt(), 24,
                if (selected) blue else 0xFFD0D4D8.toInt())
        }
        filters.addView(chip("All docs", true))
        filters.addView(gap(1).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })
        filters.addView(chip("◷  Recent"))
        filters.addView(gap(1).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) })
        filters.addView(chip("★  Favourite"))
        filters.addView(gap(1).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) })
        filters.addView(chip("Business Cards"))
        filterScroll.addView(filters)
        root.addView(filterScroll)
        root.addView(View(this).apply { setBackgroundColor(0xFFE4E7EB.toInt()) },
            LinearLayout.LayoutParams(-1, dp(1)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(16))
        }

        val folderHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(10))
        }
        folderHeading.addView(label("Folders (${homeFolders.size})", 15f, muted, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        folderHeading.addView(TextView(this).apply {
            text = "▣+"
            textSize = 23f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setOnClickListener { createFolderDialog() }
        })
        body.addView(folderHeading)
        homeFolders.forEach { body.addView(folderCard(it)) }

        body.addView(gap(14))
        body.addView(label("Documents (${getSavedPdfNames().size})", 15f, muted, true))
        body.addView(gap(8))
        val docs = getSavedPdfNames()
        if (docs.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(24), dp(16), dp(24))
                background = rounded(0xFFFFFFFF.toInt(), 14, 0xFFE4E7EB.toInt())
            }
            empty.addView(label("▤", 30f, blue, true))
            empty.addView(gap(6))
            empty.addView(label("No documents yet", 14f, ink, true))
            empty.addView(label("Scan a document or import photos to create your first PDF.", 11f, muted))
            body.addView(empty)
        } else {
            docs.forEach { body.addView(documentCard(it)) }
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Fixed bottom scan/import controls. No ad banner or reserved ad space.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(18), dp(10))
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = dp(8).toFloat()
        }
        val camera = Button(this).apply {
            text = "📷  Scan"
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(blue, 28)
            backgroundTintList = null
            setOnClickListener { startDocumentScan() }
        }
        bottom.addView(camera, LinearLayout.LayoutParams(dp(132), dp(48)))
        val add = Button(this).apply {
            text = "＋"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF0879B7.toInt(), 28)
            backgroundTintList = null
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Add document")
                    .setItems(arrayOf("Import images", "Scan document", "Create folder")) { _, which ->
                        when (which) {
                            0 -> imagePicker.launch(arrayOf("image/*"))
                            1 -> startDocumentScan()
                            2 -> createFolderDialog()
                        }
                    }.show()
            }
        }
        val addParams = LinearLayout.LayoutParams(dp(58), dp(48))
        addParams.leftMargin = dp(10)
        bottom.addView(add, addParams)
        root.addView(bottom)

        setContentView(root)
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
            setOnClickListener { showComingSoon("$name folder") }
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
        info.addView(label("Created today   •   0 files", 11f, 0xFF888888.toInt()))
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(TextView(this).apply {
            text = "•••"
            textSize = 18f
            setTextColor(0xFF777777.toInt())
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setItems(arrayOf("Open", "Rename", "Delete")) { _, which ->
                    when (which) {
                        0 -> showComingSoon("$name folder")
                        1 -> renameFolder(name)
                        2 -> deleteFolder(name)
                    }
                }.show()
            }
        })
        return card
    }

    private fun documentCard(name: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = rounded(0xFFFFFFFF.toInt(), 13, 0xFFE5E7EB.toInt())
            elevation = dp(2).toFloat()
            val params = LinearLayout.LayoutParams(-1, dp(86))
            params.bottomMargin = dp(9)
            layoutParams = params
            setOnClickListener { pdfPicker.launch(arrayOf("application/pdf")) }
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
            setOnClickListener { shareLatestPdf() }
        })
        return card
    }

    private fun blueForDocs() = 0xFF078BCB.toInt()

    private fun getSavedPdfNames(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        return try {
            val names = mutableListOf<String>()
            val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ?"
            val args = arrayOf("Download/DocumentScanner/")
            contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args,
                "${MediaStore.Downloads.DATE_ADDED} DESC")?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext() && names.size < 20) {
                    if (index >= 0) names.add(cursor.getString(index))
                }
            }
            names
        } catch (_: Exception) { emptyList() }
    }

    private fun createFolderDialog() {
        val field = android.widget.EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this).setTitle("Create folder").setView(field)
            .setPositiveButton("Create") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty() && !homeFolders.contains(name)) {
                    homeFolders.add(name)
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
                    buildScreen()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deleteFolder(name: String) {
        AlertDialog.Builder(this).setMessage("Delete folder '$name'?")
            .setPositiveButton("Delete") { _, _ -> homeFolders.remove(name); buildScreen() }
            .setNegativeButton("Cancel", null).show()
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
        addPdfToolGrid(body, listOf(
            PdfTool("Open PDF", "▣", 0xFFE9433A.toInt()) { pdfPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Add/Merge PDF Files", "⇲", 0xFFF06A36.toInt()) { mergePicker.launch(arrayOf("application/pdf")) },
            PdfTool("Add Page from Gallery", "▧+", 0xFFB8D52B.toInt()) { imagePicker.launch(arrayOf("image/*")) },
            PdfTool("Split", "↔", 0xFF078F83.toInt()) { splitPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Page size", "↔", 0xFF4CAF50.toInt()) { showComingSoon("Page size") },
            PdfTool("Page Adjust/Rotate/Delete", "✎", 0xFF13A6D8.toInt()) { showComingSoon("Page Adjust / Rotate / Delete") },
            PdfTool("Add Password", "▣", 0xFFED78A5.toInt()) { showComingSoon("Add Password") },
            PdfTool("Remove Password", "◉", 0xFF80516E.toInt()) { showComingSoon("Remove Password") },
            PdfTool("Signature", "S", 0xFFF6B34C.toInt()) { showComingSoon("Signature") }
        ), ink, muted)

        addPdfSectionTitle(body, "PDF To Other Format")
        addPdfToolGrid(body, listOf(
            PdfTool("PDF to Image", "▧", 0xFF82C58A.toInt()) { showComingSoon("PDF to Image") },
            PdfTool("PDF To Text", "T", 0xFF35B5E5.toInt()) { showComingSoon("PDF to Text") },
            PdfTool("PDF To ZIP", "▣", 0xFFE91E63.toInt()) { showComingSoon("PDF to ZIP") }
        ), ink, muted)

        addPdfSectionTitle(body, "Other Format To PDF")
        addPdfToolGrid(body, listOf(
            PdfTool("Image to PDF", "▧", 0xFF795548.toInt()) { imagePicker.launch(arrayOf("image/*")) },
            PdfTool("Text To PDF", "▤", 0xFF9826AC.toInt()) { showComingSoon("Text to PDF") }
        ), ink, muted)

        addPdfSectionTitle(body, "More")
        addPdfToolGrid(body, listOf(
            PdfTool("Share PDF", "↗", 0xFFFFB900.toInt()) {
                lastPdf?.let { uri ->
                    val share = Intent(Intent.ACTION_SEND).setType("application/pdf")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(share, "Share PDF"))
                } ?: pdfPicker.launch(arrayOf("application/pdf"))
            },
            PdfTool("PDF Details", "ⓘ", 0xFF673AB7.toInt()) { showComingSoon("PDF Details") }
        ), ink, muted)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private data class PdfTool(
        val title: String,
        val symbol: String,
        val color: Int,
        val action: () -> Unit
    )

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
                    textSize = 23f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    background = rounded(tool.color, 50)
                    elevation = dp(2).toFloat()
                    layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
                }
                item.addView(circle)
                item.addView(gap(9))
                val caption = label(tool.title, 11.5f, subtitleColor)
                caption.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                caption.textAlignment = View.TEXT_ALIGNMENT_CENTER
                item.addView(caption)
                row.addView(item, LinearLayout.LayoutParams(0, dp(112), 1f))
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
                    for (i in 0 until middle) first.addPage(source.getPage(i))
                    for (i in middle until total) second.addPage(source.getPage(i))
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
