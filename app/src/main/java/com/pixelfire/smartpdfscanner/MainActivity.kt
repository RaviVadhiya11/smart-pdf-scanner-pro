package com.pixelfire.smartpdfscanner

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lastPdf = uri
            openPdf(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(textValue: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    private fun gap(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun card(title: String, subtitle: String, symbol: String, action: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(16), dp(15), dp(16))
            setBackgroundColor(0xFF17233A.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            addView(label(symbol, 25f, 0xFF83DCD7.toInt(), true))
            addView(gap(10))
            addView(label(title, 15f, 0xFFF5F7FF.toInt(), true))
            addView(gap(4))
            addView(label(subtitle, 11f, 0xFFA8B5CC.toInt()))
        }
    }

    private fun buildScreen() {
        val bg = 0xFF0A1020.toInt()
        val white = 0xFFF5F7FF.toInt()
        val muted = 0xFFA8B5CC.toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("DOCUMENT SCANNER", 22f, white, true))
        brand.addView(gap(4))
        brand.addView(label("Scan • Organize • Share", 12f, muted))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("● PRO", 11f, 0xFF63D9D1.toInt(), true))
        content.addView(header)
        content.addView(gap(24))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            setBackgroundColor(0xFF202B49.toInt())
        }
        hero.addView(label("PAPERLESS WORKSPACE", 11f, 0xFF72E0D6.toInt(), true))
        hero.addView(gap(10))
        hero.addView(label("Turn paper into\nclear digital documents", 25f, white, true))
        hero.addView(gap(8))
        hero.addView(label("Auto edge detection • Smart crop • PDF export", 12f, muted))
        hero.addView(gap(18))
        hero.addView(Button(this).apply {
            text = "＋   Scan document"
            isAllCaps = false
            textSize = 15f
            setTextColor(white)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF7865F5.toInt())
            setOnClickListener { startDocumentScan() }
        }, LinearLayout.LayoutParams(-1, dp(54)))
        content.addView(hero)
        content.addView(gap(22))
        content.addView(label("QUICK TOOLS", 13f, muted, true))
        content.addView(gap(12))

        addToolRow(content,
            card("Import images", "Select multiple photos → PDF", "▧") {
                imagePicker.launch(arrayOf("image/*"))
            },
            card("My documents", "Browse and open saved PDFs", "▤") {
                pdfPicker.launch(arrayOf("application/pdf"))
            })
        content.addView(gap(10))
        addToolRow(content,
            card("OCR text", "Extract text from an image", "T") {
                ocrPicker.launch("image/*")
            },
            card("PDF tools", "Create PDF from gallery images", "PDF") {
                imagePicker.launch(arrayOf("image/*"))
            })
        content.addView(gap(18))

        status = label("Ready to scan", 14f, white, true)
        content.addView(status)
        content.addView(gap(6))
        recent = label("PDF files are saved in Downloads/DocumentScanner.", 12f, muted)
        content.addView(recent)
        content.addView(gap(20))
        content.addView(label("PRIVATE BY DESIGN", 11f, 0xFF63D9D1.toInt(), true).apply {
            gravity = Gravity.CENTER
        })
        content.addView(gap(5))
        content.addView(label("Document scanning and OCR are processed on your device.", 11f, muted).apply {
            gravity = Gravity.CENTER
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(content)
        })
    }

    private fun addToolRow(parent: LinearLayout, first: View, second: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(first, LinearLayout.LayoutParams(0, dp(142), 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        row.addView(second, LinearLayout.LayoutParams(0, dp(142), 1f))
        parent.addView(row)
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
