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
                recent.text = "Saved: ${lastPdf?.lastPathSegment ?: "PDF"}"
                Toast.makeText(this, "PDF saved in Downloads/SmartPDFScanner", Toast.LENGTH_LONG).show()
            } else status.text = "No PDF was returned."
        } else status.text = "Scan cancelled."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        val bg = 0xFF080D1B.toInt()
        val panel = 0xFF121B30.toInt()
        val white = 0xFFF4F7FF.toInt()
        val muted = 0xFF9EABC5.toInt()
        val accent = 0xFF8065FF.toInt()

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 34, 24, 24)
            setBackgroundColor(bg)
        }
        page.addView(TextView(this).apply {
            text = "✦  SMART PDF\n     SCANNER PRO"
            textSize = 25f
            setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        page.addView(space(12))
        page.addView(TextView(this).apply {
            text = "Scan documents. Create clean PDFs."
            textSize = 15f
            setTextColor(muted)
        })
        page.addView(space(28))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 22, 20, 22)
            setBackgroundColor(panel)
        }
        hero.addView(TextView(this).apply {
            text = "DOCUMENT SCANNER"
            textSize = 12f
            setTextColor(0xFF31D6D0.toInt())
        })
        hero.addView(space(8))
        hero.addView(TextView(this).apply {
            text = "Turn paper into\nprofessional PDFs"
            textSize = 24f
            setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        hero.addView(space(18))
        hero.addView(Button(this).apply {
            text = "＋  Scan document"
            isAllCaps = false
            setTextColor(white)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnClickListener { startDocumentScan() }
        }, LinearLayout.LayoutParams(-1, 58))
        page.addView(hero)
        page.addView(space(20))

        status = TextView(this).apply {
            text = "Ready to scan"
            textSize = 15f
            setTextColor(white)
        }
        page.addView(status)
        page.addView(space(8))
        recent = TextView(this).apply {
            text = "Scanned PDFs are saved in Downloads/SmartPDFScanner."
            textSize = 13f
            setTextColor(muted)
        }
        page.addView(recent)
        page.addView(space(18))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "Open PDF"
            isAllCaps = false
            setOnClickListener { openLastPdf() }
        }, LinearLayout.LayoutParams(0, 52, 1f))
        actions.addView(Button(this).apply {
            text = "Share PDF"
            isAllCaps = false
            setOnClickListener { shareLastPdf() }
        }, LinearLayout.LayoutParams(0, 52, 1f).apply { leftMargin = 10 })
        page.addView(actions)
        page.addView(space(22))
        page.addView(TextView(this).apply {
            text = "AUTO EDGE DETECTION  •  CROP  •  FILTERS  •  MULTI-PAGE PDF\nProcessed on your device by Google ML Kit."
            textSize = 11f
            setLineSpacing(5f, 1f)
            setTextColor(muted)
            gravity = Gravity.CENTER
        })
        setContentView(page)
    }

    private fun space(dp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, (dp * resources.displayMetrics.density).toInt())
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
            val name = "SmartPDF_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/SmartPDFScanner")
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

    private fun openLastPdf() {
        val uri = lastPdf ?: return Toast.makeText(this, "First scan a document.", Toast.LENGTH_SHORT).show()
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(intent) }
        catch (_: Exception) { Toast.makeText(this, "Install a PDF viewer to open this file.", Toast.LENGTH_LONG).show() }
    }

    private fun shareLastPdf() {
        val uri = lastPdf ?: return Toast.makeText(this, "First scan a document.", Toast.LENGTH_SHORT).show()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share scanned PDF"))
    }
}
