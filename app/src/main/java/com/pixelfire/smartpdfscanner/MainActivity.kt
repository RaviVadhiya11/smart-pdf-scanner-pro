package com.pixelfire.smartpdfscanner

import android.content.ContentValues
import android.content.Intent
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
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
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
                recent.text = "Latest file: ${lastPdf?.lastPathSegment ?: "PDF"}"
                Toast.makeText(this, "PDF saved in Downloads/DocumentScanner", Toast.LENGTH_LONG).show()
            } else status.text = "No PDF was returned."
        } else status.text = "Scan cancelled."
    }

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lastPdf = uri
            recent.text = "Selected: ${uri.lastPathSegment ?: "PDF document"}"
            openPdf(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    private fun gap(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun card(title: String, subtitle: String, symbol: String, action: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(16), dp(15), dp(16))
            setBackgroundColor(0xFF17233A.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        card.addView(label(symbol, 25f, 0xFF83DCD7.toInt(), true))
        card.addView(gap(10))
        card.addView(label(title, 15f, 0xFFF5F7FF.toInt(), true))
        card.addView(gap(4))
        card.addView(label(subtitle, 11f, 0xFFA8B5CC.toInt()))
        return card
    }

    private fun buildScreen() {
        val bg = 0xFF0A1020.toInt()
        val white = 0xFFF5F7FF.toInt()
        val muted = 0xFFA8B5CC.toInt()
        val accent = 0xFF7865F5.toInt()

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
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnClickListener { startDocumentScan() }
        }, LinearLayout.LayoutParams(-1, dp(54)))
        content.addView(hero)
        content.addView(gap(22))

        content.addView(label("QUICK TOOLS", 13f, muted, true))
        content.addView(gap(12))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(card("Import images", "Scan from gallery", "▧") { startDocumentScan() },
            LinearLayout.LayoutParams(0, dp(142), 1f))
        row1.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        row1.addView(card("My documents", "Open saved PDFs", "▤") { pdfPicker.launch(arrayOf("application/pdf")) },
            LinearLayout.LayoutParams(0, dp(142), 1f))
        content.addView(row1)
        content.addView(gap(10))

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(card("OCR text", "Extract text from scans", "T") {
            Toast.makeText(this, "OCR is planned for the next update.", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(142), 1f))
        row2.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        row2.addView(card("PDF tools", "Convert • Compress • Merge", "PDF") {
            Toast.makeText(this, "PDF tools are planned for the next update.", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(142), 1f))
        content.addView(row2)
        content.addView(gap(18))

        status = label("Ready to scan", 14f, white, true)
        content.addView(status)
        content.addView(gap(6))
        recent = label("Your scanned PDFs are saved in Downloads/DocumentScanner.", 12f, muted)
        content.addView(recent)
        content.addView(gap(20))
        content.addView(label("PRIVATE BY DESIGN", 11f, 0xFF63D9D1.toInt(), true).apply {
            gravity = Gravity.CENTER
        })
        content.addView(gap(5))
        content.addView(label("Document scanning is handled on your device by Google ML Kit.", 11f, muted).apply {
            gravity = Gravity.CENTER
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(content)
        }
        setContentView(scroll)
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
                    if (input == null || output == null) error("Unable to write PDF")
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

    private fun openPdf(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(intent) }
        catch (_: Exception) { Toast.makeText(this, "Install a PDF viewer to open this file.", Toast.LENGTH_LONG).show() }
    }
}
