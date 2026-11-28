package com.pixelfire.smartpdfscanner

import android.Manifest
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import android.view.ViewGroup
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
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.google.firebase.messaging.FirebaseMessaging
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
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64


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
    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The signature pad sits inside a ScrollView. Keep the parent from
                // stealing finger movement while the user is drawing a signature.
                parent?.requestDisallowInterceptTouchEvent(true)
                activePath = Path().apply { moveTo(event.x, event.y) }
                activePath?.let { strokes.add(it) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                activePath?.lineTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                activePath?.lineTo(event.x, event.y)
                activePath = null
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                activePath = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
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

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.isEmpty) return false
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
                val minSize = minOf(48f, imageRect.width() * 0.4f, imageRect.height() * 0.4f)
                when (activeCorner) {
                    0 -> { cropRect.left = event.x.coerceIn(imageRect.left, cropRect.right - minSize); cropRect.top = event.y.coerceIn(imageRect.top, cropRect.bottom - minSize) }
                    1 -> { cropRect.right = event.x.coerceIn(cropRect.left + minSize, imageRect.right); cropRect.top = event.y.coerceIn(imageRect.top, cropRect.bottom - minSize) }
                    2 -> { cropRect.left = event.x.coerceIn(imageRect.left, cropRect.right - minSize); cropRect.bottom = event.y.coerceIn(cropRect.top + minSize, imageRect.bottom) }
                    3 -> { cropRect.right = event.x.coerceIn(cropRect.left + minSize, imageRect.right); cropRect.bottom = event.y.coerceIn(cropRect.top + minSize, imageRect.bottom) }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { activeCorner = -1; if (event.action == MotionEvent.ACTION_UP) performClick(); return true }
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
    private var pendingDocumentNotification: String? = null
    private var pendingDocumentUri: Uri? = null
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingDocumentNotification
        pendingDocumentNotification = null
        val uri = pendingDocumentUri
        pendingDocumentUri = null
        if (granted && pending != null && uri != null) postDocumentReadyNotification(pending, uri)
    }

    private fun createDocNexaNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel(
                "important_updates", "Important updates", android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Important DocNexa announcements and service updates" })
            manager.createNotificationChannel(android.app.NotificationChannel(
                "document_status", "Document status", android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Notifications when document work is completed" })
        }
    }

    private fun notifyDocumentReady(uri: Uri) {
        val fileName = uri.lastPathSegment ?: "Your PDF"
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!prefs.getBoolean("notification_permission_asked", false)) {
                pendingDocumentNotification = fileName
                pendingDocumentUri = uri
                prefs.edit().putBoolean("notification_permission_asked", true).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        postDocumentReadyNotification(fileName, uri)
    }

    @SuppressLint("MissingPermission")
    private fun postDocumentReadyNotification(fileName: String, uri: Uri) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.pixelfire.smartpdfscanner.OPEN_NOTIFICATION_PDF"
            putExtra("notification_pdf_uri", uri.toString())
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 2101, openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, "document_status")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Your document is ready")
            .setContentText(fileName)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText("$fileName has been saved in DocNexa."))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        androidx.core.app.NotificationManagerCompat.from(this).notify(2101, notification)
    }

    private val loadedNativeAds = mutableListOf<NativeAd>()
    private var adsConsentAllowed = false
    private var privacyOptionsRequired = false
    private lateinit var status: TextView
    private lateinit var recent: TextView
    private var lastPdf: Uri? = null
    private fun signedInUserId(): String = prefs.getString("cloud_user_id", "")?.takeIf { it.isNotBlank() } ?: "guest"
    private fun accountPrefKey(key: String): String = "${key}_${signedInUserId().replace(Regex("[^A-Za-z0-9_-]"), "_")}"
    private fun privateDocumentsDir(): java.io.File {
        val root = java.io.File(filesDir, "accounts").apply { if (!exists()) mkdirs() }
        val folder = java.io.File(root, signedInUserId().replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { if (!exists()) mkdirs() }
        return folder
    }
    private fun privateDocumentUri(file: java.io.File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    private fun privateDocumentFile(uri: Uri): java.io.File? {
        if (uri.authority != "$packageName.fileprovider") return null
        val segments = uri.pathSegments
        val accountIndex = segments.indexOf("accounts")
        if (accountIndex < 0 || accountIndex + 2 != segments.lastIndex) return null
        val uriAccount = segments[accountIndex + 1]
        val currentAccount = signedInUserId().replace(Regex("[^A-Za-z0-9_-]"), "_")
        if (uriAccount != currentAccount) return null
        val name = segments.lastOrNull() ?: return null
        val directory = privateDocumentsDir()
        return java.io.File(directory, name).takeIf {
            it.isFile && runCatching { it.canonicalFile.parentFile == directory.canonicalFile }.getOrDefault(false)
        }
    }
    private fun saveBytesPrivately(bytes: ByteArray, name: String): Uri? {
        return try {
            val target = PrivateDocumentStore.save(privateDocumentsDir(), bytes, name)
            val uri = privateDocumentUri(target)
            if (target.extension.equals("pdf", true)) assignPdf(uri)
            uri
        } catch (_: Exception) { null }
    }
    private var signaturePng: ByteArray? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val qrAlreadyRead = AtomicBoolean(false)
    private var qrCameraProvider: ProcessCameraProvider? = null
    private var qrScannerClient: com.google.mlkit.vision.barcode.BarcodeScanner? = null
    @Volatile private var qrCameraGeneration = 0

    private fun closeQrCamera() {
        qrCameraGeneration++
        qrCameraProvider?.unbindAll()
        qrScannerClient?.close()
        qrScannerClient = null
    }
    private val qrCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) openLiveQrCamera() else Toast.makeText(this, "Camera permission is required for live QR scanning.", Toast.LENGTH_LONG).show() }

    private val scanExportBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            status.text = "Scan cancelled."
            return@registerForActivityResult
        }
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val pageUris = scan?.pages.orEmpty().map { it.imageUri }
        val scannerPdf = scan?.pdf
        if(pageUris.isEmpty() && scannerPdf==null){
            status.text="No scanned pages were returned."
            return@registerForActivityResult
        }
        if(!scanExportBusy.compareAndSet(false,true))return@registerForActivityResult
        val owner=signedInUserId()
        val directory=privateDocumentsDir()
        val folder=selectedFolder
        val mode=prefs.getInt("scanner_filter_mode",0).coerceIn(0,3)
        val progress=appDialog().setTitle("Preparing your scan")
            .setMessage("Preserving original images…").setCancelable(false).create()
        progress.show()
        status.text="Creating high-quality PDF…"
        fun update(message:String){runOnUiThread{if(!isFinishing&&!isDestroyed&&progress.isShowing)progress.setMessage(message)}}
        Thread {
            var originals=emptyList<Uri>()
            var saved:Uri?=null
            var quality="Print-ready A4"
            val errors=mutableListOf<String>()
            try {
                val stamp=System.currentTimeMillis()
                val stable=pageUris.mapIndexed{index,uri->
                    try{
                        val target=PrivateDocumentStore.save(directory,"DocNexa_Scan_${stamp}_Page_${index+1}.jpg"){stream->
                            (contentResolver.openInputStream(uri)?:error("Cannot read scanned page")).use{input->require(input.copyTo(stream)>0){"Empty scan image"}}
                        }
                        privateDocumentUri(target)
                    }catch(e:Exception){errors.add("Image "+(index+1)+": "+e.javaClass.simpleName);null}
                }
                originals=stable.filterNotNull()
                val sources=pageUris.mapIndexed{index,uri->stable[index]?:uri}
                fun attempt(stage:String,block:()->Uri):Uri?=try{block()}catch(e:Exception){
                    errors.add(stage+": "+e.javaClass.simpleName);android.util.Log.w("DocNexaScan",stage+" failed",e);null
                }catch(e:OutOfMemoryError){
                    errors.add(stage+": low memory");android.util.Log.w("DocNexaScan",stage+" memory limit");null
                }
                if(sources.isNotEmpty()){
                    update("Creating A4 PDF • preserving page detail…")
                    saved=attempt("High quality"){createPrintReadyScanPdf(sources,directory,mode,6000,16_000_000L,stamp)}
                    if(saved==null && mode!=0){
                        update("Retrying without the additional filter…")
                        saved=attempt("Original images"){createPrintReadyScanPdf(sources,directory,0,6000,16_000_000L,stamp)}
                        if(saved!=null)quality="Print-ready A4 • original colors"
                    }
                }
                if(saved==null && scannerPdf!=null){
                    update("Saving the scanner's original PDF…")
                    saved=attempt("Scanner PDF"){copyScannedPdf(scannerPdf.uri,directory,scannerPdf.pageCount,stamp)}
                    if(saved!=null)quality="Original scanner PDF • scanner page size"
                }
                if(saved==null && sources.isNotEmpty()){
                    update("Retrying with memory-safe page resolution…")
                    saved=attempt("Compatibility PDF"){createPrintReadyScanPdf(sources,directory,0,2400,4_000_000L,stamp)}
                    if(saved!=null)quality="Compatibility PDF • reduced resolution"
                }
            }catch(e:Exception){errors.add(e.javaClass.simpleName);android.util.Log.w("DocNexaScan","Scan export failed",e)}
            finally{
                scanExportBusy.set(false)
                val finalPdf=saved
                val imageCount=originals.size
                runOnUiThread{
                    if(isFinishing||isDestroyed)return@runOnUiThread
                    progress.dismiss()
                    if(owner!=signedInUserId())return@runOnUiThread
                    if(finalPdf!=null){
                        lastPdf=finalPdf
                        folderAssignments[finalPdf.toString()]=folder
                        prefs.edit().putString(accountPrefKey("folder_assignments"),folderAssignmentSnapshot()).putBoolean("has_completed_first_scan",true).apply()
                        notifyDocumentReady(finalPdf)
                        buildScreen()
                        showExportSuccess(finalPdf,finalPdf.lastPathSegment?:"Scanned_Document.pdf","application/pdf")
                        Toast.makeText(this,quality+" • "+imageCount+" original image(s) saved",Toast.LENGTH_LONG).show()
                    }else{
                        status.text="PDF save failed"
                        appDialog().setTitle("Scan export could not finish")
                            .setMessage("Original images saved: "+imageCount+". If available, use Image to PDF to recover these pages without scanning again. Check free storage.\n\n"+errors.distinct().take(6).joinToString("\n"))
                            .setPositiveButton("OK",null).show()
                    }
                }
            }
        }.start()
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) showImageImportPreview(uris)
    }

    private val idCardPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) showIdCardImportPreview(uris.take(2))
    }

    private val passportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showPassportPhotoEditor(uri)
    }

    private val searchableOcrPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) createSearchableOcrPdf(uri)
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

    private val allFilesPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            val mime = contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
            when {
                mime.startsWith("image/") -> showImageImportPreview(listOf(uri))
                mime == "application/pdf" || uri.toString().lowercase(Locale.ROOT).endsWith(".pdf") -> {
                    lastPdf = uri
                    openPdf(uri)
                }
                else -> openDocumentViewer(uri)
            }
        }
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

    private val printPdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) printPdfDocument(uri)
    }

    private fun printPdfDocument(uri: Uri, invoiceA4: Boolean = false) {
        try {
            val manager = getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
            val displayName = if(invoiceA4) (privateDocumentFile(uri)?.name ?: "DocNexa Invoice.pdf") else "DocNexa PDF"
            val adapter = object : android.print.PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: android.print.PrintAttributes?,
                    newAttributes: android.print.PrintAttributes?,
                    cancellationSignal: android.os.CancellationSignal,
                    callback: LayoutResultCallback,
                    extras: Bundle?
                ) {
                    if (cancellationSignal.isCanceled) {
                        callback.onLayoutCancelled()
                        return
                    }
                    try {
                        val descriptor = contentResolver.openFileDescriptor(uri, "r")
                            ?: throw java.io.IOException("Cannot open PDF")
                        val count = PdfRenderer(descriptor).use { it.pageCount }
                        val info = android.print.PrintDocumentInfo.Builder(if(invoiceA4) displayName else "DocNexa_Document.pdf")
                            .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(count)
                            .build()
                        callback.onLayoutFinished(info, true)
                    } catch (e: Exception) {
                        callback.onLayoutFailed(e.localizedMessage ?: "Unable to read PDF")
                    }
                }

                override fun onWrite(
                    pages: Array<out android.print.PageRange>,
                    destination: ParcelFileDescriptor,
                    cancellationSignal: android.os.CancellationSignal,
                    callback: WriteResultCallback
                ) {
                    try {
                        if (cancellationSignal.isCanceled) {
                            callback.onWriteCancelled()
                            return
                        }
                        contentResolver.openInputStream(uri).use { input ->
                            if (input == null) throw java.io.IOException("Cannot read selected PDF")
                            PDDocument.load(input).use { source ->
                                val requested = PrintPagePlan.selectedPages(
                                    source.numberOfPages,
                                    pages.map { PrintPagePlan.Range(it.start, it.end) }
                                )
                                if (requested.isEmpty()) {
                                    callback.onWriteFailed("No printable pages were selected.")
                                    return
                                }
                                android.os.ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                                    if (requested.size == source.numberOfPages &&
                                        requested.indices.all { requested[it] == it }) {
                                        source.save(output)
                                    } else {
                                        PDDocument().use { subset ->
                                            requested.forEach { pageIndex ->
                                                if (cancellationSignal.isCanceled) {
                                                    callback.onWriteCancelled()
                                                    return
                                                }
                                                subset.importPage(source.getPage(pageIndex))
                                            }
                                            subset.save(output)
                                        }
                                    }
                                    output.flush()
                                }
                            }
                        }
                        callback.onWriteFinished(pages)
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.localizedMessage ?: "PDF printing failed")
                    }
                }
            }
            val attributes=android.print.PrintAttributes.Builder()
            if(invoiceA4) attributes.setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4).setColorMode(android.print.PrintAttributes.COLOR_MODE_COLOR)
            manager.print(displayName, adapter, attributes.build())
        } catch (e: Exception) {
            Toast.makeText(this, e.localizedMessage ?: "Printing unavailable", Toast.LENGTH_LONG).show()
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
    private var pendingAiTask: String = "summary"
    private var pendingAiPrompt: String = ""
    private val aiPdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runAiForPdf(uri, pendingAiTask, pendingAiPrompt)
    }
    private val collagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) showPageCollage(uri) }
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
    private val reorderPagesPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showPageOrganizer(uri, PdfPagePlan.Operation.REORDER)
    }
    private val deletePagesPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showPageOrganizer(uri, PdfPagePlan.Operation.DELETE)
    }
    private val extractPagesPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showPageOrganizer(uri, PdfPagePlan.Operation.EXTRACT)
    }
    private val duplicatePagesPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showPageOrganizer(uri, PdfPagePlan.Operation.DUPLICATE)
    }
    private val pageNumbersPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showPageNumbersDialog(uri)
    }
    private val watermarkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addWatermarkDialog(uri)
    }


    private fun requestAdConsent() {
        val consentInfo = UserMessagingPlatform.getConsentInformation(this)
        val params = ConsentRequestParameters.Builder().build()
        consentInfo.requestConsentInfoUpdate(
            this,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { formError ->
                    privacyOptionsRequired = consentInfo.privacyOptionsRequirementStatus ==
                        ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
                    adsConsentAllowed = consentInfo.canRequestAds()
                    if (adsConsentAllowed) {
                        MobileAds.initialize(this) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) buildScreen()
                            }
                        }
                    } else if (formError != null) {
                        android.util.Log.w("DocNexa", "Consent form not available: ${formError.message}")
                    }
                }
            },
            { error ->
                android.util.Log.w("DocNexa", "Consent info update failed: ${error.message}")
                adsConsentAllowed = consentInfo.canRequestAds()
                if (adsConsentAllowed) {
                    MobileAds.initialize(this) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) buildScreen()
                        }
                    }
                }
            }
        )
    }

    private fun showPrivacyChoices() {
        UserMessagingPlatform.showPrivacyOptionsForm(this) { formError ->
            if (formError != null) {
                Toast.makeText(this, "Privacy options could not be opened: ${formError.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initializeRemoteConfig() {
        // Defaults keep the app safe and usable even when Remote Config is offline.
        val remoteConfig = FirebaseRemoteConfig.getInstance()
        val settings = com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(3600)
            .build()
        remoteConfig.setConfigSettingsAsync(settings)
        remoteConfig.setDefaultsAsync(
            mapOf(
                "announcement_enabled" to false,
                "announcement_message" to ""
            )
        )
        remoteConfig.fetchAndActivate().addOnCompleteListener(this) { task ->
            if (task.isSuccessful && remoteConfig.getBoolean("announcement_enabled")) {
                val message = remoteConfig.getString("announcement_message").trim()
                if (message.isNotEmpty() && !isFinishing && !isDestroyed) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trackPrivacyWindow(window)
        credentialOperation=savedInstanceState?.getString("app_lock_credential_operation")
        // Reattach the AndroidX callback if authentication survives recreation.
        biometricPrompt
        handleSocialAuthIntent(intent)
        // Firebase Analytics records a lightweight app-open event. Crashlytics
        // automatically captures uncaught crashes after Firebase initialization.
        runCatching {
            FirebaseAnalytics.getInstance(this).logEvent("docnexa_app_open", Bundle().apply {
                putString("app_section", "main")
            })
            FirebaseCrashlytics.getInstance().setCustomKey("app_section", "main")
        }
        createDocNexaNotificationChannels()
        // Subscribe every installation to important app announcements by default.
        // Users can still control notification visibility in Android system settings.
        FirebaseMessaging.getInstance().subscribeToTopic("docnexa_announcements")
            .addOnFailureListener { error -> android.util.Log.w("DocNexa", "Announcement topic subscription failed", error) }
        PDFBoxResourceLoader.init(applicationContext)
        buildScreen()
        connectPlayBilling()
        // Consent is requested once the first validated connection is available.
        openNotificationPdf(intent)
        handleViewerIntent(intent)
        initializeRemoteConfig()
        pendingResultSource = savedInstanceState?.getString("result_pending_source")?.let { Uri.parse(it) }
        pendingResultName = savedInstanceState?.getString("result_pending_name")
        pendingDriveBackupAccount = savedInstanceState?.getString("drive_backup_account")
        pendingDriveImportAccount = savedInstanceState?.getString("drive_import_account")
        directDriveAccount = savedInstanceState?.getString("direct_drive_account")
        directDriveResolutionPending = directDriveAccount != null
        directDriveTarget = savedInstanceState?.getString("direct_drive_target")?.let{Uri.parse(it)}
        savedInstanceState?.getBundle("invoice_ready")?.let{state->window.decorView.post{
            val uri=state.getString("uri")?.let{Uri.parse(it)}
            if(uri!=null)showInvoiceReady(uri,state.getString("kind").orEmpty(),state.getString("number").orEmpty(),state.getString("total").orEmpty(),state.getString("payment").orEmpty(),state.getInt("pages"),state.getString("owner").orEmpty())
        }}
        val resultUris = savedInstanceState?.getStringArrayList("result_uris")
        val resultNames = savedInstanceState?.getStringArrayList("result_names")
        val resultMimes = savedInstanceState?.getStringArrayList("result_mimes")
        if (resultUris != null && resultNames != null && resultMimes != null) window.decorView.post {
            resultUris.indices.forEach { index ->
                if (index < resultNames.size && index < resultMimes.size) showExportSuccess(Uri.parse(resultUris[index]), resultNames[index], resultMimes[index])
            }
            val index = savedInstanceState?.getInt("result_selected", 0) ?: 0
            if (index in exportResults.items.indices) { exportResults.select(index); refreshExportResult?.invoke() }
        }
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

    private fun showPremiumDialog(builder: AlertDialog.Builder): AlertDialog = builder.show()

    /** Shared dialog appearance without replacing each tool's validation or button callbacks. */
    /**
     * One shared DocNexa premium dialog skin. All appDialog() call sites inherit
     * the same rounded surface, typography, input styling and clear action hierarchy.
     */
    private enum class ButtonRole { Primary, Secondary, Premium, Danger }

    /** Shared native controls, including buttons created dynamically inside tools and dialogs.
     * Keep actual enabled states, click handlers and explicit tool layout parameters unchanged.
     * Existing branded home actions and the native ad CTA retain their dedicated appearance.
     */
    private fun nativeButton(context: android.content.Context, role: ButtonRole = ButtonRole.Secondary): Button {
        val foreground = when(role) {
            ButtonRole.Primary -> 0xFFFFFFFF.toInt()
            ButtonRole.Secondary -> 0xFF174C94.toInt()
            ButtonRole.Premium -> 0xFF5936AA.toInt()
            ButtonRole.Danger -> 0xFFB42336.toInt()
        }
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        val normal = when(role) {
            ButtonRole.Primary -> gradient(0xFF3478F6.toInt(),0xFF1556BE.toInt(),12)
            ButtonRole.Secondary -> rounded(0xFFF0F5FF.toInt(),12,0xFFD4E3F7.toInt())
            ButtonRole.Premium -> rounded(0xFFF2EDFF.toInt(),12,0xFFD9C9F8.toInt())
            ButtonRole.Danger -> rounded(0xFFFFF1F2.toInt(),12,0xFFF2CDD3.toInt())
        }
        val surface = android.graphics.drawable.StateListDrawable().apply {
            addState(disabled,rounded(0xFFE8EDF5.toInt(),12,0xFFD9E2EE.toInt()))
            addState(intArrayOf(),normal)
        }
        val ripple=android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(if(role==ButtonRole.Primary)0x33FFFFFF else 0x223478F6),
            surface,null)
        return Button(context).apply {
            isAllCaps=false
            textSize=13f
            typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
            gravity=Gravity.CENTER
            minWidth=0
            minimumWidth=0
            minHeight=dp(48)
            minimumHeight=dp(48)
            maxLines=2
            setTextColor(android.content.res.ColorStateList(
                arrayOf(disabled,intArrayOf()),intArrayOf(0xFF65748A.toInt(),foreground)))
            backgroundTintList=null
            background=android.graphics.drawable.InsetDrawable(ripple,dp(2),dp(3),dp(2),dp(3))
            setPadding(dp(10),dp(8),dp(10),dp(8))
            stateListAnimator=null
            elevation=0f
        }
    }

    private fun popupGlyph(symbol:String, tint:Int): View = object : View(this) {
        private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color=tint;strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;style=Paint.Style.STROKE
        }
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            val size=minOf(width,height,dp(28)).toFloat()
            if(size<=0f)return
            canvas.save();canvas.translate((width-size)/2f,(height-size)/2f);canvas.scale(size/24f,size/24f)
            when(symbol){
                "camera" -> {
                    canvas.drawRoundRect(2f,6f,22f,21f,3f,3f,ink)
                    canvas.drawLine(7f,6f,9f,3f,ink);canvas.drawLine(9f,3f,15f,3f,ink);canvas.drawLine(15f,3f,17f,6f,ink)
                    canvas.drawCircle(12f,13.5f,4f,ink)
                }
                "gallery" -> {
                    canvas.drawRoundRect(2f,3f,22f,21f,3f,3f,ink);canvas.drawCircle(8f,8f,1.8f,ink)
                    val path=android.graphics.Path().apply{moveTo(3f,17f);lineTo(9f,11f);lineTo(13f,15f);lineTo(17f,10f);lineTo(22f,16f)}
                    canvas.drawPath(path,ink)
                }
                "qr" -> {
                    listOf(3f to 3f,15f to 3f,3f to 15f).forEach{(x,y)->canvas.drawRect(x,y,x+6f,y+6f,ink);canvas.drawPoint(x+3f,y+3f,ink)}
                    canvas.drawLine(15f,15f,21f,15f,ink);canvas.drawLine(15f,15f,15f,21f,ink);canvas.drawLine(18f,18f,21f,21f,ink)
                }
                "trash" -> {
                    canvas.drawRoundRect(6f,8f,18f,22f,2f,2f,ink)
                    canvas.drawLine(3f,6f,21f,6f,ink);canvas.drawRoundRect(9f,2f,15f,6f,1f,1f,ink)
                    canvas.drawLine(10f,11f,10f,18f,ink);canvas.drawLine(14f,11f,14f,18f,ink)
                }
                "shield" -> {
                    val path=android.graphics.Path().apply{moveTo(12f,2f);lineTo(21f,6f);lineTo(20f,14f);quadTo(18f,19f,12f,22f);quadTo(6f,19f,4f,14f);lineTo(3f,6f);close()}
                    canvas.drawPath(path,ink);canvas.drawLine(8f,12f,11f,15f,ink);canvas.drawLine(11f,15f,16f,9f,ink)
                }
                "download","upload" -> {
                    val direction=if(symbol=="download")1f else -1f
                    val tip=12f+direction*5f
                    canvas.drawLine(12f,12f-direction*9f,12f,tip,ink)
                    canvas.drawLine(7f,tip-direction*5f,12f,tip,ink);canvas.drawLine(17f,tip-direction*5f,12f,tip,ink)
                    canvas.drawLine(4f,20f,20f,20f,ink)
                }
                "print" -> {
                    canvas.drawRoundRect(3f,8f,21f,17f,2f,2f,ink)
                    canvas.drawRect(7f,2f,17f,8f,ink);canvas.drawRect(7f,14f,17f,22f,ink)
                    canvas.drawPoint(17f,11f,ink)
                }
                "search" -> {
                    canvas.drawCircle(10f,10f,7f,ink);canvas.drawLine(15f,15f,22f,22f,ink)
                }
                "share" -> {
                    canvas.drawCircle(5f,12f,3f,ink);canvas.drawCircle(19f,4f,3f,ink);canvas.drawCircle(19f,20f,3f,ink)
                    canvas.drawLine(8f,10f,16f,5f,ink);canvas.drawLine(8f,14f,16f,19f,ink)
                }
                "edit" -> {
                    val path=android.graphics.Path().apply{moveTo(4f,16f);lineTo(16f,4f);lineTo(20f,8f);lineTo(8f,20f);lineTo(3f,21f);close()}
                    canvas.drawPath(path,ink);canvas.drawLine(13f,7f,17f,11f,ink)
                }
                "folder" -> {
                    val path=android.graphics.Path().apply{moveTo(2f,5f);lineTo(9f,5f);lineTo(12f,8f);lineTo(22f,8f);lineTo(22f,21f);lineTo(2f,21f);close()}
                    canvas.drawPath(path,ink)
                }
                else -> {
                    canvas.drawRoundRect(4f,2f,20f,22f,2f,2f,ink)
                    for(y in listOf(8f,12f,16f))canvas.drawLine(8f,y,16f,y,ink)
                }
            }
            canvas.restore()
        }
    }.apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}

    private fun popupOptionCard(symbol:String,title:String,subtitle:String="",primary:Boolean=false,action:(()->Unit)?=null):LinearLayout {
        val premium=title.contains("• PRO") || title.contains("Premium locked",true)
        val danger=title.startsWith("Delete",true)||title.startsWith("Remove permanently",true)
        val accent=if(primary)0xFFFFFFFF.toInt() else if(danger)0xFFB42336.toInt() else if(premium)0xFF5936AA.toInt() else 0xFF2563EB.toInt()
        val surface=if(primary)gradient(0xFF1764DE.toInt(),0xFF0B3F99.toInt(),18)
            else rounded(if(danger)0xFFFFF5F6.toInt() else if(premium)0xFFF7F3FF.toInt() else 0xFFF8FAFF.toInt(),18,if(danger)0xFFF1D0D5.toInt() else 0xFFDCE7F5.toInt())
        val row=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            minimumHeight=dp(64);setPadding(dp(12),dp(10),dp(12),dp(10))
            background=if(action!=null)android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if(primary)0x33FFFFFF else 0x223478F6),surface,null)else surface
            if(action!=null){isClickable=true;isFocusable=true;setOnClickListener{action()}}
        }
        val tile=FrameLayout(this).apply{
            background=rounded(if(primary)0x26FFFFFF else if(premium)0xFFEDE5FF.toInt() else if(danger)0xFFFFE7EB.toInt() else 0xFFE4EEFF.toInt(),12)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            when(symbol){
                "drive" -> addView(driveBrandIcon(),FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER))
                "camera","gallery","qr","document","trash","shield","download","upload","print","search","share","edit","folder" -> addView(popupGlyph(symbol,accent),FrameLayout.LayoutParams(-1,-1))
                else -> addView(label(symbol,if(symbol.length>2)12f else 20f,accent,true).apply{gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
            }
        }
        row.addView(tile,LinearLayout.LayoutParams(dp(44),dp(44)))
        val copy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(11),0,dp(6),0)}
        copy.addView(label(title,14f,if(primary)0xFFFFFFFF.toInt() else 0xFF172B4D.toInt(),true))
        if(subtitle.isNotBlank()){copy.addView(gap(3));copy.addView(label(subtitle,12f,if(primary)0xFFDCE8FF.toInt() else 0xFF52627A.toInt()))}
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(label("›",22f,if(primary)0xFFFFFFFF.toInt() else 0xFF7186A6.toInt()).apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO})
        return row
    }

    private fun addPopupOption(panel:LinearLayout,symbol:String,title:String,subtitle:String,primary:Boolean=false,action:()->Unit){
        panel.addView(popupOptionCard(symbol,title,subtitle,primary,action),LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})
    }

    private fun popupHeader(panel:LinearLayout,kicker:String,title:String,subtitle:String){
        val hero=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(13),dp(15),dp(14));background=gradient(0xFF103F91.toInt(),0xFF071E50.toInt(),16)
        }
        hero.addView(label(kicker,10f,0xFFC1D7FF.toInt(),true))
        hero.addView(gap(5));hero.addView(label(title,18f,0xFFFFFFFF.toInt(),true))
        hero.addView(gap(4));hero.addView(label(subtitle,12f,0xFFDCE8FF.toInt()))
        panel.addView(hero,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
    }

    // Single-choice action menus keep the native ListView click dispatch and original indices.
    // Radio/multi-choice dialogs retain their original adapters and selection widgets.
    private fun AlertDialog.Builder.setPremiumItems(items:Array<out CharSequence>,listener:android.content.DialogInterface.OnClickListener):AlertDialog.Builder {
        val adapter=object:android.widget.ArrayAdapter<CharSequence>(this@MainActivity,android.R.layout.simple_list_item_1,items.toList()){
            override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
                val value=getItem(position)?.toString().orEmpty()
                val symbol=when{
                    value.startsWith("Delete",true)||value.contains("permanently",true)->"trash"
                    value.contains("Google Drive",true)->"drive"
                    value.contains("gallery",true)||value.contains("image",true)->"gallery"
                    value.contains("camera",true)||value.startsWith("Scan",true)->"camera"
                    value.contains("Premium",true)||value.contains("• PRO")->"♛"
                    value.contains("QR",true)->"qr"
                    value.startsWith("Print",true)->"print"
                    value.startsWith("Share",true)->"share"
                    value.startsWith("Upload",true)->"upload"
                    value.startsWith("Save",true)||value.startsWith("Download",true)||value.startsWith("Export",true)->"download"
                    value.contains("password",true)||value.startsWith("Protect",true)->"shield"
                    value.startsWith("Search",true)||value.startsWith("Find",true)->"search"
                    value.startsWith("Edit",true)||value.startsWith("Rename",true)->"edit"
                    value.contains("folder",true)->"folder"
                    value.contains("PDF",true)->"PDF"
                    else->"document"
                }
                return LinearLayout(this@MainActivity).apply{
                    orientation=LinearLayout.VERTICAL
                    setPadding(dp(4),dp(3),dp(4),dp(3))
                    addView(popupOptionCard(symbol,value),LinearLayout.LayoutParams(-1,-2))
                }
            }
        }
        return setAdapter(adapter,listener)
    }

    private fun appDialog(fullScreen: Boolean = false): AlertDialog.Builder = object : com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DocNexaDialogTheme) {
        override fun create(): AlertDialog {
            val dialog = super.create()
            trackPrivacyWindow(dialog.window)
            dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    view.post {
                        if (!dialog.isShowing) return@post
                        dialog.window?.apply {
                            if (fullScreen) {
                                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                setDimAmount(0f)
                            } else {
                                setLayout(minOf(dp(560), (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(1))), attributes.height)
                                setDimAmount(0.52f)
                            }
                            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                            setBackgroundDrawable(if (fullScreen) android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt())
                                else rounded(0xFFFFFFFF.toInt(), 26, 0xFFD9E5F5.toInt()))
                        }
                        // Decorate the existing native title rather than replacing it. This preserves
                        // accessibility, custom titles, dynamic titles, native list and form callbacks.
                        view.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.let { heading ->
                            if (heading.visibility == View.VISIBLE && heading.text.isNotBlank()) {
                                heading.setTextColor(0xFFFFFFFF.toInt())
                                heading.textSize = 18f
                                heading.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                                heading.setPadding(dp(14),dp(12),dp(14),dp(12))
                                heading.background = gradient(0xFF174B9C.toInt(),0xFF0B245A.toInt(),16)
                                heading.maxLines = Int.MAX_VALUE
                                if (Build.VERSION.SDK_INT >= 28) heading.isAccessibilityHeading = true
                            }
                        }
                        fun styleDialogTree(node: View) {
                            if (node is NativeAdView) return
                            when (node.id) {
                                android.R.id.message -> if (node is TextView) {
                                    node.setTextColor(0xFF53657E.toInt())
                                    node.textSize = 14f
                                    node.setLineSpacing(dp(3).toFloat(), 1.12f)
                                }
                                android.R.id.text1 -> if (node is TextView) {
                                    node.setTextColor(0xFF243B5A.toInt())
                                    node.textSize = 15f
                                    node.minimumHeight = dp(52)
                                    node.setPadding(dp(12), dp(12), dp(12), dp(12))
                                }
                            }
                            if (node is EditText) {
                                node.setTextColor(0xFF172B4D.toInt())
                                node.setHintTextColor(0xFF64748B.toInt())
                                // Preserve explicit editor text sizes; native font scaling remains enabled.
                                node.setPadding(dp(14), dp(12), dp(14), dp(12))
                                node.minimumHeight = dp(48)
                                val inputSurface = android.graphics.drawable.StateListDrawable().apply {
                                    addState(intArrayOf(android.R.attr.state_focused),rounded(0xFFFFFFFF.toInt(),14,0xFF3478F6.toInt()))
                                    addState(intArrayOf(),rounded(0xFFF3F7FF.toInt(),14,0xFFD4E2F4.toInt()))
                                }
                                node.background = inputSurface
                                (node.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                                    it.topMargin = dp(8); it.bottomMargin = dp(8)
                                }
                            }
                            if (node is android.widget.CompoundButton) {
                                node.setTextColor(0xFF243B5A.toInt())
                                node.buttonTintList = android.content.res.ColorStateList(
                                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                                    intArrayOf(0xFF2563EB.toInt(), 0xFF64748B.toInt())
                                )
                            }
                            if (node is android.widget.ProgressBar && node !is android.widget.SeekBar) {
                                node.indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF2563EB.toInt())
                                node.progressTintList = android.content.res.ColorStateList.valueOf(0xFF2563EB.toInt())
                            }
                            if (node is ViewGroup) for (i in 0 until node.childCount) styleDialogTree(node.getChildAt(i))
                        }
                        styleDialogTree(view)
                        dialog.listView?.apply {
                            divider = android.graphics.drawable.ColorDrawable(0x00000000)
                            dividerHeight = dp(5)
                            selector = rounded(0x223478F6,14)
                            setDrawSelectorOnTop(true)
                            setPadding(dp(8), dp(6), dp(8), dp(6))
                            clipToPadding = false
                        }
                        fun styleAction(which: Int, primary: Boolean) {
                            dialog.getButton(which)?.apply {
                                isAllCaps = false
                                textSize = 13f
                                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                                minHeight = dp(48)
                                minimumHeight = dp(48)
                                setPadding(dp(12), dp(8), dp(12), dp(8))
                                val actionText=text.toString().trim().lowercase(Locale.ROOT)
                                val destructive=actionText=="delete"||actionText.startsWith("delete ")||actionText=="sign out"||actionText=="remove"||actionText=="discard"||actionText=="empty trash"
                                val disabled = intArrayOf(-android.R.attr.state_enabled)
                                val foreground=if(primary)0xFFFFFFFF.toInt() else if(destructive)0xFFB42336.toInt() else 0xFF174C94.toInt()
                                setTextColor(android.content.res.ColorStateList(
                                    arrayOf(disabled, intArrayOf()), intArrayOf(0xFF66758A.toInt(), foreground)))
                                val surface = android.graphics.drawable.StateListDrawable().apply {
                                    addState(disabled, rounded(0xFFE8EDF5.toInt(), 14))
                                    addState(intArrayOf(), if (destructive && primary) gradient(0xFFC93649.toInt(),0xFF9F2034.toInt(),14) else if (primary) gradient(0xFF3478F6.toInt(), 0xFF1556BE.toInt(), 14)
                                        else if(destructive)rounded(0xFFFFF1F2.toInt(),14,0xFFF2CDD3.toInt())
                                        else rounded(0xFFF0F5FC.toInt(), 14, 0xFFDCE7F5.toInt()))
                                }
                                backgroundTintList = null
                                background = android.graphics.drawable.RippleDrawable(
                                    android.content.res.ColorStateList.valueOf(if (primary) 0x33FFFFFF else 0x223478F6), surface, null)
                                stateListAnimator=null
                                elevation=0f
                            }
                        }
                        styleAction(AlertDialog.BUTTON_POSITIVE, true)
                        styleAction(AlertDialog.BUTTON_NEGATIVE, false)
                        styleAction(AlertDialog.BUTTON_NEUTRAL, false)
                        // A background export can open a dialog after connectivity is lost.
                        // Keep the offline gate above newly attached app-owned tool dialogs.
                        if (!isInternetAvailable() || appLockRequired()) refreshStrictOnlineGate(raise = true)
                    }
                }
                override fun onViewDetachedFromWindow(view: View) = Unit
            })
            return dialog
        }
    }

    private fun toolDialogScroll(content: View, maxHeightFraction: Float = 0.57f): ScrollView = ScrollView(this).apply {
        isFillViewport = false
        clipToPadding = false
        addView(content)
        // Reserve room for the title and actions on small screens and with large font settings.
        layoutParams = ViewGroup.LayoutParams(-1, -2)
        viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val maximum = (resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
                if (height > maximum) layoutParams = layoutParams.apply { height = maximum }
            }
        })
    }

    private fun bindPageSelectionAction(dialog: AlertDialog, field: EditText, count: Int, extraFields: List<EditText> = emptyList(), extraValid: () -> Boolean = { true }) {
        fun validate() {
            val valid = runCatching { PdfPagePlan.selection(field.text.toString(), count) }.isSuccess && extraValid()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = valid
        }
        (listOf(field) + extraFields).forEach { input ->
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { validate() }
            })
        }
        validate()
    }

    private val pagePreviewExecutor = Executors.newSingleThreadExecutor()

    /** Render at most eight small previews per batch, keeping large documents responsive. */
    private fun addVisualPageSelector(
        parent: LinearLayout, uri: Uri, count: Int, field: EditText,
        reorder: Boolean = false
    ) {
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(holder)
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        holder.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(toolbar)
        })
        val counter = label("", 12f, 0xFF2563EB.toInt(), true).apply { setPadding(0, dp(8), 0, dp(8)) }
        holder.addView(counter)
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        holder.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        val pager = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val previous = nativeButton(this, ButtonRole.Secondary).apply { text = "‹ Previous"; isAllCaps = false; textSize = 11f }
        val position = label("", 11f, 0xFF64748B.toInt()).apply { gravity = Gravity.CENTER }
        val next = nativeButton(this, ButtonRole.Secondary).apply { text = "Next ›"; isAllCaps = false; textSize = 11f }
        pager.addView(previous, LinearLayout.LayoutParams(-2, dp(48)))
        pager.addView(position, LinearLayout.LayoutParams(0, -2, 1f))
        pager.addView(next, LinearLayout.LayoutParams(-2, dp(48)))
        holder.addView(pager)
        var batchStart = 0
        val generation = java.util.concurrent.atomic.AtomicInteger()
        val cache = android.util.LruCache<Int, Bitmap>(16)
        fun selection(): List<Int> = runCatching { PdfPagePlan.selection(field.text.toString(), count) }.getOrDefault(emptyList())
        fun choose(pages: List<Int>) { field.setText(PdfPagePlan.formatSelection(pages)) }
        fun shortcut(title: String, action: () -> Unit) {
            toolbar.addView(nativeButton(this, ButtonRole.Secondary).apply {
                text = title; isAllCaps = false; textSize = 11f
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-2, dp(48)))
        }
        if (reorder) {
            shortcut("Reset") { choose((0 until count).toList()) }
            shortcut("Reverse") { choose(selection().ifEmpty { (0 until count).toList() }.reversed()) }
        } else {
            shortcut("All") { choose((0 until count).toList()) }
            shortcut("Odd") { choose((0 until count).filter { it % 2 == 0 }) }
            shortcut("Even") { choose((0 until count).filter { it % 2 == 1 }) }
            shortcut("First half") { choose((0 until ((count + 1) / 2)).toList()) }
            shortcut("Last half") { choose(((count + 1) / 2 until count).toList()) }
            shortcut("Invert") {
                val current = selection().toSet()
                choose((0 until count).filterNot { it in current })
            }
            shortcut("Clear") { choose(emptyList()) }
        }
        fun render() {
            val selected = selection()
            val pages = if (reorder && selected.size == count) selected else (0 until count).toList()
            batchStart = batchStart.coerceIn(0, maxOf(0, ((count - 1) / 8) * 8))
            counter.text = if (reorder) "${selected.size} / $count pages in order • use arrows to move" else "${selected.size} / $count selected • tap a page"
            strip.removeAllViews()
            val token = generation.incrementAndGet()
            val requests = mutableListOf<Triple<Int, ImageView, TextView>>()
            val selectedSet = selected.toHashSet()
            pages.drop(batchStart).take(8).forEachIndexed { index, pageIndex ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(7), dp(6), dp(7))
                    background = rounded(if (pageIndex in selectedSet) 0xFFEAF2FF.toInt() else 0xFFF8FAFC.toInt(), 12, 0xFFD4E2F4.toInt())
                }
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Preview of page ${pageIndex + 1}"
                    setBackgroundColor(0xFFFFFFFF.toInt())
                }
                val fallback = label("Loading preview…", 9f, 0xFF64748B.toInt()).apply { gravity = Gravity.CENTER }
                val frame = FrameLayout(this).apply {
                    addView(image, FrameLayout.LayoutParams(-1, -1))
                    addView(fallback, FrameLayout.LayoutParams(-1, -1))
                }
                card.addView(frame, LinearLayout.LayoutParams(-1, dp(104)))
                if (reorder) {
                    card.addView(label("Page ${pageIndex + 1}", 12f, 0xFF172B4D.toInt(), true))
                    card.addView(label("Position ${batchStart + index + 1}", 10f, 0xFF64748B.toInt()))
                    val moves = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    listOf(-1 to "‹", 1 to "›").forEach { (direction, symbol) ->
                        moves.addView(nativeButton(this, ButtonRole.Secondary).apply {
                            text = symbol; minWidth = 0; minimumWidth = 0
                            contentDescription = "Move page ${pageIndex + 1} ${if (direction < 0) "earlier" else "later"}"
                            isEnabled = selected.size == count && selected.indexOf(pageIndex) + direction in selected.indices
                            setOnClickListener { choose(PdfPagePlan.move(selection(), pageIndex, direction)) }
                        }, LinearLayout.LayoutParams(0, dp(48), 1f))
                    }
                    card.addView(moves)
                } else {
                    val check = android.widget.CheckBox(this).apply {
                        text = "Page ${pageIndex + 1}"; textSize = 11f
                        isChecked = pageIndex in selectedSet
                        setOnCheckedChangeListener { _, checked ->
                            val current = selection().toMutableList()
                            if (checked && pageIndex !in current) current.add(pageIndex)
                            if (!checked) current.remove(pageIndex)
                            // Explicitly keep an empty list invalid instead of silently selecting all.
                            choose(current)
                        }
                    }
                    card.addView(check)
                    card.setOnClickListener { check.performClick() }
                }
                strip.addView(card, LinearLayout.LayoutParams(dp(108), -2).apply { rightMargin = dp(8) })
                val cached = cache.get(pageIndex)
                if (cached != null) { image.setImageBitmap(cached); fallback.visibility = View.GONE }
                else requests.add(Triple(pageIndex, image, fallback))
            }
            previous.isEnabled = batchStart > 0
            next.isEnabled = batchStart + 8 < count
            position.text = "${batchStart + 1}–${minOf(batchStart + 8, count)} / $count"
            pager.visibility = if (count > 8) View.VISIBLE else View.GONE
            if (requests.isNotEmpty() && !pagePreviewExecutor.isShutdown) pagePreviewExecutor.execute {
                try {
                    val fd = contentResolver.openFileDescriptor(uri, "r") ?: error("No preview")
                    fd.use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            for ((pageIndex, image, fallback) in requests) {
                                if (generation.get() != token || isDestroyed) break
                                val bitmap = renderer.openPage(pageIndex).use { page ->
                                    val scale = minOf(144f / page.width, 180f / page.height)
                                    Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888).also {
                                        Canvas(it).drawColor(android.graphics.Color.WHITE)
                                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                                cache.put(pageIndex, bitmap)
                                runOnUiThread {
                                    if (generation.get() == token && holder.isAttachedToWindow) {
                                        image.setImageBitmap(bitmap); fallback.visibility = View.GONE
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        if (generation.get() == token) requests.forEach { it.third.text = "Preview unavailable" }
                    }
                }
            }
        }
        val refresh = Runnable { render() }
        previous.setOnClickListener { batchStart -= 8; render() }
        next.setOnClickListener { batchStart += 8; render() }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                holder.removeCallbacks(refresh); holder.postDelayed(refresh, 180)
            }
        })
        holder.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { holder.post(refresh) }
            override fun onViewDetachedFromWindow(view: View) { generation.incrementAndGet(); holder.removeCallbacks(refresh); cache.evictAll() }
        })
    }


    private fun containsNativeAdSlot(view: View): Boolean {
        if (view.tag == "docnexa_native_ad_slot") return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsNativeAdSlot(view.getChildAt(i))) return true
            }
        }
        return false
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
    private val folderAssignments by lazy { java.util.Collections.synchronizedMap(prefs.getString(accountPrefKey("folder_assignments"), "")?.split("\\n")?.filter { it.contains("|") }?.associate { it.substringBefore("|") to it.substringAfter("|") }?.toMutableMap() ?: mutableMapOf()) }

    private fun folderAssignmentSnapshot(): String = synchronized(folderAssignments) {
        folderAssignments.entries.joinToString("\\n") { "${it.key}|${it.value}" }
    }

    private fun loadFolders() {
        homeFolders.clear()
        homeFolders.addAll(prefs.getStringSet(accountPrefKey("folders"), setOf("New Folder", "ID Cards", "Bills")) ?: setOf("New Folder", "ID Cards", "Bills"))
        if (homeFolders.isEmpty()) homeFolders.add("New Folder")
    }
    private fun saveFolders() { prefs.edit().putStringSet(accountPrefKey("folders"), homeFolders.toSet()).apply() }
    private fun assignPdf(uri: Uri) {
        folderAssignments[uri.toString()] = selectedFolder
        prefs.edit().putString(accountPrefKey("folder_assignments"), folderAssignmentSnapshot()).apply()
    }

    private fun styleSystemBars(statusColor: Int, lightStatus: Boolean) {
        window.statusBarColor = statusColor
        window.navigationBarColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 0xFFFFFFFF.toInt() else 0xFF172B4D.toInt()
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightStatus
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) isAppearanceLightNavigationBars = true
        }
    }

    /** Resolution-independent native toolbar icons; no font glyph or emoji dependency. */
    private fun homeHeaderGlyph(kind: String): View = object : View(this) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (kind == "crown") 0xFFFFC542.toInt() else android.graphics.Color.WHITE
            strokeWidth = 2.1f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = minOf(width, height, dp(26)).toFloat()
            if (size <= 0f) return
            canvas.save()
            canvas.translate((width - size) / 2f, (height - size) / 2f)
            canvas.scale(size / 24f, size / 24f)
            ink.style = Paint.Style.STROKE
            when (kind) {
                "menu" -> {
                    for (y in listOf(5f, 12f, 19f)) canvas.drawLine(3f, y, 21f, y, ink)
                }
                "search" -> {
                    canvas.drawCircle(10f, 10f, 6.5f, ink)
                    canvas.drawLine(15f, 15f, 21f, 21f, ink)
                }
                "more" -> {
                    ink.style = Paint.Style.FILL
                    for (y in listOf(5f, 12f, 19f)) canvas.drawCircle(12f, y, 1.8f, ink)
                }
                "crown" -> {
                    ink.style = Paint.Style.FILL
                    val crown = android.graphics.Path().apply {
                        moveTo(2f, 6f); lineTo(7.5f, 10.5f); lineTo(12f, 3f)
                        lineTo(16.5f, 10.5f); lineTo(22f, 6f); lineTo(19.5f, 18f)
                        lineTo(4.5f, 18f); close()
                    }
                    canvas.drawPath(crown, ink)
                    canvas.drawRoundRect(4.5f, 20f, 19.5f, 22f, 1f, 1f, ink)
                }
            }
            canvas.restore()
        }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    private fun homeHeaderAction(kind: String, description: String, action: (View) -> Unit): View =
        android.widget.FrameLayout(this).apply {
            contentDescription = description
            isClickable = true
            isFocusable = true
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            val selectable = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true)) {
                setBackgroundResource(selectable.resourceId)
            }
            addView(homeHeaderGlyph(kind), android.widget.FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { action(this) }
        }

    private fun buildScreen() {
        loadFolders()
        selectedFolder = "New Folder"
        val bg = 0xFFF5F8FF.toInt()
        val navy = 0xFF172B4D.toInt()
        val blue = 0xFF3478F6.toInt()
        val paleBlue = 0xFFEAF2FF.toInt()
        val muted = 0xFF718096.toInt()

        // Android 15+ draws edge-to-edge; use dark status icons over the light app background.
        styleSystemBars(0xFFF5F8FF.toInt(), true)

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

        // Full-width native toolbar: retain the blue brand, with Premium in the header.
        val compactHeader = resources.configuration.screenWidthDp < 360 ||
            resources.configuration.fontScale > 1.15f
        val headerShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(88)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            background = gradient(0xFF073B91.toInt(), 0xFF06245F.toInt(), 18)
            elevation = dp(3).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerShell.addView(header, LinearLayout.LayoutParams(-1, -2))
        val headerActions = if (compactHeader) LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            headerShell.addView(this, LinearLayout.LayoutParams(-1, -2))
        } else header
        header.addView(homeHeaderAction("menu", "Open navigation menu") { showAppDrawer() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
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
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
        })
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), 0, dp(2), 0)
        }
        brand.addView(label("DocNexa", 18f, 0xFFFFFFFF.toInt(), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        brand.addView(label("PDF Scanner & Editor", 10f, 0xFFDCE8FF.toInt()).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val premiumChip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(52)
            background = rounded(0x244A8CFF, 12)
            isClickable = true
            isFocusable = true
            contentDescription = "DocNexa Premium plans"
            setOnClickListener { studioPremiumInfo() }
        }
        premiumChip.addView(homeHeaderGlyph("crown"), LinearLayout.LayoutParams(dp(28), dp(25)))
        val premiumLabel = label(if (hasVerifiedPremium()) "PRO Active" else "Premium", 10f, 0xFFFFD66B.toInt(), true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
        }
        headerMembershipLabels.add(java.lang.ref.WeakReference(premiumLabel))
        premiumChip.addView(premiumLabel, LinearLayout.LayoutParams(-1, -2))
        headerActions.addView(premiumChip, LinearLayout.LayoutParams(dp(if (compactHeader) 88 else 64), -2))
        headerActions.addView(homeHeaderAction("search", "Search documents") { showDocumentSearch() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        headerActions.addView(homeHeaderAction("more", "More options") { anchor -> showHomeOverflowMenu(anchor) },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        // Fixed header above the scroll region; no overlap with the hero.
        root.addView(headerShell, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(6)
            rightMargin = dp(6)
            topMargin = dp(6)
            bottomMargin = dp(10)
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
        quickItem("▧ Studio") { showImageStudio() }
        quickItem("♛ Premium") { studioPremiumInfo() }
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

        content.addView(toolCard("Image Studio", "Convert • Resize • Remove BG • Upscale", "▧", 0xFF6546C8.toInt()) { showImageStudio() })
        content.addView(gap(10))
        content.addView(toolCard("Bill / Invoice Maker", "Bills • GST invoices • Quotations • Receipts", "₹", 0xFFEA580C.toInt()) { showInvoiceStudio() })
        content.addView(toolCard("All Document Viewer", "PDF • Word • Excel • PPT • Images • Files", "▤", 0xFF2563EB.toInt()) { showDocumentViewerHub() })
        content.addView(gap(10))
        addNativeAdCard(content)

        // Before first successful scan show onboarding; afterwards reserve this area for ads.
        val hasScannedOnce = prefs.getBoolean("has_completed_first_scan", false)
        status = label(
            if (hasScannedOnce) "Your scanner is ready" else "Ready to scan your first document",
            14f, navy, true
        )
        recent = label("Saved inside DocNexa • use Save As for a device copy", 11f, muted)
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
        val savedDocs = getSavedPdfs()
        val folderHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        folderHeading.addView(label("Folders (${homeFolders.size})", 17f, navy, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        folderHeading.addView(nativeButton(this, ButtonRole.Primary).apply {
            text = "▣ +"
            isAllCaps = false
            contentDescription = "Create new folder"
            setOnClickListener { createFolderDialog() }
        })
        content.addView(folderHeading)
        content.addView(gap(8))
        homeFolders.forEach { folder -> content.addView(folderCard(folder)) }
        val documentsHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        documentsHeading.addView(label("Documents (${savedDocs.size})", 17f, navy, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        documentsHeading.addView(label("View all  ›", 12f, blue, true).apply {
            setPadding(dp(8), dp(10), dp(4), dp(10))
            setOnClickListener { showAllDocumentsScreen() }
        })
        content.addView(documentsHeading)
        content.addView(gap(8))
        if (savedDocs.isEmpty()) content.addView(label("Your scanned PDFs will appear here automatically.", 11f, muted))
        else savedDocs.take(20).forEach { item -> content.addView(documentCard(item.first, item.second)) }
        content.addView(gap(16))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        addBottomAdAndActions(root)
        setScreenContent(root)
    }

    private fun showAllDocumentsScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F8FF.toInt())
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = gradient(0xFF073B91.toInt(), 0xFF06245F.toInt(), 16)
        }
        top.addView(label("‹", 32f, 0xFFFFFFFF.toInt()).apply {
            setPadding(dp(8), 0, dp(18), 0)
            setOnClickListener { buildScreen() }
        })
        top.addView(label("My Documents", 20f, 0xFFFFFFFF.toInt(), true),
            LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label("⌕", 28f, 0xFFFFFFFF.toInt()).apply {
            setOnClickListener { showDocumentSearch() }
        })
        root.addView(top)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(20))
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val modes = listOf("All docs", "Recent", "Favourite")
        val buttons = mutableListOf<TextView>()
        fun render(mode: Int) {
            buttons.forEachIndexed { index, button ->
                button.background = rounded(if (index == mode) 0xFF2563EB.toInt() else 0xFFFFFFFF.toInt(), 16, 0xFFDCE7FA.toInt())
                button.setTextColor(if (index == mode) 0xFFFFFFFF.toInt() else 0xFF172B4D.toInt())
            }
            results.removeAllViews()
            val all = getSavedPdfs()
            val docs = when (mode) {
                1 -> all.take(10)
                2 -> all.filter { (_, uri) -> prefs.getBoolean("favorite_" + uri.toString().hashCode(), false) }
                else -> all
            }
            results.addView(label("Documents (${docs.size})", 16f, 0xFF172B4D.toInt(), true))
            results.addView(gap(8))
            if (docs.isEmpty()) results.addView(label("No documents in this section.", 13f, 0xFF718096.toInt()))
            docs.forEach { (name, uri) -> results.addView(documentCard(name, uri)) }
        }
        modes.forEachIndexed { index, name ->
            val button = label(name, 12f, 0xFF172B4D.toInt(), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(13), dp(10), dp(13))
                setOnClickListener { render(index) }
            }
            buttons.add(button)
            tabs.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
        }
        body.addView(tabs)
        body.addView(gap(14))
        val folderHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        folderHead.addView(label("Folders (${homeFolders.size})", 16f, 0xFF172B4D.toInt(), true),
            LinearLayout.LayoutParams(0, -2, 1f))
        folderHead.addView(label("＋ New", 13f, 0xFF2563EB.toInt(), true).apply {
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { createFolderDialog() }
        })
        body.addView(folderHead)
        homeFolders.forEach { body.addView(folderCard(it)) }
        body.addView(gap(12))
        body.addView(results)
        render(0)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setScreenContent(root)
    }

    private fun showDocumentSearch() {
        val field = EditText(this).apply { hint = "Name, tags or date note"; setSingleLine(true) }
        appDialog().setTitle("Search documents").setView(field)
            .setPositiveButton("Search") { _, _ ->
                val query = field.text.toString().trim()
                val matches = getSavedPdfs().filter { (it.first + " " + prefs.getString("document_tag_" + it.second.toString().hashCode(), "") + " " +
                    prefs.getString("document_datetime_" + it.second.toString().hashCode(), "")).contains(query, ignoreCase = true) }
                    .sortedWith(compareBy({ !it.first.startsWith(query, ignoreCase = true) }, { it.first.lowercase(java.util.Locale.ROOT) }))
                if (query.isBlank()) Toast.makeText(this, "Type a document name to search", Toast.LENGTH_SHORT).show()
                else if (matches.isEmpty()) appDialog().setTitle("No documents found")
                    .setMessage("No saved PDF matches: " + query).setPositiveButton("OK", null).show()
                else appDialog().setTitle("Search results • " + matches.size)
                    .setPremiumItems(matches.map { it.first }.toTypedArray()) { _, index -> openPdf(matches[index].second) }
                    .setNegativeButton("Close", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    // The Android system document picker provides Downloads, device storage and
    // available document providers. MIME filtering is deliberately broad here,
    // while unsupported formats are handled safely after selection.
    private val officeDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) convertOfficeTextToPdf(uri)
    }

    // Bounded, namespace-aware offline Office extraction.
    private fun convertOfficeTextToPdf(uri: Uri) {
        Thread {
            try {
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: error("Cannot identify document")
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                require(ext in setOf("docx", "xlsx", "pptx")) { "Select DOCX, XLSX or PPTX" }
                val content = readOfficeViewerText(uri,ext)
                runOnUiThread {
                    appDialog().setTitle("Office to PDF • Offline")
                        .setMessage("Text, worksheet values and slide order are extracted locally. Complex formatting, images and charts are not reproduced. Continue?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Create PDF") { _, _ -> createPdfFromText(content.toString(), name.substringBeforeLast('.').take(70)) }.show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.message ?: "Could not convert Office file", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun readOfficeViewerText(uri:Uri,ext:String):String {
        require(ext in setOf("docx","xlsx","pptx")){"Unsupported Office format"}
        val parts = mutableMapOf<String, ByteArray>()
        var total = 0L
        var entries = 0
        val pattern = Regex("word/document\\.xml|xl/(sharedStrings\\.xml|worksheets/sheet[0-9]+\\.xml)|ppt/slides/slide[0-9]+\\.xml")
        contentResolver.openInputStream(uri)?.use { stream ->
            java.util.zip.ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 5000) { "Too many document sections" }
                    val wanted = !entry.isDirectory && entry.name.matches(pattern)
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count = 0
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        count += n
                        total += n
                        require(total <= 96L * 1024 * 1024) { "Expanded document exceeds 96 MB" }
                        if (wanted) {
                            require(count <= 8 * 1024 * 1024) { "Document section exceeds 8 MB" }
                            output.write(buffer, 0, n)
                        }
                    }
                    if (wanted) parts[entry.name] = output.toByteArray()
                    zip.closeEntry()
                }
            }
        } ?: error("Cannot read document")
        fun parse(bytes: ByteArray, event: (org.xmlpull.v1.XmlPullParser, Int) -> Unit) {
            val parser = android.util.Xml.newPullParser()
            parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(bytes.inputStream(), null)
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                event(parser, parser.eventType)
                parser.nextToken()
            }
        }
        val start = org.xmlpull.v1.XmlPullParser.START_TAG
        val end = org.xmlpull.v1.XmlPullParser.END_TAG
        val txt = org.xmlpull.v1.XmlPullParser.TEXT
        val shared = mutableListOf<String>()
        parts["xl/sharedStrings.xml"]?.let { bytes ->
            var inText = false
            var value = StringBuilder()
            parse(bytes) { p, e ->
                if (e == start && p.name == "si") value = StringBuilder()
                if (e == start && p.name == "t") inText = true
                if (e == txt && inText) value.append(p.text)
                if (e == end && p.name == "t") inText = false
                if (e == end && p.name == "si") shared.add(value.toString())
            }
        }
        val content = StringBuilder()
        val ordered = parts.keys.filter { it != "xl/sharedStrings.xml" }.sortedBy {
            Regex("[0-9]+").find(it)?.value?.toIntOrNull() ?: 0
        }
        ordered.forEachIndexed { index, key ->
            if (ext != "docx") content.append("\n").append(if (ext == "xlsx") "Worksheet " else "Slide ").append(index + 1).append("\n\n")
            var inText = false
            var cellType = ""
            var cell = StringBuilder()
            var inCell = false
            var firstCell = true
            parse(parts.getValue(key)) { p, e ->
                if (e == start) {
                    when (p.name) {
                        "c" -> if (ext == "xlsx") { inCell = true; cellType = p.getAttributeValue(null, "t") ?: ""; cell = StringBuilder() }
                        "row" -> firstCell = true
                        "t", "v" -> inText = true
                        "tab" -> if (ext == "docx") content.append('\t')
                        "br" -> if (ext != "xlsx") content.append('\n')
                    }
                } else if ((e == txt || e == org.xmlpull.v1.XmlPullParser.CDSECT || e == org.xmlpull.v1.XmlPullParser.ENTITY_REF) && inText) {
                    val value = p.text ?: ""
                    if (ext == "xlsx") { if (inCell) cell.append(value) } else content.append(value)
                } else if (e == end) {
                    when (p.name) {
                        "t", "v" -> inText = false
                        "c" -> if (ext == "xlsx") {
                            if (!firstCell) content.append(" | ")
                            firstCell = false
                            val raw = cell.toString()
                            content.append(when (cellType) {
                                "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                "b" -> if (raw == "1") "TRUE" else "FALSE"
                                else -> raw
                            })
                            inCell = false
                        }
                        "p" -> if (ext != "xlsx") content.append('\n')
                        "row", "tr" -> content.append('\n')
                    }
                }
                require(content.length <= 500_000) { "Document text exceeds conversion limit" }
            }
        }
        require(content.isNotBlank() && ordered.isNotEmpty()) { "No readable Office content found" }

        return content.toString()
    }

    private fun showEditCreateFileChoices() {
        val options = arrayOf(
            "📁  Browse all files",
            "📝  Edit an existing PDF",
            "🖼  Create PDF from images",
            "📑  Merge PDF documents",
            "🛠  Advanced PDF tools",
            "📄  Word / Excel / PowerPoint"
        )
        val descriptions = arrayOf(
            "Browse PDF, images and other documents on your device",
            "Open a PDF in the editor",
            "Select multiple photos to make a PDF",
            "Combine two or more PDF files",
            "Explore editing and document utilities",
            "Offline Office conversion with worksheet values and slide order"
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val dialog = appDialog().setTitle("Edit or Create PDF")
            .setView(toolDialogScroll(container,0.72f))
            .setNegativeButton("Close", null)
            .create()
        popupHeader(container,"DOCNEXA • PDF WORKSPACE","Create or edit documents","Choose a tool to get started.")
        val icons=listOf("document","PDF","gallery","⇄","✎","W")
        options.forEachIndexed { index, title ->
            addPopupOption(container,icons[index],title.substringAfter("  "),descriptions[index]){
                dialog.dismiss()
                    when (index) {
                        0 -> allFilesPicker.launch(arrayOf("*/*"))
                        1 -> pdfEditorPicker.launch(arrayOf("application/pdf"))
                        2 -> imagePicker.launch(arrayOf("image/*"))
                        3 -> mergePicker.launch(arrayOf("application/pdf"))
                        4 -> showPdfTools()
                        else -> officeDocumentPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                    }
            }
        }
        dialog.show()
    }

    private fun showHomeOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Premium plans").setOnMenuItemClickListener { studioPremiumInfo(); true }
        popup.menu.add("Favourite documents").setOnMenuItemClickListener { showFavouriteDocs(); true }
        popup.menu.add("Google Drive & Backup").setOnMenuItemClickListener { showDriveBackupHub(); true }
        popup.menu.add("PDF / JPG").setOnMenuItemClickListener {
            appDialog()
                .setTitle("PDF / JPG")
                .setPremiumItems(arrayOf("Create PDF from images", "Open PDF file")) { _, which ->
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
            appDialog().setTitle("Sort documents")
                .setPremiumItems(arrayOf("Name (A–Z)", "Name (Z–A)", "Newest first", "Oldest first")) { _, which ->
                    prefs.edit().putInt("document_sort_order", which).apply()
                    buildScreen()
                }.show()
            true
        }
        popup.menu.add("Select").setOnMenuItemClickListener {
            val docs = getSavedPdfs()
            if (docs.isEmpty()) Toast.makeText(this, "No documents to select yet", Toast.LENGTH_SHORT).show()
            else appDialog().setTitle("Select documents")
                .setMultiChoiceItems(docs.map { it.first }.toTypedArray(), BooleanArray(docs.size)) { _, _, _ -> }
                .setPositiveButton("Done", null).show()
            true
        }
        popup.menu.add("Create Folder").setOnMenuItemClickListener { createFolderDialog(); true }
        popup.menu.add("Account / Cloud Sync").setOnMenuItemClickListener {
            if (cloudToken().isBlank()) showCloudLoginDialog() else cloudAccountMenu()
            true
        }
        popup.menu.add("Privacy choices").setOnMenuItemClickListener { showPrivacyChoices(); true }
        popup.menu.add("View Mode").setOnMenuItemClickListener {
            appDialog().setTitle("View mode")
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
        bar.addView(Button(this).apply {
            text = "♛ Plans"; isAllCaps = false; textSize = 12f
            setTextColor(0xFFFFFFFF.toInt()); background = gradient(0xFF6546C8.toInt(), 0xFF4034A4.toInt(), 24); backgroundTintList = null
            contentDescription = "Open Premium plans"; setOnClickListener { studioPremiumInfo() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        // Keep Scan and Import controls above the responsive horizontal Native Ad.
        footer.addView(bar)
        addNativeAdCard(footer)
        root.addView(footer)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("app_lock_credential_operation",credentialOperation)
        pendingResultSource?.let { outState.putString("result_pending_source", it.toString()) }
        outState.putString("result_pending_name", pendingResultName)
        outState.putString("drive_backup_account", pendingDriveBackupAccount)
        outState.putString("drive_import_account", pendingDriveImportAccount)
        if (directDriveResolutionPending) {
            outState.putString("direct_drive_account", directDriveAccount)
            outState.putString("direct_drive_target",directDriveTarget?.toString())
        }
        if(invoiceReadyDialog?.isShowing==true)outState.putBundle("invoice_ready",invoiceReadyState)
        if (exportResultDialog?.isShowing == true) {
            val files = exportResults.items
            outState.putStringArrayList("result_uris", ArrayList(files.map { it.uri }))
            outState.putStringArrayList("result_names", ArrayList(files.map { it.fileName }))
            outState.putStringArrayList("result_mimes", ArrayList(files.map { it.mime }))
            outState.putInt("result_selected", exportResults.selectedIndex)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        stopStrictOnlineMonitoring()
        onlineGateDialog?.dismiss()
        onlineGateDialog = null
        privacyGateDialog?.dismiss()
        privacyGateDialog = null
        privacyWindows.clear()
        billingHandler.removeCallbacks(billingRefreshTask)
        billingReadyCallbacks.clear()
        playBillingClient?.endConnection()
        premiumAdDisposers.toList().forEach { it() }
        exportResultDialog?.dismiss()
        invoiceReadyDialog?.dismiss()
        activeDocumentViewer?.dismiss()
        viewerReadExecutor.shutdownNow()
        pagePreviewExecutor.shutdownNow()
        closeQrCamera()
        cameraExecutor.shutdownNow()
        loadedNativeAds.forEach { it.destroy() }
        loadedNativeAds.clear()
        super.onDestroy()
    }

    private fun moveToTrash(uri: Uri) {
        val set = (prefs.getStringSet("trash_uris", emptySet()) ?: emptySet()).toMutableSet()
        set.add(uri.toString())
        prefs.edit().putStringSet("trash_uris", set).apply()
        Toast.makeText(this, "Moved to Trash", Toast.LENGTH_SHORT).show()
        buildScreen()
    }

    private fun showTrashScreen() {
        val owner = signedInUserId()
        val trashed = prefs.getStringSet("trash_uris", emptySet()).orEmpty().toList().filter { raw ->
            val uri = Uri.parse(raw)
            // Never expose another account's private FileProvider documents.
            if (uri.authority != "$packageName.fileprovider") true
            else {
                val parts = uri.pathSegments
                val index = parts.indexOf("accounts")
                index >= 0 && index + 2 == parts.lastIndex &&
                    parts[index + 1] == owner.replace(Regex("[^A-Za-z0-9_-]"), "_")
            }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
        }
        popupHeader(panel, "DOCNEXA • DOCUMENT RECOVERY", "Recycle bin",
            if (trashed.isEmpty()) "No documents in Trash" else "${trashed.size} document(s) in Trash")
        fun binIcon(): View = object : View(this@MainActivity) {
            private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF2563EB.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val side = minOf(width, height).toFloat()
                if (side <= 0f) return
                canvas.save()
                canvas.translate((width-side)/2f,(height-side)/2f)
                canvas.scale(side/48f,side/48f)
                canvas.drawRoundRect(13f,17f,35f,39f,4f,4f,ink)
                canvas.drawLine(9f,14f,39f,14f,ink)
                canvas.drawRoundRect(19f,8f,29f,14f,2f,2f,ink)
                canvas.drawLine(20f,22f,20f,33f,ink)
                canvas.drawLine(28f,22f,28f,33f,ink)
                canvas.restore()
            }
        }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val dialog = appDialog().setTitle("Trash").setView(toolDialogScroll(panel,0.73f))
            .setPositiveButton("Close",null).create()
        fun currentEntry(raw:String):Boolean =
            owner == signedInUserId() && prefs.getStringSet("trash_uris",emptySet()).orEmpty().contains(raw)
        fun removeEntry(raw:String) {
            val entries = prefs.getStringSet("trash_uris",emptySet()).orEmpty().toMutableSet()
            entries.remove(raw)
            prefs.edit().putStringSet("trash_uris",entries).apply()
        }
        fun refreshAfterAction(message:String) {
            Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            buildScreen()
            if (!isFinishing && !isDestroyed && owner == signedInUserId()) showTrashScreen()
        }
        if (trashed.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(16),dp(20),dp(16),dp(20))
                background = rounded(0xFFF8FAFF.toInt(),18,0xFFDCE7F5.toInt())
            }
            val artwork = FrameLayout(this).apply {
                background = rounded(0xFFE5EEFF.toInt(),24)
                addView(binIcon(),FrameLayout.LayoutParams(dp(54),dp(54),Gravity.CENTER))
            }
            empty.addView(artwork,LinearLayout.LayoutParams(dp(80),dp(80)))
            empty.addView(gap(12))
            empty.addView(label("Trash is empty",19f,0xFF172B4D.toInt(),true).apply{gravity=Gravity.CENTER})
            empty.addView(gap(5))
            empty.addView(label("Deleted documents will appear here. Restore them whenever they are still in Trash.",12f,0xFF52627A.toInt()).apply{gravity=Gravity.CENTER})
            panel.addView(empty)
        } else {
            panel.addView(label("RECENTLY DELETED",12f,0xFF52627A.toInt(),true))
            panel.addView(gap(8))
            trashed.forEach { raw ->
                val target = Uri.parse(raw)
                val file = privateDocumentFile(target)
                val name = file?.name ?: target.lastPathSegment ?: "Deleted document"
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12),dp(10),dp(12),dp(10))
                    background = rounded(0xFFF8FAFF.toInt(),16,0xFFDCE7F5.toInt())
                }
                val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
                val preview = ImageView(this).apply {
                    scaleType=ImageView.ScaleType.FIT_CENTER
                    contentDescription="Preview of "+name
                    background=rounded(0xFFE5EEFF.toInt(),10)
                    visibility=View.GONE
                }
                val fallback = documentIconTile(48)
                row.addView(fallback)
                row.addView(preview,LinearLayout.LayoutParams(dp(48),dp(60)))
                val info=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0)}
                info.addView(label(name,14f,0xFF172B4D.toInt(),true).apply{
                    maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
                })
                info.addView(label((file?.let{formatFileSize(it.length())}?.plus(" • ") ?: "")+"In Trash",11f,0xFF52627A.toInt()))
                row.addView(info,LinearLayout.LayoutParams(0,-2,1f))
                card.addView(row);card.addView(gap(9))
                val actions=LinearLayout(this).apply{
                    orientation=if(resources.configuration.screenWidthDp<360 || resources.configuration.fontScale>=1.3f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                }
                fun action(title:String,role:ButtonRole,click:()->Unit){
                    val button=nativeButton(this@MainActivity,role).apply{
                        this.text=title;setOnClickListener{click()}
                    }
                    val params=if(actions.orientation==LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0,-2,1f)
                        else LinearLayout.LayoutParams(-1,-2)
                    params.setMargins(dp(3),dp(3),dp(3),dp(3))
                    actions.addView(button,params)
                }
                action("Restore",ButtonRole.Secondary){
                    if(currentEntry(raw)){
                        removeEntry(raw)
                        refreshAfterAction("Document restored")
                    }
                }
                action("Delete permanently",ButtonRole.Danger){
                    if(currentEntry(raw)) appDialog().setTitle("Delete permanently?")
                        .setMessage(name+"\n\nThis removes the original file. It cannot be restored from Trash.")
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Delete"){_,_->
                            if(currentEntry(raw)){
                                val removed=runCatching{
                                    if(target.authority=="$packageName.fileprovider") privateDocumentFile(target)?.delete()==true
                                    else contentResolver.delete(target,null,null)>0
                                }.getOrDefault(false)
                                if(removed){
                                    removeEntry(raw)
                                    refreshAfterAction("Deleted permanently")
                                }else Toast.makeText(this@MainActivity,"Could not delete the file. Trash entry retained.",Toast.LENGTH_LONG).show()
                            }
                        }.show()
                }
                card.addView(actions)
                panel.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
                if(name.endsWith(".pdf",true) && !pagePreviewExecutor.isShutdown){
                    // Thumbnails are read off the UI thread and discarded after the dialog closes.
                    pagePreviewExecutor.execute{
                        val bitmap=if(owner==signedInUserId()) createPdfThumbnail(target) else null
                        if(bitmap!=null) runOnUiThread{
                            if(dialog.isShowing && owner==signedInUserId() && !isDestroyed){
                                preview.setImageBitmap(bitmap);preview.visibility=View.VISIBLE;fallback.visibility=View.GONE
                            }else bitmap.recycle()
                        }
                    }
                }
            }
        }
        val note=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(10),dp(12),dp(10))
            background=rounded(0xFFEFF5FF.toInt(),12)
        }
        note.addView(label("Recovery & safety",12f,0xFF172B4D.toInt(),true))
        note.addView(label("Restoring returns a document to your library. Permanent deletion requires confirmation and cannot be undone.",11f,0xFF52627A.toInt()))
        panel.addView(gap(9));panel.addView(note)
        var disposeAd:()->Unit={}
        dialog.setOnDismissListener{disposeAd()}
        dialog.show()
        disposeAd=addNativeAdCard(panel)
    }

    private fun showAppDrawer() {
        val drawer = android.app.Dialog(this)
        val screenWidth = resources.displayMetrics.widthPixels
        val drawerWidth = minOf(dp(400), (screenWidth * 0.90f).toInt())
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFF5F8FF.toInt(), 20)
            clipToOutline = true
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
        }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(18))
        }
        val signedIn = cloudToken().isNotBlank() && signedInUserId() != "guest"
        val signedInEmail = if (signedIn) prefs.getString("cloud_email", "").orEmpty() else ""
        val premium = hasVerifiedPremium()
        val online = isInternetAvailable()
        val driveEmail = prefs.getString(accountPrefKey("drive_last_email"), "").orEmpty()
        fun ripple(surface: android.graphics.drawable.Drawable, light: Boolean = false) =
            android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if(light)0x33FFFFFF else 0x223478F6),surface,null)
        fun addCard(view: View, bottom: Int = 10) {
            menu.addView(view, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(bottom) })
        }
        fun iconTile(symbol: String, tint: Int = 0xFF2563EB.toInt(), fill: Int = 0xFFEAF2FF.toInt(), drive: Boolean = false): FrameLayout =
            FrameLayout(this).apply {
                background=rounded(fill,12)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if(drive) addView(driveBrandIcon(),FrameLayout.LayoutParams(dp(25),dp(25),Gravity.CENTER))
                else addView(label(symbol,20f,tint,true).apply{gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
            }
        fun accountAction() { drawer.dismiss(); if(signedIn)cloudAccountMenu() else showCloudLoginDialog() }
        val hero=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(14),dp(14),dp(14),dp(14))
            background=gradient(0xFF103F91.toInt(),0xFF071E50.toInt(),18)
        }
        val top=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        top.addView(label("DOCNEXA  •  WORKSPACE",10f,0xFFC1D7FF.toInt(),true),LinearLayout.LayoutParams(0,-2,1f))
        val close=FrameLayout(this).apply {
            background=ripple(rounded(0x33FFFFFF,12),true)
            contentDescription="Close navigation menu"
            isClickable=true;isFocusable=true
            addView(label("×",23f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
            setOnClickListener{drawer.dismiss()}
        }
        top.addView(close,LinearLayout.LayoutParams(dp(48),dp(48)))
        hero.addView(top);hero.addView(gap(8))
        val account=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            isClickable=true;isFocusable=true
            background=ripple(rounded(0x12FFFFFF,14),true)
            setPadding(dp(8),dp(10),dp(8),dp(10))
            setOnClickListener{accountAction()}
        }
        val avatar=FrameLayout(this).apply {
            background=rounded(0xFF3478F6.toInt(),18,0xFF79B5FF.toInt())
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if(signedInEmail.isNotBlank()) avatar.addView(label(signedInEmail.take(1).uppercase(Locale.ROOT),25f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
        else avatar.addView(ImageView(this).apply{setImageResource(android.R.drawable.ic_menu_myplaces);setColorFilter(0xFFFFFFFF.toInt())},FrameLayout.LayoutParams(dp(30),dp(30),Gravity.CENTER))
        account.addView(avatar,LinearLayout.LayoutParams(dp(52),dp(52)))
        val accountText=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0)}
        accountText.addView(label(if(signedIn)"Your account" else "Welcome to DocNexa",16f,0xFFFFFFFF.toInt(),true))
        accountText.addView(label(if(signedIn)signedInEmail.ifBlank{"Signed-in account"} else "Sign in to access cloud features",12f,0xFFDCE8FF.toInt()).apply{maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END})
        account.addView(accountText,LinearLayout.LayoutParams(0,-2,1f));hero.addView(account)
        hero.addView(gap(10))
        val statuses=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        fun chip(text:String,fg:Int,bg:Int):TextView=label(text,11f,fg,true).apply{
            gravity=Gravity.CENTER;setPadding(dp(7),dp(7),dp(7),dp(7));background=rounded(bg,10)
        }
        statuses.addView(chip(if(online)"● Online" else "● Offline",if(online)0xFFB7F2CE.toInt() else 0xFFFFD4BE.toInt(),0x26FFFFFF),LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(4)})
        statuses.addView(chip(if(premium)"Premium active" else "Free plan",if(premium)0xFFFFD66B.toInt() else 0xFFDCE8FF.toInt(),0x26FFFFFF),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(4)})
        hero.addView(statuses);hero.addView(gap(10))
        val authActions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        authActions.addView(nativeButton(this,ButtonRole.Primary).apply{
            text=if(signedIn)"Account" else "Sign in"
            setOnClickListener{accountAction()}
        },LinearLayout.LayoutParams(0,-2,1f))
        authActions.addView(nativeButton(this,if(signedIn)ButtonRole.Danger else ButtonRole.Secondary).apply{
            text=if(signedIn)"Sign out" else "Sign up"
            setOnClickListener{drawer.dismiss();if(signedIn)confirmCloudSignOut() else showCloudLoginDialog(startWithSignup=true)}
        },LinearLayout.LayoutParams(0,-2,1f))
        hero.addView(authActions);addCard(hero)
        val membership=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(12),dp(14),dp(12),dp(14));minimumHeight=dp(82)
            background=ripple(gradient(0xFF173578.toInt(),0xFF633CB2.toInt(),16),true)
            isClickable=true;isFocusable=true
            setOnClickListener{drawer.dismiss();studioPremiumInfo()}
        }
        membership.addView(FrameLayout(this).apply{
            background=rounded(0x33FFFFFF,12)
            addView(homeHeaderGlyph("crown"),FrameLayout.LayoutParams(-1,-1))
        },LinearLayout.LayoutParams(dp(44),dp(48)))
        val membershipCopy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,dp(4),0)}
        membershipCopy.addView(label(if(premium)"Premium membership" else "Upgrade to Premium",16f,0xFFFFFFFF.toInt(),true))
        membershipCopy.addView(label(if(premium)"Manage your subscription" else "Ad-free • Advanced PDF • Pro tools",11f,0xFFE6DCFF.toInt()))
        membership.addView(membershipCopy,LinearLayout.LayoutParams(0,-2,1f))
        membership.addView(label("›",24f,0xFFFFD66B.toInt(),true));addCard(membership)
        var activeSection:LinearLayout=menu
        fun section(title:String,initiallyExpanded:Boolean=false) {
            val card=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL
                background=rounded(0xFFFFFFFF.toInt(),16,0xFFDCE7F5.toInt())
                setPadding(dp(6),dp(4),dp(6),dp(4))
            }
            val heading=LinearLayout(this).apply{
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
                minimumHeight=dp(48);setPadding(dp(10),dp(8),dp(10),dp(8))
                isClickable=true;isFocusable=true;background=ripple(rounded(0xFFFFFFFF.toInt(),12))
            }
            val group=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
            val stateKey="drawer_section_"+title.lowercase(Locale.ROOT).replace(" ","_")
            val expanded=prefs.getBoolean(stateKey,initiallyExpanded)
            group.visibility=if(expanded)View.VISIBLE else View.GONE
            heading.addView(label(title,14f,0xFF172B4D.toInt(),true),LinearLayout.LayoutParams(0,-2,1f))
            val arrow=label(if(expanded)"⌃" else "⌄",18f,0xFF64748B.toInt(),true)
            heading.addView(arrow)
            heading.contentDescription=title+if(expanded)", expanded" else ", collapsed"
            heading.setOnClickListener{
                val open=group.visibility!=View.VISIBLE
                group.visibility=if(open)View.VISIBLE else View.GONE
                arrow.text=if(open)"⌃" else "⌄"
                heading.contentDescription=title+if(open)", expanded" else ", collapsed"
                prefs.edit().putBoolean(stateKey,open).apply()
            }
            card.addView(heading);card.addView(group);addCard(card);activeSection=group
        }
        fun item(symbol:String,title:String,subtitle:String,drive:Boolean=false,action:()->Unit) {
            val row=LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(8),dp(7),dp(8),dp(7));minimumHeight=dp(62)
                isClickable=true;isFocusable=true
                background=ripple(rounded(0xFFF8FAFF.toInt(),12))
                setOnClickListener{drawer.dismiss();action()}
            }
            row.addView(iconTile(symbol,drive=drive),LinearLayout.LayoutParams(dp(40),dp(40)))
            val copy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,dp(5),0)}
            copy.addView(label(title,14f,0xFF172B4D.toInt(),true))
            copy.addView(label(subtitle,11f,0xFF64748B.toInt()))
            row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(label("›",21f,0xFF7186A6.toInt()))
            activeSection.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(4)})
        }
        section("General",true)
        item("♡","Favourite","Your starred documents"){showFavouriteDocs()}
        item("◇","Manage tags","Organize your files"){manageTagsDialog()}
        item("▤","Trash","Restore or remove deleted files"){showTrashScreen()}
        section("Backup & Cloud",true)
        item("","Google Drive & Backup",if(driveEmail.isNotBlank())"Previously authorized • Check access" else "Connect, upload and restore",drive=true){showDriveBackupHub()}
        section("Preferences",true)
        item("⚙","Settings","App preferences and defaults"){showSettingsDialog()}
        section("Tools")
        item("PDF","PDF Tools","Merge, split and convert"){showPdfTools()}
        item("▧","Image Studio","Resize, convert and enhance"){showImageStudio()}
        item("₹","Bill / Invoice Maker","Create professional billing PDFs"){showInvoiceStudio()}
        item("▤","All Document Viewer","Read PDF, Office and image files"){showDocumentViewerHub()}
        item("✦","AI Document Assistant","Work with document content"){showAiAssistant()}
        item("▣","Edit/Create PDF","Create and edit documents"){showEditCreateFileChoices()}
        item("✍","Signature","Create your signature"){openSignatureEditor()}
        item("▦","QR Scanner","Scan QR codes"){showQrScanner()}
        item("▦","QR Generator","Create QR codes"){showQrGenerator()}
        section("Membership")
        item("♛","Compare plans","Explore Free and Premium"){studioPremiumInfo()}
        item("↻","Restore purchase","Recover eligible membership"){if(cloudToken().isBlank())showCloudLoginDialog() else refreshPlayPurchases(true)}
        item("⚙","Manage subscription","Open Google Play billing"){managePlaySubscription()}
        section("Other")
        item("?","Help and FAQ","Answers and guidance"){showHelpAndFaq()}
        item("✉","Contact us","Get in touch with support"){openFeedbackEmail(false)}
        item("★","Send feedback","Share your suggestions"){openFeedbackEmail(false,true)}
        item("⚑","Report a bug","Report an issue"){openFeedbackEmail(true)}
        item("ⓘ","About us","Learn about DocNexa"){showAboutDocNexa()}
        section("Privacy & Legal")
        item("▣","Privacy Policy","How data is handled"){showPrivacyPolicy()}
        item("§","Terms of Use","Service terms"){showTermsOfUse()}
        item("⌑","Data and account help","Account and data controls"){showDataHelp()}
        section("More")
        item("↗","Share with friends","Share DocNexa"){shareApp()}
        item("★","Rate us","Open the store listing"){openRateUs()}
        @Suppress("DEPRECATION")
        val version=runCatching{packageManager.getPackageInfo(packageName,0).versionName}.getOrNull().orEmpty()
        val footer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(8),dp(10),dp(8),dp(8))}
        footer.addView(label("DocNexa"+if(version.isNotBlank())" • v"+version else "",12f,0xFF172B4D.toInt(),true))
        footer.addView(label("PDF Scanner & Editor",11f,0xFF64748B.toInt()))
        menu.addView(footer)
        scroll.addView(menu);panel.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        drawer.setContentView(panel)
        drawer.setCanceledOnTouchOutside(true)
        drawer.window?.apply{
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.48f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(drawerWidth,-1)
            setGravity(Gravity.START)
            navigationBarColor=0xFFF5F8FF.toInt()
        }
        drawer.show()
        trackPrivacyWindow(drawer.window)
        drawer.window?.setLayout(drawerWidth,-1)
        if (!isInternetAvailable() || appLockRequired()) refreshStrictOnlineGate(raise = true)
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
            "Scanned PDFs are saved in DocNexa app storage on your device.",
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
                    showPremiumDialog(appDialog().setTitle(topic)
                        .setMessage(answers[index]).setPositiveButton("Got it", null)
                        .setNeutralButton("Contact support") { _, _ -> openFeedbackEmail(false) })
                }
            }
            panel.addView(row)
        }
        showPremiumDialog(appDialog().setTitle("DocNexa • Help & FAQ")
            .setView(panel).setNegativeButton("Close", null))
    }

    private fun openFeedbackEmail(reportBug: Boolean, feedback: Boolean = false) {
        val recipient = "business@pixelfirestudio.in"
        val subject = when {
            reportBug -> "DocNexa bug report"
            feedback -> "DocNexa app feedback"
            else -> "DocNexa support request"
        }
        val appVersion = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0" } catch (_: Exception) { "1.0" }
        val body = when {
            reportBug -> "Please describe the problem below:\n\nApp version: ${appVersion}\nAndroid: ${Build.VERSION.RELEASE}\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\n\nSteps to reproduce:\n\nExpected result:\n\nActual result:"
            feedback -> "Hello DocNexa team,\n\nMy feedback or suggestion:\n\nWhat I like about DocNexa:\n\nWhat could be improved:\n\nApp version: ${appVersion}"
            else -> "Hello DocNexa team,\n\nI need help with:\n\nApp version: ${appVersion}"
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$recipient")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(Intent.createChooser(intent, when {
                reportBug -> "Send bug report"
                feedback -> "Send feedback"
                else -> "Contact DocNexa"
            }))
        } catch (_: Exception) {
            appDialog().setTitle("Email app not found")
                .setMessage("Please install or set up an email app on your phone, then try again.")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun showAboutDocNexa() {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0" } catch (_: Exception) { "1.0" }
        appDialog().setTitle("About DocNexa")
            .setMessage("DOCNEXA\nPDF Scanner & Editor\nVersion $version\n\nScan, organise and manage PDF documents from your Android device. Save PDFs locally and optionally use an account for cloud backup and sync.\n\nDocNexa is provided as a document productivity tool. Always keep a separate backup of important files.")
            .setPositiveButton("OK", null).show()
    }

    private fun showPrivacyPolicy() {
        val policy = """
            DOCNEXA – PRIVACY POLICY
            Last updated: 28 November 2026

            1. Account information
            Supabase processes your email address, account ID, authentication credentials and session tokens to provide sign-in, verification, password recovery, cloud sync and account deletion. DocNexa does not store your password in readable form.

            2. Documents and permissions
            Scans, images and PDFs are processed to provide scanner, OCR, conversion and PDF tools. Local documents are stored in private app storage. Camera is used when you choose scanning or live QR scanning. Android's file picker is used when you choose a file. The app does not automatically upload every local document.

            Internet access is required to use the app. Local processing can still take place on-device; an internet connection does not mean your files are uploaded. Optional App Lock uses Android biometric authentication or device credentials, which DocNexa does not collect or store. When enabled, screenshots are blocked in DocNexa windows.

            3. Optional Cloud Sync
            When you sign in and choose Cloud Sync, PDFs are uploaded to a private Supabase Storage bucket in a folder associated with your account ID. Signing out does not delete cloud files. In-app account deletion removes your DocNexa cloud account and its synced PDFs when the deletion service succeeds. Local files and copies exported elsewhere remain on your device.

            4. AI features
            When you choose an AI document feature, extracted document text and the task/prompt are sent over HTTPS to DocNexa's Supabase-hosted AI service for processing. Do not submit sensitive information unless you are comfortable with this processing. AI results can be inaccurate.

            5. Analytics and diagnostics
            The app uses Firebase Analytics for app usage events, Crashlytics for crash diagnostics, Firebase Performance Monitoring for performance data, Remote Config for app settings/announcements, and Firebase Cloud Messaging for service announcements. These services may process app/device identifiers, app and device information, diagnostic data and notification tokens.

            6. Advertising
            DocNexa contains Google Mobile Ads, including native ads. Google and its advertising partners may process device identifiers, IP-derived approximate location, ad interactions and advertising information to deliver, measure and protect ads, subject to device settings and Google's policies.

            7. Sharing, retention and deletion
            The app does not share a document with another person unless you choose an export/share action. Local files remain until you delete them, clear app data or uninstall the app. Exported copies may remain in other apps or folders. Cloud files remain until deleted through account features or account deletion. Service providers may retain diagnostics and advertising data under their own policies.

            8. Children, security and contact
            DocNexa is not designed for children. Network requests to DocNexa services use HTTPS and local documents are stored in Android app-private storage. No system is completely secure. For privacy questions, contact business@pixelfirestudio.in.

            Full policy and external account deletion request:
            https://ravivadhiya11.github.io/smart-pdf-scanner-pro/privacy-policy.html
            https://ravivadhiya11.github.io/smart-pdf-scanner-pro/delete-account.html
        """.trimIndent()
        val view = TextView(this).apply {
            text = policy
            textSize = 13f
            setTextColor(0xFF263957.toInt())
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(view) }
        showPremiumDialog(appDialog().setTitle("Privacy Policy")
            .setView(scroll).setPositiveButton("Close", null)
            .setNeutralButton("Open online policy") { _, _ ->
                CustomTabsIntent.Builder().build().launchUrl(
                    this,
                    Uri.parse("https://ravivadhiya11.github.io/smart-pdf-scanner-pro/privacy-policy.html")
                )
            })
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
            • DocNexa requires a validated internet connection. Optional cloud features depend on the configured third-party service and its availability.
            • Features may change as the app is updated.

            By using DocNexa, you agree to use it responsibly and understand these limitations.
        """.trimIndent()
        val view = TextView(this).apply {
            text = terms; textSize = 13f; setTextColor(0xFF263957.toInt())
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        showPremiumDialog(appDialog().setTitle("Terms of Use")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Close", null))
    }

    private fun showDataHelp() {
        appDialog().setTitle("Your data and account")
            .setMessage("Local PDFs are stored inside DocNexa and may be removed when you uninstall the app or clear app data. Use Save As to keep a separate device copy. Cloud sync is optional. Signing out does not erase local PDFs or cloud copies. In-app account deletion removes your DocNexa cloud account and synced cloud PDFs when successful; local and exported copies remain. You can also request deletion at https://ravivadhiya11.github.io/smart-pdf-scanner-pro/delete-account.html. Never send your password.")
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

    private val socialRedirectUri = "docnexa://auth-callback"

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun beginSocialSignIn(provider: String) {
        if (!requireInternet("Social sign-in")) return
        try {
            val random = SecureRandom()
            val verifierBytes = ByteArray(32).also { random.nextBytes(it) }
            val verifier = base64Url(verifierBytes)
            val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            val state = base64Url(ByteArray(24).also { random.nextBytes(it) })
            prefs.edit()
                .putString("oauth_pkce_verifier", verifier)
                .putString("oauth_state", state)
                .putString("oauth_provider", provider)
                .apply()
            val url = SupabaseCloud.buildOAuthUrl(provider, socialRedirectUri, challenge, state)
            // Secure Chrome Custom Tab keeps OAuth visually attached to DocNexa.
            // Google sign-in must use a trusted browser surface, not an embedded WebView.
            val customTab = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setToolbarColor(android.graphics.Color.rgb(16, 20, 38))
                .build()
            customTab.launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            prefs.edit().remove("oauth_pkce_verifier").remove("oauth_state").remove("oauth_provider").apply()
            Toast.makeText(this, e.localizedMessage ?: "Could not start social sign-in.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSocialAuthIntent(intent: Intent?) {
        val callback = intent?.data ?: return
        if (callback.scheme != "docnexa" || callback.host != "auth-callback") return
        val error = callback.getQueryParameter("error_description")
            ?: callback.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            prefs.edit().remove("oauth_pkce_verifier").remove("oauth_state").remove("oauth_provider").apply()
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        val code = callback.getQueryParameter("code")
        val returnedState = callback.getQueryParameter("state")
        val expectedState = prefs.getString("oauth_state", null)
        val verifier = prefs.getString("oauth_pkce_verifier", null)
        if (code.isNullOrBlank() || returnedState.isNullOrBlank() || expectedState.isNullOrBlank() ||
            verifier.isNullOrBlank() || !MessageDigest.isEqual(returnedState.toByteArray(), expectedState.toByteArray())) {
            Toast.makeText(this, "Sign-in callback could not be verified. Please try again.", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().remove("oauth_state").remove("oauth_pkce_verifier").remove("oauth_provider").apply()
        Toast.makeText(this, "Completing secure sign-in…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val result = SupabaseCloud.exchangeOAuthCode(code, verifier)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    prefs.edit()
                        .putString("cloud_access_token", result.accessToken)
                        .putString("cloud_refresh_token", result.refreshToken)
                        .putString("cloud_user_id", result.userId)
                        .putString("cloud_email", result.email)
                        .apply()
                    Toast.makeText(this, "Welcome to DocNexa Cloud.", Toast.LENGTH_LONG).show()
                    syncCloudNow()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, e.localizedMessage ?: "Social sign-in failed.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSocialAuthIntent(intent)
        openNotificationPdf(intent)
        handleViewerIntent(intent)
    }

    private fun handleViewerIntent(intent:Intent?){
        if(intent?.action!=Intent.ACTION_VIEW)return
        val uri=intent.data ?:return
        if(uri.scheme!="content")return
        val owner=signedInUserId()
        window.decorView.post{if(!isFinishing&&!isDestroyed&&owner==signedInUserId())openDocumentViewer(uri)}
    }

    private fun openNotificationPdf(intent: Intent?) {
        val raw = intent?.getStringExtra("notification_pdf_uri") ?: return
        intent.removeExtra("notification_pdf_uri")
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        if (uri.authority == "$packageName.fileprovider" && privateDocumentFile(uri) == null) {
            Toast.makeText(this, "This PDF belongs to a different DocNexa account. Sign in to that account to open it.", Toast.LENGTH_LONG).show()
            return
        }
        window.decorView.post { if (!isFinishing && !isDestroyed) openPdf(uri) }
    }

    private fun cloudToken(): String = prefs.getString("cloud_access_token", "") ?: ""

    private fun showCloudLoginDialog(startWithSignup: Boolean = false) {
        if (!requireInternet("Login and account creation")) return
        val dialog = android.app.Dialog(this)
        var signupMode = startWithSignup

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = rounded(0xFFFFFFFF.toInt(), 26, 0xFFDCE8FF.toInt())
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val logo = TextView(this).apply {
            text = "D"
            textSize = 30f
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = gradient(0xFF073B91.toInt(), 0xFF3478F6.toInt(), 18)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
        }
        content.addView(logo)
        content.addView(gap(10))
        content.addView(label("DOCNEXA", 22f, 0xFF172B4D.toInt(), true))
        content.addView(gap(5))
        val subtitle = label(
            "Your documents, securely organised.",
            12f, 0xFF718096.toInt()
        )
        content.addView(subtitle)
        content.addView(gap(16))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(0xFFEAF2FF.toInt(), 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun tabButton(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        val loginTab = tabButton("SIGN IN")
        val signupTab = tabButton("CREATE ACCOUNT")
        tabs.addView(loginTab, LinearLayout.LayoutParams(0, dp(46), 1f))
        tabs.addView(signupTab, LinearLayout.LayoutParams(0, dp(46), 1f))
        content.addView(tabs)
        content.addView(gap(14))

        fun authField(hintText: String, inputType: Int): EditText = EditText(this).apply {
            hint = hintText
            setSingleLine(true)
            textSize = 14f
            setTextColor(0xFF172B4D.toInt())
            setHintTextColor(0xFF8A98AD.toInt())
            this.inputType = inputType
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(0xFFF5F8FF.toInt(), 13, 0xFFD4E1F5.toInt())
        }

        val name = authField(
            "Your full name",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        )
        val email = authField(
            "you@example.com",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )
        val password = authField(
            "Password (minimum 8 characters)",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        fun captionView(text: String): TextView = label(text, 10f, 0xFF52627D.toInt(), true).apply {
            gravity = Gravity.START
            setPadding(dp(2), 0, 0, dp(6))
        }
        val nameCaption = captionView("FULL NAME")
        val emailCaption = captionView("EMAIL ADDRESS")
        val passwordCaption = captionView("PASSWORD")
        content.addView(nameCaption)
        content.addView(name, LinearLayout.LayoutParams(-1, dp(50)))
        content.addView(gap(10))
        content.addView(emailCaption)
        content.addView(email, LinearLayout.LayoutParams(-1, dp(50)))
        content.addView(gap(10))
        content.addView(passwordCaption)
        val passwordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFFF5F8FF.toInt(), 13, 0xFFD4E1F5.toInt())
            clipToOutline = true
        }
        password.background = null
        password.setPadding(dp(15), dp(12), dp(4), dp(12))
        passwordRow.addView(password, LinearLayout.LayoutParams(0, dp(50), 1f))
        val showPassword = TextView(this).apply {
            text = "SHOW"
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF3478F6.toInt())
            setPadding(dp(10), 0, dp(14), 0)
        }
        showPassword.setOnClickListener {
            val visible = password.inputType == (android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            password.inputType = if (visible) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            showPassword.text = if (visible) "SHOW" else "HIDE"
            password.setSelection(password.text?.length ?: 0)
        }
        passwordRow.addView(showPassword, LinearLayout.LayoutParams(-2, -1))
        content.addView(passwordRow, LinearLayout.LayoutParams(-1, dp(50)))
        content.addView(gap(8))

        val recovery = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val forgot = TextView(this).apply {
            text = "Forgot password?"
            textSize = 12f
            setTextColor(0xFF3478F6.toInt())
            setPadding(dp(2), dp(8), dp(8), dp(8))
            setOnClickListener { sendCloudPasswordReset(email.text.toString().trim()) }
        }
        val resend = TextView(this).apply {
            text = "Resend verification"
            textSize = 12f
            setTextColor(0xFF3478F6.toInt())
            gravity = Gravity.END
            setPadding(dp(8), dp(8), dp(2), dp(8))
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
            background = gradient(0xFF073B91.toInt(), 0xFF3478F6.toInt(), 14)
            backgroundTintList = null
            layoutParams = LinearLayout.LayoutParams(-1, dp(50))
        }
        content.addView(submit)
        content.addView(gap(11))
        content.addView(label("OR CONTINUE WITH", 10f, 0xFF718096.toInt(), true).apply {
            gravity = Gravity.CENTER
        })
        content.addView(gap(7))

        val socialButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun socialButton(title: String, backgroundColor: Int, textColor: Int, provider: String): TextView =
            TextView(this).apply {
                text = title
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(textColor)
                background = rounded(backgroundColor, 13, 0xFFD4E1F5.toInt())
                setOnClickListener { beginSocialSignIn(provider) }
            }
        socialButtons.addView(
            socialButton("G   Google", 0xFFFFFFFF.toInt(), 0xFF172B4D.toInt(), "google"),
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(6) }
        )
        socialButtons.addView(
            socialButton("f   Facebook", 0xFFEAF2FF.toInt(), 0xFF073B91.toInt(), "facebook"),
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) }
        )
        content.addView(socialButtons)
        content.addView(gap(8))

        val guest = TextView(this).apply {
            text = "Continue as Guest"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF3478F6.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(10))
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(guest)
        content.addView(gap(2))
        content.addView(label(
            "Your account is protected. Your password is securely handled by Supabase.",
            10f, 0xFF718096.toInt()
        ).apply { gravity = Gravity.CENTER })
        scroll.addView(content)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        dialog.setContentView(panel)

        fun renderMode() {
            nameCaption.visibility = if (signupMode) View.VISIBLE else View.GONE
            name.visibility = if (signupMode) View.VISIBLE else View.GONE
            forgot.visibility = if (signupMode) View.GONE else View.VISIBLE
            loginTab.setTextColor(if (signupMode) 0xFF718096.toInt() else 0xFF073B91.toInt())
            loginTab.background = if (signupMode) null else rounded(0xFFFFFFFF.toInt(), 11)
            signupTab.setTextColor(if (signupMode) 0xFF073B91.toInt() else 0xFF718096.toInt())
            signupTab.background = if (signupMode) rounded(0xFFFFFFFF.toInt(), 11) else null
            submit.text = if (signupMode) "CREATE ACCOUNT  →" else "SIGN IN  →"
            subtitle.text = if (signupMode) {
                "Create your secure account and sync your documents."
            } else {
                "Welcome back. Sign in to access your cloud documents."
            }
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
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val dialogWidth = minOf((screenWidth * 0.94f).toInt(), dp(520))
        val dialogHeight = minOf((screenHeight * 0.86f).toInt(), screenHeight - dp(80))
        trackPrivacyWindow(dialog.window)
        dialog.window?.setLayout(dialogWidth, dialogHeight)
        if (!isInternetAvailable() || appLockRequired()) refreshStrictOnlineGate(raise = true)
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
                            .putString("cloud_refresh_token", result.refreshToken)
                            .putString("cloud_user_id", result.userId)
                            .putString("cloud_email", email).apply()
                        dialog.dismiss()
                        Toast.makeText(this, "Welcome to DocNexa Cloud.", Toast.LENGTH_LONG).show()
                        syncCloudNow()
                    } else if (signUp) {
                        appDialog()
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

    // Optional app-access gate uses Android's own device credential challenge.
    // No PIN, password or biometric data is collected or stored by DocNexa.
    private val privacyWindows = java.util.WeakHashMap<android.view.Window,Boolean>()
    private var privacyGateDialog: android.app.Dialog? = null
    private var appLockUnlocked = false
    private var credentialOperation: String? = null
    private fun appLockEnabled() = prefs.getBoolean("device_credential_app_lock",false)
    private fun appLockRequired() = appLockEnabled() && !appLockUnlocked
    private fun keyguardManager() = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
    private fun trackPrivacyWindow(target: android.view.Window?) {
        if(target==null)return
        privacyWindows[target]=true
        if(appLockEnabled())target.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else target.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun refreshPrivacyWindowFlags(){
        privacyWindows.keys.toList().forEach{trackPrivacyWindow(it)}
    }
    private fun completeCredential(success:Boolean){
        val operation=credentialOperation
        credentialOperation=null
        if(operation==null)return
        if(success){
            when(operation){
                "enable" -> {
                    prefs.edit().putBoolean("device_credential_app_lock",true).apply()
                    appLockUnlocked=true
                    Toast.makeText(this,"App Lock enabled",Toast.LENGTH_SHORT).show()
                }
                "disable" -> {
                    prefs.edit().putBoolean("device_credential_app_lock",false).apply()
                    appLockUnlocked=false
                    Toast.makeText(this,"App Lock disabled",Toast.LENGTH_SHORT).show()
                }
                "unlock" -> appLockUnlocked=true
            }
            refreshPrivacyWindowFlags()
        }else if(operation=="unlock"){
            // Cancellation must not expose the underlying document or tool.
            finish()
            return
        }
        refreshStrictOnlineGate()
    }
    private val credentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        completeCredential(result.resultCode==android.app.Activity.RESULT_OK)
    }
    private val biometricPrompt: androidx.biometric.BiometricPrompt by lazy {
        androidx.biometric.BiometricPrompt(this,ContextCompat.getMainExecutor(this),
            object:androidx.biometric.BiometricPrompt.AuthenticationCallback(){
                override fun onAuthenticationSucceeded(result:androidx.biometric.BiometricPrompt.AuthenticationResult){
                    completeCredential(true)
                }
                override fun onAuthenticationError(errorCode:Int,errString:CharSequence){
                    val operation=credentialOperation ?: return
                    if(errorCode==androidx.biometric.BiometricPrompt.ERROR_LOCKOUT ||
                        errorCode==androidx.biometric.BiometricPrompt.ERROR_LOCKOUT_PERMANENT ||
                        errorCode==androidx.biometric.BiometricPrompt.ERROR_HW_UNAVAILABLE ||
                        errorCode==androidx.biometric.BiometricPrompt.ERROR_NO_BIOMETRICS){
                        launchSystemCredential(operation)
                    }else completeCredential(false)
                }
            })
    }
    @Suppress("DEPRECATION")
    private fun launchSystemCredential(operation:String){
        val intent=keyguardManager().createConfirmDeviceCredentialIntent("DocNexa","Confirm your device screen lock")
        if(intent==null){completeCredential(false);return}
        credentialOperation=operation
        try{credentialLauncher.launch(intent)}
        catch(_:Exception){completeCredential(false)}
    }
    private fun requestDeviceCredential(operation:String){
        if(credentialOperation!=null || isFinishing || isDestroyed)return
        if(!keyguardManager().isDeviceSecure){
            Toast.makeText(this,"Set a PIN, pattern or password in Android Security settings first.",Toast.LENGTH_LONG).show()
            if(operation=="unlock")finish()
            else runCatching{startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))}
            return
        }
        credentialOperation=operation
        val biometric=if(Build.VERSION.SDK_INT>=30) androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
            else androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
        val manager=androidx.biometric.BiometricManager.from(this)
        if(manager.canAuthenticate(biometric)!=androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS){
            launchSystemCredential(operation)
            return
        }
        try{
            val prompt=androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock DocNexa")
                .setSubtitle("Verify your identity")
                .setAllowedAuthenticators(biometric or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            biometricPrompt.authenticate(prompt)
        }catch(_:Exception){launchSystemCredential(operation)}
    }
    private fun refreshPrivacyGate(raise:Boolean=false){
        if(!onlineForeground||isFinishing||isDestroyed)return
        if(!appLockRequired()){
            privacyGateDialog?.dismiss();privacyGateDialog=null
            return
        }
        // A blank, secure privacy cover prevents previews behind the OS prompt.
        // No custom unlock dialog or extra button is shown.
        if(privacyGateDialog?.isShowing!=true || (raise && credentialOperation==null)){
            privacyGateDialog?.dismiss()
            val cover=android.app.Dialog(this,android.R.style.Theme_Material_Light_NoActionBar)
            cover.setCancelable(false);cover.setCanceledOnTouchOutside(false)
            cover.setContentView(View(this).apply{setBackgroundColor(0xFFF5F8FF.toInt())})
            privacyGateDialog=cover
            cover.show()
            trackPrivacyWindow(cover.window)
            cover.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
        }
        if(credentialOperation==null){
            window.decorView.post{
                if(onlineForeground && appLockRequired() && credentialOperation==null &&
                    !isFinishing && !isDestroyed && isInternetAvailable() && !supportFragmentManager.isStateSaved)
                    requestDeviceCredential("unlock")
            }
        }
    }

    private var onlineGateDialog: android.app.Dialog? = null
    private var onlineGateStatus: TextView? = null
    private var onlineForeground = false
    private var onlineCallbackRegistered = false
    private var onlineConsentRequested = false
    private val onlineHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val onlineMonitor = object : Runnable {
        override fun run() {
            if (!onlineForeground || isFinishing || isDestroyed) return
            refreshStrictOnlineGate()
            onlineHandler.postDelayed(this, 1500L)
        }
    }
    private val onlineNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = scheduleOnlineRefresh()
        override fun onLost(network: android.net.Network) = scheduleOnlineRefresh()
        override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) = scheduleOnlineRefresh()
    }
    private fun scheduleOnlineRefresh() {
        onlineHandler.post {
            if (onlineForeground && !isFinishing && !isDestroyed) refreshStrictOnlineGate()
        }
    }
    private fun startStrictOnlineMode() {
        onlineForeground = true
        if (!onlineCallbackRegistered) {
            val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            onlineCallbackRegistered = runCatching {
                manager.registerDefaultNetworkCallback(onlineNetworkCallback)
            }.isSuccess
        }
        refreshStrictOnlineGate()
        onlineHandler.removeCallbacks(onlineMonitor)
        onlineHandler.postDelayed(onlineMonitor, 1500L)
    }
    private fun stopStrictOnlineMonitoring() {
        onlineForeground = false
        onlineHandler.removeCallbacksAndMessages(null)
        if (onlineCallbackRegistered) {
            onlineCallbackRegistered = false
            val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { manager.unregisterNetworkCallback(onlineNetworkCallback) }
        }
    }
    private fun isInternetAvailable(): Boolean = runCatching {
        val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
    }.getOrDefault(false)

    private fun refreshStrictOnlineGate(raise: Boolean = false) {
        if (!onlineForeground || isFinishing || isDestroyed) return
        if (isInternetAvailable()) {
            onlineGateDialog?.dismiss()
            onlineGateDialog = null
            onlineGateStatus = null
            refreshPrivacyGate(raise)
            if (appLockRequired()) return
            if (!onlineConsentRequested) {
                onlineConsentRequested = true
                requestAdConsent()
            }
            return
        }
        // Only re-create the gate when another app-owned window was attached above it.
        // The existing activity and tool dialogs stay intact, including in-progress work.
        if (onlineGateDialog?.isShowing == true && !raise) return
        onlineGateDialog?.dismiss()
        val blocker = android.app.Dialog(this, android.R.style.Theme_Material_Light_NoActionBar)
        blocker.setCancelable(false)
        blocker.setCanceledOnTouchOutside(false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18),dp(20),dp(18),dp(20))
            background = gradient(0xFFF5F8FF.toInt(),0xFFE8F0FF.toInt(),0)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root,ViewGroup.LayoutParams(-1,-2))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18),dp(22),dp(18),dp(18))
            background = rounded(0xFFFFFFFF.toInt(),24,0xFFDCE7F5.toInt())
        }
        val art = FrameLayout(this).apply {
            background = rounded(0xFFE6EFFF.toInt(),22)
            addView(object:View(this@MainActivity){
                private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF2563EB.toInt();style = Paint.Style.STROKE
                    strokeWidth=2.5f;strokeCap=Paint.Cap.ROUND
                }
                override fun onDraw(canvas:Canvas){
                    super.onDraw(canvas)
                    val side=minOf(width,height).toFloat()
                    if(side<=0f)return
                    canvas.save();canvas.translate((width-side)/2f,(height-side)/2f);canvas.scale(side/48f,side/48f)
                    canvas.drawArc(3f,10f,45f,45f,225f,90f,false,ink)
                    canvas.drawArc(11f,19f,37f,42f,225f,90f,false,ink)
                    canvas.drawCircle(24f,35f,1.5f,ink)
                    ink.color=0xFFFFFFFF.toInt();ink.strokeWidth=6f;canvas.drawLine(9f,8f,39f,39f,ink)
                    ink.color=0xFF2563EB.toInt();ink.strokeWidth=2.5f;canvas.drawLine(9f,8f,39f,39f,ink)
                    canvas.restore()
                }
            },FrameLayout.LayoutParams(dp(62),dp(62),Gravity.CENTER))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(art,LinearLayout.LayoutParams(dp(90),dp(90)))
        content.addView(gap(14))
        content.addView(label("No Internet Connection",22f,0xFF102B60.toInt(),true).apply{gravity=Gravity.CENTER})
        content.addView(gap(7))
        content.addView(label("DocNexa requires an active internet connection. Connect to Wi-Fi or mobile data to continue.",13f,0xFF52627A.toInt()).apply{gravity=Gravity.CENTER})
        content.addView(gap(12))
        val connection = label("Waiting for a verified internet connection…",12f,0xFF52627A.toInt()).apply {
            gravity=Gravity.CENTER
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        onlineGateStatus=connection
        content.addView(connection)
        content.addView(gap(12))
        content.addView(nativeButton(this,ButtonRole.Primary).apply {
            this.text="Try Again"
            setOnClickListener {
                if(isInternetAvailable())refreshStrictOnlineGate()
                else onlineGateStatus?.text="Still offline. Check Wi-Fi or mobile data."
            }
        },LinearLayout.LayoutParams(-1,-2))
        content.addView(nativeButton(this,ButtonRole.Secondary).apply {
            this.text="Network Settings"
            setOnClickListener{
                runCatching{startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))}
                    .onFailure{runCatching{startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))}}
            }
        },LinearLayout.LayoutParams(-1,-2))
        content.addView(gap(8))
        content.addView(label("Your saved documents are safe. Local processing already started may finish.",11f,0xFF52627A.toInt()).apply{gravity=Gravity.CENTER})
        content.addView(gap(10))
        content.addView(label("DOCNEXA • SECURE DOCUMENT STUDIO",10f,0xFF174C94.toInt(),true).apply{gravity=Gravity.CENTER})
        root.addView(content,LinearLayout.LayoutParams(minOf(dp(440),(resources.displayMetrics.widthPixels-dp(36)).coerceAtLeast(dp(1))),-2))
        blocker.setContentView(scroll)
        onlineGateDialog=blocker
        blocker.show()
        trackPrivacyWindow(blocker.window)
        blocker.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFFF5F8FF.toInt()))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun requireInternet(feature: String): Boolean {
        if (isInternetAvailable()) return true
        refreshStrictOnlineGate()
        return false
    }

    private fun cloudAccountMenu() {
        if (!requireInternet("DocNexa Cloud account")) return
        appDialog().setTitle("DocNexa Cloud")
            .setMessage("Connected: ${prefs.getString("cloud_email", "")}")
            .setPremiumItems(arrayOf("Sync now", "Sign out")) { _, which ->
                if (which == 0) syncCloudNow() else confirmCloudSignOut()
            }.setNegativeButton("Close", null).show()
    }

    private fun confirmCloudSignOut() {
        appDialog()
            .setTitle("Sign out of DocNexa?")
            .setMessage("Your cloud account will be disconnected on this device. Its PDFs stay safely stored under that account and will appear again when you sign back into the same email.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign out") { _, _ ->
                prefs.edit().remove("cloud_access_token")
                    .remove("cloud_refresh_token")
                    .remove("cloud_user_id")
                    .remove("cloud_email")
                    .apply()
                lastPdf = null
                pendingDocumentNotification = null
                pendingDocumentUri = null
                androidx.core.app.NotificationManagerCompat.from(this).cancel(2101)
                loadFolders()
                Toast.makeText(this, "Signed out. This account’s PDFs are kept separate and safe.", Toast.LENGTH_LONG).show()
                buildScreen()
            }
            .show()
    }

    private fun confirmCloudAccountDeletion() {
        appDialog()
            .setTitle("Delete DocNexa account permanently?")
            .setMessage(
                "This permanently deletes your DocNexa Cloud account and PDFs synced to DocNexa Cloud. " +
                    "PDFs saved locally on this phone will not be deleted. This action cannot be undone."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                val token = cloudToken()
                if (token.isBlank()) {
                    Toast.makeText(this, "Please sign in again before deleting your account.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                Toast.makeText(this, "Deleting DocNexa Cloud account…", Toast.LENGTH_SHORT).show()
                Thread {
                    try {
                        SupabaseCloud.deleteAccount(token)
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                prefs.edit()
                                    .remove("cloud_access_token")
                                    .remove("cloud_refresh_token")
                                    .remove("cloud_user_id")
                                    .remove("cloud_email")
                                    .apply()
                                lastPdf = null
                                loadFolders()
                                Toast.makeText(this, "Your DocNexa Cloud account was deleted.", Toast.LENGTH_LONG).show()
                                buildScreen()
                            }
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(
                                    this,
                                    error.localizedMessage ?: "Account deletion failed. Please contact support.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }.start()
            }
            .show()
    }

    private fun syncCloudNow() {
        if (!requireInternet("Manual Cloud Sync")) return
        val token = cloudToken()
        val userId = prefs.getString("cloud_user_id", "") ?: ""
        if (token.isBlank() || userId.isBlank()) { showCloudLoginDialog(); return }
        val localDocs = getSavedPdfs()
        status.text = "Syncing this account with DocNexa Cloud…"
        Thread {
            try {
                var activeToken = token
                val refreshToken = prefs.getString("cloud_refresh_token", "") ?: ""
                if (refreshToken.isNotBlank()) {
                    try {
                        val refreshed = SupabaseCloud.refreshSession(refreshToken)
                        if (refreshed.userId != userId) throw IllegalStateException("The refreshed session belongs to a different account. Please sign in again.")
                        activeToken = refreshed.accessToken
                        prefs.edit()
                            .putString("cloud_access_token", refreshed.accessToken)
                            .putString("cloud_refresh_token", refreshed.refreshToken)
                            .putString("cloud_email", refreshed.email)
                            .apply()
                    } catch (refreshError: Exception) {
                        android.util.Log.w("DocNexa", "Session refresh failed; trying the current access token", refreshError)
                    }
                }
                var uploaded = 0
                for ((name, uri) in localDocs) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Could not read $name")
                    SupabaseCloud.upload(activeToken, userId, name, bytes)
                    uploaded++
                }
                val remoteFiles = SupabaseCloud.list(activeToken, userId)
                var downloaded = 0
                val localNames = getSavedPdfs().map { it.first }.toMutableSet()
                for (name in remoteFiles) {
                    if (name !in localNames && name.endsWith(".pdf", true)) {
                        val bytes = SupabaseCloud.download(activeToken, userId, name)
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
        panel.addView(gap(10))
        // Native ad is shown only in this spacious dialog; no ad is placed beside action buttons.
        addNativeAdCard(panel)
        showPremiumDialog(appDialog().setTitle("DocNexa • Manage Tags").setView(panel)
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

        section("MEMBERSHIP")
        setting("♛", "DocNexa Premium", if(hasVerifiedPremium()) "Active • manage your subscription" else "Compare Free, Monthly and Yearly plans") { studioPremiumInfo() }
        setting("↻", "Restore Purchase", "Check your Google Play membership") { if(cloudToken().isBlank()) showCloudLoginDialog() else refreshPlayPurchases(true) }
        section("CLOUD STORAGE & BACKUP")
        setting("☁", "Google Drive & Backup", "Import, save and restore selected files") { showDriveBackupHub() }
        section("PRIVACY & SECURITY")
        setting("◆","App Lock",if(appLockEnabled())"Enabled • fingerprint or device screen lock" else "Protect DocNexa with fingerprint, PIN or password"){
            requestDeviceCredential(if(appLockEnabled())"disable" else "enable")
        }
        setting("●","Internet required","Validated Wi-Fi or mobile data is required to use DocNexa"){refreshStrictOnlineGate()}
        section("DOCUMENT PREFERENCES")
        setting("⇅", "Default sort order", sortNames[currentSort]) {
            appDialog().setTitle("Sort documents")
                .setSingleChoiceItems(sortNames, currentSort) { dialog, which ->
                    prefs.edit().putInt("document_sort_order", which).apply()
                    dialog.dismiss()
                    showDocsScreen()
                }.setNegativeButton("Cancel", null).show()
        }
        setting("▤", "Storage location", "DocNexa app storage • ${docs.size} saved PDF(s)") {
            showPremiumDialog(appDialog()
                .setTitle("DocNexa storage")
                .setMessage("Your scanned and created PDFs are saved in the Documents folder:\n\nAndroid app-private storage (current account)\n\nCurrently indexed: ${docs.size} PDF(s).\n\nUse Save a copy or Share to export documents to a folder you can access outside DocNexa.")
                .setPositiveButton("OK", null))
        }

        val accountEmail = prefs.getString("cloud_email", "").orEmpty()
        if (accountEmail.isNotBlank() && cloudToken().isNotBlank()) {
            section("DOCNEXA CLOUD ACCOUNT")
            setting("!", "Delete cloud account", "Permanently delete your cloud account and synced PDFs") {
                confirmCloudAccountDeletion()
            }
        }
        if (accountEmail.equals("ravivadhiya76@gmail.com", ignoreCase = true)) {
            section("ADMIN")
            setting("✦", "Admin notification panel", "Compose an announcement for subscribed DocNexa users") {
                showAdminNotificationPanel()
            }
        }

        section("APP & SUPPORT")
        setting("ⓘ", "App information", "DocNexa • Version $appVersion") {
            showPremiumDialog(appDialog()
                .setTitle("About DocNexa")
                .setMessage("DocNexa\nPDF Scanner & Editor\nVersion $appVersion\n\nScan, create, organize and manage PDF documents on your device.")
                .setPositiveButton("Done", null))
        }
        setting("↗", "Share DocNexa", "Invite friends to try the app") { shareApp() }
        setting("✉", "Contact support", "Send feedback or report a problem") { openFeedbackEmail(false) }

        section("MAINTENANCE")
        setting("♲", "Clear temporary files", "Remove temporary app cache; saved PDFs are not deleted") {
            appDialog().setTitle("Clear temporary files?")
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
        showPremiumDialog(appDialog().setTitle("Settings • DocNexa")
            .setView(scroll).setNegativeButton("Close", null))
    }


    private fun showAdminNotificationPanel() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
            background = rounded(0xFFF3F7FF.toInt(), 18)
        }
        val titleInput = EditText(this).apply {
            hint = "Notification title"
            setSingleLine(true)
            setText("DocNexa Update")
            textSize = 15f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xFFFFFFFF.toInt(), 12, 0xFFDCE6F2.toInt())
        }
        val messageInput = EditText(this).apply {
            hint = "Write your announcement..."
            minLines = 3
            maxLines = 5
            textSize = 14f
            gravity = Gravity.TOP
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xFFFFFFFF.toInt(), 12, 0xFFDCE6F2.toInt())
        }
        container.addView(label("TITLE", 11f, 0xFF3478D8.toInt(), true))
        container.addView(titleInput, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(6) })
        container.addView(gap(12))
        container.addView(label("MESSAGE", 11f, 0xFF3478D8.toInt(), true))
        container.addView(messageInput, LinearLayout.LayoutParams(-1, dp(116)).apply { topMargin = dp(6) })
        container.addView(gap(12))
        container.addView(label("AUDIENCE", 11f, 0xFF3478D8.toInt(), true))
        container.addView(label("Users subscribed to Important App Updates", 13f, 0xFF52647A.toInt()).apply {
            setPadding(0, dp(6), 0, dp(6))
        })
        container.addView(label("Preview", 12f, 0xFF3478D8.toInt(), true))
        val preview = TextView(this).apply {
            text = "Your notification preview will appear here."
            textSize = 14f
            setTextColor(0xFF24364B.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xFFFFFFFF.toInt(), 12, 0xFFDCE6F2.toInt())
        }
        container.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        fun updatePreview() {
            preview.text = "${titleInput.text.toString().trim().ifBlank { "DocNexa Update" }}\n\n${messageInput.text.toString().trim().ifBlank { "Your message preview..." }}"
        }
        titleInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        messageInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        updatePreview()
        val dialog = appDialog().setTitle("DocNexa • Admin Panel")
            .setView(container)
            .setNegativeButton("Close", null)
            .setNeutralButton("Copy details", null)
            .setPositiveButton("Open secure sender", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val title = titleInput.text.toString().trim()
                val body = messageInput.text.toString().trim()
                if (title.isBlank() || body.isBlank() || title.length > 80 || body.length > 500) {
                    Toast.makeText(this, "Enter a title (max 80) and message (max 500 characters).", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DocNexa notification", "$title\n$body"))
                Toast.makeText(this, "Title and message copied. Paste them in the secure sender.", Toast.LENGTH_LONG).show()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text.toString().trim()
                val body = messageInput.text.toString().trim()
                if (title.isBlank() || body.isBlank() || title.length > 80 || body.length > 500) {
                    Toast.makeText(this, "Enter a title (max 80) and message (max 500 characters).", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RaviVadhiya11/smart-pdf-scanner-pro/actions/workflows/send-admin-notification.yml")))
                Toast.makeText(this, "Paste your title and message in GitHub Actions, then review and send.", Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
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
            appDialog().setTitle("Sort documents")
                .setPremiumItems(arrayOf("Name (A–Z)", "Name (Z–A)", "Newest first", "Oldest first")) { _, which ->
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
        styleSystemBars(bg, true)

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
        addNativeAdCard(body)
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
                appDialog().setPremiumItems(arrayOf("Open", "Rename", "Delete")) { _, which ->
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
        val placeholder = documentIconTile(62)
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFFEAF2FF.toInt(),13,0xFFDCE7FA.toInt())
            contentDescription = "Preview of $name"
            visibility = View.GONE
        }
        card.addView(placeholder)
        card.addView(preview,LinearLayout.LayoutParams(dp(62),dp(62)))
        val thumbnailOwner=signedInUserId()
        card.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(view:View){
                if(pagePreviewExecutor.isShutdown)return
                pagePreviewExecutor.execute{
                    val bitmap=if(thumbnailOwner==signedInUserId())createPdfThumbnail(uri) else null
                    if(bitmap!=null)runOnUiThread{
                        if(card.isAttachedToWindow&&!isDestroyed&&thumbnailOwner==signedInUserId()){
                            preview.setImageBitmap(bitmap);preview.visibility=View.VISIBLE;placeholder.visibility=View.GONE
                        }else bitmap.recycle()
                    }
                }
            }
            override fun onViewDetachedFromWindow(view:View)=Unit
        })
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
        action("Export first page as image", "Choose JPEG or lossless PNG with Save As and Share", "▧") { savePdfPageAsJpg(uri) }
        action("Export all pages as images", "Save every page as a separate image", "▧") { convertPdfToImages(uri) }
        action("Create ZIP archive", "Package this PDF into a ZIP file", "ZIP") { zipPdf(uri) }
        action("Extract PDF text", "Read and copy selectable text", "T") { extractPdfText(uri) }
        action("PDF details", "View page count, size and metadata", "ⓘ") { showPdfDetails(uri) }

        section("Edit and organize")
        action("Edit PDF", "Add text, signature and annotations", "✎") { showPdfEditor(uri) }
        action("Compress PDF", "Reduce file size with quality options", "⇩") { showCompressionOptions(uri) }
        action("Page Collage", "Arrange 2 or 4 pages per sheet", "▦") { showPageCollage(uri) }
        action("Split PDF", "Extract selected pages into a new PDF", "↔") { splitPdfFile(uri) }
        action("Rotate pages", "Rotate all or selected pages", "⟳") { rotatePdfPages(uri) }
        action("Move to Folder", "Organize this document", "▰") { movePdfToFolder(uri) }
        action("Rename PDF", "Change the document filename", "Aa") { renamePdfDocument(uri, name) }
        action("Copy PDF", "Create another copy inside DocNexa", "▣") { copyPdfDocument(uri) }
        action("Tags and notes", "Add searchable labels or a date note", "⌑") {
            appDialog().setTitle("Document details")
                .setPremiumItems(arrayOf("Edit tags", "Add date / time note")) { _, which ->
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
            appDialog().setTitle("Move to Trash?")
                .setMessage("This PDF will be moved to DocNexa Trash. You can restore it later.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Move to Trash") { _, _ -> moveToTrash(uri) }.show()
        }

        showPremiumDialog(appDialog().setTitle(name).setView(scroll)
            .setNegativeButton("Close", null))
    }

    private fun showMoreDocumentActions(name: String, uri: Uri) {
        val more = arrayOf(
            "Favourite / Unfavourite", "Pin Document", "OCR Text",
            "Copy PDF", "Split PDF", "Rename", "Tags",
            "Date and Time setting", "Open with Google Docs", "Page Collage"
        )
        appDialog().setTitle("More • $name").setPremiumItems(more) { _, which ->
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
                9 -> showPageCollage(uri)
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
        showPdfImageExportOptions(uri, true)
    }

    private fun showPdfImageExportOptions(uri: Uri, firstOnly: Boolean) {
        val options = arrayOf("JPEG • up to 200 DPI", "PNG • up to 300 DPI (lossless)", "JPEG • up to 300 DPI", if (hasVerifiedPremium()) "PNG • up to 600 DPI (Premium)" else "PNG • up to 600 DPI (Premium locked)")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(label("Export original PDF pages at print-quality resolution. Large pages or low-memory devices may use a lower resolution. The original PDF is unchanged.", 12f, 0xFF53627A.toInt()))
        }
        val group = android.widget.RadioGroup(this)
        options.forEachIndexed { index, option ->
            group.addView(android.widget.RadioButton(this).apply {
                id = 6200 + index
                text = option
            })
        }
        panel.addView(group)
        group.check(if (firstOnly) 6202 else 6201)
        appDialog().setTitle(if (firstOnly) "Export first page" else "Export PDF pages")
            .setView(panel).setNegativeButton("Cancel", null)
            .setPositiveButton("Export") { _, _ ->
                val index = (group.checkedRadioButtonId - 6200).coerceIn(0, 3)
                val premium = index == 3
                if (premium && !requirePremiumTool("600 DPI PDF image export")) return@setPositiveButton
                exportPdfPageImages(uri, firstOnly, listOf(200, 300, 300, 600)[index], index == 1 || index == 3, premium)
            }.show()
    }

    private fun exportPdfPageImages(uri: Uri, firstOnly: Boolean, dpi: Int, png: Boolean, premium: Boolean) {
        advancedToolJob("Exporting high-quality PDF images…") {
            val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open PDF")
            val renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
            renderer.use {
                require(renderer.pageCount > 0) { "PDF has no pages" }
                val count = if (firstOnly) 1 else renderer.pageCount
                var completed = 0
                var capped = false
                val extension = if (png) "png" else "jpg"
                val mime = if (png) "image/png" else "image/jpeg"
                val stamp = System.currentTimeMillis()
                for (index in 0 until count) {
                    if (premium && !hasVerifiedPremium()) error("Premium verification expired. Previously exported pages are retained.")
                    renderer.openPage(index).use { page ->
                        val dimensions = NativeExportPlan.pdfDimensions(page.width, page.height, dpi, 7680, exportPixelBudget(36_000_000L, 8L))
                        capped = capped || dimensions.limited
                        val bitmap = Bitmap.createBitmap(dimensions.width, dimensions.height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val name = "DocNexa_Page_" + (index + 1) + "_" + stamp + "_" + dimensions.width + "x" + dimensions.height + "." + extension
                            val file = PrivateDocumentStore.save(privateDocumentsDir(), name) { stream ->
                                check(bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 98, stream)) { "Image encoding failed" }
                            }
                            showExportSuccess(privateDocumentUri(file), name, mime)
                            completed++
                        } finally { bitmap.recycle() }
                    }
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Exported " + completed + " page(s)" + (if (capped) " • resolution limited for device safety" else " • high quality")
                    }
                }
            }
        }
    }

    private fun exportPixelBudget(maxPixels: Long = 16_000_000L, bytesPerPixel: Long = 16L): Long {
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        require(available >= 16L * 1024 * 1024) { "Not enough memory. Close other documents and try again." }
        return (available / bytesPerPixel).coerceIn(1_000_000L, maxPixels)
    }

    private fun showPageCollage(uri: Uri) {
        var perSheet = 2
        appDialog().setTitle("Page Collage")
            .setView(label("Arrange pages on A4 sheets. Original kept. The new PDF contains page images; searchable text, links and forms are flattened.", 13f, 0xFF69778D.toInt()).apply { setPadding(dp(24), dp(12), dp(24), dp(12)) })
            .setSingleChoiceItems(arrayOf("2 pages per sheet", "4 pages per sheet"), 0) { _, index -> perSheet = if (index == 0) 2 else 4 }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create collage") { _, _ -> createPageCollage(uri, perSheet) }.show()
    }

    private fun createPageCollage(uri: Uri, perSheet: Int) {
        val progress = appDialog().setTitle("Creating page collage")
            .setMessage("Preparing pages…").setCancelable(false).create()
        progress.show()
        Thread {
            try {
                val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open PDF")
                descriptor.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        require(renderer.pageCount > 0) { "PDF has no pages" }
                        val totalPages = renderer.pageCount
                        val output = PdfDocument()
                        try {
                            val columns = if (perSheet == 4) 2 else 1
                            val cellWidth = (595 - 48) / columns
                            val cellHeight = (842 - 48) / 2
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                            for (start in 0 until renderer.pageCount step perSheet) {
                                val sheet = output.startPage(PdfDocument.PageInfo.Builder(595, 842, start / perSheet + 1).create())
                                try {
                                    sheet.canvas.drawColor(android.graphics.Color.WHITE)
                                    for (slot in 0 until minOf(perSheet, renderer.pageCount - start)) {
                                        renderer.openPage(start + slot).use { source ->
                                            val scale = minOf(cellWidth * 2f / source.width, (cellHeight - 22) * 2f / source.height)
                                            val bitmap = Bitmap.createBitmap((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                            try {
                                                Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                                                source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                                val x = 24 + (slot % columns) * cellWidth
                                                val y = 24 + (slot / columns) * cellHeight
                                                sheet.canvas.drawBitmap(bitmap, null, fitRect(bitmap.width, bitmap.height, cellWidth - 12, cellHeight - 26, x + 6, y), paint)
                                                paint.textSize = 10f
                                                paint.color = android.graphics.Color.DKGRAY
                                                sheet.canvas.drawText("Page ${start + slot + 1}", x + 6f, (y + cellHeight - 8).toFloat(), paint)
                                            } finally { bitmap.recycle() }
                                        }
                                    }
                                } finally { output.finishPage(sheet) }
                                runOnUiThread { if (!isDestroyed) progress.setMessage("Processed ${minOf(start + perSheet, totalPages)} of $totalPages pages") }
                            }
                            val file = PrivateDocumentStore.save(privateDocumentsDir(), "Page_Collage_${System.currentTimeMillis()}.pdf") { output.writeTo(it) }
                            val saved = privateDocumentUri(file)
                            assignPdf(saved)
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    progress.dismiss()
                                    lastPdf = saved
                                    showExportSuccess(saved, file.name, "application/pdf")
                                }
                            }
                        } finally { output.close() }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { if (!isFinishing && !isDestroyed) {
                    progress.dismiss()
                    appDialog().setTitle("Could not create collage").setMessage(e.localizedMessage ?: "Check this PDF and available storage.").setPositiveButton("OK", null).show()
                } }
            }
        }.start()
    }

    private fun copyPdfDocument(uri: Uri) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read PDF")
                val copied = savePdfBytes(bytes, "Copy")
                runOnUiThread { Toast.makeText(this, if (copied != null) "PDF copy saved in DocNexa app storage" else "Copy failed", Toast.LENGTH_LONG).show(); buildScreen() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Copy failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun renamePdfDocument(uri: Uri, oldName: String) {
        val field = EditText(this).apply { setText(oldName.removeSuffix(".pdf")); selectAll() }
        appDialog().setTitle("Rename PDF").setView(field)
            .setPositiveButton("Save") { _, _ ->
                val value = field.text.toString().trim()
                if (value.isBlank()) {
                    Toast.makeText(this, "Enter a file name", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        val newName = if (value.endsWith(".pdf", true)) value else "$value.pdf"
                        val privateFile = privateDocumentFile(uri)
                        var renamedFile: java.io.File? = null
                        val changed = if (privateFile != null) {
                            renamedFile = PrivateDocumentStore.rename(privateFile, newName)
                            true
                        } else {
                            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
                            contentResolver.update(uri, values, null, null) > 0
                        }
                        if (changed) {
                            val renamedUri = renamedFile?.let { privateDocumentUri(it) } ?: uri
                            if (renamedUri != uri) migrateDocumentMetadata(uri, renamedUri)
                            Toast.makeText(this, "PDF renamed", Toast.LENGTH_SHORT).show()
                            buildScreen()
                        } else Toast.makeText(this, "Rename failed", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, e.localizedMessage ?: "Rename failed", Toast.LENGTH_LONG).show()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun migrateDocumentMetadata(oldUri: Uri, newUri: Uri) {
        val old = oldUri.toString()
        val new = newUri.toString()
        folderAssignments.remove(old)?.let { folderAssignments[new] = it }
        val editor = prefs.edit().putString(accountPrefKey("folder_assignments"), folderAssignmentSnapshot())
        for (key in listOf("favourite_uris", "trash_uris")) {
            val values = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
            if (values.remove(old)) { values.add(new); editor.putStringSet(key, values) }
        }
        for (prefix in listOf("document_tag_", "document_datetime_")) {
            val oldKey = prefix + old.hashCode()
            prefs.getString(oldKey, null)?.let { editor.putString(prefix + new.hashCode(), it).remove(oldKey) }
        }
        editor.apply()
        if (lastPdf == oldUri) lastPdf = newUri
    }

    private fun movePdfToFolder(uri: Uri) {
        if (homeFolders.isEmpty()) loadFolders()
        val folders = homeFolders.toTypedArray()
        appDialog().setTitle("Move to Folder").setPremiumItems(folders) { _, which ->
            folderAssignments[uri.toString()] = folders[which]
            prefs.edit().putString(accountPrefKey("folder_assignments"), folderAssignmentSnapshot()).apply()
            Toast.makeText(this, "Moved to ${folders[which]}", Toast.LENGTH_SHORT).show()
            buildScreen()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun documentTagDialog(uri: Uri) {
        val key = "document_tag_" + uri.toString().hashCode()
        val field = EditText(this).apply { hint = "Enter tags separated by commas"; setText(prefs.getString(key, "")) }
        appDialog().setTitle("Document Tags").setView(field)
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
        appDialog().setTitle("Document date / time label").setMessage("Add a custom date or time note for this document.")
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("document_datetime_" + uri.toString().hashCode(), field.text.toString().trim()).apply()
                Toast.makeText(this, "Date/time note saved", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun createPdfThumbnail(uri: Uri): Bitmap? {
        if(uri.authority=="$packageName.fileprovider" && privateDocumentFile(uri)==null)return null
        var bitmap:Bitmap?=null
        return try {
            val descriptor=contentResolver.openFileDescriptor(uri,"r")?:return null
            val renderer=try{PdfRenderer(descriptor)}catch(e:Exception){runCatching{descriptor.close()};throw e}
            renderer.use{
                if(it.pageCount<=0)return null
                it.openPage(0).use{page->
                    val maxSide=dp(124).coerceIn(1,300)
                    val scale=minOf(maxSide.toFloat()/page.width,maxSide.toFloat()/page.height)
                    val target=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                    bitmap=target
                    target.eraseColor(android.graphics.Color.WHITE)
                    page.render(target,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    target
                }
            }
        }catch(_:Exception){bitmap?.recycle();null}
        catch(_:OutOfMemoryError){bitmap?.recycle();null}
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
        val results = mutableListOf<Pair<String, Uri>>()
        val modified = mutableMapOf<Uri, Long>()
        try {
            privateDocumentsDir().listFiles()?.filter { it.isFile && it.extension.equals("pdf", true) }?.forEach { file ->
                val uri = privateDocumentUri(file)
                if (!trashed.contains(uri.toString())) { results.add(file.name to uri); modified[uri] = file.lastModified() }
            }
            // Do not enumerate shared Downloads/MediaStore files here: they have no
            // trustworthy account owner and must never leak into another signed-in account.
            // Existing exported copies remain in Downloads and are not deleted by sign-out.
        } catch (_: Exception) { }
        return when (prefs.getInt("document_sort_order", 2)) {
            0 -> results.sortedBy { it.first.lowercase(Locale.ROOT) }
            1 -> results.sortedByDescending { it.first.lowercase(Locale.ROOT) }
            3 -> results.sortedBy { modified[it.second] ?: 0L }
            else -> results.sortedByDescending { modified[it.second] ?: 0L }
        }
    }
    private fun createFolderDialog() {
        val field = android.widget.EditText(this).apply { hint = "Folder name" }
        appDialog().setTitle("Create folder").setView(field)
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
        appDialog().setTitle("Rename folder").setView(field)
            .setPositiveButton("Save") { _, _ ->
                val name = field.text.toString().trim()
                val index = homeFolders.indexOf(oldName)
                if (name.isNotEmpty() && index >= 0) {
                    homeFolders[index] = name
                    saveFolders()
                    synchronized(folderAssignments) { folderAssignments.entries.filter { it.value == oldName }.forEach { folderAssignments[it.key] = name } }
                    prefs.edit().putString(accountPrefKey("folder_assignments"), folderAssignmentSnapshot()).apply()
                    buildScreen()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deleteFolder(name: String) {
        appDialog().setMessage("Delete folder '$name'?")
            .setPositiveButton("Delete") { _, _ -> homeFolders.remove(name); saveFolders(); buildScreen() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showFolderContents(folder: String) {
        selectedFolder = folder
        val items = getSavedPdfs().filter { folderAssignments[it.second.toString()] == folder }
        val names = items.map { it.first }.toTypedArray()
        if (items.isEmpty()) {
            appDialog().setTitle(folder).setMessage("This folder is empty. New PDFs created while this folder is selected will be saved here.").setPositiveButton("OK", null).show()
            return
        }
        appDialog().setTitle(folder).setPremiumItems(names) { _, which -> openPdf(items[which].second) }
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
        val quickTools = listOf(
            PdfTool("Open PDF", "▣", 0xFFE9433A.toInt()) { pdfPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Page Details", "↔", 0xFF4CAF50.toInt()) { detailsPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Signature", "S", 0xFFF6B34C.toInt()) { openSignatureEditor() },
            PdfTool("PDF to Image", "▧", 0xFF82C58A.toInt()) { pdfToImagePicker.launch(arrayOf("application/pdf")) },
            PdfTool("Image to PDF", "▧", 0xFF795548.toInt()) { imagePicker.launch(arrayOf("image/*")) },
            PdfTool("Scanner Filters", "◐", 0xFF0D9488.toInt()) { showScannerFilterSettings() },
            PdfTool("Print PDF", "▤", 0xFF2563EB.toInt()) { printPdfPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Share PDF", "↗", 0xFFFFB900.toInt()) {
                lastPdf?.let { uri ->
                    val share = Intent(Intent.ACTION_SEND).setType("application/pdf")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(share, "Share PDF"))
                } ?: pdfPicker.launch(arrayOf("application/pdf"))
            }
        )
        val pdfTools = listOf(
            PdfTool("Create PDF", "＋", 0xFF2563EB.toInt()) { showCreatePdfHub() },
            PdfTool("Page Collage", "▦", 0xFF0891B2.toInt()) { collagePicker.launch(arrayOf("application/pdf")) },
            PdfTool("Reorder Pages", "⇅", 0xFF2563EB.toInt()) { reorderPagesPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Delete Pages", "−", 0xFFDC4D58.toInt()) { deletePagesPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Extract Pages", "⇱", 0xFF0891B2.toInt()) { extractPagesPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Duplicate Pages", "▣", 0xFF7C3AED.toInt()) { duplicatePagesPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Page Numbers", "#", 0xFF0D9488.toInt()) { pageNumbersPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Compress PDF", "↓", 0xFFEA580C.toInt()) { compressPicker.launch(arrayOf("application/pdf")) },
            PdfTool("PDF to Word", "W", 0xFF2563EB.toInt()) { pdfToWordPicker.launch(arrayOf("application/pdf")) },
            PdfTool("OCR Image", "Aa", 0xFF7C3AED.toInt()) { ocrPicker.launch("image/*") },
            PdfTool("Searchable OCR PDF", "Aa+", 0xFF0D9488.toInt()) { searchableOcrPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Watermark", "▧", 0xFF64748B.toInt()) { watermarkPicker.launch(arrayOf("application/pdf")) },
            PdfTool("PDF Editor", "✎", 0xFF2563EB.toInt()) { pdfEditorPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Add/Merge PDF Files", "⇲", 0xFFF06A36.toInt()) { mergePicker.launch(arrayOf("application/pdf")) },
            PdfTool("Add Page from Gallery", "▧+", 0xFFB8D52B.toInt()) { imagePicker.launch(arrayOf("image/*")) },
            PdfTool("Split", "↔", 0xFF078F83.toInt()) { splitPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Rotate all pages", "✎", 0xFF13A6D8.toInt()) { rotatePicker.launch(arrayOf("application/pdf")) },
            PdfTool("Add Password", "▣", 0xFFED78A5.toInt()) { passwordPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Remove Password", "◉", 0xFF80516E.toInt()) { removePasswordPicker.launch(arrayOf("application/pdf")) },
            PdfTool("PDF To Text", "T", 0xFF35B5E5.toInt()) { pdfTextPicker.launch(arrayOf("application/pdf")) },
            PdfTool("PDF To ZIP", "▣", 0xFFE91E63.toInt()) { zipPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Text To PDF", "▤", 0xFF9826AC.toInt()) { createTextPdfDialog() },
            PdfTool("PDF Details", "ⓘ", 0xFF673AB7.toInt()) { detailsPicker.launch(arrayOf("application/pdf")) },
            PdfTool("PDF Compare", "≠", 0xFF2563EB.toInt()) { compareDocumentsPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Image Quality Enhancer", "✦", 0xFF0D9488.toInt()) { enhanceDocumentPicker.launch(arrayOf("image/*")) },
            PdfTool("Batch Document Studio", "▦", 0xFF7C3AED.toInt()) { batchDocumentsPicker.launch(arrayOf("application/pdf")) },
            PdfTool("PDF to Excel", "X", 0xFF16834A.toInt()) { excelExportPicker.launch(arrayOf("application/pdf")) },
            PdfTool("Book Scanner", "▤", 0xFF0891B2.toInt()) { bookSpreadPicker.launch(arrayOf("image/*")) },
            PdfTool("Document Eraser", "✎", 0xFF7C3AED.toInt()) { documentEraserPicker.launch(arrayOf("image/*")) },
            PdfTool("PDF Redaction", "▣", 0xFFDC4D58.toInt()) { secureRedactionPicker.launch(arrayOf("application/pdf")) },
            PdfTool("PDF to PowerPoint", "P", 0xFFEA580C.toInt()) { powerpointExportPicker.launch(arrayOf("application/pdf")) }
        )
        val catalog = (quickTools + pdfTools).distinctBy { it.title }
        val defaultPins = quickTools.map { it.title }.toSet()
        var pinned = (prefs.getStringSet("pdf_tool_pins", defaultPins) ?: defaultPins).toSet()
        var category = "All"
        var query = ""

        val quickHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        quickHeader.addView(label("Quick Access", 17f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        val customize = nativeButton(this, ButtonRole.Secondary).apply {
            text = "Customize"
            textSize = 11f
            isAllCaps = false
            contentDescription = "Choose Quick Access tools"
        }
        quickHeader.addView(customize)
        body.addView(quickHeader)
        body.addView(label("Your favorite tools, always within reach", 11f, muted))
        val quickGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(quickGrid)
        fun renderQuickAccess() {
            quickGrid.removeAllViews()
            val favorites = catalog.filter { it.title in pinned }
            if (favorites.isEmpty()) {
                quickGrid.addView(label("Tap Customize to pin your favorite tools.", 12f, muted))
            } else addPdfQuickGrid(quickGrid, favorites)
        }
        customize.setOnClickListener {
            val selected = catalog.map { it.title in pinned }.toBooleanArray()
            appDialog()
                .setTitle("Customize Quick Access")
                .setMultiChoiceItems(catalog.map { it.title }.toTypedArray(), selected) { _, which, checked ->
                    selected[which] = checked
                }
                .setPositiveButton("Save") { _, _ ->
                    pinned = catalog.filterIndexed { index, _ -> selected[index] }.map { it.title }.toSet()
                    prefs.edit().putStringSet("pdf_tool_pins", pinned).apply()
                    renderQuickAccess()
                }
                .setNeutralButton("Reset") { _, _ ->
                    pinned = defaultPins
                    prefs.edit().remove("pdf_tool_pins").apply()
                    renderQuickAccess()
                }
                .setNegativeButton("Cancel", null).show()
        }
        renderQuickAccess()
        addNativeAdCard(body)
        addPdfSectionTitle(body, "PDF Tools")
        val search = EditText(this).apply {
            hint = "Search tools: merge, split, password…"
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFFFFFFFF.toInt(), 12, 0xFFD6E3F5.toInt())
            contentDescription = "Search PDF tools"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        body.addView(search, LinearLayout.LayoutParams(-1, -2))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        body.addView(chipScroll)
        val results = label("", 11f, muted)
        results.setPadding(dp(4), dp(6), dp(4), dp(10))
        body.addView(results)
        val toolGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(toolGrid)
        fun toolCategory(tool: PdfTool): String = when (tool.title) {
            "Create PDF", "Text To PDF", "Image to PDF", "Add Page from Gallery", "Page Collage", "Book Scanner" -> "Create"
            "Add/Merge PDF Files", "Split", "Rotate all pages", "Signature", "Watermark", "PDF Editor", "Page Numbers", "Document Eraser", "PDF Redaction" -> "Edit"
            "Reorder Pages", "Delete Pages", "Extract Pages", "Duplicate Pages" -> "Organize"
            "PDF to Image", "PDF To Text", "PDF To ZIP", "PDF to Word", "OCR Image", "Compress PDF", "PDF to Excel", "PDF to PowerPoint", "Image Quality Enhancer" -> "Convert"
            "Add Password", "Remove Password" -> "Protect"
            else -> "View & share"
        }
        fun renderTools() {
            val matching = catalog.filter {
                (category == "All" || toolCategory(it) == category) &&
                    (it.title + " " + pdfToolDescription(it.title)).contains(query, ignoreCase = true)
            }
            results.text = "${matching.size} tools • Choose a tool to get started"
            toolGrid.removeAllViews()
            if (matching.isEmpty()) {
                toolGrid.addView(label("No matching tools. Try another search or choose All.", 13f, muted))
            } else addPdfToolGrid(toolGrid, matching, ink, muted)
            for (i in 0 until chips.childCount) {
                (chips.getChildAt(i) as Button).apply {
                    val active = text.toString() == category
                    setTextColor(if (active) 0xFFFFFFFF.toInt() else ink)
                    background = rounded(if (active) 0xFF2563EB.toInt() else 0xFFE7EEFA.toInt(), 18)
                    isSelected = active
                }
            }
        }
        listOf("All", "Create", "Organize", "Edit", "Convert", "Protect", "View & share").forEach { name ->
            chips.addView(nativeButton(this, ButtonRole.Secondary).apply {
                text = name
                textSize = 11f
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener { category = name; renderTools() }
            }, LinearLayout.LayoutParams(-2, dp(48)).apply {
                rightMargin = dp(6); topMargin = dp(10)
            })
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim().orEmpty()
                renderTools()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        renderTools()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setScreenContent(root)
    }


    /**
     * Loads and renders a Google Native Advanced ad using registered native assets.
     * The ad is inserted between Quick Access and the PDF Tools grid.
     */
    private fun addNativeAdCard(parent: LinearLayout, insertIndex: Int = parent.childCount): () -> Unit {
        if (!adsConsentAllowed || hasVerifiedPremium()) return {}
        val disposed = AtomicBoolean(false)
        var displayedAd: NativeAd? = null
        var displayedAdView: NativeAdView? = null
        val slot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(8), dp(9), dp(8))
            background = rounded(0xFFFFFFFF.toInt(), 14, 0xFFDCE6F5.toInt())
            elevation = dp(2).toFloat()
            tag = "docnexa_native_ad_slot"
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        }
        slot.addView(label("SPONSORED", 9f, 0xFF718096.toInt(), true))
        slot.addView(gap(5))
        slot.addView(label("Loading advertisement…", 11f, 0xFF718096.toInt()))
        parent.addView(slot, insertIndex.coerceIn(0, parent.childCount))

        val loader = AdLoader.Builder(this, getString(R.string.native_ad_unit_id))
            .forNativeAd { nativeAd ->
                if (disposed.get() || hasVerifiedPremium() || isFinishing || isDestroyed || !slot.isAttachedToWindow) {
                    nativeAd.destroy()
                    return@forNativeAd
                }
                runOnUiThread {
                    if (disposed.get() || hasVerifiedPremium() || isFinishing || isDestroyed || !slot.isAttachedToWindow) {
                        nativeAd.destroy()
                        return@runOnUiThread
                    }
                    displayedAd = nativeAd
                    loadedNativeAds.add(nativeAd)
                    slot.removeAllViews()
                    slot.addView(label("SPONSORED", 9f, 0xFF718096.toInt(), true))
                    slot.addView(gap(5))
                    // Avoid embedding Google's media WebView in the native ad card.
                    // Some devices/networks show a "Web page not available" page there.
                    // The icon, headline, advertiser, body and CTA remain a complete native ad.
                    val adView = NativeAdView(this).apply {
                        setBackgroundColor(0xFFFFFFFF.toInt())
                    }

                    displayedAdView = adView
                    // NativeAdView is a FrameLayout. Stack assets in a vertical child to avoid overlap.
                    val assets = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp(18), 0, 0)
                    }
                    adView.addView(assets, FrameLayout.LayoutParams(-1, -2))
                    val details = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(7), 0, 0)
                    }
                    val icon = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = rounded(0xFFF0F4FA.toInt(), 8)
                    }
                    nativeAd.icon?.drawable?.let { icon.setImageDrawable(it) }
                    adView.iconView = icon
                    details.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))

                    val titleBlock = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), 0, dp(6), 0)
                    }
                    val headline = label(nativeAd.headline ?: "Sponsored", 12.5f, 0xFF172B4D.toInt(), true).apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    titleBlock.addView(headline)
                    adView.headlineView = headline
                    val advertiser = label(nativeAd.advertiser ?: "Advertiser", 9.5f, 0xFF718096.toInt())
                    titleBlock.addView(advertiser)
                    adView.advertiserView = advertiser
                    details.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))

                    val cta = Button(this).apply {
                        text = nativeAd.callToAction ?: "Open"
                        isAllCaps = false
                        textSize = 10.5f
                        minHeight = dp(36)
                        setPadding(dp(10), 0, dp(10), 0)
                        setTextColor(0xFFFFFFFF.toInt())
                        background = rounded(0xFF087EC2.toInt(), 10)
                        backgroundTintList = null
                    }
                    adView.callToActionView = cta
                    details.addView(cta, LinearLayout.LayoutParams(-2, dp(36)))
                    assets.addView(details)

                    nativeAd.body?.takeIf { it.isNotBlank() }?.let { bodyText ->
                        val bodyView = label(bodyText, 10f, 0xFF52627A.toInt()).apply {
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(0, dp(5), 0, 0)
                        }
                        adView.bodyView = bodyView
                        assets.addView(bodyView)
                    }
                    adView.setNativeAd(nativeAd)
                    slot.addView(adView)
                }
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    runOnUiThread { (slot.parent as? ViewGroup)?.removeView(slot) }
                }
            })
            .build()
        lateinit var disposeSlot: () -> Unit
        fun releaseAd() {
            premiumAdDisposers.remove(disposeSlot)
            if (disposed.compareAndSet(false, true)) {
                displayedAdView?.destroy()
                displayedAdView = null
                displayedAd?.let { ad -> if (loadedNativeAds.remove(ad)) ad.destroy() }
                displayedAd = null
                slot.visibility = View.GONE
            }
        }
        disposeSlot = { releaseAd(); (slot.parent as? ViewGroup)?.removeView(slot) }
        premiumAdDisposers.add(disposeSlot)
        slot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { releaseAd() }
        })
        loader.loadAd(AdRequest.Builder().build())
        return disposeSlot
    }


    private val compareDocumentsPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
        if (files.size == 2) compareSelectedPdfs(files[0], files[1])
        else if (files.isNotEmpty()) Toast.makeText(this, "Select exactly two PDFs", Toast.LENGTH_LONG).show()
    }
    private val enhanceDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showDocumentEnhancer(uri)
    }
    private val batchDocumentsPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
        if (files.isNotEmpty()) showBatchDocuments(files)
    }
    private val excelExportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) exportPdfSpreadsheet(uri)
    }

    private fun advancedToolJob(title: String, action: () -> Unit) {
        status.text = title
        Thread {
            try { action() } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Operation failed"
                        appDialog().setTitle("Unable to complete").setMessage(e.localizedMessage ?: "Document processing failed")
                            .setPositiveButton("OK", null).show()
                    }
                }
            }
        }.start()
    }

    private fun compareSelectedPdfs(first: Uri, second: Uri) {
        if (!requirePremiumTool("PDF Compare")) return
        advancedToolJob("Comparing PDF documents…") {
            fun fingerprints(uri: Uri): List<Pair<String, String>> =
                contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
                    PdfRenderer(android.os.ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { renderer ->
                        require(renderer.pageCount <= 150) { "Comparison supports up to 150 pages per document" }
                        (0 until renderer.pageCount).map { index ->
                            renderer.openPage(index).use { page ->
                                val scale = minOf(1f, 800f / maxOf(page.width, page.height))
                                val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val buffer = java.nio.ByteBuffer.allocate(bitmap.byteCount)
                                    bitmap.copyPixelsToBuffer(buffer)
                                    val hash = MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
                                    "${page.width} × ${page.height}" to hash
                                } finally { bitmap.recycle() }
                            }
                        }
                    }
                }
            val a = fingerprints(first)
            val b = fingerprints(second)
            val changed = (0 until maxOf(a.size, b.size)).filter { a.getOrNull(it) != b.getOrNull(it) }
            val report = "First PDF: ${a.size} pages\nSecond PDF: ${b.size} pages\nDifferent or missing pages: ${changed.size}\n\n" +
                if (changed.isEmpty()) "No differences detected at preview resolution." else changed.joinToString(", ") { (it + 1).toString() }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    status.text = "Comparison complete"
                    appDialog().setTitle("PDF Compare • Visual report").setMessage(report + "\n\nCompares rendered pages by position; does not compare metadata or hidden content.")
                        .setPositiveButton("Save report") { _, _ -> createPdfFromText(report, "PDF_Comparison") }
                        .setNegativeButton("Close", null).show()
                }
            }
        }
    }

    private fun showDocumentEnhancer(uri: Uri) {
        val options = arrayOf("Natural contrast", "Clean grayscale", "High contrast text")
        appDialog().setTitle("Image Quality Enhancer").setPremiumItems(options) { _, choice ->
            advancedToolJob("Enhancing image…") {
                val source = decodeImageSafely(uri, 2400)
                val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                try {
                    val saturation = android.graphics.ColorMatrix().apply { setSaturation(if (choice == 0) 1f else 0f) }
                    val gain = if (choice == 2) 1.65f else 1.18f
                    val offset = 128f * (1f - gain)
                    val contrast = android.graphics.ColorMatrix(floatArrayOf(gain,0f,0f,0f,offset, 0f,gain,0f,0f,offset, 0f,0f,gain,0f,offset, 0f,0f,0f,1f,0f))
                    saturation.postConcat(contrast)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = android.graphics.ColorMatrixColorFilter(saturation) }
                    Canvas(result).drawBitmap(source, 0f, 0f, paint)
                    val bytes = ByteArrayOutputStream().also { result.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            val preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            val image = ImageView(this).apply { setImageBitmap(preview); adjustViewBounds = true; maxHeight = dp(360) }
                            appDialog().setTitle("Enhanced preview").setView(image)
                                .setPositiveButton("Save PNG") { _, _ -> saveDocumentBytes(bytes, "Enhanced", "image/png", "png") }
                                .setNegativeButton("Close", null).show()
                            status.text = "Enhancement complete"
                        }
                    }
                } finally { source.recycle(); result.recycle() }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun showBatchDocuments(files: List<Uri>) {
        if (!requirePremiumTool("Batch Document Studio")) return
        if (files.size > 20) { Toast.makeText(this, "Select up to 20 PDFs", Toast.LENGTH_LONG).show(); return }
        appDialog().setTitle("Batch Document Studio")
            .setPremiumItems(arrayOf("Merge into one PDF", "Export ZIP archive")) { _, operation ->
                advancedToolJob("Processing batch…") {
                    val bytes = ByteArrayOutputStream()
                    if (operation == 0) {
                        PDDocument().use { output ->
                            files.forEach { uri ->
                                contentResolver.openInputStream(uri)!!.use { input ->
                                    PDDocument.load(input).use { source ->
                                        require(output.numberOfPages + source.numberOfPages <= 300) { "Maximum 300 merged pages" }
                                        PDFMergerUtility().appendDocument(output, source)
                                    }
                                }
                            }
                            output.save(bytes)
                        }
                    } else {
                        ZipOutputStream(bytes).use { zip ->
                            var total = 0L
                            files.forEachIndexed { index, uri ->
                                zip.putNextEntry(ZipEntry("Document_${index + 1}.pdf"))
                                contentResolver.openInputStream(uri)!!.use { input ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        require(total <= 64L * 1024 * 1024) { "Batch exceeds 64 MB" }
                                        zip.write(buffer, 0, count)
                                    }
                                }
                                zip.closeEntry()
                            }
                        }
                    }
                    val data = bytes.toByteArray()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            status.text = "Batch complete"
                            if (operation == 0) savePdfBytes(data, "Batch_Merged")
                            else saveDocumentBytes(data, "Batch_Documents", "application/zip", "zip")
                        }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun exportPdfSpreadsheet(uri: Uri) {
        if (!requirePremiumTool("PDF to Excel")) return
        appDialog().setTitle("PDF to Excel")
            .setMessage("Extract selectable text into an editable XLSX sheet. Tabs or runs of spaces separate columns. Scanned pages need OCR first; complex tables may require manual correction.")
            .setPositiveButton("Export XLSX") { _, _ ->
                advancedToolJob("Exporting spreadsheet…") {
                    val text = contentResolver.openInputStream(uri)!!.use { input ->
                        PDDocument.load(input).use { doc ->
                            require(doc.numberOfPages <= 100) { "Maximum 100 pages" }
                            PDFTextStripper().apply { sortByPosition = true }.getText(doc)
                        }
                    }
                    require(text.isNotBlank() && text.length <= 500000) { "No selectable text or document too large" }
                    fun xml(value: String) = value.filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
                        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    val rows = text.lineSequence().filter { it.isNotBlank() }.take(10000).mapIndexed { index, line ->
                        val cells = line.trim().split(Regex("\\t+| {2,}")).take(100).joinToString("") { cell ->
                            "<c t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(cell)}</t></is></c>"
                        }
                        "<row r=\"${index + 1}\">$cells</row>"
                    }.joinToString("")
                    val output = ByteArrayOutputStream()
                    ZipOutputStream(output).use { zip ->
                        fun part(name: String, value: String) {
                            zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                        }
                        part("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
                        part("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
                        part("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="PDF Text" sheetId="1" r:id="rId1"/></sheets></workbook>""")
                        part("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
                        part("xl/worksheets/sheet1.xml", """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>""")
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            status.text = "Excel export complete"
                            saveDocumentBytes(output.toByteArray(), "PDF_Excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx")
                        }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }


    private val bookSpreadPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showBookSpread(uri)
    }
    private val documentEraserPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showDocumentEraser(uri)
    }
    private val secureRedactionPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectRedactionPage(uri)
    }

    private fun showBookSpread(uri: Uri) {
        advancedToolJob("Preparing book spread…") {
            val bitmap = decodeImageSafely(uri, 3000)
            require(bitmap.width >= 2) { "Image is too narrow" }
            runOnUiThread {
                if (isFinishing || isDestroyed) { bitmap.recycle(); return@runOnUiThread }
                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                val split = SeekBar(this).apply { max = 60; progress = 30 }
                val preview = object : View(this) {
                    private val ink = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    override fun onDraw(canvas: Canvas) {
                        super.onDraw(canvas)
                        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
                        val w = bitmap.width * scale
                        val h = bitmap.height * scale
                        val left = (width - w) / 2f
                        val top = (height - h) / 2f
                        canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + w, top + h), ink)
                        val center = left + w * (20 + split.progress) / 100f
                        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2563EB.toInt(); strokeWidth = dp(3).toFloat() }
                        canvas.drawLine(center, top, center, top + h, line)
                    }
                }
                val reverse = android.widget.CheckBox(this).apply { text = "Right page first" }
                panel.addView(label("Adjust the center divider. Each half becomes a separate page.", 12f, 0xFF64748B.toInt()))
                panel.addView(preview, LinearLayout.LayoutParams(-1, dp(260)))
                panel.addView(split)
                panel.addView(reverse)
                split.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { preview.invalidate() }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
                val dialog = appDialog().setTitle("Book Scanner • Split spread").setView(panel)
                    .setPositiveButton("Create PDF") { _, _ ->
                        val middle = (bitmap.width * (20 + split.progress) / 100).coerceIn(1, bitmap.width - 1)
                        val left = Bitmap.createBitmap(bitmap, 0, 0, middle, bitmap.height)
                        val right = Bitmap.createBitmap(bitmap, middle, 0, bitmap.width - middle, bitmap.height)
                        createPdfFromBitmaps(if (reverse.isChecked) listOf(right, left) else listOf(left, right), "Book_Scan")
                    }.setNegativeButton("Cancel", null).create()
                dialog.setOnDismissListener { if (!bitmap.isRecycled) bitmap.recycle() }
                dialog.show()
                status.text = "Book spread ready"
            }
        }
    }

    private fun showDocumentEraser(uri: Uri) {
        advancedToolJob("Preparing image editor…") {
            val source = decodeImageSafely(uri, 2400)
            val editable = source.copy(Bitmap.Config.ARGB_8888, true)
            source.recycle()
            runOnUiThread {
                if (isFinishing || isDestroyed) { editable.recycle(); return@runOnUiThread }
                val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
                val brush = SeekBar(this).apply { max = 90; progress = 25 }
                val editor = object : View(this) {
                    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                    val target = Canvas(editable)
                    var previousX = 0f
                    var previousY = 0f
                    private fun scale() = minOf(width.toFloat() / editable.width, height.toFloat() / editable.height).coerceAtLeast(0.001f)
                    override fun onDraw(canvas: Canvas) {
                        val scale = scale()
                        val left = (width - editable.width * scale) / 2
                        val top = (height - editable.height * scale) / 2
                        canvas.drawBitmap(editable, null, android.graphics.RectF(left, top, left + editable.width * scale, top + editable.height * scale), Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        val scale = scale()
                        val x = ((event.x - (width - editable.width * scale) / 2) / scale).coerceIn(0f, editable.width.toFloat())
                        val y = ((event.y - (height - editable.height * scale) / 2) / scale).coerceIn(0f, editable.height.toFloat())
                        ink.strokeWidth = (brush.progress + 10) / scale
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); target.drawCircle(x, y, ink.strokeWidth / 2, ink); previousX = x; previousY = y }
                            MotionEvent.ACTION_MOVE -> { target.drawLine(previousX, previousY, x, y, ink); previousX = x; previousY = y }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); performClick() }
                        }
                        invalidate()
                        return true
                    }
                    override fun performClick(): Boolean { super.performClick(); return true }
                }
                panel.addView(label("Paint white over unwanted marks on white paper. This is a manual brush, not AI inpainting.", 12f, 0xFF64748B.toInt()))
                panel.addView(editor, LinearLayout.LayoutParams(-1, dp(300)))
                panel.addView(label("Brush size", 12f, 0xFF64748B.toInt()))
                panel.addView(brush)
                val dialog = appDialog().setTitle("Document Eraser").setView(panel)
                    .setPositiveButton("Save PNG") { _, _ ->
                        val bytes = ByteArrayOutputStream().also { editable.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                        saveDocumentBytes(bytes, "Clean_Document", "image/png", "png")
                    }.setNegativeButton("Cancel", null).create()
                dialog.setOnDismissListener { editable.recycle() }
                dialog.show()
                status.text = "Document editor ready"
            }
        }
    }

    private fun selectRedactionPage(uri: Uri) {
        advancedToolJob("Opening PDF for redaction…") {
            val count = contentResolver.openFileDescriptor(uri, "r")?.let { descriptor ->
                PdfRenderer(descriptor).use { it.pageCount }
            } ?: error("Cannot open PDF")
            require(count in 1..50) { "Redaction supports 1–50 pages per job" }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    appDialog().setTitle("PDF Redaction • Select page")
                        .setPremiumItems(Array(count) { "Page " + (it + 1) }) { _, index -> showRedactionPreview(uri, index) }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        }
    }

    private fun showRedactionPreview(uri: Uri, pageIndex: Int) {
        advancedToolJob("Rendering redaction preview…") {
            val preview = contentResolver.openFileDescriptor(uri, "r")?.let { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(pageIndex).use { page ->
                        val scale = minOf(2f, 1600f / maxOf(page.width, page.height))
                        Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(android.graphics.Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            } ?: error("Cannot render PDF")
            runOnUiThread {
                if (isFinishing || isDestroyed) { preview.recycle(); return@runOnUiThread }
                val regions = mutableListOf<android.graphics.RectF>()
                val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
                val editor = object : View(this) {
                    var active: android.graphics.RectF? = null
                    var startX = 0f
                    var startY = 0f
                    private fun bounds(): android.graphics.RectF {
                        val scale = minOf(width.toFloat() / preview.width, height.toFloat() / preview.height)
                        val w = preview.width * scale
                        val h = preview.height * scale
                        return android.graphics.RectF((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2)
                    }
                    override fun onDraw(canvas: Canvas) {
                        val b = bounds()
                        canvas.drawBitmap(preview, null, b, Paint(Paint.FILTER_BITMAP_FLAG))
                        val paint = Paint().apply { color = android.graphics.Color.BLACK }
                        (regions + listOfNotNull(active)).forEach {
                            canvas.drawRect(b.left + it.left*b.width(), b.top + it.top*b.height(), b.left + it.right*b.width(), b.top + it.bottom*b.height(), paint)
                        }
                    }
                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        val b = bounds()
                        val x = ((event.x-b.left)/b.width().coerceAtLeast(1f)).coerceIn(0f,1f)
                        val y = ((event.y-b.top)/b.height().coerceAtLeast(1f)).coerceIn(0f,1f)
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); startX=x; startY=y; active=null }
                            MotionEvent.ACTION_MOVE -> active = android.graphics.RectF(minOf(x,startX),minOf(y,startY),maxOf(x,startX),maxOf(y,startY))
                            MotionEvent.ACTION_UP -> {
                                val rect = android.graphics.RectF(minOf(x,startX),minOf(y,startY),maxOf(x,startX),maxOf(y,startY))
                                if (rect.width() > 0.003f && rect.height() > 0.003f) regions.add(rect)
                                active=null; parent?.requestDisallowInterceptTouchEvent(false); performClick()
                            }
                            MotionEvent.ACTION_CANCEL -> { active=null; parent?.requestDisallowInterceptTouchEvent(false) }
                        }
                        invalidate()
                        return true
                    }
                    override fun performClick(): Boolean { super.performClick(); return true }
                }
                panel.addView(label("Drag black rectangles over sensitive details. Review every region before saving. Other pages are not automatically redacted.",12f,0xFF64748B.toInt()))
                panel.addView(editor, LinearLayout.LayoutParams(-1,dp(340)))
                panel.addView(nativeButton(this, ButtonRole.Secondary).apply { text="Undo last region"; setOnClickListener { if(regions.isNotEmpty()) regions.removeAt(regions.lastIndex); editor.invalidate() } })
                val dialog = appDialog().setTitle("PDF Redaction • Page "+(pageIndex+1)).setView(panel)
                    .setNegativeButton("Cancel",null).setPositiveButton("Export redacted PDF",null).create()
                dialog.setOnDismissListener { preview.recycle() }
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if(regions.isEmpty()) { Toast.makeText(this,"Mark at least one region",Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    val selected = regions.map { android.graphics.RectF(it) }
                    dialog.dismiss()
                    appDialog().setTitle("Permanently remove marked content?")
                        .setMessage("Creates a new image-only PDF from rendered pages, with marked pixels overwritten. Text search, forms, links and original metadata are discarded. Original PDF remains unchanged. Review output before sharing.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Create copy") { _, _ -> exportRedactedPdf(uri,pageIndex,selected) }.show()
                }
                status.text="Redaction preview ready"
            }
        }
    }

    private fun exportRedactedPdf(uri: Uri, selectedPage: Int, regions: List<android.graphics.RectF>) {
        advancedToolJob("Creating redacted copy…") {
            val output = PdfDocument()
            try {
                contentResolver.openFileDescriptor(uri,"r")?.let { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        require(renderer.pageCount in 1..50) { "Maximum 50 pages" }
                        for(i in 0 until renderer.pageCount) {
                            renderer.openPage(i).use { page ->
                                val scale=minOf(2.5f,2200f/maxOf(page.width,page.height))
                                val bitmap=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    if(i==selectedPage) {
                                        val canvas=Canvas(bitmap)
                                        val paint=Paint().apply { color=android.graphics.Color.BLACK }
                                        regions.forEach { rect ->
                                            canvas.drawRect((rect.left*bitmap.width-2).coerceAtLeast(0f),(rect.top*bitmap.height-2).coerceAtLeast(0f),(rect.right*bitmap.width+2).coerceAtMost(bitmap.width.toFloat()),(rect.bottom*bitmap.height+2).coerceAtMost(bitmap.height.toFloat()),paint)
                                        }
                                    }
                                    val resultPage=output.startPage(PdfDocument.PageInfo.Builder(page.width,page.height,i+1).create())
                                    resultPage.canvas.drawBitmap(bitmap,null,Rect(0,0,page.width,page.height),Paint(Paint.FILTER_BITMAP_FLAG))
                                    output.finishPage(resultPage)
                                } finally { bitmap.recycle() }
                            }
                        }
                    }
                } ?: error("Cannot read PDF")
                val bytes=ByteArrayOutputStream().also { output.writeTo(it) }.toByteArray()
                runOnUiThread {
                    if(!isFinishing && !isDestroyed) {
                        status.text="Redacted PDF created"
                        savePdfBytes(bytes,"Redacted")
                    }
                }
            } finally { output.close() }
        }
    }


    private val powerpointExportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) exportPdfPresentation(uri)
    }

    private fun exportPdfPresentation(uri: Uri) {
        if (!requirePremiumTool("PDF to PowerPoint")) return
        appDialog().setTitle("PDF to PowerPoint")
            .setMessage("Create a widescreen PPTX with one high-resolution image per PDF page. Original visual layout is preserved, but individual text and objects are not editable. Maximum 40 pages.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Export PPTX") { _, _ ->
                advancedToolJob("Creating PowerPoint…") {
                    val output = ByteArrayOutputStream()
                    val p = "http://schemas.openxmlformats.org/presentationml/2006/main"
                    val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
                    val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                    val relNs = "http://schemas.openxmlformats.org/package/2006/relationships"
                    fun rel(id: String, type: String, target: String) = """<Relationship Id="$id" Type="$r/$type" Target="$target"/>"""
                    fun rels(body: String) = """<Relationships xmlns="$relNs">$body</Relationships>"""
                    val group = """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""
                    val clrMap = """<p:clrMap accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" bg1="lt1" bg2="lt2" folHlink="folHlink" hlink="hlink" tx1="dk1" tx2="dk2"/>"""
                    val ns = "xmlns:p=\"$p\" xmlns:a=\"$a\" xmlns:r=\"$r\""
                    val cx = 12192000L
                    val cy = 6858000L
                    var count = 0
                    ZipOutputStream(output).use { zip ->
                        fun part(name: String, data: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
                        fun xml(name: String, value: String) = part(name, value.toByteArray(Charsets.UTF_8))
                        val descriptor = contentResolver.openFileDescriptor(uri,"r") ?: error("Cannot open PDF")
                        PdfRenderer(descriptor).use { renderer ->
                            count = renderer.pageCount
                            require(count in 1..40) { "Select a PDF containing 1–40 pages" }
                            for (index in 0 until count) {
                                val n = index + 1
                                renderer.openPage(index).use { page ->
                                    val scale = minOf(3f, 2200f/maxOf(page.width,page.height))
                                    val bitmap = Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                                    try {
                                        bitmap.eraseColor(android.graphics.Color.WHITE)
                                        page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        val image = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG,94,it) }.toByteArray()
                                        part("ppt/media/page$n.jpg",image)
                                        require(output.size() <= 64*1024*1024) { "Presentation exceeds 64 MB" }
                                        val ratio = minOf(cx.toDouble()/page.width,cy.toDouble()/page.height)
                                        val w = (page.width*ratio).toLong()
                                        val h = (page.height*ratio).toLong()
                                        val left = (cx-w)/2
                                        val top = (cy-h)/2
                                        val picture = """<p:pic><p:nvPicPr><p:cNvPr id="2" name="PDF Page $n"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="$left" y="$top"/><a:ext cx="$w" cy="$h"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>"""
                                        xml("ppt/slides/slide$n.xml","""<p:sld $ns><p:cSld><p:spTree>$group$picture</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>""")
                                        xml("ppt/slides/_rels/slide$n.xml.rels",rels(rel("rId1","slideLayout","../slideLayouts/slideLayout1.xml")+rel("rId2","image","../media/page$n.jpg")))
                                    } finally { bitmap.recycle() }
                                }
                            }
                        }
                        val slides = (1..count).joinToString("") { n -> """<p:sldId id="${255+n}" r:id="rId${n+1}"/>""" }
                        xml("ppt/presentation.xml","""<p:presentation $ns><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>$slides</p:sldIdLst><p:sldSz cx="$cx" cy="$cy" type="screen16x9"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>""")
                        xml("ppt/_rels/presentation.xml.rels",rels(rel("rId1","slideMaster","slideMasters/slideMaster1.xml")+(1..count).joinToString("") { n -> rel("rId${n+1}","slide","slides/slide$n.xml") }))
                        xml("ppt/slideMasters/slideMaster1.xml","""<p:sldMaster $ns><p:cSld><p:spTree>$group</p:spTree></p:cSld>$clrMap<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles/></p:sldMaster>""")
                        xml("ppt/slideMasters/_rels/slideMaster1.xml.rels",rels(rel("rId1","slideLayout","../slideLayouts/slideLayout1.xml")+rel("rId2","theme","../theme/theme1.xml")))
                        xml("ppt/slideLayouts/slideLayout1.xml","""<p:sldLayout $ns type="blank" preserve="1"><p:cSld name="Blank"><p:spTree>$group</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>""")
                        xml("ppt/slideLayouts/_rels/slideLayout1.xml.rels",rels(rel("rId1","slideMaster","../slideMasters/slideMaster1.xml")))
                        val colors = listOf("dk1" to "000000","lt1" to "FFFFFF","dk2" to "172B4D","lt2" to "F1F5F9","accent1" to "2563EB","accent2" to "0D9488","accent3" to "7C3AED","accent4" to "EA580C","accent5" to "DC2626","accent6" to "64748B","hlink" to "2563EB","folHlink" to "7C3AED").joinToString("") { (key,value) -> """<a:$key><a:srgbClr val="$value"/></a:$key>""" }
                        val fill = """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>"""
                        val line = """<a:ln w="12700">$fill<a:prstDash val="solid"/></a:ln>"""
                        xml("ppt/theme/theme1.xml","""<a:theme xmlns:a="$a" name="DocNexa"><a:themeElements><a:clrScheme name="DocNexa">$colors</a:clrScheme><a:fontScheme name="Arial"><a:majorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="Default"><a:fillStyleLst>$fill$fill$fill</a:fillStyleLst><a:lnStyleLst>$line$line$line</a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst>$fill$fill$fill</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>""")
                        xml("_rels/.rels",rels(rel("rId1","officeDocument","ppt/presentation.xml")))
                        val slideTypes = (1..count).joinToString("") { n -> """<Override PartName="/ppt/slides/slide$n.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""" }
                        xml("[Content_Types].xml","""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>$slideTypes</Types>""")
                    }
                    val data = output.toByteArray()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            status.text = "PowerPoint export complete"
                            saveDocumentBytes(data,"PDF_Presentation","application/vnd.openxmlformats-officedocument.presentationml.presentation","pptx")
                        }
                    }
                }
            }.show()
    }


    // Image Studio reuses the existing export and document tools, not duplicate implementations.
    private val studioConvertPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { studioFormatDialog(listOf(it)) } }
    private val studioBatchPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files -> if(files.isNotEmpty()) studioFormatDialog(files) }
    private val studioResizePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { studioResizeDialog(it, false) } }
    private val studioSocialPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { studioResizeDialog(it, true) } }
    private val studioUpscalePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { studioUpscaleDialog(it) } }
    private val studioBgPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { studioBackground(it, false) } }
    private val studioBgChangePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { studioBackground(it, true) } }
    private val studioCompressPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { studioCompressDialog(it) } }
    private val studioCropPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { studioCrop(it) } }
    private val studioWatermarkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { studioWatermark(it) } }
    private val studioZipPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files -> if(files.isNotEmpty()) studioZip(files) }

    private fun showImageStudio() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFF4F7FC.toInt()) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12),dp(12),dp(12),dp(12)); setBackgroundColor(0xFF10254C.toInt())
        }
        header.addView(label("‹",26f,android.graphics.Color.WHITE,true).apply {
            contentDescription="Back to Home"; setPadding(dp(10),dp(5),dp(18),dp(5)); setOnClickListener { buildScreen() }
        })
        header.addView(label("Image Studio",20f,android.graphics.Color.WHITE,true),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(label("♛ PRO",12f,0xFFFFD66B.toInt(),true).apply {
            setPadding(dp(8),dp(8),dp(8),dp(8)); setOnClickListener { studioPremiumInfo() }
        })
        root.addView(header)
        val body = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(14),dp(14),dp(24)) }
        val banner = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(18),dp(18),dp(18)); background=gradient(0xFF1764DE.toInt(),0xFF7541DF.toInt(),22)
        }
        banner.addView(label("ALL-IN-ONE IMAGE TOOLS",11f,0xFFE1E7FF.toInt(),true))
        banner.addView(gap(6)); banner.addView(label("Create. Convert. Enhance.",22f,android.graphics.Color.WHITE,true))
        banner.addView(gap(5)); banner.addView(label("Private, on-device image processing and high-quality exports",12f,0xFFE5EDFF.toInt()))
        body.addView(banner); body.addView(gap(12))
        body.addView(membershipHomeCard()); body.addView(gap(12))
        data class StudioTile(val title:String,val description:String,val icon:String,val group:String,val action:()->Unit)
        val tiles = listOf(
            StudioTile("Format Converter","JPG, PNG, WebP, BMP","⇄","Convert"){studioConvertPicker.launch(arrayOf("image/*"))},
            StudioTile("Resize Image","Custom dimensions","↔","Edit"){studioResizePicker.launch(arrayOf("image/*"))},
            StudioTile("Remove Background","AI subject cutout","✦","AI"){studioBgPicker.launch(arrayOf("image/*"))},
            StudioTile("Image Upscaler",if(hasVerifiedPremium())"HD / 2K / 4K / 8K" else "HD / 2K • 4K / 8K Pro","4K","Edit"){studioUpscalePicker.launch(arrayOf("image/*"))},
            StudioTile("Image Enhancer","Contrast and text clarity","◐","Edit"){enhanceDocumentPicker.launch(arrayOf("image/*"))},
            StudioTile("Compress Image","Target KB / MB","↓","Convert"){studioCompressPicker.launch(arrayOf("image/*"))},
            StudioTile("Crop & Rotate","Adjust image framing","▧","Edit"){studioCropPicker.launch(arrayOf("image/*"))},
            StudioTile("Background Changer","AI cutout + solid color","▣","AI"){studioBgChangePicker.launch(arrayOf("image/*"))},
            StudioTile("Passport Photo","ID and print sizes","▤","Edit"){passportPicker.launch(arrayOf("image/*"))},
            StudioTile("Social Media Resize","Square, story, thumbnail","▥","Edit"){studioSocialPicker.launch(arrayOf("image/*"))},
            StudioTile("Image to Text","Offline OCR","Aa","Utility"){ocrPicker.launch("image/*")},
            StudioTile("Image Watermark","Custom text","W","Edit"){studioWatermarkPicker.launch(arrayOf("image/*"))},
            StudioTile("Batch Converter","Convert up to 10 images","▦","Convert"){studioBatchPicker.launch(arrayOf("image/*"))},
            StudioTile("Image to ZIP","Archive original images","ZIP","Utility"){studioZipPicker.launch(arrayOf("image/*"))},
            StudioTile("Image to PDF","High-quality PDF export","PDF","Convert"){imagePicker.launch(arrayOf("image/*"))},
            StudioTile("Document Eraser","Manual white brush","✎","Edit"){documentEraserPicker.launch(arrayOf("image/*"))}
        )
        val tabs = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val scroller=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(tabs) }
        body.addView(scroller)
        val search=EditText(this).apply { hint="Search image tools"; setSingleLine(true); textSize=14f; setPadding(dp(12),dp(10),dp(12),dp(10)); background=rounded(0xFFFFFFFF.toInt(),12,0xFFD6E3F5.toInt()) }
        body.addView(search); body.addView(gap(10))
        val grid=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        var category="All"
        fun render(){
            grid.removeAllViews()
            val filtered=tiles.filter{ (category=="All" || it.group==category) && (it.title+" "+it.description).contains(search.text.toString(),ignoreCase=true) }
            if(filtered.isEmpty()) grid.addView(label("No matching tools.",12f,0xFF64748B.toInt()))
            filtered.chunked(2).forEach { pair ->
                val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
                pair.forEach { item ->
                    row.addView(toolCard(if(item.title in premiumStudioTools) item.title+" • PRO" else item.title,if(item.title=="Remove Background")"Free preview • PRO export" else item.description,item.icon,0xFF3478F6.toInt()) { if(item.title !in premiumStudioTools || requirePremiumTool(item.title)) item.action() },LinearLayout.LayoutParams(0,dp(128),1f).apply { setMargins(dp(4),dp(4),dp(4),dp(4)) })
                }
                if(pair.size==1) row.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
                grid.addView(row)
            }
        }
        listOf("All","Convert","Edit","AI","Utility").forEach { name ->
            tabs.addView(nativeButton(this, ButtonRole.Secondary).apply { text=name; isAllCaps=false; textSize=11f; setOnClickListener { category=name; render() } })
        }
        search.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render()}
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        body.addView(grid); render(); addNativeAdCard(body)
        root.addView(ScrollView(this).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f))
        setScreenContent(root)
    }

    private val premiumAdDisposers = mutableListOf<() -> Unit>()
    private val billingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var playBillingClient: BillingClient? = null
    private var billingConnecting = false
    private val billingReadyCallbacks = mutableListOf<() -> Unit>()
    private var billingChecking = false
    private var billingCheckoutPreparing = false
    private var billingCheckGeneration = 0
    @Volatile private var verifiedPremiumUser = ""
    @Volatile private var verifiedPremiumUntil = 0L
    private var premiumDialog: AlertDialog? = null
    private val premiumProduct = "docnexa_premium"
    private val premiumPlans = listOf("premium-monthly", "premium-yearly")
    private val billingRefreshTask = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (verifiedPremiumUser.isNotBlank() && (verifiedPremiumUser != signedInUserId() || verifiedPremiumUntil <= System.currentTimeMillis())) {
                verifiedPremiumUser = ""
                verifiedPremiumUntil = 0
                buildScreen()
            }
            refreshPlayPurchases(false)
            billingHandler.postDelayed(this, 60_000L)
        }
    }

    private val premiumPdfTools = setOf("PDF Compare", "PDF to Excel", "PDF to PowerPoint", "Searchable OCR PDF", "Batch Document Studio")
    private val premiumStudioTools = setOf("Batch Converter", "Background Changer")
    private fun requirePremiumTool(feature: String): Boolean {
        if (hasVerifiedPremium()) return true
        studioPremiumInfo(feature)
        return false
    }
    private fun openPdfCatalogTool(tool: PdfTool) {
        if (tool.title !in premiumPdfTools || requirePremiumTool(tool.title)) tool.action()
    }
    private fun pdfCatalogTitle(title: String): String =
        if (title in premiumPdfTools) "$title • PRO" else title

    private fun hasVerifiedPremium(): Boolean =
        verifiedPremiumUser.isNotBlank() && verifiedPremiumUser == signedInUserId() &&
            verifiedPremiumUntil > System.currentTimeMillis()

    override fun onResume() {
        super.onResume()
        startStrictOnlineMode()
        refreshPlayPurchases(false)
        billingHandler.removeCallbacks(billingRefreshTask)
        billingHandler.postDelayed(billingRefreshTask, 60_000L)
    }

    override fun onStop() {
        if(appLockEnabled() && credentialOperation==null) appLockUnlocked=false
        super.onStop()
    }

    override fun onPause() {
        stopStrictOnlineMonitoring()
        billingHandler.removeCallbacks(billingRefreshTask)
        super.onPause()
    }

    private fun connectPlayBilling(onReady: (() -> Unit)? = null) {
        if (isFinishing || isDestroyed) return
        val client = playBillingClient ?: BillingClient.newBuilder(this)
            .setListener { result, purchases ->
                runOnUiThread {
                    when (result.responseCode) {
                        BillingClient.BillingResponseCode.OK -> {
                            if (purchases.orEmpty().any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
                                Toast.makeText(this, "Payment pending. Premium unlocks after payment verification.", Toast.LENGTH_LONG).show()
                            }
                            refreshPlayPurchases(true)
                        }
                        BillingClient.BillingResponseCode.USER_CANCELED -> Unit
                        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refreshPlayPurchases(true)
                        else -> Toast.makeText(this, "Google Play purchase unavailable. Try again later.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build().also { playBillingClient = it }
        if (client.isReady) { onReady?.invoke(); return }
        if (onReady != null) billingReadyCallbacks.add(onReady)
        if (billingConnecting) return
        billingConnecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                runOnUiThread {
                    billingConnecting = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val callbacks = billingReadyCallbacks.toList()
                    billingReadyCallbacks.clear()
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        callbacks.forEach { it() }
                        if (callbacks.isEmpty()) refreshPlayPurchases(false)
                    } else if (callbacks.isNotEmpty()) Toast.makeText(this@MainActivity, "Google Play Billing is unavailable. Plan details are still visible.", Toast.LENGTH_LONG).show()
                }
            }
            override fun onBillingServiceDisconnected() { runOnUiThread { billingConnecting = false; billingReadyCallbacks.clear() } }
        })
    }

    private fun refreshPlayPurchases(notify: Boolean) {
        if (isFinishing || isDestroyed || billingChecking) return
        val user = signedInUserId()
        val auth = cloudToken()
        if (user == "guest" || auth.isBlank()) {
            verifiedPremiumUser = ""; verifiedPremiumUntil = 0L
            if (notify) Toast.makeText(this, "Sign in to restore your membership.", Toast.LENGTH_LONG).show()
            return
        }
        connectPlayBilling {
            val client = playBillingClient ?: return@connectPlayBilling
            if (billingChecking) return@connectPlayBilling
            billingChecking = true
            val generation = ++billingCheckGeneration
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) { billingChecking = false; return@runOnUiThread }
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        billingChecking = false
                        if (notify) Toast.makeText(this, "Could not check purchases. Try Restore Purchase again.", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val tokens = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && premiumProduct in it.products }.map { it.purchaseToken }
                    Thread {
                        var expiry = 0L
                        var unavailable = false
                        for (token in tokens) {
                            try {
                                val verified = SupabaseCloud.playBilling(auth, "verify", token)
                                if (verified.optBoolean("active")) expiry = maxOf(expiry, verified.optLong("expiresAt"))
                            } catch (_: Exception) { unavailable = true }
                        }
                        runOnUiThread {
                            billingChecking = false
                            if (isFinishing || isDestroyed || generation != billingCheckGeneration || user != signedInUserId()) return@runOnUiThread
                            val wasPremium = verifiedPremiumUser == user && verifiedPremiumUser.isNotBlank()
                            if (expiry > System.currentTimeMillis()) {
                                verifiedPremiumUser = user
                                verifiedPremiumUntil = minOf(expiry, System.currentTimeMillis() + 5 * 60_000L)
                                premiumAdDisposers.toList().forEach { it() }
                            } else if (!unavailable) {
                                verifiedPremiumUser = ""; verifiedPremiumUntil = 0L
                            }
                            refreshMembershipLabels()
                            if (wasPremium && !hasVerifiedPremium()) buildScreen()
                            if (notify) {
                                val message = when {
                                    hasVerifiedPremium() -> "Premium active • Ads removed"
                                    unavailable -> "Purchase verification unavailable. Please restore again later."
                                    else -> "No active verified membership found."
                                }
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                                if (hasVerifiedPremium()) premiumDialog?.dismiss()
                            }
                        }
                    }.start()
                }
            }
        }
    }

    private fun managePlaySubscription() {
        val url = "https://play.google.com/store/account/subscriptions?sku=$premiumProduct&package=$packageName"
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure {
            Toast.makeText(this, "Open Google Play Store to manage your subscription.", Toast.LENGTH_LONG).show()
        }
    }

    private val membershipLabels = mutableListOf<java.lang.ref.WeakReference<TextView>>()
    private val headerMembershipLabels = mutableListOf<java.lang.ref.WeakReference<TextView>>()

    private fun refreshMembershipLabels() {
        val headerIterator = headerMembershipLabels.iterator()
        while (headerIterator.hasNext()) {
            val view = headerIterator.next().get()
            if (view == null) headerIterator.remove()
            else view.text = if (hasVerifiedPremium()) "PRO Active" else "Premium"
        }
        val iterator = membershipLabels.iterator()
        while(iterator.hasNext()) {
            val view = iterator.next().get()
            if(view == null) iterator.remove()
            else view.text = if(hasVerifiedPremium()) "PREMIUM ACTIVE • Manage plan" else "FREE PLAN • Explore Premium"
        }
    }

    private fun membershipHomeCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = gradient(0xFF142C64.toInt(), 0xFF6546C8.toInt(), 20)
            elevation = dp(2).toFloat()
            isClickable = true; isFocusable = true
            setOnClickListener { studioPremiumInfo() }
        }
        val badge = label(if(hasVerifiedPremium()) "PREMIUM ACTIVE • Manage plan" else "FREE PLAN • Explore Premium", 10.5f, 0xFFFFD66B.toInt(), true)
        membershipLabels.add(java.lang.ref.WeakReference(badge))
        card.addView(badge); card.addView(gap(5))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label("♛ DocNexa Premium", 19f, android.graphics.Color.WHITE, true))
        text.addView(gap(4))
        text.addView(label("No ads • Advanced PDF tools • 4K / 8K images", 11f, 0xFFE3E9FF.toInt()))
        row.addView(text,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(label("›",28f,0xFFFFD66B.toInt(),true))
        card.addView(row); card.addView(gap(8))
        card.addView(label("Compare Free, Monthly & Yearly plans  →",12f,0xFFFFFFFF.toInt(),true))
        return card
    }

    private fun studioPremiumInfo(feature: String = "") {
        premiumDialog?.dismiss()
        val panel = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(8),dp(14),dp(14)) }
        val hero = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)); background=gradient(0xFF073B91.toInt(),0xFF4036A0.toInt(),18) }
        hero.addView(label("♛ DOCNEXA PREMIUM",12f,0xFFFFD66B.toInt(),true))
        hero.addView(gap(5))
        hero.addView(label(if(hasVerifiedPremium()) "Premium membership active" else "Unlock your full workspace",22f,android.graphics.Color.WHITE,true))
        hero.addView(gap(5))
        hero.addView(label("Ad-free documents • Advanced tools • 4K and 8K export",12f,0xFFE5EDFF.toInt()))
        if(feature.isNotBlank()) hero.addView(label("$feature requires Premium",13f,0xFFFFD66B.toInt(),true))
        panel.addView(hero); panel.addView(gap(12))
        fun section(title:String){panel.addView(label(title,14f,0xFF172B4D.toInt(),true));panel.addView(gap(6))}
        val comparison=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=rounded(0xFFFFFFFF.toInt(),14,0xFFDCE6F5.toInt());setPadding(dp(10),dp(6),dp(10),dp(6))}
        fun row(name:String,free:String,pro:String,heading:Boolean=false){
            val line=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(7),0,dp(7))}
            line.addView(label(name,11.5f,0xFF263957.toInt(),heading),LinearLayout.LayoutParams(0,-2,1.9f))
            line.addView(label(free,11f,0xFF64748B.toInt(),heading).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
            line.addView(label(pro,11f,0xFF16754A.toInt(),true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
            comparison.addView(line)
            comparison.addView(View(this).apply{setBackgroundColor(0xFFEAF0F8.toInt())},LinearLayout.LayoutParams(-1,dp(1)))
        }
        row("FEATURE","FREE","PRO",true)
        row("Scan & high-quality PDF","✓","✓")
        row("Edit, merge, sign & print","✓","✓")
        row("Format / resize / compress","✓","✓")
        row("HD & 2K image export","✓","✓")
        row("PDF to Excel / PowerPoint","—","✓")
        row("PDF Compare / OCR PDF","—","✓")
        row("Batch document / image tools","—","✓")
        row("AI background removal","Preview","Export")
        row("Background changer","—","✓")
        row("4K / 8K image scaling","—","✓")
        row("Custom resize above 2048 px","—","✓")
        row("PDF image export up to 300 DPI","✓","✓")
        row("PDF image export up to 600 DPI*","—","✓")
        row("Bill / invoice PDF","✓","✓")
        row("Premium invoice styles / logo","—","✓")
        row("Customer / product records","—","✓")
        row("Billing reports / CSV","—","✓")
        row("Google Drive manual backup","✓","✓")
        row("Google Drive batch backup","—","✓")
        row("Native ads","Shown","None")
        section("Choose your membership")
        val planButtons=mutableMapOf<String,Button>()
        val planPrices=mutableMapOf<String,TextView>()
        val planHints=mutableMapOf<String,TextView>()
        premiumPlans.forEach { plan ->
            val monthly=plan=="premium-monthly"
            val card=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL;setPadding(dp(13),dp(12),dp(13),dp(12))
                background=rounded(if(monthly)0xFFFFFFFF.toInt() else 0xFFF2EEFF.toInt(),16,if(monthly)0xFFDCE6F5.toInt() else 0xFF9C87E9.toInt())
            }
            card.addView(label(if(monthly)"MONTHLY PREMIUM" else "YEARLY PREMIUM",12f,0xFF6546C8.toInt(),true))
            val price=label("Checking Google Play price…",20f,0xFF172B4D.toInt(),true)
            val hint=label("Auto-renewing • Price shown when available",11f,0xFF64748B.toInt())
            card.addView(price);card.addView(hint);card.addView(gap(5))
            card.addView(label("All Premium features • Auto-renewing",12f,0xFF263957.toInt()))
            val button=nativeButton(this, ButtonRole.Primary).apply{text="Checking availability…";isAllCaps=false;isEnabled=false;minHeight=dp(48)}
            card.addView(button,LinearLayout.LayoutParams(-1,-2))
            planButtons[plan]=button;planPrices[plan]=price;planHints[plan]=hint
            panel.addView(card);panel.addView(gap(8))
        }
        val message=label(if(hasVerifiedPremium())"Your active membership unlocks Premium tools." else "Live prices are provided by Google Play when available. No payment is collected before activation.",12f,0xFF64748B.toInt())
        panel.addView(message);panel.addView(gap(12))
        section("Compare Free & Premium")
        panel.addView(comparison);panel.addView(gap(12))
        panel.addView(label("Subscriptions renew automatically until canceled in Google Play. Prices, taxes and final terms are shown before purchase. High-resolution scaling does not recreate missing image detail. *PDF export resolution depends on page size and available memory.",11f,0xFF64748B.toInt()))
        panel.addView(gap(10))
        val dialog=appDialog().setTitle("Plans & membership").setView(toolDialogScroll(panel,0.74f))
            .setNegativeButton("Close",null).setNeutralButton("Restore"){_,_->
                if(cloudToken().isBlank()) showCloudLoginDialog() else refreshPlayPurchases(true)
            }.setPositiveButton("Manage"){_,_->managePlaySubscription()}.create()
        premiumDialog=dialog
        dialog.setOnDismissListener { if(premiumDialog===dialog) premiumDialog=null }
        dialog.show()
        if(hasVerifiedPremium()){
            planPrices.values.forEach { it.text="Membership active" }
            planHints.values.forEach { it.text="View billing details in Google Play" }
            planButtons.values.forEach { it.text="Manage current plan";it.isEnabled=true;it.setOnClickListener{managePlaySubscription()} }
            return
        }
        connectPlayBilling {
            val client=playBillingClient ?: return@connectPlayBilling
            val product=QueryProductDetailsParams.Product.newBuilder().setProductId(premiumProduct).setProductType(BillingClient.ProductType.SUBS).build()
            client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { result, details ->
                runOnUiThread {
                    if(!dialog.isShowing || isFinishing || isDestroyed) return@runOnUiThread
                    val item=details.productDetailsList.firstOrNull{it.productId==premiumProduct}
                    if(result.responseCode!=BillingClient.BillingResponseCode.OK || item==null){
                        message.text="Google Play plans are not available on this installation. No payment can be started."
                        planPrices.values.forEach { it.text="Price unavailable" }
                        planButtons.values.forEach { it.text="Not available"; it.isEnabled=false }
                        return@runOnUiThread
                    }
                    message.text="Choose an available auto-renewing plan. Sign in before checkout."
                    val offers=item.subscriptionOfferDetails.orEmpty().filter{it.offerId==null && it.basePlanId in premiumPlans}
                    premiumPlans.forEach { plan ->
                        val offer=offers.firstOrNull{it.basePlanId==plan}
                        if(offer==null){planPrices[plan]?.text="Price unavailable";planButtons[plan]?.text="Not available";return@forEach}
                        val phase=offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@forEach
                        val period=if(plan=="premium-monthly") "month" else "year"
                        planPrices[plan]?.text=phase.formattedPrice+" / "+period
                        planHints[plan]?.text="Price from Google Play"
                        val button=planButtons[plan] ?: return@forEach
                        button.text=if(cloudToken().isBlank())"Sign in to subscribe" else "Continue with "+if(period=="month")"Monthly" else "Yearly"
                        button.isEnabled=true
                        button.setOnClickListener {
                            if(cloudToken().isBlank()){dialog.dismiss();showCloudLoginDialog()}
                            else { button.isEnabled=false;beginVerifiedPurchase(dialog,button,item,offer.offerToken) }
                        }
                    }
                    if(offers.isEmpty())message.text="No eligible active base plans found."
                }
            }
        }
    }

    private fun beginVerifiedPurchase(dialog:AlertDialog,button:Button,product:ProductDetails,offerToken:String){
        val account=signedInUserId();val auth=cloudToken()
        if(account=="guest"||auth.isBlank()){button.isEnabled=true;return}
        if(billingCheckoutPreparing){button.isEnabled=true;Toast.makeText(this,"Checkout is already being prepared.",Toast.LENGTH_SHORT).show();return}
        billingCheckoutPreparing=true
        button.text="Checking secure checkout…"
        Thread {
            val ready=runCatching{SupabaseCloud.playBilling(auth,"ready").optBoolean("ready")}.getOrDefault(false)
            runOnUiThread {
                billingCheckoutPreparing=false
                if(isFinishing||isDestroyed||!dialog.isShowing)return@runOnUiThread
                button.isEnabled=true
                button.text="Continue with Google Play"
                if(!ready || account!=signedInUserId()){
                    Toast.makeText(this,"Secure subscription service is not activated. No payment collected.",Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if(hasVerifiedPremium()){managePlaySubscription();return@runOnUiThread}
                val id=MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
                val params=BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).setOfferToken(offerToken).build()
                val result=playBillingClient?.launchBillingFlow(this,BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).setObfuscatedAccountId(id).build())
                if(result?.responseCode!=BillingClient.BillingResponseCode.OK)Toast.makeText(this,"Could not start Google Play checkout.",Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun studioEncode(bitmap:Bitmap,format:String,quality:Int=95):ByteArray {
        val output=ByteArrayOutputStream()
        if(format=="bmp"){
            val w=bitmap.width; val h=bitmap.height
            val stride=(w*3+3) and -4
            val header=java.nio.ByteBuffer.allocate(54).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put(66.toByte());header.put(77.toByte());header.putInt(54+stride*h);header.putInt(0);header.putInt(54)
            header.putInt(40);header.putInt(w);header.putInt(h);header.putShort(1.toShort());header.putShort(24.toShort())
            header.putInt(0);header.putInt(stride*h);header.putInt(2835);header.putInt(2835);header.putInt(0);header.putInt(0)
            output.write(header.array())
            val colors=IntArray(w); val row=ByteArray(stride)
            for(y in h-1 downTo 0){
                bitmap.getPixels(colors,0,w,0,y,w,1)
                for(i in 0 until w){
                    val c=colors[i];val alpha=android.graphics.Color.alpha(c)
                    fun white(v:Int)=(v*alpha+255*(255-alpha))/255
                    row[i*3]=white(android.graphics.Color.blue(c)).toByte()
                    row[i*3+1]=white(android.graphics.Color.green(c)).toByte()
                    row[i*3+2]=white(android.graphics.Color.red(c)).toByte()
                }
                output.write(row)
            }
        } else {
            val encoding=when(format){
                "jpg"->Bitmap.CompressFormat.JPEG
                "webp"->if(Build.VERSION.SDK_INT>=30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
                else->Bitmap.CompressFormat.PNG
            }
            val flattened=if(format=="jpg" && bitmap.hasAlpha()) Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888).apply {
                Canvas(this).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bitmap,0f,0f,null) }
            } else bitmap
            try { check(flattened.compress(encoding,quality,output)){"Image encoder failed"} }
            finally { if(flattened!==bitmap) flattened.recycle() }
        }
        return output.toByteArray()
    }

    private fun studioMime(format:String)=when(format){"jpg"->"image/jpeg";"webp"->"image/webp";"bmp"->"image/bmp";else->"image/png"}

    private fun studioResult(bitmap:Bitmap,prefix:String,format:String="png",quality:Int=95,requiresPremium:Boolean=false) {
        val owner=signedInUserId()
        val width=bitmap.width; val height=bitmap.height
        val bytes: ByteArray
        val preview: Bitmap
        try {
            bytes=studioEncode(bitmap,format,quality)
            val thumbScale=minOf(1f,900f/maxOf(width,height))
            val thumb=Bitmap.createScaledBitmap(bitmap,(width*thumbScale).toInt().coerceAtLeast(1),(height*thumbScale).toInt().coerceAtLeast(1),true)
            preview=if(thumb===bitmap) bitmap.copy(Bitmap.Config.ARGB_8888,false) else thumb
        } finally { bitmap.recycle() }
        runOnUiThread {
            if(isFinishing || isDestroyed || owner!=signedInUserId()){preview.recycle();return@runOnUiThread}
            val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8))}
            panel.addView(ImageView(this).apply{setImageBitmap(preview);adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER},LinearLayout.LayoutParams(-1,dp(250)))
            panel.addView(label("${width} × ${height} px • ${format.uppercase(Locale.ROOT)} • ${bytes.size/1024} KB",12f,0xFF64748B.toInt()))
            val dialog=appDialog().setTitle("Export preview").setView(panel).setNegativeButton("Close",null)
                .setPositiveButton("Save ${format.uppercase(Locale.ROOT)}"){_,_->
                    if(owner!=signedInUserId()) Toast.makeText(this,"Account changed. Please create the image again.",Toast.LENGTH_LONG).show()
                    else if(requiresPremium && !hasVerifiedPremium()) studioPremiumInfo("High-quality image export")
                    else advancedToolJob("Saving image…") {
                        require(owner==signedInUserId()) { "Account changed. Please create the image again." }
                        if(requiresPremium && !hasVerifiedPremium()) error("Premium verification required")
                        saveDocumentBytes(bytes,prefix,studioMime(format),format) ?: error("Unable to save image")
                    }
                }.create()
            dialog.setOnDismissListener{preview.recycle()}
            dialog.show()
        }
    }

    private fun studioFormatDialog(files:List<Uri>){
        if(files.size > 1 && !requirePremiumTool("Batch Converter")) return
        if(files.size>10){Toast.makeText(this,"Select up to 10 images",Toast.LENGTH_LONG).show();return}
        val formats=arrayOf("jpg","png","webp","bmp")
        appDialog().setTitle(if(files.size==1)"Image Format Converter" else "Batch Image Converter")
            .setPremiumItems(arrayOf("JPEG • white background","PNG • lossless","WebP • compact","BMP • uncompressed")){_,choice->
                val format=formats[choice]
                advancedToolJob("Converting images…"){
                    if(files.size==1) {
                        val premium = hasVerifiedPremium()
                        studioResult(decodeImageSafely(files[0],if(premium) 4200 else 2048,minOf(exportPixelBudget(),if(premium) 12_000_000L else 4_000_000L)),"Converted",format,98,premium)
                    }
                    else {
                        val owner=signedInUserId()
                        require(hasVerifiedPremium()) { "Premium verification required" }
                        val target=PrivateDocumentStore.save(privateDocumentsDir(),"Converted_Images_"+System.currentTimeMillis()+".zip") { stream ->
                            ZipOutputStream(stream).use{zip->
                                var total=0L
                                files.forEachIndexed{index,uri->
                                    require(owner==signedInUserId() && hasVerifiedPremium()) { "Account or Premium access changed" }
                                    val image=decodeImageSafely(uri,2048,minOf(4_000_000L,exportPixelBudget()))
                                    try{
                                        val data=studioEncode(image,format)
                                        total+=data.size
                                        require(total<=64L*1024*1024){"Batch exceeds 64 MB"}
                                        zip.putNextEntry(ZipEntry("Image_${index+1}.$format"));zip.write(data);zip.closeEntry()
                                    }finally{image.recycle()}
                                }
                                require(owner==signedInUserId() && hasVerifiedPremium()) { "Account or Premium access changed" }
                            }
                        }
                        runOnUiThread{if(!isFinishing&&!isDestroyed&&owner==signedInUserId())showExportSuccess(privateDocumentUri(target),target.name,"application/zip")}
                    }
                }
            }.setNegativeButton("Cancel",null).show()
    }

    private fun studioResizeDialog(uri:Uri,social:Boolean){
        if(social){
            val sizes=listOf(1080 to 1080,1080 to 1920,1280 to 720,1200 to 630)
            appDialog().setTitle("Social Media Resize").setPremiumItems(arrayOf("Square • 1080 × 1080","Story • 1080 × 1920","Thumbnail • 1280 × 720","Landscape • 1200 × 630")){_,i->
                studioResize(uri,sizes[i].first,sizes[i].second,true)
            }.setNegativeButton("Cancel",null).show()
            return
        }
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))}
        val w=EditText(this).apply{hint="Width (px)";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText("1920")}
        val h=EditText(this).apply{hint="Height (px)";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText("1080")}
        val fit=android.widget.CheckBox(this).apply{text="Fit without stretching (white margins)";isChecked=true}
        panel.addView(w);panel.addView(h);panel.addView(fit)
        val dialog=appDialog().setTitle("Resize Image").setView(panel).setNegativeButton("Cancel",null).setPositiveButton("Resize",null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val width=w.text.toString().toIntOrNull()?:0;val height=h.text.toString().toIntOrNull()?:0
            if(width !in 1..4096 || height !in 1..4096 || width.toLong()*height > 16_000_000L){Toast.makeText(this,"Use 1–4096 px per side, up to 16 megapixels.",Toast.LENGTH_LONG).show();return@setOnClickListener}
            if(maxOf(width,height)>2048 && !hasVerifiedPremium()){dialog.dismiss();studioPremiumInfo("High-resolution image resize");return@setOnClickListener}
            dialog.dismiss();studioResize(uri,width,height,fit.isChecked)
        }
    }

    private fun studioResize(uri:Uri,width:Int,height:Int,fit:Boolean){
        advancedToolJob("Resizing image…"){
            val premium = maxOf(width,height)>2048
            if(premium && !hasVerifiedPremium()) error("Premium verification required")
            val budget = exportPixelBudget(16_000_000L, 8L)
            require(width.toLong()*height <= budget) { "Not enough memory for these dimensions. Choose a smaller size." }
            val source=decodeImageSafely(uri,if(premium) 4200 else 2048,minOf(budget,if(premium) 12_000_000L else 4_000_000L))
            try{
                val output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(output);canvas.drawColor(android.graphics.Color.WHITE)
                val rect=if(fit)fitRect(source.width,source.height,width,height,0,0) else Rect(0,0,width,height)
                canvas.drawBitmap(source,null,rect,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                studioResult(output,"Resized",requiresPremium=premium)
            }finally{source.recycle()}
        }
    }

    private fun studioUpscaleDialog(uri:Uri){
        appDialog().setTitle("Image Upscaler • Resolution")
            .setPremiumItems(arrayOf("HD • up to 1280 px","2K • up to 2048 px",if(hasVerifiedPremium())"4K • 3840 px" else "4K • Premium locked",if(hasVerifiedPremium())"8K • 7680 px" else "8K • Premium locked")){_,i->
                if(i>=2 && !hasVerifiedPremium()){studioPremiumInfo();return@setPremiumItems}
                advancedToolJob("Upscaling image…"){
                    val edge=listOf(1280,2048,3840,7680)[i]
                    val source=decodeImageSafely(uri,edge,minOf(exportPixelBudget(),if(i>=2) 16_000_000L else 4_000_000L))
                    if(i>=2 && !hasVerifiedPremium()){source.recycle();error("Premium verification required")}
                    val runtime=Runtime.getRuntime()
                    val estimated=edge.toLong()*edge*4L*source.width.coerceAtMost(source.height)/maxOf(source.width,source.height)
                    if(estimated*3L > runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory())){source.recycle();error("Not enough memory for this resolution. Try a smaller image or 4K.")}
                    val scale=edge.toFloat()/maxOf(source.width,source.height)
                    val resized=Bitmap.createScaledBitmap(source,(source.width*scale).toInt().coerceAtLeast(1),(source.height*scale).toInt().coerceAtLeast(1),true)
                    if(resized!==source) source.recycle()
                    if(i>=2 && !hasVerifiedPremium()){resized.recycle();error("Premium verification required")}
                    studioResult(resized,"Upscaled",if(i>=2 && !resized.hasAlpha())"jpg" else "png",98,i>=2)
                }
            }.setNegativeButton("Cancel",null).show()
    }

    private fun studioBackground(uri:Uri,change:Boolean){
        if(change && !requirePremiumTool("Background Changer")) return
        val colors=intArrayOf(android.graphics.Color.WHITE,0xFFE2E8F0.toInt(),0xFFBFE4FF.toInt(),0xFF172B4D.toInt())
        fun process(color:Int?){
            advancedToolJob("Removing background…"){
                val source=decodeImageSafely(uri,1600,2_500_000L)
                val options=com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
                val client=com.google.mlkit.vision.segmentation.subject.SubjectSegmentation.getClient(options)
                try{
                    val result=com.google.android.gms.tasks.Tasks.await(client.process(InputImage.fromBitmap(source,0)))
                    val foreground=result.foregroundBitmap?:error("Subject model may still be downloading, or no subject detected. Check Google Play services and try again.")
                    val output=if(color==null) foreground.copy(Bitmap.Config.ARGB_8888,true) else Bitmap.createBitmap(foreground.width,foreground.height,Bitmap.Config.ARGB_8888).apply{
                        Canvas(this).apply{drawColor(color);drawBitmap(foreground,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG))}
                    }
                    if(foreground!==source)foreground.recycle()
                    studioResult(output,if(color==null)"Background_Removed" else "Background_Changed",requiresPremium=true)
                }finally{client.close();source.recycle()}
            }
        }
        if(change)appDialog().setTitle("Choose background").setPremiumItems(arrayOf("White","Light gray","Sky blue","Navy")){_,i->process(colors[i])}.setNegativeButton("Cancel",null).show()
        else process(null)
    }

    private fun studioCompressDialog(uri:Uri){
        val field=EditText(this).apply{hint="Target size in KB";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText("300")}
        appDialog().setTitle("Compress Image • JPEG").setView(field)
            .setPositiveButton("Compress"){_,_->
                val target=field.text.toString().toIntOrNull()?:0
                if(target !in 20..5000){Toast.makeText(this,"Enter 20–5000 KB",Toast.LENGTH_LONG).show();return@setPositiveButton}
                advancedToolJob("Compressing image…"){
                    var image=decodeImageSafely(uri,2048,4_000_000L)
                    try{
                        var quality=95
                        var data=studioEncode(image,"jpg",quality)
                        while(data.size>target*1024 && quality>20){quality=(quality-10).coerceAtLeast(20);data=studioEncode(image,"jpg",quality)}
                        while(data.size>target*1024 && maxOf(image.width,image.height)>320){
                            val resized=Bitmap.createScaledBitmap(image,(image.width*0.75f).toInt().coerceAtLeast(1),(image.height*0.75f).toInt().coerceAtLeast(1),true)
                            if(resized===image)break
                            image.recycle();image=resized;data=studioEncode(image,"jpg",quality)
                        }
                        require(data.size<=target*1024){"Cannot meet target size without excessive quality loss"}
                        val output=image.copy(Bitmap.Config.ARGB_8888,false)
                        studioResult(output,"Compressed","jpg",quality)
                    }finally{image.recycle()}
                }
            }.setNegativeButton("Cancel",null).show()
    }

    private fun studioCrop(uri:Uri){
        advancedToolJob("Preparing crop…"){
            val source=decodeImageSafely(uri,2048,4_000_000L)
            runOnUiThread{
                if(isFinishing||isDestroyed){source.recycle();return@runOnUiThread}
                val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
                val crop=ImageCropView(this,source)
                panel.addView(crop,LinearLayout.LayoutParams(-1,dp(320)))
                panel.addView(nativeButton(this, ButtonRole.Secondary).apply{text="Rotate 90°";setOnClickListener{crop.rotateImage()}})
                panel.addView(nativeButton(this, ButtonRole.Secondary).apply{text="Reset crop";setOnClickListener{crop.resetCrop()}})
                val dialog=appDialog().setTitle("Crop & Rotate").setView(panel).setPositiveButton("Preview export"){_,_->
                    val result=crop.getCroppedBitmap().copy(Bitmap.Config.ARGB_8888,false)
                    advancedToolJob("Exporting crop…"){studioResult(result,"Cropped")}
                }.setNegativeButton("Cancel",null).create()
                dialog.setOnDismissListener{source.recycle()};dialog.show()
            }
        }
    }

    private fun studioWatermark(uri:Uri){
        val field=EditText(this).apply{hint="Watermark text";setSingleLine(true)}
        appDialog().setTitle("Image Watermark").setView(field).setPositiveButton("Apply"){_,_->
            val value=field.text.toString().trim().take(100)
            if(value.isBlank()){Toast.makeText(this,"Enter watermark text",Toast.LENGTH_SHORT).show();return@setPositiveButton}
            advancedToolJob("Applying watermark…"){
                val original=decodeImageSafely(uri,2048,4_000_000L)
                val image=original.copy(Bitmap.Config.ARGB_8888,true);original.recycle()
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xEFFFFFFF.toInt();textSize=(image.width*0.05f).coerceAtLeast(12f);typeface=android.graphics.Typeface.DEFAULT_BOLD;setShadowLayer(3f,1f,1f,android.graphics.Color.BLACK)}
                val width=paint.measureText(value)
                if(width>image.width*0.9f)paint.textSize*=image.width*0.9f/width
                Canvas(image).drawText(value,image.width*0.05f,image.height*0.9f,paint)
                studioResult(image,"Watermarked")
            }
        }.setNegativeButton("Cancel",null).show()
    }

    private fun studioZip(files:List<Uri>){
        if(files.size>30){Toast.makeText(this,"Select up to 30 images",Toast.LENGTH_LONG).show();return}
        advancedToolJob("Creating image archive…"){
            val output=ByteArrayOutputStream()
            ZipOutputStream(output).use{zip->
                var total=0L
                files.forEachIndexed{index,uri->
                    val ext=when(contentResolver.getType(uri)){"image/jpeg"->"jpg";"image/png"->"png";"image/webp"->"webp";"image/gif"->"gif";"image/bmp"->"bmp";"image/heic"->"heic";"image/avif"->"avif";"image/tiff"->"tiff";else->"image"}
                    zip.putNextEntry(ZipEntry("Image_${index+1}.$ext"))
                    contentResolver.openInputStream(uri)?.use{input->
                        val buffer=ByteArray(8192)
                        while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=64L*1024*1024){"Archive exceeds 64 MB"};zip.write(buffer,0,n)}
                    }?:error("Cannot read image")
                    zip.closeEntry()
                }
            }
            runOnUiThread{if(!isFinishing&&!isDestroyed)saveDocumentBytes(output.toByteArray(),"Image_Archive","application/zip","zip")}
        }
    }


    private var invoiceLogoCallback: ((ByteArray) -> Unit)? = null
    private val invoiceLogoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback=invoiceLogoCallback;invoiceLogoCallback=null
        if(uri!=null && callback!=null && requirePremiumTool("Business logo")){
            advancedToolJob("Preparing business logo…"){
                val image=decodeImageSafely(uri,600,360_000L)
                try {
                    val data=ByteArrayOutputStream().also{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()
                    runOnUiThread{if(!isFinishing&&!isDestroyed)callback(data)}
                }finally{image.recycle()}
            }
        }
    }

    private fun showInvoiceStudio(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xFFF4F7FC.toInt())}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(14),dp(14),dp(14));setBackgroundColor(0xFF10254C.toInt())}
        header.addView(label("‹",26f,android.graphics.Color.WHITE,true).apply{setPadding(dp(8),0,dp(16),0);contentDescription="Back to Home";setOnClickListener{buildScreen()}})
        header.addView(label("Bill / Invoice Maker",19f,android.graphics.Color.WHITE,true),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(label("♛ PRO",12f,0xFFFFD66B.toInt(),true).apply{setOnClickListener{studioPremiumInfo()}})
        root.addView(header)
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(14),dp(14),dp(20))}
        val banner=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(18));background=gradient(0xFF1756C8.toInt(),0xFF7541DF.toInt(),22)}
        banner.addView(label("DOCNEXA • BUSINESS STUDIO",11f,0xFFE4E7FF.toInt(),true))
        banner.addView(gap(6));banner.addView(label("Create professional bills & invoices",22f,android.graphics.Color.WHITE,true))
        banner.addView(gap(5));banner.addView(label("Private on-device records • A4 PDF • Share and print",12f,0xFFE4E7FF.toInt()))
        banner.addView(gap(12));banner.addView(nativeButton(this, ButtonRole.Primary).apply{text="+ Create invoice";isAllCaps=false;setOnClickListener{showInvoiceEditor("Invoice")}},LinearLayout.LayoutParams(-1,dp(52)))
        panel.addView(banner);panel.addView(gap(12))
        val draftCount=invoiceRecords("Drafts").length()
        val historyCount=getSavedPdfs().count{it.first.startsWith("Billing_")}
        val overview=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=rounded(0xFFFFFFFF.toInt(),16,0xFFDCE6F5.toInt())}
        fun metric(value:Int,title:String,action:()->Unit){val tile=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;minimumHeight=dp(62);isClickable=true;isFocusable=true;setOnClickListener{action()}};tile.addView(label(value.toString(),23f,0xFF1769E8.toInt(),true));tile.addView(label(title,12f,0xFF52627A.toInt()));overview.addView(tile,LinearLayout.LayoutParams(0,-2,1f))}
        metric(historyCount,"Saved PDFs"){showInvoiceHistory()};metric(draftCount,"Drafts"){showInvoiceDrafts()};panel.addView(overview);panel.addView(gap(12))
        val kinds=listOf("Invoice" to "Professional service invoice","Bill" to "Simple sales bill","GST Invoice" to "Item tax and GST details","Quotation" to "Price proposal","Estimate" to "Estimated project cost","Receipt" to "Payment received document","Delivery Challan" to "Goods delivery document")
        val tiles: List<Triple<String,String,()->Unit>> = kinds.map{(kind,desc)->Triple(kind,desc,{showInvoiceEditor(kind)})}+listOf(
            Triple("Business Profile","Save business details",{showInvoiceDirectory("Business")}),
            Triple("Customers • PRO","Reusable customer records",{if(requirePremiumTool("Customer manager"))showInvoiceDirectory("Customers")}),
            Triple("Products • PRO","Reusable product records",{if(requirePremiumTool("Product manager"))showInvoiceDirectory("Products")}),
            Triple("Invoice History","Open saved billing PDFs",{showInvoiceHistory()}),
            Triple("Saved Drafts","Continue or delete unfinished documents",{showInvoiceDrafts()}),
            Triple("Billing Reports • PRO","Invoice totals and CSV export",{if(requirePremiumTool("Billing reports"))showBillingReports()})
        )
        tiles.chunked(2).forEach{pair->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            pair.forEach{(title,description,action)->row.addView(toolCard(title,description,when{title.startsWith("Business")->"B";title.startsWith("Customers")->"C";title.startsWith("Products")->"P";title.contains("History")->"↺";title.contains("Drafts")->"✎";title.contains("Reports")->"▤";title=="Receipt"->"✓";else->"₹"},0xFF2563EB.toInt(),action),LinearLayout.LayoutParams(0,dp(128),1f).apply{setMargins(dp(4),dp(4),dp(4),dp(4))})}
            if(pair.size==1)row.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
            panel.addView(row)
        }
        panel.addView(gap(10));panel.addView(label("Check all tax rates, invoice numbers and statutory requirements before issuing a bill. This tool does not submit GST returns or government e-invoices.",11f,0xFF64748B.toInt()))
        addNativeAdCard(panel)
        root.addView(ScrollView(this).apply{addView(panel)},LinearLayout.LayoutParams(-1,0,1f))
        setScreenContent(root)
    }

    private fun invoiceRecords(kind:String):org.json.JSONArray =
        runCatching{org.json.JSONArray(prefs.getString(accountPrefKey("invoice_records_"+kind),"[]")?:"[]")}.getOrDefault(org.json.JSONArray())

    private fun showInvoiceDirectory(kind:String){
        val records=invoiceRecords(kind)
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(8))}
        if(records.length()==0)panel.addView(label("No saved records yet.",13f,0xFF64748B.toInt()))
        for(i in 0 until records.length()){
            val obj=records.optJSONObject(i)?:continue
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            row.addView(label(obj.optString("name")+"\n"+obj.optString("detail"),13f,0xFF263957.toInt(),true),LinearLayout.LayoutParams(0,-2,1f))
            row.addView(nativeButton(this, ButtonRole.Danger).apply{text="Delete";isAllCaps=false;setOnClickListener{
                appDialog().setTitle("Delete record?").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->
                    val updated=org.json.JSONArray();for(j in 0 until records.length())if(j!=i)updated.put(records.get(j))
                    prefs.edit().putString(accountPrefKey("invoice_records_"+kind),updated.toString()).apply()
                    panel.removeView(row)
                }.show()
            }})
            panel.addView(row)
        }
        appDialog().setTitle(kind).setView(toolDialogScroll(panel)).setNegativeButton("Close",null).setPositiveButton("Add record"){_,_->editInvoiceRecord(kind)}.show()
    }

    private fun editInvoiceRecord(kind:String){
        if(kind!="Business" && !requirePremiumTool(kind))return
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(8))}
        fun field(hint:String)=EditText(this).apply{this.hint=hint;setSingleLine(true);filters=arrayOf(android.text.InputFilter.LengthFilter(150));panel.addView(this)}
        val name=field(if(kind=="Products")"Product name" else "Name")
        val detail=field(if(kind=="Products")"HSN / SAC (optional)" else "Address")
        val extra=field(if(kind=="Products")"Unit price (INR)" else "GSTIN (optional)")
        val contact=if(kind=="Business")field("Phone / email")else null
        val dialog=appDialog().setTitle("Add "+kind.lowercase(Locale.ROOT)).setView(panel).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val n=name.text.toString().trim()
            val e=extra.text.toString().trim()
            if(n.isEmpty() || (kind=="Products" && (e.toBigDecimalOrNull()?.let{it>=java.math.BigDecimal.ZERO && it<=java.math.BigDecimal("100000000") }!=true))){Toast.makeText(this,"Enter valid details",Toast.LENGTH_SHORT).show();return@setOnClickListener}
            val records=if(kind=="Business")org.json.JSONArray()else invoiceRecords(kind)
            if(records.length()>=100){Toast.makeText(this,"Maximum 100 records",Toast.LENGTH_SHORT).show();return@setOnClickListener}
            records.put(org.json.JSONObject().put("name",n).put("detail",detail.text.toString()).put("extra",e).put("contact",contact?.text?.toString()?:""))
            prefs.edit().putString(accountPrefKey("invoice_records_"+kind),records.toString()).apply()
            dialog.dismiss();Toast.makeText(this,"Record saved",Toast.LENGTH_SHORT).show()
        }
    }

    private fun recordBillingExport(draft:InvoiceDocument.Draft,uri:Uri){
        val records=invoiceRecords("Exports")
        val entry=org.json.JSONObject().put("number",draft.number).put("kind",draft.kind).put("date",draft.date)
            .put("customer",draft.customer).put("amount",InvoiceDocument.format(InvoiceDocument.totals(draft).total)).put("paid",InvoiceDocument.format(draft.paid)).put("uri",uri.toString())
        val updated=org.json.JSONArray()
        for(i in maxOf(0,records.length()-499) until records.length()){
            val existing=records.optJSONObject(i)?:continue
            if(existing.optString("kind")!=draft.kind||existing.optString("number")!=draft.number)updated.put(existing)
        }
        updated.put(entry)
        prefs.edit().putString(accountPrefKey("invoice_records_Exports"),updated.toString()).apply()
    }

    private fun showBillingReports(){
        if(!requirePremiumTool("Billing reports"))return
        val saved=getSavedPdfs().map{it.second.toString()}.toSet()
        val records=invoiceRecords("Exports")
        val bills=(0 until records.length()).mapNotNull{records.optJSONObject(it)}.filter{it.optString("uri") in saved && it.optString("kind") in listOf("Invoice","GST Invoice","Bill")}
        val total=bills.fold(java.math.BigDecimal.ZERO){a,b->a+(b.optString("amount").toBigDecimalOrNull()?:java.math.BigDecimal.ZERO)}
        val paidTotal=bills.fold(java.math.BigDecimal.ZERO){a,b->a+(b.optString("paid").toBigDecimalOrNull()?:java.math.BigDecimal.ZERO)}
        appDialog().setTitle("Billing reports • Invoice totals")
            .setMessage("${bills.size} saved invoice(s) / bill(s)\nTotal invoiced: INR ${InvoiceDocument.format(total)}\nRecorded received: INR ${InvoiceDocument.format(paidTotal)}\nRecorded balance: INR ${InvoiceDocument.format(total-paidTotal)}\n\nLocal export records only. This is not verified revenue or payment collection. Quotes, receipts and delivery challans are excluded.")
            .setNegativeButton("Close",null).setPositiveButton("Export CSV"){_,_->
                if(requirePremiumTool("Billing report export")){
                    fun cell(value:String):String{
                        val safe=if(value.trimStart().firstOrNull() in listOf('=','+','-','@','\t','\r')) "'"+value else value
                        return "\""+safe.replace("\"","\"\"")+"\""
                    }
                    val csv=buildString{
                        append("Document,Number,Date,Customer,Total INR,Recorded received INR\r\n")
                        bills.forEach{b->append(listOf("kind","number","date","customer","amount","paid").joinToString(","){cell(b.optString(it))});append("\r\n")}
                    }
                    saveDocumentBytes(csv.toByteArray(Charsets.UTF_8),"Billing_Report","text/csv","csv")
                }
            }.show()
    }

    private fun showInvoiceHistory(){
        val records=getSavedPdfs().filter{it.first.startsWith("Billing_")}
        if(records.isEmpty()){appDialog().setTitle("Invoice History").setMessage("Your exported bills will appear here.").setPositiveButton("OK",null).show();return}
        appDialog().setTitle("Invoice History").setPremiumItems(records.map{it.first}.toTypedArray()){_,i->
            val item=records[i]
            appDialog().setTitle(item.first).setPremiumItems(arrayOf("Open PDF","Share PDF","Print PDF","Save a copy")){_,action->
                when(action){0->openPdf(item.second);1->sharePdf(item.second);2->printPdfDocument(item.second);3->showExportSuccess(item.second,item.first,"application/pdf")}
            }.show()
        }.setNegativeButton("Close",null).show()
    }

    private fun showInvoiceDrafts(){
        val drafts=invoiceRecords("Drafts")
        val entries=(0 until drafts.length()).mapNotNull{drafts.optJSONObject(it)}
        if(entries.isEmpty()){appDialog().setTitle("Saved drafts").setMessage("Use Save draft in any bill editor to continue later.").setPositiveButton("OK",null).show();return}
        appDialog().setTitle("Saved drafts").setPremiumItems(entries.map{it.optString("kind")+" • "+it.optString("number")+" • "+it.optString("customer").ifBlank{"Unnamed customer"}}.toTypedArray()){_,i->
            val item=entries[i]
            appDialog().setTitle("Draft options").setPremiumItems(arrayOf("Continue editing","Delete draft")){_,action->
                if(action==0)showInvoiceEditor(item.optString("kind","Invoice"),item)
                else appDialog().setTitle("Delete this draft?").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->
                    val latest=invoiceRecords("Drafts");val updated=org.json.JSONArray()
                    for(j in 0 until latest.length()){val obj=latest.optJSONObject(j)?:continue;if(obj.optString("id")!=item.optString("id"))updated.put(obj)}
                    prefs.edit().putString(accountPrefKey("invoice_records_Drafts"),updated.toString()).apply()
                    showInvoiceDrafts()
                }.show()
            }.show()
        }.setNegativeButton("Close",null).show()
    }

    private fun saveInvoiceDraft(obj:org.json.JSONObject){
        val existing=invoiceRecords("Drafts");val updated=org.json.JSONArray()
        for(i in 0 until existing.length()){val item=existing.optJSONObject(i)?:continue;if(item.optString("id")!=obj.optString("id"))updated.put(item)}
        require(updated.length()<50){"Maximum 50 drafts. Delete an older draft first."}
        updated.put(obj)
        prefs.edit().putString(accountPrefKey("invoice_records_Drafts"),updated.toString()).apply()
    }

    private fun showInvoiceEditor(kind:String,initialDraft:org.json.JSONObject?=null){
        val owner=signedInUserId()
        val draftId=initialDraft?.optString("id")?.takeIf{it.isNotBlank()}?:java.util.UUID.randomUUID().toString()
        fun initial(key:String,fallback:String="")=initialDraft?.optString(key,fallback)?:fallback
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(8))}
        fun section(title:String){panel.addView(gap(12));panel.addView(label(title,14f,0xFF2563EB.toInt(),true));panel.addView(gap(5))}
        fun field(title:String,initial:String="",max:Int=100,numeric:Boolean=false):EditText{
            panel.addView(label(title,11f,0xFF52627A.toInt(),true))
            return EditText(this).apply{hint=title;setText(initial);setSingleLine(true);filters=arrayOf(android.text.InputFilter.LengthFilter(max))
                if(numeric)inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                panel.addView(this)}
        }
        val profile=invoiceRecords("Business").optJSONObject(0)
        section("1 • Business details")
        val business=field("Business name",initial("business",profile?.optString("name").orEmpty()),70)
        val address=field("Business address",initial("address",profile?.optString("detail").orEmpty()),150)
        val contact=field("Phone / email",initial("contact",profile?.optString("contact").orEmpty()),80)
        val gstin=field("Business GSTIN (if registered)",initial("gstin",profile?.optString("extra").orEmpty()),15)
        var logo:ByteArray?=null;var signature:ByteArray?=null
        panel.addView(nativeButton(this, ButtonRole.Premium).apply{text="Add business logo • PRO";isAllCaps=false;setOnClickListener{
            if(requirePremiumTool("Business logo")){invoiceLogoCallback={data->logo=data;text="Logo added • Change"};invoiceLogoPicker.launch(arrayOf("image/*"))}
        }})
        section("2 • Customer details")
        val customer=field("Customer name",initial("customer"),70)
        val customerAddress=field("Customer address",initial("customerAddress"),150)
        val customerGstin=field("Customer GSTIN (optional)",initial("customerGstin"),15)
        val supply=field("Place of supply / state",initial("supply"),50)
        panel.addView(nativeButton(this, ButtonRole.Premium).apply{text="Use saved customer • PRO";isAllCaps=false;setOnClickListener{
            if(requirePremiumTool("Customer manager")){
                val records=invoiceRecords("Customers")
                if(records.length()==0){Toast.makeText(this@MainActivity,"Save a customer in Customers first",Toast.LENGTH_LONG).show();return@setOnClickListener}
                appDialog().setTitle("Select customer").setPremiumItems(Array(records.length()){records.getJSONObject(it).optString("name")}){_,i->
                    val obj=records.getJSONObject(i);customer.setText(obj.optString("name"));customerAddress.setText(obj.optString("detail"));customerGstin.setText(obj.optString("extra"))
                }.show()
            }
        }})
        section("3 • Document details")
        val number=field("Document number",initial("number",when(kind){"Bill"->"BILL";"Receipt"->"REC";"Quotation"->"QUO";"Estimate"->"EST";"Delivery Challan"->"DC";else->"INV"}+"-"+SimpleDateFormat("yyMMdd-HHmmss",Locale.US).format(Date())),24)
        val date=field("Date (DD/MM/YYYY)",initial("date",SimpleDateFormat("dd/MM/yyyy",Locale.US).format(Date())),10)
        val due=field("Due date (optional)",initial("due"),10)
        fun calendar(field:EditText){val cal=java.util.Calendar.getInstance();runCatching{SimpleDateFormat("dd/MM/yyyy",Locale.US).apply{isLenient=false}.parse(field.text.toString())?.let{cal.time=it}};android.app.DatePickerDialog(this,{_,year,month,day->field.setText(String.format(Locale.US,"%02d/%02d/%04d",day,month+1,year))},cal.get(java.util.Calendar.YEAR),cal.get(java.util.Calendar.MONTH),cal.get(java.util.Calendar.DAY_OF_MONTH)).show()}
        val dateActions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("Pick date" to date,"Pick due date" to due).forEach{(title,target)->dateActions.addView(nativeButton(this, ButtonRole.Secondary).apply{text=title;isAllCaps=false;setOnClickListener{calendar(target)}},LinearLayout.LayoutParams(0,-2,1f))};panel.addView(dateActions)
        val taxMode=android.widget.CheckBox(this).apply{text="Interstate supply (IGST instead of CGST + SGST)";isChecked=initialDraft?.optBoolean("interstate")?:false;panel.addView(this)}
        section("4 • Items / products")
        val rows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};panel.addView(rows)
        data class Entry(val box:LinearLayout,val name:EditText,val hsn:EditText,val qty:EditText,val price:EditText,val tax:EditText)
        val entries=mutableListOf<Entry>()
        var updateTotals:()->Unit={}
        fun watch(input:EditText){input.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){updateTotals()};override fun afterTextChanged(s:android.text.Editable?){}})}
        fun addEntry(saved:org.json.JSONObject?=null){
            if(entries.size>=100){Toast.makeText(this,"Maximum 100 items",Toast.LENGTH_SHORT).show();return}
            val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8));background=rounded(0xFFF3F7FF.toInt(),12,0xFFDCE6F5.toInt())}
            fun itemField(hint:String,initial:String,max:Int,numeric:Boolean=false)=EditText(this).apply{
                this.hint=hint;setText(initial);setSingleLine(true);filters=arrayOf(android.text.InputFilter.LengthFilter(max))
                if(numeric)inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                box.addView(label(hint,11f,0xFF52627A.toInt()));box.addView(this)
            }
            val n=itemField("Item / service description",saved?.optString("name").orEmpty(),160)
            val h=itemField("HSN / SAC",saved?.optString("hsn").orEmpty(),12)
            val q=itemField("Quantity",saved?.optString("qty","1")?:"1",10,true)
            val p=itemField("Unit rate (INR)",saved?.optString("rate").orEmpty(),13,true)
            val t=itemField("GST rate (%)",saved?.optString("tax","0")?:"0",6,true)
            box.addView(nativeButton(this, ButtonRole.Secondary).apply{text="Choose GST rate";isAllCaps=false;setOnClickListener{val rates=arrayOf("0","5","12","18","28");appDialog().setTitle("Common GST rates").setPremiumItems(rates.map{it+"%"}.toTypedArray()){_,i->t.setText(rates[i])}.setNegativeButton("Custom / Close",null).show()}})
            val entry=Entry(box,n,h,q,p,t);entries.add(entry)
            val lineTotal=label("Item subtotal: INR 0.00",12f,0xFF2563EB.toInt(),true)
            box.addView(lineTotal)
            fun updateLine(){val qty=q.text.toString().toBigDecimalOrNull();val rate=p.text.toString().toBigDecimalOrNull();lineTotal.text=if(qty!=null&&rate!=null) "Item subtotal: INR "+InvoiceDocument.format(qty*rate) else "Enter quantity and rate"}
            listOf(q,p,t).forEach{input->watch(input);input.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){updateLine()};override fun afterTextChanged(s:android.text.Editable?){}})}
            updateLine()
            box.addView(nativeButton(this, ButtonRole.Premium).apply{text="Use saved product • PRO";isAllCaps=false;setOnClickListener{
                if(requirePremiumTool("Product manager")){
                    val records=invoiceRecords("Products")
                    if(records.length()==0){Toast.makeText(this@MainActivity,"Save a product first",Toast.LENGTH_LONG).show();return@setOnClickListener}
                    appDialog().setTitle("Select product").setPremiumItems(Array(records.length()){records.getJSONObject(it).optString("name")}){_,i->
                        val obj=records.getJSONObject(i);n.setText(obj.optString("name"));h.setText(obj.optString("detail"));p.setText(obj.optString("extra"))
                    }.show()
                }
            }})
            box.addView(nativeButton(this, ButtonRole.Danger).apply{text="Remove item";isAllCaps=false;setOnClickListener{if(entries.size>1){entries.remove(entry);rows.removeView(box);updateTotals()}}})
            rows.addView(box);rows.addView(gap(4));updateTotals()
        }
        val savedItems=initialDraft?.optJSONArray("items")
        if(savedItems==null||savedItems.length()==0)addEntry() else for(i in 0 until minOf(savedItems.length(),100))addEntry(savedItems.optJSONObject(i))
        panel.addView(nativeButton(this, ButtonRole.Primary).apply{text="+ Add item";isAllCaps=false;setOnClickListener{addEntry()}})
        val discount=field("Total discount (INR)",initial("discount","0"),13,true)
        panel.addView(nativeButton(this, ButtonRole.Secondary).apply{text="Apply percentage discount";isAllCaps=false;setOnClickListener{
            val input=EditText(this@MainActivity).apply{hint="Discount % (0–100)";inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL;filters=arrayOf(android.text.InputFilter.LengthFilter(6))}
            val holder=LinearLayout(this@MainActivity).apply{setPadding(dp(16),dp(8),dp(16),dp(8));addView(input,LinearLayout.LayoutParams(-1,-2))}
            val picker=appDialog().setTitle("Percentage discount").setView(holder).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();picker.show()
            picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{try{val percentage=input.text.toString().toBigDecimalOrNull()?:error("Enter percentage");val subtotal=entries.fold(java.math.BigDecimal.ZERO){sum,e->val q=e.qty.text.toString().toBigDecimalOrNull()?:error("Enter quantity");val rate=e.price.text.toString().toBigDecimalOrNull()?:error("Enter rate");require(q.signum()>0&&rate.signum()>=0){"Check item values"};sum+InvoiceDocument.money(q*rate)};discount.setText(InvoiceDocument.format(InvoiceDocument.percentageDiscount(subtotal,percentage)));picker.dismiss()}catch(e:Exception){input.error=e.message?:"Invalid percentage"}}
        }})
        val notes=field("Notes / payment terms",initial("notes"),300)
        val tracked=InvoiceDocument.paymentTracked(kind)
        val paid=if(tracked)field("Amount received (INR)",initial("paid","0"),16,true)else EditText(this).apply{setText("0")}
        val method=if(tracked)field("Payment method (Cash / UPI / Bank / Card)",initial("method"),60)else EditText(this)
        val reference=if(tracked)field("Payment reference (optional)",initial("reference"),80)else EditText(this)
        if(tracked)panel.addView(nativeButton(this, ButtonRole.Secondary).apply{text="Choose payment method";isAllCaps=false;setOnClickListener{val methods=arrayOf("Cash","UPI","Bank transfer","Card","Cheque","Other");appDialog().setTitle("Payment method").setPremiumItems(methods){_,i->method.setText(methods[i])}.setNegativeButton("Cancel",null).show()}})
        val summary=label("",14f,0xFF172B4D.toInt(),true).apply{setPadding(dp(12),dp(14),dp(12),dp(14));background=rounded(0xFFEAF2FF.toInt(),14)}
        panel.addView(gap(8));panel.addView(summary)
        updateTotals={
            summary.text=runCatching{
                val items=entries.map{e->
                    val q=e.qty.text.toString().toBigDecimalOrNull()?:error("Enter quantity")
                    val p=e.price.text.toString().toBigDecimalOrNull()?:error("Enter item rate")
                    val t=e.tax.text.toString().toBigDecimalOrNull()?:error("Enter GST rate")
                    require(q.signum()>0&&p.signum()>=0&&t.signum()>=0&&t<=java.math.BigDecimal("100")){"Check item values"}
                    InvoiceDocument.Item("","",q,p,t)
                }
                val cut=discount.text.toString().toBigDecimalOrNull()?:error("Enter discount")
                val t=InvoiceDocument.totals(items,cut,taxMode.isChecked)
                val received=paid.text.toString().toBigDecimalOrNull()?:error("Enter received amount")
                val payment=if(tracked)"\nReceived: INR "+InvoiceDocument.format(received)+"\nBalance: INR "+InvoiceDocument.format(t.total-received)+" • "+InvoiceDocument.paymentStatus(t.total,received) else ""
                "Subtotal: INR "+InvoiceDocument.format(t.base)+"\nDiscount: INR "+InvoiceDocument.format(t.discount)+"\nCGST: "+InvoiceDocument.format(t.cgst)+"  SGST: "+InvoiceDocument.format(t.sgst)+"  IGST: "+InvoiceDocument.format(t.igst)+"\nTOTAL: INR "+InvoiceDocument.format(t.total)+payment
            }.getOrElse{it.message?:"Enter valid item values"}
        }
        watch(discount);watch(paid);taxMode.setOnCheckedChangeListener{_,_->updateTotals()};updateTotals()
        section("5 • PDF template")
        val template=android.widget.Spinner(this).apply{
            adapter=android.widget.ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Modern Blue • Free","Minimal Clean • Free","Corporate Green • PRO","Creative Purple • PRO","Classic Red • PRO","Gold Premium • PRO"))
        }
        template.setSelection((initialDraft?.optInt("template",0)?:0).coerceIn(0,5))
        panel.addView(template)
        panel.addView(nativeButton(this, ButtonRole.Premium).apply{text="Add handwritten signature • PRO";isAllCaps=false;setOnClickListener{
            if(requirePremiumTool("Invoice signature")){
                val pad=SignatureCanvas(this@MainActivity).apply{setBackgroundColor(android.graphics.Color.WHITE)}
                val container=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;addView(pad,LinearLayout.LayoutParams(-1,dp(180)));addView(nativeButton(this@MainActivity, ButtonRole.Secondary).apply{text="Clear";setOnClickListener{pad.clear()}})}
                appDialog().setTitle("Draw signature").setView(container).setNegativeButton("Cancel",null).setPositiveButton("Use signature"){_,_->
                    if(pad.hasSignature()){val bitmap=pad.toBitmap();try{signature=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();text="Signature added • Change"}finally{bitmap.recycle()}}
                }.show()
            }
        }})
        panel.addView(label("Enter your own verified GSTIN, tax rates and invoice numbering. Preview before issuing. This is a PDF document maker, not an accounting or government e-invoicing service.",11f,0xFF64748B.toInt()))
        val dialog=appDialog().setTitle("Create $kind").setView(toolDialogScroll(panel,0.72f)).setNegativeButton("Cancel",null).setNeutralButton("Save draft",null).setPositiveButton("Preview",null).create()
        dialog.show()
        dialog.setOnDismissListener{invoiceLogoCallback=null}
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener{
            if(owner!=signedInUserId()){dialog.dismiss();return@setOnClickListener}
            try{
                fun raw(e:EditText)=e.text.toString()
                val items=org.json.JSONArray()
                entries.forEach{e->items.put(org.json.JSONObject().put("name",raw(e.name)).put("hsn",raw(e.hsn)).put("qty",raw(e.qty)).put("rate",raw(e.price)).put("tax",raw(e.tax)))}
                val obj=org.json.JSONObject().put("id",draftId).put("kind",kind).put("business",raw(business)).put("address",raw(address)).put("contact",raw(contact)).put("gstin",raw(gstin))
                    .put("customer",raw(customer)).put("customerAddress",raw(customerAddress)).put("customerGstin",raw(customerGstin)).put("supply",raw(supply))
                    .put("number",raw(number)).put("date",raw(date)).put("due",raw(due)).put("interstate",taxMode.isChecked).put("items",items)
                    .put("discount",raw(discount)).put("notes",raw(notes)).put("paid",raw(paid)).put("method",raw(method)).put("reference",raw(reference)).put("template",template.selectedItemPosition)
                saveInvoiceDraft(obj)
                Toast.makeText(this,"Draft saved on this device. Logo and signature must be added again.",Toast.LENGTH_LONG).show()
            }catch(e:Exception){Toast.makeText(this,e.message?:"Could not save draft",Toast.LENGTH_LONG).show()}
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            try{
                require(owner==signedInUserId()){"Account changed; reopen the invoice editor"}
                fun value(e:EditText)=e.text.toString().trim()
                fun decimal(e:EditText,max:String,scale:Int):java.math.BigDecimal{
                    val n=value(e).toBigDecimalOrNull()?:error("Enter valid "+e.hint)
                    require(n>=java.math.BigDecimal.ZERO&&n<=java.math.BigDecimal(max)&&n.scale()<=scale){"Invalid "+e.hint}
                    return n
                }
                require(value(business).isNotBlank()&&value(customer).isNotBlank()&&value(number).isNotBlank()){"Enter business, customer and document number"}
                val parser=SimpleDateFormat("dd/MM/yyyy",Locale.US).apply{isLenient=false}
                fun validDate(s:String):Boolean=runCatching{val p=parser.parse(s);p!=null&&parser.format(p)==s}.getOrDefault(false)
                require(validDate(value(date))&&(value(due).isBlank()||validDate(value(due)))){"Use valid DD/MM/YYYY dates"}
                require(value(due).isBlank()||!parser.parse(value(due))!!.before(parser.parse(value(date)))){"Due date cannot be before document date"}
                val items=entries.map{e->
                    require(value(e.name).isNotBlank()){"Enter each item description"}
                    val quantity=decimal(e.qty,"1000000",3);require(quantity.signum()>0){"Quantity must be positive"}
                    InvoiceDocument.Item(value(e.name),value(e.hsn),quantity,decimal(e.price,"100000000",2),decimal(e.tax,"100",2))
                }
                if(kind=="GST Invoice"){require(value(gstin).matches(Regex("[0-9A-Za-z]{15}"))&&value(supply).isNotBlank()){"Enter supplier GSTIN and place of supply"}}
                if(kind=="Receipt")require(value(method).isNotBlank()){"Enter payment method before issuing a receipt"}
                val pro=template.selectedItemPosition>=2||logo!=null||signature!=null
                if(pro&&!requirePremiumTool("Premium invoice design"))return@setOnClickListener
                val draft=InvoiceDocument.Draft(kind,value(number),value(date),value(due),value(business),value(address),value(contact),value(gstin).uppercase(Locale.ROOT),value(customer),value(customerAddress),value(customerGstin).uppercase(Locale.ROOT),value(supply),items,decimal(discount,"100000000000000",2),taxMode.isChecked,value(notes),template.selectedItemPosition,logo,signature,decimal(paid,"10000000000000000",2),value(method),value(reference))
                val totals=InvoiceDocument.totals(draft)
                require(totals.total<=java.math.BigDecimal("999999999999999.99")){"Invoice total exceeds supported amount"}
                if(tracked)InvoiceDocument.paymentStatus(totals.total,draft.paid)
                if(kind=="Receipt")require(draft.paid.signum()>0 && draft.paid.compareTo(totals.total)==0){"A receipt must record the full received amount. Use an invoice for an outstanding balance."}
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
                Thread{
                    try{
                        val data=InvoiceDocument.pdf(draft)
                        runOnUiThread{
                            if(!isFinishing&&!isDestroyed&&dialog.isShowing){
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                                showInvoicePreview(draft,data,owner,pro) { dialog.dismiss() }
                            }
                        }
                    }catch(e:Exception){runOnUiThread{if(dialog.isShowing){dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true;Toast.makeText(this,e.message?:"PDF creation failed",Toast.LENGTH_LONG).show()}}}
                }.start()
            }catch(e:Exception){Toast.makeText(this,e.message?:"Check invoice details",Toast.LENGTH_LONG).show()}
        }
    }

    private fun showInvoicePreview(draft:InvoiceDocument.Draft,data:ByteArray,owner:String,pro:Boolean,onSaved:()->Unit){
        if(owner!=signedInUserId())return
        val temp=java.io.File.createTempFile("invoice_preview_",".pdf",cacheDir)
        try{temp.writeBytes(data)}catch(e:Exception){temp.delete();Toast.makeText(this,"Could not preview invoice",Toast.LENGTH_LONG).show();return}
        val renderer=try{PdfRenderer(android.os.ParcelFileDescriptor.open(temp,android.os.ParcelFileDescriptor.MODE_READ_ONLY))}
        catch(e:Exception){temp.delete();Toast.makeText(this,"Could not open preview",Toast.LENGTH_LONG).show();return}
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8))}
        val amounts=InvoiceDocument.totals(draft)
        val hero=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12));background=gradient(0xFF073B91.toInt(),0xFF06245F.toInt(),16)}
        hero.addView(label(draft.kind+" • "+draft.number,12f,0xFFDCE8FF.toInt(),true));hero.addView(label("INR "+InvoiceDocument.format(amounts.total),22f,0xFFFFFFFF.toInt(),true))
        if(InvoiceDocument.paymentTracked(draft.kind))hero.addView(label(InvoiceDocument.paymentStatus(amounts.total,draft.paid)+" • Balance INR "+InvoiceDocument.format(amounts.total-draft.paid),12f,0xFFDCE8FF.toInt()))
        panel.addView(hero);panel.addView(gap(8))
        val image=ImageView(this).apply{adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Invoice PDF page preview";background=rounded(0xFFF3F7FF.toInt(),10,0xFFDCE6F5.toInt())}
        val info=label("",12f,0xFF263957.toInt(),true)
        panel.addView(info);panel.addView(image,LinearLayout.LayoutParams(-1,dp(360)))
        val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        var index=0;var bitmap:Bitmap?=null
        fun render(){
            renderer.openPage(index).use{page->
                val next=Bitmap.createBitmap(page.width*2,page.height*2,Bitmap.Config.ARGB_8888)
                next.eraseColor(android.graphics.Color.WHITE);page.render(next,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                image.setImageBitmap(next);bitmap?.recycle();bitmap=next
            }
            info.text="Page ${index+1} / ${renderer.pageCount} • Total INR "+InvoiceDocument.format(InvoiceDocument.totals(draft).total)
        }
        nav.addView(nativeButton(this, ButtonRole.Secondary).apply{text="‹ Previous";setOnClickListener{if(index>0){index--;render()}}},LinearLayout.LayoutParams(0,-2,1f))
        nav.addView(nativeButton(this, ButtonRole.Secondary).apply{text="Next ›";setOnClickListener{if(index<renderer.pageCount-1){index++;render()}}},LinearLayout.LayoutParams(0,-2,1f))
        panel.addView(nav)
        val dialog=appDialog().setTitle("Invoice PDF preview").setView(toolDialogScroll(panel,0.72f)).setNegativeButton("Edit",null).setPositiveButton("Save PDF",null).create()
        dialog.setOnDismissListener{image.setImageDrawable(null);bitmap?.recycle();renderer.close();temp.delete()}
        dialog.show();render()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            if(owner!=signedInUserId()){dialog.dismiss();return@setOnClickListener}
            if(pro&&!requirePremiumTool("Premium invoice export"))return@setOnClickListener
            val prefix="Billing_"+draft.kind.replace(" ","_")+"_"+draft.number.replace(Regex("[^A-Za-z0-9_-]"),"_").take(24)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            val stamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())
            val saved=saveBytesPrivately(data,prefix+"_"+stamp+".pdf")
            if(saved!=null){
                val pageCount=renderer.pageCount
                val reportSaved=runCatching{recordBillingExport(draft,saved)}.isSuccess
                dialog.dismiss();onSaved()
                showInvoiceReady(saved,draft.kind,draft.number,InvoiceDocument.format(amounts.total),
                    if(InvoiceDocument.paymentTracked(draft.kind)) InvoiceDocument.paymentStatus(amounts.total,draft.paid) else "",
                    pageCount,owner)
                if(!reportSaved)Toast.makeText(this,"PDF saved, but billing report record could not be updated.",Toast.LENGTH_LONG).show()
            } else {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                Toast.makeText(this,"Could not save PDF",Toast.LENGTH_LONG).show()
            }
        }
    }

    private var invoiceReadyDialog: AlertDialog? = null
    private var invoiceReadyStatus: TextView? = null
    private var invoiceReadyState: Bundle? = null

    private fun showInvoiceReady(uri:Uri,kind:String,number:String,total:String,payment:String,pages:Int,owner:String) {
        val file=privateDocumentFile(uri) ?: return
        if(owner!=signedInUserId() || isFinishing || isDestroyed)return
        invoiceReadyDialog?.dismiss()
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(12))}
        val hero=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16))
            background=gradient(0xFF073B91.toInt(),0xFF06245F.toInt(),18)
        }
        hero.addView(label("✓  PDF SAVED SUCCESSFULLY",11f,0xFFB7F2CE.toInt(),true))
        hero.addView(gap(6));hero.addView(label(kind+" ready",23f,0xFFFFFFFF.toInt(),true))
        hero.addView(label("No. "+number,12f,0xFFDCE8FF.toInt()))
        hero.addView(gap(8));hero.addView(label("INR "+total,24f,0xFFFFFFFF.toInt(),true))
        if(payment.isNotBlank())hero.addView(label(payment,12f,0xFFB7F2CE.toInt(),true))
        panel.addView(hero);panel.addView(gap(12))
        val fileCard=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12))
            background=rounded(0xFFF4F8FF.toInt(),14,0xFFDCE7F5.toInt())
        }
        fileCard.addView(label("PDF",15f,0xFF2563EB.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0xFFE4EEFF.toInt(),12)},LinearLayout.LayoutParams(dp(48),dp(52)))
        val details=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0)}
        details.addView(label(file.name,12f,0xFF172B4D.toInt(),true))
        details.addView(label("A4 • "+pages+" page(s) • "+formatFileSize(file.length()),12f,0xFF52627A.toInt()))
        details.addView(label("Original PDF • Print ready",11f,0xFF128268.toInt(),true))
        fileCard.addView(details,LinearLayout.LayoutParams(0,-2,1f));panel.addView(fileCard);panel.addView(gap(12))
        val info=label("Saved inside DocNexa. Download a copy or share the original PDF.",12f,0xFF52627A.toInt()).apply{accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        val item=ExportResultItem(uri.toString(),file.name,"application/pdf")
        fun available():Boolean {
            if(owner==signedInUserId() && privateDocumentFile(uri)!=null)return true
            Toast.makeText(this,"File unavailable or account changed.",Toast.LENGTH_LONG).show()
            return false
        }
        fun action(symbol:String,title:String,description:String,primary:Boolean=false,drive:Boolean=false,click:()->Unit){
            val row=LinearLayout(this).apply{
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(62)
                setPadding(dp(12),dp(10),dp(12),dp(10));isClickable=true;isFocusable=true
                background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if(primary)0x33FFFFFF else 0x223478F6),
                    if(primary)gradient(0xFF3478F6.toInt(),0xFF1556BE.toInt(),14) else rounded(0xFFF4F8FF.toInt(),14,0xFFDCE7F5.toInt()),null)
                setOnClickListener{if(available())click()}
            }
            val tile=FrameLayout(this).apply{
                background=rounded(if(primary)0x33FFFFFF else 0xFFE4EEFF.toInt(),10)
                if(drive)addView(driveBrandIcon(),FrameLayout.LayoutParams(dp(30),dp(30),Gravity.CENTER))
                else addView(label(symbol,19f,if(primary)0xFFFFFFFF.toInt() else 0xFF2563EB.toInt(),true).apply{gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},FrameLayout.LayoutParams(-1,-1))
            }
            row.addView(tile,LinearLayout.LayoutParams(dp(42),dp(42)))
            val copy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,dp(6),0)}
            copy.addView(label(title,15f,if(primary)0xFFFFFFFF.toInt() else 0xFF172B4D.toInt(),true))
            copy.addView(label(description,11f,if(primary)0xFFE1EDFF.toInt() else 0xFF64748B.toInt()))
            row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(label("›",22f,if(primary)0xFFFFFFFF.toInt() else 0xFF3478F6.toInt()))
            panel.addView(row);panel.addView(gap(7))
        }
        action("↓","Download PDF","Save an original-quality copy to your device",true){saveResultAs(item)}
        action("↗","Share PDF","Send using WhatsApp, email or another app"){sharePdf(uri)}
        action("","Google Drive","Upload or choose a Drive folder",drive=true){confirmDriveSave(item)}
        action("▤","Print PDF","A4 • Original PDF, no screenshot conversion"){printPdfDocument(uri,true)}
        action("▣","Open PDF","View the complete saved document"){openPdf(uri)}
        panel.addView(gap(3));panel.addView(info)
        val dialog=appDialog().setTitle("Invoice complete").setView(toolDialogScroll(panel,0.72f))
            .setNegativeButton("Done",null).create()
        invoiceReadyDialog=dialog;invoiceReadyStatus=info
        invoiceReadyState=Bundle().apply{putString("uri",uri.toString());putString("kind",kind);putString("number",number);putString("total",total);putString("payment",payment);putInt("pages",pages);putString("owner",owner)}
        dialog.setOnDismissListener{if(invoiceReadyDialog===dialog){invoiceReadyDialog=null;invoiceReadyStatus=null;invoiceReadyState=null}}
        dialog.show()
    }

    private fun showCreatePdfHub() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        popupHeader(panel,"DOCNEXA • PDF STUDIO","Create a PDF","Choose the source and document style.")
        val dialog=appDialog().setTitle("Create PDF").setView(toolDialogScroll(panel,0.72f)).setNegativeButton("Close",null).create()
        fun option(title:String,subtitle:String,icon:String,action:()->Unit){
            addPopupOption(panel,icon,title,subtitle){dialog.dismiss();action()}
        }
        option("Images to PDF", "Select, preview and arrange photos", "gallery") {
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
        dialog.show()
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
        showPremiumDialog(appDialog().setTitle("Blank PDF • Settings").setView(panel)
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
        showPremiumDialog(appDialog().setTitle("Advanced PDF Design").setView(ScrollView(this).apply { addView(panel) })
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

    private fun addPdfQuickGrid(parent: LinearLayout, tools: List<PdfTool>) {
        tools.chunked(3).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { tool ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(5), dp(12), dp(5), dp(12))
                    background = rounded(0xFFFFFFFF.toInt(), 14, 0xFFDCE6F5.toInt())
                    isClickable = true
                    isFocusable = true
                    contentDescription = tool.title
                    setOnClickListener { openPdfCatalogTool(tool) }
                }
                item.addView(TextView(this).apply {
                    text = tool.symbol
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(tool.color)
                    background = rounded(0xFFEAF2FF.toInt(), 12)
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                item.addView(gap(7))
                item.addView(label(pdfCatalogTitle(tool.title), 12f, 0xFF263957.toInt(), true).apply {
                    gravity = Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    minLines = 2
                })
                row.addView(item, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(dp(3), dp(5), dp(3), dp(5))
                })
            }
            repeat(3 - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun pdfToolDescription(title: String): String = when (title) {
        "Page Collage" -> "Arrange 2 or 4 PDF pages per A4 sheet"
        "Reorder Pages" -> "Custom page order or reverse all pages"
        "Delete Pages" -> "Remove selected pages from a copy"
        "Extract Pages" -> "Save selected pages in your chosen order"
        "Duplicate Pages" -> "Insert a copy after selected pages"
        "Page Numbers" -> "Number selected pages at top or bottom"
        "Compress PDF" -> "Choose size, quality and custom compression"
        "PDF to Word" -> "Export searchable text to a Word document"
        "OCR Image" -> "Recognize and copy text from an image"
        "Watermark" -> "Add a text watermark to your PDF"
        "PDF Editor" -> "Text, signature, shapes and page tools"
        "Open PDF" -> "Open and read a PDF document"
        "Page Details", "PDF Details" -> "View pages and metadata"
        "Signature" -> "Draw, save and place your signature"
        "PDF to Image" -> "Export PDF pages as images"
        "Image to PDF" -> "Turn gallery images into a PDF"
        "Share PDF" -> "Share your most recent PDF"
        "Add/Merge PDF Files" -> "Combine multiple PDFs"
        "Add Page from Gallery" -> "Create PDF pages from gallery photos"
        "Split" -> "Extract custom page ranges"
        "Rotate all pages" -> "Rotate selected pages"
        "Add Password" -> "Protect with a password"
        "Remove Password" -> "Unlock an accessible PDF"
        "PDF To Text" -> "Extract and copy text"
        "PDF To ZIP" -> "Package PDF for sharing"
        "Text To PDF" -> "Create a PDF from notes"
        "Create PDF" -> "Images • Text • Blank • Design"
        else -> "Open PDF utility"
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
                    minimumHeight = dp(148)
                    isClickable = true
                    isFocusable = true
                    contentDescription = tool.title + ". " + pdfToolDescription(tool.title)
                    setOnClickListener { openPdfCatalogTool(tool) }
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
                val caption = label(pdfCatalogTitle(tool.title), 13f, titleColor, true)
                caption.gravity = Gravity.CENTER
                caption.textAlignment = View.TEXT_ALIGNMENT_CENTER
                item.addView(caption)
                item.addView(gap(4))
                val sub = label(pdfToolDescription(tool.title), 11f, subtitleColor)
                sub.gravity = Gravity.CENTER
                sub.textAlignment = View.TEXT_ALIGNMENT_CENTER
                item.addView(sub)
                row.addView(item, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    leftMargin = dp(4); rightMargin = dp(4); bottomMargin = dp(9)
                })
            }
            if (rowTools.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            parent.addView(row, LinearLayout.LayoutParams(-1, -2))
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
        showPremiumDialog(appDialog().setTitle(title).setView(panel)
            .setNeutralButton(copyLabel) { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(title, body))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Close", null))
    }


    private fun showAiAssistant() {
        if (cloudToken().isBlank()) {
            appDialog().setTitle("Sign in required")
                .setMessage("Sign in to your DocNexa account before using online AI features. Your PDF will be sent securely to DocNexa AI only after you choose a document.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign in") { _, _ -> showCloudLoginDialog() }
                .show()
            return
        }
        if (!requireInternet("DocNexa AI")) return

        val tasks = listOf(
            arrayOf("summary", "PDF Summary", "Get key points and a clear overview", "▤"),
            arrayOf("ask", "Ask your PDF", "Ask questions and find answers", "?"),
            arrayOf("translate", "Translate PDF", "Convert document text to your language", "文"),
            arrayOf("ocr", "Smart OCR Cleanup", "Clean and organize extracted text", "✧"),
            arrayOf("assistant", "AI Document Assistant", "Get help understanding your document", "✦")
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = gradient(0xFF102E66.toInt(), 0xFF087F9B.toInt(), 20)
        }
        val heroIcon = TextView(this).apply {
            text = "✦"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0x33FFFFFF, 14)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }
        hero.addView(heroIcon)
        val heroText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
        }
        heroText.addView(label("DocNexa AI", 20f, 0xFFFFFFFF.toInt(), true))
        heroText.addView(gap(3))
        heroText.addView(label("Your intelligent PDF workspace", 12f, 0xFFE0F5FF.toInt()))
        hero.addView(heroText, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(hero)
        content.addView(gap(14))
        content.addView(label("CHOOSE AN AI TOOL", 10f, 0xFF718096.toInt(), true))
        content.addView(gap(9))

        fun openTask(task: String, title: String) {
            val hint = when (task) {
                "ask" -> "Ask a question about this PDF"
                "translate" -> "Enter target language, e.g. Gujarati"
                "ocr" -> "Optional instruction for cleaning extracted text"
                "assistant" -> "What would you like help with?"
                else -> "Optional summary instructions"
            }
            showAiPromptDialog(task, title, hint)
        }
        fun makeCard(item: Array<String>): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(13), dp(10), dp(13))
                background = rounded(0xFFFFFFFF.toInt(), 16, 0xFFE1E9F4.toInt())
                elevation = dp(1).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener { openTask(item[0], item[1]) }
            }
            val symbol = TextView(this).apply {
                text = item[3]
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(0xFF087F9B.toInt())
                background = rounded(0xFFE8F6FA.toInt(), 12)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            }
            card.addView(symbol)
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            textCol.addView(label(item[1], 13f, 0xFF1C3150.toInt(), true))
            textCol.addView(gap(3))
            textCol.addView(label(item[2], 10f, 0xFF718096.toInt()))
            card.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(label("›", 23f, 0xFF7890AB.toInt(), true))
            return card
        }
        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        firstRow.addView(makeCard(tasks[0]), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        firstRow.addView(makeCard(tasks[1]), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        content.addView(firstRow)
        content.addView(gap(10))
        val secondRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        secondRow.addView(makeCard(tasks[2]), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        secondRow.addView(makeCard(tasks[3]), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        content.addView(secondRow)
        content.addView(gap(10))
        content.addView(makeCard(tasks[4]))
        content.addView(gap(12))
        content.addView(label("🔒  Your document is sent only when you choose a PDF.", 10f, 0xFF6B7C93.toInt()))

        appDialog()
            .setView(content)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAiPromptDialog(task: String, title: String, hint: String) {
        val field = EditText(this).apply {
            this.hint = hint
            minLines = if (task == "ask" || task == "assistant") 2 else 1
            maxLines = 4
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        appDialog().setTitle(title)
            .setMessage("Choose a PDF on the next screen. Text from that PDF will be sent to the AI service for processing.")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose PDF") { _, _ ->
                pendingAiTask = task
                pendingAiPrompt = field.text.toString().trim().ifBlank {
                    when (task) {
                        "summary" -> "Summarize this document with headings and key points."
                        "ask" -> ""
                        "translate" -> "Translate the document into Gujarati."
                        "ocr" -> "Correct OCR spacing and formatting while preserving the original meaning."
                        else -> "Help me understand this document and identify its important points."
                    }
                }
                if ((task == "ask" || task == "translate") && pendingAiPrompt.isBlank()) {
                    Toast.makeText(this, if (task == "ask") "Please enter your question." else "Please enter the target language.", Toast.LENGTH_LONG).show()
                } else {
                    aiPdfPicker.launch(arrayOf("application/pdf"))
                }
            }.show()
    }

    private fun runAiForPdf(uri: Uri, task: String, prompt: String) {
        if (cloudToken().isBlank()) {
            Toast.makeText(this, "Your DocNexa session has expired. Please sign in again.", Toast.LENGTH_LONG).show()
            showCloudLoginDialog()
            return
        }
        val progress = appDialog().setTitle("DocNexa AI")
            .setMessage("Reading PDF and preparing your request…")
            .setCancelable(false).create()
        progress.show()
        Thread {
            try {
                val extracted = contentResolver.openInputStream(uri)?.use { stream ->
                    PDDocument.load(stream).use { document ->
                        PDFTextStripper().apply { sortByPosition = true }.getText(document)
                    }
                } ?: error("Could not open the selected PDF.")
                if (extracted.isBlank()) {
                    throw IllegalStateException("No selectable text was found. For a scanned PDF, run Image To Text (OCR) first, then use the AI assistant with the extracted text.")
                }
                if (extracted.length > 30000) {
                    throw IllegalStateException("This PDF contains too much text for the current AI request. Please split it into smaller PDFs and try again.")
                }
                val answer = SupabaseCloud.generateAi(cloudToken(), task, prompt, extracted)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        progress.dismiss()
                        val title = when (task) {
                            "summary" -> "DocNexa AI • PDF Summary"
                            "ask" -> "DocNexa AI • Ask PDF"
                            "translate" -> "DocNexa AI • Translation"
                            "ocr" -> "DocNexa AI • Smart Text Cleanup"
                            else -> "DocNexa AI • Assistant"
                        }
                        showLongTextReport(title, answer, "Copy Answer")
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        progress.dismiss()
                        appDialog().setTitle("DocNexa AI")
                            .setMessage(error.localizedMessage ?: "AI request failed. Please try again.")
                            .setPositiveButton("OK", null).show()
                    }
                }
            }
        }.start()
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
                        showPremiumDialog(appDialog().setTitle("PDF To Text")
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
                    range.setText(if (count == 1) "1" else "1-$count")
                    addVisualPageSelector(panel, uri, count, range)
                    panel.addView(label("Select pages above or enter a range. Rotation: 90° clockwise.", 11f, 0xFF718096.toInt()))
                    showPremiumDialog(appDialog().setTitle("DocNexa • Rotate Pages")
                        .setView(toolDialogScroll(panel)).setNegativeButton("Cancel", null)
                        .setPositiveButton("Rotate & Save") { _, _ ->
                            val pages = parsePageRanges(range.text.toString(), count)
                            if (pages.isEmpty()) Toast.makeText(this, "Please enter a valid page range", Toast.LENGTH_LONG).show()
                            else rotateSelectedPdfPages(uri, pages)
                        }).also { bindPageSelectionAction(it, range, count) }
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
        showPremiumDialog(appDialog().setTitle("Text To PDF • Advanced")
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
                        Toast.makeText(this, "PDF created with $pageNo page(s) and saved inside DocNexa", Toast.LENGTH_LONG).show()
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
        val fontButton = nativeButton(this, ButtonRole.Secondary).apply { text = "Font: $selectedSignatureFont" }
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
        val dialog = appDialog()
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
            addView(nativeButton(this@MainActivity, ButtonRole.Secondary).apply { text = "Undo"; setOnClickListener { canvas.undo() } })
            addView(nativeButton(this@MainActivity, ButtonRole.Secondary).apply { text = "Clear"; setOnClickListener { canvas.clear(); typed.setText("") } })
            addView(nativeButton(this@MainActivity, ButtonRole.Primary).apply {
                text = "Save PNG"
                setOnClickListener {
                    val bitmap = buildSignatureBitmap(canvas, typed.text.toString(), selectedSignatureFont)
                    if (bitmap == null) Toast.makeText(this@MainActivity, "Please draw or type a signature first.", Toast.LENGTH_LONG).show()
                    else {
                        saveSignaturePng(bitmap)
                        bitmap.recycle()
                    }
                }
            })
        }
        layout.addView(toolbar)
        addNativeAdCard(layout)
        dialog.show()
    }

    private fun buildSignatureBitmap(canvas: SignatureCanvas, text: String, fontName: String): Bitmap? {
        val value = text.trim()
        return if (value.isNotEmpty()) renderTypedSignature(value, fontName)
        else if (canvas.hasSignature()) canvas.toBitmap()
        else null
    }

    private fun saveSignaturePng(signature: Bitmap) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "DocNexa_Signature_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DocNexa")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create PNG file")
            contentResolver.openOutputStream(uri)?.use { output ->
                if (!signature.compress(Bitmap.CompressFormat.PNG, 100, output)) error("PNG export failed")
            } ?: error("Could not write PNG file")
            Toast.makeText(this, "Transparent signature PNG saved to Pictures/DocNexa", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.localizedMessage ?: "Could not save signature PNG", Toast.LENGTH_LONG).show()
        }
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
            Toast.makeText(this, if (saved != null) "Signature-only PDF saved in DocNexa app storage" else "Could not save signature PDF", Toast.LENGTH_LONG).show()
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
        val pageNumber = EditText(this).apply {
            hint = "Page number (1 to total pages)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
        }
        val widthControl = SeekBar(this).apply { max = 70; progress = 35 }
        val xControl = SeekBar(this).apply { max = 100; progress = 78 }
        val yControl = SeekBar(this).apply { max = 100; progress = 8 }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
            addView(label("Choose where your signature should appear", 13f, 0xFF263957.toInt(), true))
            addView(gap(8))
            addView(label("PDF page number", 12f, 0xFF64748B.toInt(), true))
            addView(pageNumber)
            addView(gap(8))
            addView(label("Signature size", 12f, 0xFF64748B.toInt(), true))
            addView(widthControl)
            addView(label("Horizontal position • left to right", 12f, 0xFF64748B.toInt(), true))
            addView(xControl)
            addView(label("Vertical position • top to bottom", 12f, 0xFF64748B.toInt(), true))
            addView(yControl)
            addView(label("Tip: Position values are relative to the selected PDF page.", 11f, 0xFF718096.toInt()))
        }
        appDialog()
            .setTitle("DocNexa • Place Signature")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply Signature", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val selectedPage = pageNumber.text.toString().toIntOrNull()
                        if (selectedPage == null || selectedPage < 1) {
                            pageNumber.error = "Enter a valid page number"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        Thread {
                            try {
                                val input = contentResolver.openInputStream(uri) ?: error("Cannot open PDF")
                                val bytes = input.use { stream ->
                                    PDDocument.load(stream).use { doc ->
                                        require(selectedPage <= doc.numberOfPages) { "This PDF has only ${doc.numberOfPages} page(s)." }
                                        val page = doc.getPage(selectedPage - 1)
                                        val image = PDImageXObject.createFromByteArray(doc, png, "signature")
                                        val box = page.mediaBox
                                        val width = (box.width * (0.12f + widthControl.progress / 100f * 0.55f)).coerceAtMost(box.width * 0.8f)
                                        val height = width * image.height.toFloat() / image.width.toFloat()
                                        val maxX = (box.width - width).coerceAtLeast(0f)
                                        val maxY = (box.height - height).coerceAtLeast(0f)
                                        val x = maxX * (xControl.progress / 100f)
                                        val yFromTop = maxY * (yControl.progress / 100f)
                                        val y = maxY - yFromTop
                                        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                                            cs.drawImage(image, x, y, width, height)
                                        }
                                        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                                    }
                                }
                                val saved = savePdfBytes(bytes, "Signed")
                                runOnUiThread {
                                    lastPdf = saved
                                    status.text = if (saved != null) "Signature added to PDF" else "Could not save signed PDF"
                                    recent.text = "Latest: ${saved?.lastPathSegment ?: "Signed PDF"}"
                                    Toast.makeText(this, if (saved != null) "Signed PDF saved successfully" else "Could not save signed PDF", Toast.LENGTH_LONG).show()
                                    if (saved != null) buildScreen()
                                }
                            } catch (e: Exception) {
                                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Could not sign PDF", Toast.LENGTH_LONG).show() }
                            } finally { signaturePng = null }
                        }.start()
                    }
                }
                dialog.show()
            }
    }


    private fun inspectPageTool(uri: Uri, onReady: (Int) -> Unit) {
        val progress = appDialog().setTitle("Opening PDF")
            .setView(android.widget.ProgressBar(this).apply { setPadding(dp(24), dp(20), dp(24), dp(20)) })
            .setCancelable(false).show()
        Thread {
            try {
                val count = (contentResolver.openInputStream(uri) ?: error("Cannot open this PDF.")).use { input ->
                    PDDocument.load(input).use { doc ->
                        require(doc.numberOfPages > 0) { "This PDF has no pages." }
                        require(doc.currentAccessPermission.canAssembleDocument() && doc.currentAccessPermission.canModify()) {
                            "This PDF restricts editing. Use an unlocked copy you are allowed to edit."
                        }
                        doc.numberOfPages
                    }
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) { progress.dismiss(); onReady(count) }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        progress.dismiss()
                        appDialog().setTitle("Cannot edit this PDF")
                            .setMessage(e.localizedMessage ?: "Unlock password-protected PDFs before using this tool.")
                            .setPositiveButton("Close", null).show()
                    }
                }
            }
        }.start()
    }

    private fun showPageOrganizer(uri: Uri, operation: PdfPagePlan.Operation) {
        inspectPageTool(uri) { count ->
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            panel.addView(label("$count pages • Original stays unchanged", 12f, 0xFF2563EB.toInt(), true))
            panel.addView(gap(12))
            val instructions = when (operation) {
                PdfPagePlan.Operation.REORDER -> "Move pages with the arrows or type a custom order. Include every page once."
                PdfPagePlan.Operation.DELETE -> "Tap pages to remove or enter a range. At least one page must remain."
                PdfPagePlan.Operation.EXTRACT -> "Tap pages to keep. Their selection order becomes the output order."
                PdfPagePlan.Operation.DUPLICATE -> "Tap pages to duplicate. A copy is inserted immediately after each selected page."
            }
            panel.addView(label(instructions, 13f, 0xFF475569.toInt()))
            val field = EditText(this).apply {
                hint = "Page numbers / ranges"
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                contentDescription = "Page numbers and ranges"
                if (operation == PdfPagePlan.Operation.REORDER) setText(if (count == 1) "1" else "1-$count")
                else if (operation == PdfPagePlan.Operation.EXTRACT) setText("1")
            }
            panel.addView(field)
            addVisualPageSelector(panel, uri, count, field, reorder = operation == PdfPagePlan.Operation.REORDER)
            val preview = label("", 12f, 0xFF475569.toInt())
            panel.addView(preview)
            panel.addView(gap(12))
            panel.addView(label("The new PDF copies page artwork. Annotations, bookmarks and interactive form fields are not copied.", 11f, 0xFF718096.toInt()))
            val dialog = appDialog().setTitle(operation.title)
                .setView(toolDialogScroll(panel))
                .setPositiveButton("Save new PDF", null)
                .setNegativeButton("Cancel", null)
                .apply { if (operation == PdfPagePlan.Operation.REORDER) setNeutralButton("Reverse order", null) }
                .create()
            fun updatePreview() {
                val plan = runCatching { PdfPagePlan.build(operation, field.text.toString(), count) }.getOrNull()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = plan != null
                preview.text = if (plan == null) "Enter a valid selection to preview the output." else {
                    val sample = plan.take(12).joinToString(", ") { (it + 1).toString() }
                    "Output: ${plan.size} pages\nOrder: $sample${if (plan.size > 12) ", …" else ""}"
                }
            }
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { field.error = null; updatePreview() }
            })
            dialog.setOnShowListener {
                updatePreview()
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    field.setText(if (count == 1) "1" else "$count-1")
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    try {
                        val plan = PdfPagePlan.build(operation, field.text.toString(), count)
                        dialog.dismiss()
                        runPagePdfOperation(uri, operation.title, operation.outputPrefix) { source ->
                            require(source.numberOfPages == count) { "The PDF changed. Select it again before editing." }
                            PDDocument().use { output ->
                                plan.forEach { index ->
                                    val original = source.getPage(index)
                                    importPdfPageWithResources(output, original).apply {
                                        // Page annotations may point at the old page tree or AcroForm.
                                        setAnnotations(emptyList())
                                    }
                                }
                                ByteArrayOutputStream().also { output.save(it) }.toByteArray()
                            }
                        }
                    } catch (e: IllegalArgumentException) {
                        field.error = e.message
                        field.requestFocus()
                    }
                }
            }
            updatePreview()
            dialog.show()
        }
    }

    private fun showPageNumbersDialog(uri: Uri) {
        inspectPageTool(uri) { count ->
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            panel.addView(label("$count pages • Saves a new numbered copy", 12f, 0xFF2563EB.toInt(), true))
            panel.addView(gap(10))
            val range = EditText(this).apply {
                hint = "Pages to number"
                setSingleLine(true)
                contentDescription = "Pages to number"
            }
            val start = EditText(this).apply {
                hint = "First number"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText("1")
                contentDescription = "Starting page number"
            }
            val top = android.widget.CheckBox(this).apply { text = "Place numbers at the top (default: bottom)" }
            val prefix = android.widget.CheckBox(this).apply { text = "Use Page prefix, for example Page 1"; isChecked = true }
            range.setText(if (count == 1) "1" else "1-$count")
            panel.addView(range)
            addVisualPageSelector(panel, uri, count, range)
            panel.addView(label("Numbers follow document order, even if a range is typed in reverse.", 11f, 0xFF718096.toInt()))
            panel.addView(start)
            panel.addView(top)
            panel.addView(prefix)
            panel.addView(label("Numbers are centered on each visible page and respect page rotation.", 12f, 0xFF475569.toInt()))
            val dialog = appDialog().setTitle("Add Page Numbers")
                .setView(toolDialogScroll(panel))
                .setPositiveButton("Save numbered PDF", null).setNegativeButton("Cancel", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val pages = try {
                        PdfPagePlan.selection(range.text.toString(), count).sorted()
                    } catch (e: IllegalArgumentException) {
                        range.error = e.message
                        return@setOnClickListener
                    }
                    val first = start.text.toString().toIntOrNull()
                    if (first == null || first < 1 || first.toLong() + pages.size - 1 > Int.MAX_VALUE) {
                        start.error = "Enter a positive starting number within the supported range."
                        return@setOnClickListener
                    }
                    val atTop = top.isChecked
                    val withPrefix = prefix.isChecked
                    dialog.dismiss()
                    runPagePdfOperation(uri, "Adding page numbers", "Numbered") { doc ->
                        require(doc.numberOfPages == count) { "The PDF changed. Select it again before editing." }
                        pages.forEachIndexed { index, pageIndex ->
                            val page = doc.getPage(pageIndex)
                            val box = page.cropBox
                            val rotation = ((page.rotation % 360) + 360) % 360
                            val width = if (rotation == 90 || rotation == 270) box.height else box.width
                            val height = if (rotation == 90 || rotation == 270) box.width else box.height
                            require(width >= 40f && height >= 40f) { "This PDF contains a page too small for page numbers." }
                            val text = (if (withPrefix) "Page " else "") + (first + index)
                            val font = PDType1Font.HELVETICA
                            val fontSize = minOf(12f, (width - 16f) / (font.getStringWidth(text) / 1000f))
                            val x = (width - font.getStringWidth(text) / 1000f * fontSize) / 2f
                            val y = if (atTop) height - 22f else 14f
                            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                                stream.saveGraphicsState()
                                stream.transform(com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(box.lowerLeftX, box.lowerLeftY))
                                val transform = when (rotation) {
                                    90 -> com.tom_roush.pdfbox.util.Matrix(0f, 1f, -1f, 0f, box.width, 0f)
                                    180 -> com.tom_roush.pdfbox.util.Matrix(-1f, 0f, 0f, -1f, box.width, box.height)
                                    270 -> com.tom_roush.pdfbox.util.Matrix(0f, -1f, 1f, 0f, 0f, box.height)
                                    else -> com.tom_roush.pdfbox.util.Matrix()
                                }
                                stream.transform(transform)
                                stream.beginText()
                                stream.setFont(font, fontSize)
                                stream.setNonStrokingColor(40, 50, 65)
                                stream.newLineAtOffset(x, y)
                                stream.showText(text)
                                stream.endText()
                                stream.restoreGraphicsState()
                            }
                        }
                        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                    }
                }
            }
            dialog.show()
            bindPageSelectionAction(dialog, range, count, listOf(start)) {
                val first = start.text.toString().toIntOrNull()
                val selectedCount = runCatching { PdfPagePlan.selection(range.text.toString(), count).size }.getOrDefault(0)
                first != null && first > 0 && first.toLong() + selectedCount - 1 <= Int.MAX_VALUE
            }
        }
    }

    private fun runPagePdfOperation(uri: Uri, title: String, prefix: String, transform: (PDDocument) -> ByteArray) {
        val progress = appDialog().setTitle(title)
            .setMessage("Processing your PDF…")
            .setView(android.widget.ProgressBar(this).apply { setPadding(dp(24), dp(16), dp(24), dp(16)) })
            .setCancelable(false).show()
        Thread {
            try {
                val bytes = (contentResolver.openInputStream(uri) ?: error("Cannot open this PDF.")).use { input ->
                    PDDocument.load(input).use { doc ->
                        require(doc.currentAccessPermission.canModify() && doc.currentAccessPermission.canAssembleDocument()) {
                            "This PDF does not allow editing."
                        }
                        transform(doc)
                    }
                }
                // A unique name prevents two operations in the same second from overwriting each other.
                val name = "${prefix}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.pdf"
                val saved = saveBytesPrivately(bytes, name) ?: error("Could not save the new PDF. Check available storage.")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        progress.dismiss()
                        lastPdf = saved
                        showExportSuccess(saved, saved.lastPathSegment ?: name, "application/pdf")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        progress.dismiss()
                        appDialog().setTitle("PDF operation failed")
                            .setMessage(e.localizedMessage ?: "Try another PDF or a smaller page selection.")
                            .setPositiveButton("Close", null).show()
                    }
                }
            }
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
        val quickBar = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val quickActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(10))
        }
        fun quickEdit(title: String, symbol: String, action: () -> Unit) {
            val button = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(0xFF202B3A.toInt(), 12)
                setOnClickListener { action() }
            }
            button.addView(label(symbol, 22f, 0xFF80BFFF.toInt(), true).apply {
                gravity = Gravity.CENTER
            })
            button.addView(label(title, 11f, 0xFFFFFFFF.toInt()).apply {
                gravity = Gravity.CENTER
            })
            quickActions.addView(button, LinearLayout.LayoutParams(dp(94), dp(82)).apply {
                marginEnd = dp(6)
            })
        }
        quickEdit("Preview", "▣") { openPdf(uri) }
        quickEdit("Text", "T") { addTextToPdfDialog(uri) }
        quickEdit("Signature", "✍") { openSignatureEditor(uri) }
        quickEdit("Watermark", "▧") { addWatermarkDialog(uri) }
        quickEdit("Whitener", "□") { addWhitenerDialog(uri) }
        quickEdit("Shapes", "◇") { addShapeDialog(uri) }
        quickEdit("Rotate", "↻") { rotatePdfPages(uri) }
        quickEdit("Pages", "⇅") { showPageOrganizer(uri, PdfPagePlan.Operation.REORDER) }
        quickEdit("Print", "▤") { printPdfDocument(uri) }
        quickEdit("Share", "↗") { sharePdf(uri) }
        quickBar.addView(quickActions)
        root.addView(quickBar)
        root.addView(gap(8))
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
        editorAction("Reorder Pages", "Choose a custom order or reverse the PDF", "⇅") { showPageOrganizer(uri, PdfPagePlan.Operation.REORDER) }
        editorAction("Delete Pages", "Remove selected pages from a new copy", "−") { showPageOrganizer(uri, PdfPagePlan.Operation.DELETE) }
        editorAction("Extract Pages", "Keep selected pages in your chosen order", "⇱") { showPageOrganizer(uri, PdfPagePlan.Operation.EXTRACT) }
        editorAction("Duplicate Pages", "Insert a copy after selected pages", "▣") { showPageOrganizer(uri, PdfPagePlan.Operation.DUPLICATE) }
        editorAction("Page Numbers", "Add page numbers at the top or bottom", "#") { showPageNumbersDialog(uri) }
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
        editorAction("Print PDF", "Open Android print preview and printer settings", "▤") { printPdfDocument(uri) }
        editorAction("Share PDF", "Share the original PDF", "↗") { sharePdf(uri) }
        scroller.addView(list); root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        setScreenContent(root)
    }

    private fun sharePdf(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData=android.content.ClipData.newRawUri("DocNexa PDF",uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(send, "Share PDF"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app available to share this PDF", Toast.LENGTH_LONG).show()
        }
    }

    // Android Storage Access Framework: only user-selected files and destinations.
    private fun sharePdfToGoogleDrive(uri: Uri) {
        val name = privateDocumentFile(uri)?.name ?: runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "DocNexa_Document.pdf"
        confirmDriveSave(ExportResultItem(uri.toString(), name, "application/pdf"))
    }

    private var pendingDriveBackupAccount: String? = null
    private var pendingDriveImportAccount: String? = null
    private val driveImportBusy = AtomicBoolean(false)

    private fun backupMime(name: String): String? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heif"
        "gif" -> "image/gif"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        else -> null
    }

    private val driveImportPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val account = pendingDriveImportAccount
        pendingDriveImportAccount = null
        if (uri != null && account != null && account == signedInUserId()) restoreDriveFile(uri, account)
        else if (uri != null) Toast.makeText(this, "Account changed. Select the file again.", Toast.LENGTH_LONG).show()
    }

    private fun launchDriveImport() {
        if (driveImportBusy.get() || pendingDriveImportAccount != null) {
            Toast.makeText(this, "Finish the current import first.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingDriveImportAccount = signedInUserId()
        try {
            driveImportPicker.launch(arrayOf("application/pdf", "image/*",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "text/plain", "text/csv", "application/zip"))
        } catch (e: Exception) {
            pendingDriveImportAccount = null
            Toast.makeText(this, "File picker unavailable.", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreDriveFile(uri: Uri, account: String) {
        if (!driveImportBusy.compareAndSet(false, true)) return
        val directory = privateDocumentsDir()
        advancedToolJob("Importing original file…") {
            try {
                require(account == signedInUserId()) { "Account changed. Select the file again." }
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: error("Unable to read file name.")
                val mime = backupMime(name) ?: error("This file format is not supported.")
                val target = PrivateDocumentStore.save(directory, name) { output ->
                    (contentResolver.openInputStream(uri) ?: error("Cannot read selected file.")).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(account == signedInUserId()) { "Account changed during import." }
                            total += count
                            require(total <= 128L * 1024 * 1024) { "Files larger than 128 MB are not supported." }
                            output.write(buffer, 0, count)
                        }
                        require(total > 0L) { "The selected file is empty." }
                    }
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && account == signedInUserId()) {
                        val saved = privateDocumentUri(target)
                        if (mime == "application/pdf") {
                            assignPdf(saved)
                            lastPdf = saved
                            buildScreen()
                        }
                        status.text = "Imported original file"
                        showExportSuccess(saved, target.name, mime)
                    }
                }
            } finally { driveImportBusy.set(false) }
        }
    }

    // A Drive-specific action must never fall back to a local document picker.
    private fun confirmDriveSave(item: ExportResultItem) {
        val source = Uri.parse(item.uri)
        val account = signedInUserId()
        val file = privateDocumentFile(source)
        if (source.authority == "$packageName.fileprovider" && file == null) {
            updateResultStatus("This file is no longer available in your DocNexa account.")
            return
        }
        val direct = file != null && file.length() in 1L..DirectDriveApi.MAX_BYTES && backupMime(file.name) != null
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        popupHeader(panel, "DOCNEXA • CLOUD EXPORT", "Save to Google Drive", "Original quality • Secure account access")
        panel.addView(label(item.fileName, 14f, 0xFF172B4D.toInt(), true))
        panel.addView(gap(6))
        panel.addView(label("Your existing DocNexa file stays safe. This action never opens phone storage.", 12f, 0xFF52627A.toInt()))
        panel.addView(gap(12))
        val dialog = appDialog().setTitle("Cloud destination").setView(toolDialogScroll(panel, 0.72f))
            .setNegativeButton("Cancel", null).create()
        if (direct) addPopupOption(panel, "drive", "Upload to My Drive", "Connect your Google account and confirm the cloud upload.", true) {
            dialog.dismiss()
            if (account == signedInUserId() && privateDocumentFile(source) != null) {
                updateResultStatus("Google Drive authorization required. Original file is safe.")
                authorizeDirectDrive(source)
            } else updateResultStatus("File unavailable or DocNexa account changed.")
        }
        addPopupOption(panel, "drive", "Choose folder in Google Drive", "Open the official Drive app, select an account and folder, then tap Save.", !direct) {
            dialog.dismiss()
            if (account == signedInUserId()) openDriveUploadApp(item)
            else updateResultStatus("DocNexa account changed. Select the file again.")
        }
        panel.addView(label("Direct upload: up to 128 MB for supported saved files. Other files can use the Drive app.", 11f, 0xFF52627A.toInt()))
        dialog.show()
    }

    private fun openDriveUploadApp(item: ExportResultItem) {
        val source = Uri.parse(item.uri)
        if (source.authority == "$packageName.fileprovider" && privateDocumentFile(source) == null) {
            updateResultStatus("This file is no longer available in your DocNexa account.")
            return
        }
        try {
            // Explicitly target the Drive app; no chooser or local storage fallback.
            startActivity(Intent(Intent.ACTION_SEND).apply {
                setPackage("com.google.android.apps.docs")
                type = item.mime
                putExtra(Intent.EXTRA_STREAM, source)
                putExtra(Intent.EXTRA_TITLE, item.fileName)
                clipData = android.content.ClipData.newRawUri(item.fileName, source)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            updateResultStatus("Google Drive opened. Choose your account and folder, then tap Save. Upload not yet verified.")
        } catch (_: Exception) {
            appDialog().setTitle("Google Drive unavailable")
                .setMessage("Install or enable the official Google Drive app to choose a cloud folder. Your original file remains safe; no local export was made.")
                .setPositiveButton("Get Google Drive") { _, _ ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.docs"))) }
                }.setNegativeButton("Close", null).show()
        }
    }

    // Native vector mark, rendered without a bitmap or additional library.
    private fun driveBrandIcon(): View = object : View(this) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = minOf(width, height).toFloat()
            if (size <= 0f) return
            canvas.save()
            canvas.translate((width - size) / 2f, (height - size) / 2f)
            canvas.scale(size / 48f, size / 48f)
            fun polygon(color: Int, vararg points: Float) {
                val path = android.graphics.Path()
                path.moveTo(points[0], points[1])
                for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1])
                path.close()
                ink.color = color
                canvas.drawPath(path, ink)
            }
            polygon(0xFF0F9D58.toInt(), 17f, 5f, 30f, 5f, 14f, 33f, 7f, 43f, 1f, 32f)
            polygon(0xFFF4B400.toInt(), 17f, 5f, 30f, 5f, 47f, 33f, 33f, 33f)
            polygon(0xFF4285F4.toInt(), 14f, 29f, 45f, 29f, 47f, 33f, 41f, 43f, 7f, 43f)
            canvas.restore()
        }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    private fun showDriveBackupHub() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = gradient(0xFF073B91.toInt(), 0xFF06245F.toInt(), 18)
        }
        val brandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val iconTile = android.widget.FrameLayout(this).apply {
            background = rounded(0xFFFFFFFF.toInt(), 14)
            addView(driveBrandIcon(), android.widget.FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        }
        brandRow.addView(iconTile, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(12) })
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandText.addView(label("CLOUD STORAGE", 10f, 0xFFBCD7FF.toInt(), true))
        brandText.addView(label("Google Drive & Backup", 19f, 0xFFFFFFFF.toInt(), true))
        brandText.addView(label("Original quality • Secure access", 11f, 0xFFE1EDFF.toInt()))
        brandRow.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(brandRow)
        panel.addView(hero); panel.addView(gap(10))

        val owner = signedInUserId()
        val lastEmail = prefs.getString(accountPrefKey("drive_last_email"), "").orEmpty()
        val connection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFFF1F6FF.toInt(), 14, 0xFFDCE7FA.toInt())
        }
        val state = label(if (lastEmail.isBlank()) "Not connected" else "Checking connection…", 15f, 0xFF53627A.toInt(), true)
        val accountLabel = label(if (lastEmail.isBlank()) "Connect your Google account to back up files." else "Last authorized: " + lastEmail, 12f, 0xFF64748B.toInt())
        connection.addView(state); connection.addView(accountLabel)
        panel.addView(connection); panel.addView(gap(10))
        val stamp = prefs.getLong(accountPrefKey("manual_backup_time"), 0L)
        val last = if (stamp > 0L) java.text.DateFormat.getDateTimeInstance().format(Date(stamp)) else "No manual backup yet"
        panel.addView(label("Last copy written: " + last, 12f, 0xFF53627A.toInt()))
        panel.addView(gap(10))
        val dialog = appDialog().setTitle("Backup & Restore").setView(toolDialogScroll(panel, 0.72f))
            .setNegativeButton("Close", null).create()
        fun option(title:String,detail:String,action:()->Unit){
            val symbol=when{title.startsWith("Open")->"drive";title.startsWith("Manual")->"↑";else->"↓"}
            addPopupOption(panel,symbol,title,detail){dialog.dismiss();action()}
        }
        var checkedToken: String? = null
        var checkedEmail: String? = null
        val actionTitle = label("Connect Google Drive", 15f, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER }
        val connectButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(52)
            background = gradient(0xFF3478F6.toInt(), 0xFF1556BE.toInt(), 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true; isFocusable = true
            addView(actionTitle, LinearLayout.LayoutParams(-1, -2))
            setOnClickListener {
                if (owner != signedInUserId()) { dialog.dismiss(); return@setOnClickListener }
                val token = checkedToken
                val email = checkedEmail
                if (directDriveCheckBusy || directDriveBusy.get() || directDriveAccount != null) {
                    Toast.makeText(this@MainActivity, "Google Drive is checking access. Please try again when it finishes.", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    if (token != null && email != null) showDirectDriveActions(token, email, owner)
                    else authorizeDirectDrive()
                }
            }
        }
        panel.addView(connectButton); panel.addView(gap(10))
        option("Import / restore a file", "Choose an existing PDF, image or supported export from Drive or another provider.") { launchDriveImport() }
        option("Manual backup", "Upload a saved document, image or invoice to your Google Drive.") { chooseDriveBackupFile() }
        option("Open Google Drive", "Manage your Google account and uploaded files in Drive.") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/drive/my-drive")))
            } catch (_: Exception) { Toast.makeText(this, "Google Drive unavailable.", Toast.LENGTH_LONG).show() }
        }
        panel.addView(label("Drive uploads retain your original local file. Automatic backup and continuous sync are not yet available.", 11f, 0xFF64748B.toInt()))
        dialog.show()
        // Serialize silent checks with user-initiated authorization. Reuse the
        // verified token for this dialog only; never persist a bearer token.
        if (lastEmail.isNotBlank()) {
            fun showState(connected: Boolean, detail: String) {
                if (!dialog.isShowing || owner != signedInUserId() || isFinishing || isDestroyed) return
                state.text = if (connected) "● Connected" else "○ Reconnect / check access"
                state.setTextColor(if (connected) 0xFF128268.toInt() else 0xFF64748B.toInt())
                accountLabel.text = detail
                actionTitle.text = if (connected) "Manage Google Drive" else "Connect Google Drive"
                connectButton.isEnabled = true
                connectButton.alpha = 1f
            }
            if (!isInternetAvailable()) {
                showState(false, "Offline • Last authorized: " + lastEmail)
            } else if (directDriveCheckBusy || directDriveBusy.get() || directDriveAccount != null) {
                showState(false, "A Drive operation is already running. Reopen this panel to check.")
            } else {
                directDriveCheckBusy = true
                connectButton.isEnabled = false
                connectButton.alpha = 0.6f
                actionTitle.text = "Checking connection…"
                fun failed(detail: String) {
                    directDriveCheckBusy = false
                    showState(false, detail)
                }
                try {
                    val request = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
                        .setAccount(android.accounts.Account(lastEmail, "com.google"))
                        .setRequestedScopes(listOf(com.google.android.gms.common.api.Scope(directDriveScope))).build()
                    com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).authorize(request)
                        .addOnSuccessListener { authorization ->
                            val token = authorization.accessToken
                            if (authorization.hasResolution() || token.isNullOrBlank() ||
                                !authorization.grantedScopes.contains(directDriveScope)) {
                                failed("Google permission needs to be renewed.")
                            } else {
                                Thread {
                                    val email = runCatching { DirectDriveApi.accountEmail(token) }.getOrNull()
                                    runOnUiThread {
                                        directDriveCheckBusy = false
                                        if (email != null && email.equals(lastEmail, ignoreCase = true) &&
                                            owner == signedInUserId() && dialog.isShowing && !isDestroyed && !isFinishing) {
                                            checkedToken = token
                                            checkedEmail = email
                                            showState(true, email)
                                        } else showState(false, "Could not verify the saved Google account. Tap Connect to retry.")
                                    }
                                }.start()
                            }
                        }.addOnFailureListener { failed("Could not verify Google Drive access. Tap Connect to retry.") }
                } catch (_: Exception) {
                    failed("Google authorization unavailable. Tap Connect to retry.")
                }
            }
        }
    }

    private fun rememberDriveAuthorization(email: String) {
        prefs.edit().putString(accountPrefKey("drive_last_email"), email).apply()
    }

    private var directDriveAccount: String? = null
    private var directDriveTarget: Uri? = null
    private var directDriveCheckBusy = false
    private var directDriveResolutionPending = false
    private val directDriveBusy = AtomicBoolean(false)
    private val directDriveScope = "https://www.googleapis.com/auth/drive.file"

    private val directDriveAuthorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        directDriveResolutionPending = false
        val data = result.data
        if (data == null) {
            directDriveAccount = null
            showDriveAuthorizationHelp("Google returned no authorization result (Android result " + result.resultCode + ").")
        } else {
            try {
                val authorization = com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(data)
                finishDirectDriveAuthorization(authorization)
            } catch (e: Exception) {
                directDriveAccount = null
                showDriveAuthorizationHelp(driveAuthorizationError(e, "Consent result " + result.resultCode))
            }
        }
    }

    private fun driveAuthorizationError(error: Throwable, stage: String): String {
        val code = (error as? com.google.android.gms.common.api.ApiException)?.statusCode
        return if (code != null) stage + ": Google status " + code + " (" +
            com.google.android.gms.common.api.CommonStatusCodes.getStatusCodeString(code) + ")."
        else stage + ": " + error.javaClass.simpleName + "."
    }

    @Suppress("DEPRECATION")
    private fun installedDriveCertificate(): String = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull() ?: error("No signing certificate")
        MessageDigest.getInstance("SHA-1").digest(cert.toByteArray())
            .joinToString(":") { "%02X".format(it.toInt() and 255) }
    }.getOrDefault("Unavailable")

    private fun showDriveAuthorizationHelp(reason: String, retryTarget: Uri? = directDriveTarget) {
        directDriveTarget=null
        val owner = signedInUserId()
        if (isFinishing || isDestroyed) return
        val certificate = installedDriveCertificate()
        val guidance = if (reason.contains("Google status 8 (")) {
            "Google could not finish authorization. Retry using a stable internet connection. If this repeats, update Google Play services or review DocNexa access in your Google Account. You can also use the official Drive app."
        } else {
            "Check that the installed signing certificate is registered for the Android OAuth client. Select your Google account and approve Drive access."
        }
        val details = guidance + "\n\nTechnical details: " + reason + "\nPackage: " + packageName + "\nInstalled APK SHA-1:\n" + certificate
        appDialog().setTitle("Google Drive connection")
            .setMessage(details)
            .setPositiveButton("Retry") { _, _ -> if (owner == signedInUserId()) authorizeDirectDrive(retryTarget) }
            .setNeutralButton("Use Drive app") { _, _ ->
                if (owner == signedInUserId()) {
                    val file = retryTarget?.let { privateDocumentFile(it) }
                    if (retryTarget != null && file != null) openDriveUploadApp(ExportResultItem(retryTarget.toString(), file.name, backupMime(file.name) ?: "application/octet-stream"))
                    else if (retryTarget == null) chooseDriveBackupFile()
                    else updateResultStatus("Original file is no longer available.")
                }
            }
            .setNegativeButton("Close", null).show()
    }

    private fun authorizeDirectDrive(target: Uri? = null) {
        if (directDriveCheckBusy || directDriveAccount != null || directDriveBusy.get()) {
            Toast.makeText(this, "Finish the current Drive operation first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!requireInternet("Google Drive")) return
        directDriveAccount = signedInUserId()
        directDriveTarget = target
        val request = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
            .setRequestedScopes(listOf(com.google.android.gms.common.api.Scope(directDriveScope))).build()
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { authorization ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                try {
                    if (authorization.hasResolution()) {
                        val pending = authorization.pendingIntent ?: error("Authorization unavailable")
                        directDriveResolutionPending = true
                        directDriveAuthorization.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    } else finishDirectDriveAuthorization(authorization)
                } catch (e: Exception) {
                    directDriveAccount = null
                    directDriveResolutionPending = false
                    showDriveAuthorizationHelp(driveAuthorizationError(e, "Unable to open Google consent"))
                }
            }.addOnFailureListener { e ->
                directDriveAccount = null
                directDriveResolutionPending = false
                showDriveAuthorizationHelp(driveAuthorizationError(e, "Authorization request"))
            }
    }

    private fun finishDirectDriveAuthorization(result: com.google.android.gms.auth.api.identity.AuthorizationResult) {
        val account = directDriveAccount
        directDriveAccount = null
        val target = directDriveTarget
        directDriveTarget = null
        val token = result.accessToken
        if (account == null || account != signedInUserId() || token.isNullOrBlank() ||
            !result.grantedScopes.contains(directDriveScope)) {
            showDriveAuthorizationHelp(when {
                account == null || account != signedInUserId() -> "DocNexa account changed or authorization state expired."
                token.isNullOrBlank() -> "Google returned no access token."
                else -> "The required Drive file permission was not granted."
            }, if (account == signedInUserId()) target else null)
            return
        }
        if (!directDriveBusy.compareAndSet(false, true)) return
        advancedToolJob("Checking Google Drive access…") {
            try {
                val email = DirectDriveApi.accountEmail(token)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && account == signedInUserId()) {
                        rememberDriveAuthorization(email)
                        status.text = "Google Drive connected"
                        if(target==null)showDirectDriveActions(token, email, account)
                        else {
                            val file=privateDocumentFile(target)
                            if(file!=null)appDialog().setTitle("Upload to Google Drive?")
                                .setMessage(file.name+"\n\nGoogle account: "+email+"\nLocation: My Drive\n\nUpload the original file to this account?")
                                .setPositiveButton("Upload"){_,_->if(account==signedInUserId())uploadDirectDriveFiles(token,account,listOf(file),false)}
                                .setNegativeButton("Cancel",null).show()
                            else updateResultStatus("Original file is no longer available.")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && account == signedInUserId())
                        showDriveAuthorizationHelp(e.localizedMessage ?: "Unable to verify Drive access.", target)
                }
            } finally { directDriveBusy.set(false) }
        }
    }

    private fun showDirectDriveActions(token: String, email: String, account: String) {
        if (account != signedInUserId()) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(12))
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradient(0xFF073B91.toInt(), 0xFF06245F.toInt(), 16)
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }
        val logo = FrameLayout(this).apply {
            background = rounded(0xFFFFFFFF.toInt(), 12)
            addView(driveBrandIcon(), FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        }
        hero.addView(logo, LinearLayout.LayoutParams(dp(50), dp(50)))
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        details.addView(label("● Connected", 14f, 0xFF9BE9BE.toInt(), true))
        details.addView(label(email, 13f, 0xFFFFFFFF.toInt()).apply { setTextIsSelectable(true) })
        details.addView(label("Google Drive • Secure file access", 11f, 0xFFDCE8FF.toInt()))
        hero.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(hero); panel.addView(gap(14))
        val dialog = appDialog().setTitle("Google Drive").setView(toolDialogScroll(panel, 0.70f))
            .setNegativeButton("Close", null).create()
        fun action(symbol: String, title: String, subtitle: String, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(72)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x223478F6),
                    rounded(0xFFF5F8FF.toInt(), 14, 0xFFDCE7F5.toInt()), null)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    if (account == signedInUserId()) onClick()
                }
            }
            row.addView(label(symbol, 23f, 0xFF1769E8.toInt(), true).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = rounded(0xFFE5EEFF.toInt(), 12)
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(6), 0)
            }
            copy.addView(label(title, 15f, 0xFF172B4D.toInt(), true))
            copy.addView(label(subtitle, 12f, 0xFF64748B.toInt()))
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("›", 23f, 0xFF3478F6.toInt()))
            panel.addView(row); panel.addView(gap(8))
        }
        action("↑", "Upload a saved file", "Back up a document at original quality.") {
            chooseDirectDriveUpload(token, email, account, false)
        }
        if (hasVerifiedPremium()) action("▤", "Batch upload • Premium", "Back up to 10 selected files.") {
            chooseDirectDriveUpload(token, email, account, true)
        }
        action("↓", "Restore a DocNexa backup", "Find and restore previously backed-up files.") {
            listDirectDriveBackups(token, email, account)
        }
        action("⚙", "Manage Google access", "Review or remove Google account permissions.") {
            appDialog().setTitle("Manage Google access")
                .setMessage("Authorized Google account: " + email + "\n\nManage or remove DocNexa access on your Google Account. Existing Drive backups are not deleted.")
                .setPositiveButton("Open Google Account") { _, _ ->
                    if (account == signedInUserId()) prefs.edit().remove(accountPrefKey("drive_last_email")).apply()
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/connections"))) }
                }.setNegativeButton("Cancel", null).show()
        }
        dialog.show()
    }

    private fun chooseDirectDriveUpload(token: String, email: String, account: String, batch: Boolean) {
        if (account != signedInUserId() || (batch && !requirePremiumTool("Batch Drive backup"))) return
        val trashed = prefs.getStringSet("trash_uris", emptySet()).orEmpty()
        val files = privateDocumentsDir().listFiles().orEmpty().filter {
            it.isFile && it.length() in 1L..DirectDriveApi.MAX_BYTES && backupMime(it.name) != null &&
                !trashed.contains(privateDocumentUri(it).toString())
        }.sortedByDescending { it.lastModified() }.take(200)
        if (files.isEmpty()) {
            Toast.makeText(this, "No supported saved files (up to 128 MB).", Toast.LENGTH_LONG).show()
            return
        }
        val labels = files.map { it.name + " • " + formatFileSize(it.length()) }.toTypedArray()
        fun confirm(chosen: List<java.io.File>) {
            if (chosen.isEmpty()) { Toast.makeText(this, "Select at least one file.", Toast.LENGTH_SHORT).show(); return }
            appDialog().setTitle("Confirm Google Drive upload")
                .setMessage("Upload " + chosen.size + " original file(s) to " + email + "? Existing Drive files will not be overwritten.")
                .setPositiveButton("Upload") { _, _ -> uploadDirectDriveFiles(token, account, chosen, batch) }
                .setNegativeButton("Cancel", null).show()
        }
        if (!batch) appDialog().setTitle("Select file • " + email).setPremiumItems(labels) { _, index -> confirm(listOf(files[index])) }
            .setNegativeButton("Cancel", null).show()
        else {
            val checked = BooleanArray(files.size)
            appDialog().setTitle("Select up to 10 files")
                .setMultiChoiceItems(labels, checked) { _, index, selected -> checked[index] = selected }
                .setPositiveButton("Continue") { _, _ ->
                    val chosen = files.filterIndexed { index, _ -> checked[index] }
                    if (chosen.size > 10) Toast.makeText(this, "Select up to 10 files.", Toast.LENGTH_LONG).show()
                    else confirm(chosen)
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun uploadDirectDriveFiles(token: String, account: String, files: List<java.io.File>, batch: Boolean) {
        if (!directDriveBusy.compareAndSet(false, true)) return
        advancedToolJob("Uploading original files to Drive…") {
            try {
                var complete = 0
                var failed = 0
                for (file in files) {
                    if (account != signedInUserId() || (batch && !hasVerifiedPremium())) break
                    try {
                        require(privateDocumentFile(privateDocumentUri(file)) == file)
                        DirectDriveApi.upload(token, file, backupMime(file.name) ?: error("Unsupported file"), account)
                        complete++
                        if (account == signedInUserId()) prefs.edit().putLong(accountPrefKey("manual_backup_time"), System.currentTimeMillis()).apply()
                    } catch (_: Exception) { failed++ }
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && account == signedInUserId()) {
                        status.text = "Drive upload: " + complete + " saved"
                        if(invoiceReadyDialog?.isShowing==true)invoiceReadyStatus?.text=if(complete>0)"Original PDF uploaded to Google Drive." else "Google Drive upload not completed. Your local PDF is safe."
                        if (files.size == 1 && exportResultDialog?.isShowing == true &&
                            exportResults.selected?.uri == privateDocumentUri(files.first()).toString()) {
                            exportStatusView?.text = if (complete == 1) "Original file uploaded to Google Drive." else "Drive upload not completed. Your original file is safe."
                        }
                        appDialog().setTitle(if (complete == files.size) "Saved to Google Drive" else "Google Drive upload result")
                            .setMessage("Uploaded: " + complete + "\nFailed: " + failed + "\nNot attempted: " + (files.size - complete - failed) + "\nOriginal local files retained.")
                            .setPositiveButton("Done", null)
                            .setNeutralButton("Open Drive") { _, _ ->
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/drive/my-drive"))) }
                            }.show()
                    }
                }
            } finally { directDriveBusy.set(false) }
        }
    }

    private fun listDirectDriveBackups(token: String, email: String, account: String) {
        if (account != signedInUserId() || !directDriveBusy.compareAndSet(false, true)) return
        advancedToolJob("Loading DocNexa Drive backups…") {
            try {
                val backups = DirectDriveApi.list(token, account).filter { backupMime(it.name) != null }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && account == signedInUserId()) {
                        status.text = "Drive backup list ready"
                        if (backups.isEmpty()) Toast.makeText(this, "No direct backups found for this account.", Toast.LENGTH_LONG).show()
                        else appDialog().setTitle("Latest Drive backups • " + email)
                            .setPremiumItems(backups.map { it.name }.toTypedArray()) { _, index ->
                                val item = backups[index]
                                appDialog().setTitle("Restore file?")
                                    .setMessage("Save an unchanged local copy of " + item.name + "?")
                                    .setPositiveButton("Restore") { _, _ -> restoreDirectDriveBackup(token, account, item) }
                                    .setNegativeButton("Cancel", null).show()
                            }.setNegativeButton("Close", null).show()
                    }
                }
            } finally { directDriveBusy.set(false) }
        }
    }

    private fun restoreDirectDriveBackup(token: String, account: String, item: DirectDriveApi.Backup) {
        if (account != signedInUserId() || !directDriveBusy.compareAndSet(false, true)) return
        val directory = privateDocumentsDir()
        advancedToolJob("Restoring original Drive file…") {
            try {
                val mime = backupMime(item.name) ?: error("Unsupported file")
                val target = PrivateDocumentStore.save(directory, item.name) { output ->
                    DirectDriveApi.download(token, item.id, output) { account == signedInUserId() }
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && account == signedInUserId()) {
                        val saved = privateDocumentUri(target)
                        if (mime == "application/pdf") { assignPdf(saved); lastPdf = saved; buildScreen() }
                        status.text = "Drive backup restored"
                        showExportSuccess(saved, target.name, mime)
                    }
                }
            } finally { directDriveBusy.set(false) }
        }
    }

    private fun chooseDriveBackupFile() {
        val account = signedInUserId()
        val trashed = prefs.getStringSet("trash_uris", emptySet()).orEmpty()
        val files = privateDocumentsDir().listFiles().orEmpty().filter {
            it.isFile && backupMime(it.name) != null && !trashed.contains(privateDocumentUri(it).toString())
        }.sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            Toast.makeText(this, "No saved files to back up yet.", Toast.LENGTH_LONG).show()
            return
        }
        appDialog().setTitle("Choose a file to back up")
            .setPremiumItems(files.map { it.name + "  •  " + formatFileSize(it.length()) }.toTypedArray()) { _, index ->
                val file = files[index]
                if (account != signedInUserId() || !file.isFile) {
                    Toast.makeText(this, "File unavailable. Select again.", Toast.LENGTH_LONG).show()
                } else {
                    confirmDriveSave(ExportResultItem(privateDocumentUri(file).toString(), file.name, backupMime(file.name) ?: "application/octet-stream"))
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // Google Docs import/conversion support depends on the installed Google app version.
    private fun openPdfWithGoogleDocs(uri: Uri) {
        if (!requireInternet("Google Docs online features")) return
        openViewerExternal(uri,"application/pdf")
    }

    private fun addWatermarkDialog(uri: Uri) {
        val field = EditText(this).apply { hint = "Watermark text" }
        appDialog().setTitle("Watermark").setView(field)
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
        appDialog().setTitle("Whitener Tool")
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
        appDialog().setTitle("Line and Shapes")
            .setPremiumItems(arrayOf("Add rectangle", "Add line", "Add circle")) { _, which -> addShapeToPdf(uri, which) }.show()
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
        appDialog().setTitle("Add text to PDF").setView(field)
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
                        Toast.makeText(this, "Edited PDF saved in DocNexa app storage", Toast.LENGTH_LONG).show()
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
                    val presetIndicators = mutableListOf<TextView>()
                    compressionPresets.forEachIndexed { index, preset ->
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(12), dp(10), dp(12), dp(10))
                            background = rounded(if (index == selected) 0xFFEAF2FF.toInt() else 0xFFFFFFFF.toInt(), 13,
                                if (index == selected) 0xFF3478F6.toInt() else 0xFFE2EAF5.toInt())
                            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
                            minimumHeight=dp(66);isClickable=true;isFocusable=true
                            setOnClickListener {
                                selected = index
                                presetIndicators.forEachIndexed{indicatorIndex,indicator->indicator.text=if(indicatorIndex==selected)"●" else "○"}
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
                        val indicator=label(if(index==selected)"●" else "○",18f,0xFF3478F6.toInt(),true)
                        presetIndicators.add(indicator);row.addView(indicator)
                        presetRows.add(row)
                        panel.addView(row)
                    }
                    panel.addView(label("Note: Pages are converted to images. Searchable text, links, forms and annotations may not be preserved.", 10.5f, 0xFF718096.toInt()))
                    val scroll = ScrollView(this).apply { isFillViewport = true; addView(panel) }
                    showPremiumDialog(appDialog()
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
        showPremiumDialog(appDialog().setTitle("Custom compression")
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
                            val dimensions = NativeExportPlan.pdfDimensions(sourcePage.width, sourcePage.height, preset.dpi.toInt(), 4096, exportPixelBudget())
                            val width = dimensions.width
                            val height = dimensions.height
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
                        appDialog()
                            .setTitle("Compression Preview")
                            .setMessage(summary + "\n\nNote: This image-based compression reduces resolution and removes selectable/searchable text and form layers.")
                            .setPositiveButton("Save PDF") { _, _ ->
                                val saved = savePdfBytes(compressedBytes, "Compressed")
                                if (saved != null) {
                                    lastPdf = saved
                                    status.text = "Compressed PDF saved"
                                    recent.text = "Latest: ${saved.lastPathSegment}"
                                    Toast.makeText(this, "Compressed PDF saved in DocNexa app storage", Toast.LENGTH_LONG).show()
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
        showPremiumDialog(appDialog()
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
                    PDDocument.load(stream).use { PDFTextStripper().apply { sortByPosition = true; pageEnd = "\n\u000C\n" }.getText(it) }
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
            .map { if (it.contains('\u000C')) "\u000C" else it.trimEnd() }
            .filter { it == "\u000C" || it.isNotBlank() }
            .ifEmpty { listOf(text.trim()) }
        fun xml(value: String): String = value.replace("&", "&amp;")
            .replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        val body = paragraphs.joinToString("") { paragraph ->
            if (paragraph == "\u000C") return@joinToString "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"
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
        catch (_: Exception) { Toast.makeText(this, "No DOCX app found. The file is saved in DocNexa app storage.", Toast.LENGTH_LONG).show() }
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

    private val exportResults = ExportResultSession()
    private var exportResultDialog: AlertDialog? = null
    private var refreshExportResult: (() -> Unit)? = null
    private var exportStatusView: TextView? = null
    private var pendingResultSource: Uri? = null
    private var pendingResultName: String? = null
    private val resultCopyBusy = AtomicBoolean(false)
    private val resultPreviewGeneration = java.util.concurrent.atomic.AtomicInteger()

    private val resultSaveAsPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = pendingResultSource
        val name = pendingResultName ?: "Document"
        val backupAccount = pendingDriveBackupAccount
        pendingDriveBackupAccount = null
        pendingResultSource = null
        pendingResultName = null
        val destination = result.data?.data
        if (result.resultCode == RESULT_OK && destination != null && source != null) {
            if (resultCopyBusy.compareAndSet(false, true)) {
                updateResultStatus("Saving a copy…")
                Thread {
                    try {
                        if (backupAccount != null) {
                            require(backupAccount == signedInUserId() && privateDocumentFile(source) != null) { "Account changed or source is no longer available." }
                        }
                        (contentResolver.openInputStream(source) ?: error("Cannot read the saved file.")).use { input ->
                            (contentResolver.openOutputStream(destination, "wt") ?: error("Cannot write to this location.")).use { output -> input.copyTo(output) }
                        }
                        if (backupAccount != null && backupAccount == signedInUserId()) {
                            prefs.edit().putLong(accountPrefKey("manual_backup_time"), System.currentTimeMillis()).apply()
                        }
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                updateResultStatus(if (backupAccount != null) "Backup copy written to selected provider. Check Drive for sync status." else "Copy saved to your selected location.")
                                Toast.makeText(this, "$name saved", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) updateResultStatus(e.localizedMessage ?: "Save As failed. Your original file is still available.")
                        }
                    } finally { resultCopyBusy.set(false) }
                }.start()
            }
        } else if (source != null) updateResultStatus("Save As cancelled. Your saved file is still available.")
    }

    private fun updateResultStatus(message: String) {
        if (invoiceReadyDialog?.isShowing==true) invoiceReadyStatus?.text=message
        else if (exportResultDialog?.isShowing == true) exportStatusView?.text = message
        else if (!isFinishing && !isDestroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun saveResultAs(item: ExportResultItem, driveBackup: Boolean = false) {
        if (resultCopyBusy.get() || pendingResultSource != null) {
            updateResultStatus("Please finish the current export first.")
            return
        }
        pendingResultSource = Uri.parse(item.uri)
        pendingResultName = item.fileName
        pendingDriveBackupAccount = if (driveBackup) signedInUserId() else null
        try {
            resultSaveAsPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = item.mime
                putExtra(Intent.EXTRA_TITLE, item.fileName)
            })
        } catch (e: Exception) {
            pendingResultSource = null
            pendingResultName = null
            pendingDriveBackupAccount = null
            updateResultStatus(e.localizedMessage ?: "No file picker is available.")
        }
    }

    // One shared result card: additional output files join the existing card instead of stacking windows.
    private fun showExportSuccess(uri: Uri, fileName: String, mime: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            exportResults.add(ExportResultItem(uri.toString(), fileName, mime))
            if (exportResultDialog?.isShowing == true) {
                refreshExportResult?.invoke()
                return@runOnUiThread
            }
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(12))
            }
            popupHeader(panel, "DOCNEXA • EXPORT STUDIO", "File ready to export", "Saved successfully • Preview, share or back up your original file")
            val fileCount = label("", 12f, 0xFF52627A.toInt(), true)
            val selector = android.widget.Spinner(this)
            panel.addView(fileCount)
            panel.addView(selector)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = rounded(0xFFF3F7FF.toInt(), 16, 0xFFDCE7F6.toInt())
            }
            val frame = FrameLayout(this).apply { background = rounded(0xFFFFFFFF.toInt(), 10) }
            val preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Saved file preview" }
            val fileIcon = label("PDF", 17f, 0xFF2563EB.toInt(), true).apply { gravity = Gravity.CENTER }
            frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
            frame.addView(fileIcon, FrameLayout.LayoutParams(-1, -1))
            card.addView(frame, LinearLayout.LayoutParams(dp(84), dp(108)))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            val name = label("", 14f, 0xFF172B4D.toInt(), true).apply { setTextIsSelectable(true) }
            val fileTypeLabel = label("", 11f, 0xFF52627A.toInt())
            val metadata = label("Reading file details…", 11f, 0xFF52627A.toInt())
            val location = label("", 10f, 0xFF128268.toInt())
            info.addView(name); info.addView(gap(5)); info.addView(fileTypeLabel); info.addView(gap(4)); info.addView(metadata); info.addView(gap(5)); info.addView(location)
            card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            panel.addView(card)
            val resultAdHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            panel.addView(resultAdHost)
            panel.addView(gap(14))
            val statusText = label("Original file ready. Choose an export destination.", 12f, 0xFF52627A.toInt()).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(0xFFF3F7FF.toInt(), 12, 0xFFDCE7F5.toInt())
            }
            exportStatusView = statusText
            panel.addView(statusText)
            panel.addView(gap(12))
            fun resultButton(title: String, primary: Boolean, action: () -> Unit): com.google.android.material.button.MaterialButton =
                com.google.android.material.button.MaterialButton(this).apply {
                    text = title; isAllCaps = false; textSize = 13f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    maxLines = 2
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    cornerRadius = dp(14)
                    insetTop = 0; insetBottom = 0
                    minimumHeight = dp(48)
                    setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF2563EB.toInt())
                    backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) 0xFF2563EB.toInt() else 0xFFEAF2FF.toInt())
                    setOnClickListener { action() }
                }
            val openButton = resultButton("Open file", true) {
                exportResults.selected?.let { item ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.uri), item.mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: Exception) { updateResultStatus("No compatible app found. You can Save As or Share this file.") }
                }
            }
            panel.addView(openButton, LinearLayout.LayoutParams(-1, dp(52)))
            panel.addView(gap(8))
            addPopupOption(panel, "drive", "Save to Google Drive", "Cloud backup • Original quality • Choose your Google account", true) {
                exportResults.selected?.let { confirmDriveSave(it) }
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(resultButton("Save a copy", false) { exportResults.selected?.let { saveResultAs(it) } },
                LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(5) })
            actions.addView(resultButton("Share", false) {
                exportResults.selected?.let { item ->
                    try {
                        val source = Uri.parse(item.uri)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = item.mime
                            putExtra(Intent.EXTRA_STREAM, source)
                            clipData = android.content.ClipData.newRawUri(item.fileName, source)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(send, "Share ${item.fileName}"))
                    } catch (e: Exception) { updateResultStatus(e.localizedMessage ?: "Could not open sharing options.") }
                }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(5) })
            panel.addView(actions)
            panel.addView(gap(8))
            val fileUtilities = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fileUtilities.addView(resultButton("Copy file name", false) {
                exportResults.selected?.let { item ->
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DocNexa file name", item.fileName))
                    updateResultStatus("File name copied.")
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) })
            fileUtilities.addView(resultButton("File details", false) {
                exportResults.selected?.let { item ->
                    val source = Uri.parse(item.uri)
                    var size = "Unknown"
                    try {
                        contentResolver.query(source, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            val column = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                                val bytes = cursor.getLong(column)
                                size = String.format(java.util.Locale.US, "%.2f MB", bytes / 1048576.0)
                            }
                        }
                    } catch (_: Exception) { }
                    appDialog().setTitle("File details").setMessage("Name: ${item.fileName}\\nType: ${ExportResultSession.typeLabel(item.mime)}\\nSize: $size\\nLocation: DocNexa file storage").setPositiveButton("OK", null).show()
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })
            panel.addView(fileUtilities)
            panel.addView(gap(12))
            panel.addView(label("OTHER EXPORT DESTINATIONS", 12f, 0xFF52627A.toInt(), true))
            panel.addView(gap(8))
            // The original export remains in DocNexa private documents until the user
            // explicitly chooses an external destination. No cloud upload is implied.
            val destinationGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun destinationRow(firstTitle: String, firstAction: () -> Unit, secondTitle: String, secondAction: () -> Unit) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(resultButton(firstTitle, false, firstAction),
                    LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(5) })
                row.addView(resultButton(secondTitle, false, secondAction),
                    LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(5) })
                destinationGrid.addView(row)
                destinationGrid.addView(gap(8))
            }
            destinationRow(
                "📱 Phone storage", { exportResults.selected?.let { saveResultAs(it) } },
                "📂 My documents", {
                    exportResultDialog?.dismiss()
                    buildScreen()
                    Toast.makeText(this, "My Documents and Recent Documents are on the home screen.", Toast.LENGTH_LONG).show()
                }
            )
            destinationRow(
                "▤ Print PDF", {
                    exportResults.selected?.let { item ->
                        if (item.mime == "application/pdf") printPdfDocument(Uri.parse(item.uri), true)
                        else updateResultStatus("Printing from this shortcut is available for PDF files.")
                    }
                },
                "✉ Email", {
                    exportResults.selected?.let { item ->
                        try {
                            val source = Uri.parse(item.uri)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = item.mime
                                putExtra(Intent.EXTRA_STREAM, source)
                                clipData = android.content.ClipData.newRawUri(item.fileName, source)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(send, "Choose an email app"))
                        } catch (e: Exception) {
                            updateResultStatus(e.localizedMessage ?: "Unable to open email sharing.")
                        }
                    }
                }
            )
            panel.addView(destinationGrid)
            panel.addView(gap(10))
            panel.addView(label("ADVANCED FILE TOOLS", 12f, 0xFF52627A.toInt(), true))
            panel.addView(gap(6))
            val advancedPdfRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val compressResult = resultButton("↓ Compress PDF", false) {
                val item = exportResults.selected
                if (item?.mime == "application/pdf") {
                    showCompressionOptions(Uri.parse(item.uri))
                } else updateResultStatus("Compression is available for PDF files.")
            }
            val passwordResult = resultButton("🔒 Protect PDF", false) {
                val item = exportResults.selected
                if (item?.mime == "application/pdf") {
                    // Reuse the existing password tool rather than exposing a nonfunctional toggle.
                    passwordDialog(Uri.parse(item.uri), false)
                } else updateResultStatus("Password protection is available for PDF files.")
            }
            advancedPdfRow.addView(compressResult, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(5) })
            advancedPdfRow.addView(passwordResult, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(5) })
            panel.addView(advancedPdfRow)
            panel.addView(gap(8))
            val inspectPdfButton = resultButton("✓ Check PDF integrity & page count", false) {
                val item = exportResults.selected
                if (item == null || item.mime != "application/pdf") {
                    updateResultStatus("Select a PDF file to inspect.")
                } else {
                    updateResultStatus("Checking PDF structure and pages…")
                    Thread {
                        try {
                            val source = Uri.parse(item.uri)
                            val fd = contentResolver.openFileDescriptor(source, "r")
                                ?: throw java.io.IOException("Unable to open PDF")
                            val report = PdfRenderer(fd).use { renderer ->
                                val count = renderer.pageCount
                                if (count <= 0) throw java.io.IOException("PDF has no readable pages")
                                val first = renderer.openPage(0).use { page ->
                                    "${page.width} × ${page.height} px"
                                }
                                var inspected = 1
                                var failed = 0
                                val sampleLimit = minOf(count, 25)
                                for (index in 1 until sampleLimit) {
                                    try {
                                        renderer.openPage(index).use { page ->
                                            if (page.width <= 0 || page.height <= 0) failed++
                                        }
                                        inspected++
                                    } catch (_: Exception) {
                                        failed++
                                    }
                                }
                                val result = if (failed == 0) "No errors found in sampled pages" else "$failed sampled page(s) failed inspection"
                                "PDF opened • $count page(s) • First page: $first • Checked: $inspected/$count • $result"
                            }
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    updateResultStatus(report)
                                    appDialog().setTitle("PDF Integrity Report")
                                        .setMessage("File: ${item.fileName}\\n\\n$report\\n\\nThis checks up to 25 pages for basic readability. It does not guarantee the entire PDF is free from corruption.")
                                        .setPositiveButton("OK", null).show()
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    val message = e.localizedMessage ?: "Unreadable or protected PDF"
                                    updateResultStatus("PDF check failed: $message")
                                    appDialog().setTitle("PDF Check Failed")
                                        .setMessage(message)
                                        .setPositiveButton("OK", null).show()
                                }
                            }
                        }
                    }.start()
                }
            }
            panel.addView(inspectPdfButton, LinearLayout.LayoutParams(-1, dp(52)))
            val batchCheckButton = resultButton("✓ Batch check all exported PDFs", false) {
                val pdfItems = exportResults.items.filter { it.mime == "application/pdf" }
                if (pdfItems.isEmpty()) {
                    updateResultStatus("No PDF files available for batch inspection.")
                } else {
                    updateResultStatus("Checking ${pdfItems.size} exported PDF(s)…")
                    Thread {
                        val lines = mutableListOf<String>()
                        pdfItems.forEachIndexed { index, item ->
                            try {
                                val source = Uri.parse(item.uri)
                                val fd = contentResolver.openFileDescriptor(source, "r")
                                    ?: throw java.io.IOException("Cannot open PDF")
                                val report = PdfRenderer(fd).use { renderer ->
                                    val count = renderer.pageCount
                                    if (count < 1) throw java.io.IOException("No readable pages")
                                    var checked = 0
                                    for (pageIndex in 0 until minOf(count, 25)) {
                                        renderer.openPage(pageIndex).use { page ->
                                            if (page.width < 1 || page.height < 1) throw java.io.IOException("Invalid page dimensions")
                                        }
                                        checked++
                                    }
                                    "$count pages; $checked checked"
                                }
                                lines.add("${index + 1}. ${item.fileName}: OK ($report)")
                            } catch (e: Exception) {
                                lines.add("${index + 1}. ${item.fileName}: FAILED (${e.localizedMessage ?: "Unreadable PDF"})")
                            }
                        }
                        val report = lines.joinToString("\\n")
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                updateResultStatus("Batch PDF inspection complete.")
                                appDialog().setTitle("Batch PDF Inspection")
                                    .setMessage(report + "\\n\\nUp to 25 pages per PDF were checked for basic readability.")
                                    .setPositiveButton("Copy report") { _, _ ->
                                        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DocNexa PDF inspection", report))
                                        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
                                    }
                                    .setNegativeButton("Close", null).show()
                            }
                        }
                    }.start()
                }
            }
            panel.addView(batchCheckButton, LinearLayout.LayoutParams(-1, dp(54)))
            val batchCompressButton = resultButton("▣ Batch compress exported PDFs", false) {
                val pdfItems = exportResults.items.filter { it.mime == "application/pdf" }.toList()
                if (pdfItems.size < 2) {
                    updateResultStatus("Create at least two PDF files to use batch compression.")
                } else {
                    appDialog().setTitle("Batch PDF Compression")
                        .setMessage("Compress ${pdfItems.size} PDFs using the Standard preset? New copies will be saved. Compression rasterizes pages and removes searchable text, links and forms.")
                        .setPositiveButton("Compress all") { _, _ ->
                            updateResultStatus("Compressing ${pdfItems.size} PDFs in background…")
                            Thread {
                                val results = mutableListOf<String>()
                                val created = mutableListOf<Pair<Uri, String>>()
                                val preset = compressionPresets[1]
                                pdfItems.forEachIndexed { index, item ->
                                    try {
                                        val source = Uri.parse(item.uri)
                                        val descriptor = contentResolver.openFileDescriptor(source, "r")
                                            ?: throw java.io.IOException("Cannot open source")
                                        val output = PdfDocument()
                                        try {
                                            PdfRenderer(descriptor).use { renderer ->
                                                for (pageIndex in 0 until renderer.pageCount) {
                                                    renderer.openPage(pageIndex).use { sourcePage ->
                                                        val dimensions = NativeExportPlan.pdfDimensions(sourcePage.width, sourcePage.height, preset.dpi.toInt(), 4096, exportPixelBudget())
                                                        val width = dimensions.width
                                                        val height = dimensions.height
                                                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                                        try {
                                                            bitmap.eraseColor(android.graphics.Color.WHITE)
                                                            sourcePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                                            val encoded = ByteArrayOutputStream().also {
                                                                bitmap.compress(Bitmap.CompressFormat.JPEG, preset.quality, it)
                                                            }.toByteArray()
                                                            val reduced = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                                                                ?: throw java.io.IOException("Could not encode page")
                                                            try {
                                                                val page = output.startPage(PdfDocument.PageInfo.Builder(sourcePage.width, sourcePage.height, pageIndex + 1).create())
                                                                page.canvas.drawBitmap(reduced, null, android.graphics.Rect(0, 0, sourcePage.width, sourcePage.height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                                                                output.finishPage(page)
                                                            } finally { reduced.recycle() }
                                                        } finally { bitmap.recycle() }
                                                    }
                                                }
                                            }
                                            val bytes = ByteArrayOutputStream().also { output.writeTo(it) }.toByteArray()
                                            val name = "Batch_Compressed_${index + 1}_${System.currentTimeMillis()}.pdf"
                                            val saved = saveBytesPrivately(bytes, name)
                                                ?: throw java.io.IOException("Could not save output")
                                            created.add(saved to name)
                                            results.add("${index + 1}. ${item.fileName}: saved (${formatFileSize(bytes.size.toLong())})")
                                        } finally { output.close() }
                                    } catch (e: Exception) {
                                        results.add("${index + 1}. ${item.fileName}: failed (${e.localizedMessage ?: "Unknown error"})")
                                    }
                                }
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed) {
                                        created.forEach { (uri, name) ->
                                            exportResults.add(ExportResultItem(uri.toString(), name, "application/pdf"))
                                        }
                                        refreshExportResult?.invoke()
                                        updateResultStatus("Batch compression: ${created.size}/${pdfItems.size} saved.")
                                        appDialog().setTitle("Batch Compression Results")
                                            .setMessage(results.joinToString("\\n"))
                                            .setPositiveButton("OK", null).show()
                                    }
                                }
                            }.start()
                        }.setNegativeButton("Cancel", null).show()
                }
            }
            panel.addView(batchCompressButton, LinearLayout.LayoutParams(-1, dp(54)))
            panel.addView(gap(8))
            val ocrResult = resultButton("🔎 Recognize text in image (OCR)", false) {
                val item = exportResults.selected
                if (item?.mime?.startsWith("image/") == true) {
                    runOcr(Uri.parse(item.uri))
                } else {
                    ocrPicker.launch("image/*")
                    updateResultStatus("Choose an image to recognize text.")
                }
            }
            panel.addView(ocrResult, LinearLayout.LayoutParams(-1, dp(54)))
            panel.addView(gap(8))
            // ZIP is an actual archive containing the selected exported file, not PDF compression.
            val zipButton = resultButton("📦 Create ZIP archive", false) {
                val selected = exportResults.selected
                if (selected == null) {
                    updateResultStatus("Select a file first.")
                } else {
                    updateResultStatus("Creating ZIP archive…")
                    Thread {
                        try {
                            val bytes = java.io.ByteArrayOutputStream()
                            java.util.zip.ZipOutputStream(bytes).use { zip ->
                                zip.putNextEntry(java.util.zip.ZipEntry(selected.fileName.substringAfterLast('/')))
                                contentResolver.openInputStream(Uri.parse(selected.uri)).use { source ->
                                    if (source == null) throw java.io.IOException("Cannot read selected file")
                                    source.copyTo(zip)
                                }
                                zip.closeEntry()
                            }
                            val zipName = selected.fileName.substringBeforeLast('.', selected.fileName) + ".zip"
                            val saved = saveBytesPrivately(bytes.toByteArray(), zipName)
                                ?: throw java.io.IOException("Could not save ZIP archive")
                            runOnUiThread { showExportSuccess(saved, zipName, "application/zip") }
                        } catch (e: Exception) {
                            runOnUiThread { updateResultStatus(e.localizedMessage ?: "ZIP creation failed.") }
                        }
                    }.start()
                }
            }
            panel.addView(zipButton, LinearLayout.LayoutParams(-1, dp(48)))
            val gallery = resultButton("Save image to Gallery", false) { exportResults.selected?.let { item ->
                if (item.mime.startsWith("image/")) saveResultImageToGallery(item)
                else updateResultStatus("Gallery saving is only available for image files.")
            } }
            panel.addView(gallery, LinearLayout.LayoutParams(-1, dp(48)))
            val dialog = appDialog().setTitle("Save & Export").setView(toolDialogScroll(panel, 0.80f)).setNegativeButton("Done", null).create()
            exportResultDialog = dialog
            var displayedUri: String? = null
            var selectorEntries = emptyList<String>()
            var bindingSelector = false
            fun render() {
                val item = exportResults.selected ?: return
                val items = exportResults.items
                fileCount.visibility = if (items.size > 1) View.VISIBLE else View.GONE
                selector.visibility = if (items.size > 1) View.VISIBLE else View.GONE
                fileCount.text = "${items.size} files created • Choose a file"
                val labels = items.map { it.fileName }
                if (labels != selectorEntries) {
                    bindingSelector = true
                    selectorEntries = labels
                    selector.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                    selector.setSelection(exportResults.selectedIndex, false)
                    bindingSelector = false
                }
                name.text = item.fileName
                fileTypeLabel.text = ExportResultSession.typeLabel(item.mime)
                location.text = if (privateDocumentFile(Uri.parse(item.uri)) != null) "Saved inside DocNexa" else "Saved file"
                openButton.text = if (item.mime == "application/pdf") "Open PDF" else "Open file"
                gallery.visibility = if (item.mime.startsWith("image/") && Build.VERSION.SDK_INT >= 29) View.VISIBLE else View.GONE
                advancedPdfRow.visibility = if (item.mime == "application/pdf") View.VISIBLE else View.GONE
                inspectPdfButton.visibility = if (item.mime == "application/pdf") View.VISIBLE else View.GONE
                batchCheckButton.visibility = if (exportResults.items.count { it.mime == "application/pdf" } > 1) View.VISIBLE else View.GONE
                batchCompressButton.visibility = batchCheckButton.visibility
                ocrResult.visibility = if (item.mime.startsWith("image/") || item.mime == "application/pdf") View.VISIBLE else View.GONE
                if (displayedUri == item.uri) return
                displayedUri = item.uri
                val token = resultPreviewGeneration.incrementAndGet()
                preview.setImageDrawable(null)
                fileIcon.visibility = View.VISIBLE
                fileIcon.text = when {
                    item.mime == "application/pdf" -> "PDF"
                    item.mime.startsWith("image/") -> "IMG"
                    item.mime.contains("word") -> "DOCX"
                    item.mime.contains("zip") -> "ZIP"
                    else -> "FILE"
                }
                metadata.text = "Reading file details…"
                statusText.text = "Original file ready. Choose local storage or Google Drive."
                if (pagePreviewExecutor.isShutdown) return
                pagePreviewExecutor.execute {
                    if (resultPreviewGeneration.get() != token || isDestroyed) return@execute
                    var thumbnail: Bitmap? = null
                    var pages: Int? = null
                    val source = Uri.parse(item.uri)
                    var size: Long? = null
                    try {
                        contentResolver.query(source, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            val column = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) size = cursor.getLong(column).takeIf { it >= 0 }
                        }
                    } catch (_: Exception) { }
                    try {
                        if (item.mime == "application/pdf") {
                            contentResolver.openFileDescriptor(source, "r")?.use { fd ->
                                PdfRenderer(fd).use { renderer ->
                                    pages = renderer.pageCount
                                    if (renderer.pageCount > 0) renderer.openPage(0).use { page ->
                                        val scale = minOf(360f / page.width, 480f / page.height)
                                        thumbnail = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888).also { bitmap ->
                                            Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        }
                                    }
                                }
                            }
                        } else if (item.mime.startsWith("image/")) {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                            var sample = 1
                            while (bounds.outWidth / sample > 360 || bounds.outHeight / sample > 480) sample *= 2
                            contentResolver.openInputStream(source)?.use {
                                thumbnail = BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                            }
                        }
                    } catch (_: Exception) { }
                    val details = listOfNotNull(size?.let { formatFileSize(it) }, pages?.let { "$it ${if (it == 1) "page" else "pages"}" }).joinToString(" • ").ifBlank { "Preview not available" }
                    runOnUiThread {
                        if (dialog.isShowing && resultPreviewGeneration.get() == token) {
                            metadata.text = details
                            if (thumbnail != null) { preview.setImageBitmap(thumbnail); fileIcon.visibility = View.GONE }
                        } else thumbnail?.recycle()
                    }
                }
            }
            selector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!bindingSelector && position == selector.selectedItemPosition && position in exportResults.items.indices && position != exportResults.selectedIndex) {
                        exportResults.select(position); render()
                    }
                }
            }
            refreshExportResult = { render() }
            var disposeResultAd: () -> Unit = {}
            dialog.setOnDismissListener {
                disposeResultAd()
                resultPreviewGeneration.incrementAndGet()
                if (exportResultDialog === dialog) {
                    exportResultDialog = null; refreshExportResult = null; exportStatusView = null; exportResults.clear()
                }
            }
            dialog.show()
            // Request only once for the result session, after the host is attached.
            disposeResultAd = addNativeAdCard(resultAdHost)
            render()
        }
    }

    private fun saveResultImageToGallery(item: ExportResultItem) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { saveResultAs(item); return }
        if (!resultCopyBusy.compareAndSet(false, true)) { updateResultStatus("Please wait for the current export."); return }
        updateResultStatus("Saving to Gallery…")
        Thread {
            var destination: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, item.fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, item.mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DocNexa")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val target = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create Gallery image.")
                destination = target
                (contentResolver.openInputStream(Uri.parse(item.uri)) ?: error("Could not read image.")).use { input ->
                    (contentResolver.openOutputStream(target) ?: error("Could not write image.")).use { output -> input.copyTo(output) }
                }
                contentResolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                runOnUiThread { if (!isFinishing && !isDestroyed) updateResultStatus("Saved to Pictures/DocNexa.") }
            } catch (e: Exception) {
                destination?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) { } }
                runOnUiThread { if (!isFinishing && !isDestroyed) updateResultStatus(e.localizedMessage ?: "Could not save image.") }
            } finally { resultCopyBusy.set(false) }
        }.start()
    }

    private fun saveDocumentBytes(bytes: ByteArray, prefix: String, mime: String, extension: String): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "${prefix}_${stamp}.${extension}"
        val uri = saveBytesPrivately(bytes, name) ?: return null
        showExportSuccess(uri, uri.lastPathSegment ?: name, mime)
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
        showPremiumDialog(appDialog()
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
        showPdfImageExportOptions(uri, false)
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
        showPremiumDialog(appDialog().setTitle("PDF To ZIP • Create Archive")
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
                                Toast.makeText(this, "$archiveName.zip saved in DocNexa app storage", Toast.LENGTH_LONG).show()
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
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))}
        popupHeader(panel,"DOCNEXA • QR TOOLS","Scan a QR code","Use your camera or select an existing image.")
        val dialog=appDialog().setTitle("QR Scanner").setView(toolDialogScroll(panel,0.68f)).setNegativeButton("Close",null).create()
        addPopupOption(panel,"camera","Live camera scanner","Scan a QR code using your camera",true){
            dialog.dismiss()
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)openLiveQrCamera() else qrCameraPermission.launch(Manifest.permission.CAMERA)
        }
        addPopupOption(panel,"gallery","Scan from gallery","Choose an image containing a QR code"){
            dialog.dismiss();qrImagePicker.launch("image/*")
        }
        dialog.show()
    }

    private fun openLiveQrCamera() {
        qrAlreadyRead.set(false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(0xFF101216.toInt()) }
        val preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("Keep the QR code inside the camera frame. It scans automatically.", 13f, 0xFFFFFFFF.toInt()).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
        val close = nativeButton(this, ButtonRole.Secondary).apply { text = "Close scanner"; setOnClickListener { closeQrCamera(); buildScreen() } }
        root.addView(close, LinearLayout.LayoutParams(-1, dp(48)))
        setScreenContent(root)
        closeQrCamera()
        val cameraGeneration = qrCameraGeneration
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing || isDestroyed || cameraGeneration != qrCameraGeneration) return@addListener
            try {
                val provider = providerFuture.get()
                qrCameraProvider = provider
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val scanner = BarcodeScanning.getClient()
                qrScannerClient = scanner
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(cameraExecutor) { proxy -> analyzeQrFrame(proxy, scanner, cameraGeneration) }
                provider.unbindAll()
                val camera = when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> error("No camera found. Use QR scanning from an image instead.")
                }
                provider.bindToLifecycle(this, camera, cameraPreview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, e.localizedMessage ?: "Could not start camera", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
    private fun analyzeQrFrame(proxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner, generation: Int) {
        val media = proxy.image
        if (media == null || qrAlreadyRead.get() || generation != qrCameraGeneration || isDestroyed) { proxy.close(); return }
        try {
            val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { codes ->
                    if (generation != qrCameraGeneration || isFinishing || isDestroyed) return@addOnSuccessListener
                    val value = codes.mapNotNull { it.rawValue }.distinct().joinToString("\n\n")
                    if (value.isNotBlank() && qrAlreadyRead.compareAndSet(false, true)) {
                        closeQrCamera()
                        showQrResult(value)
                    }
                }
                .addOnCompleteListener { proxy.close() }
        } catch (_: Exception) { proxy.close() }
    }

    private fun showQrResult(value: String) {
        appDialog().setTitle("QR Scanner • Result")
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
            appDialog().setTitle("Favourite").setMessage("No favourite PDFs yet. Open a document menu and choose Add/Remove Favourite.")
                .setPositiveButton("OK", null).show()
            return
        }
        appDialog().setTitle("Favourite documents")
            .setPremiumItems(docs.map { it.first }.toTypedArray()) { _, index -> openPdf(docs[index].second) }
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
        appDialog().setTitle("Create QR Code").setView(inputPanel)
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
                        appDialog().setTitle("QR Code Ready").setView(preview)
                            .setMessage("Your QR code is ready. Download it to your phone or share it directly.")
                            .setPositiveButton("Download") { _, _ ->
                                val saved = saveDocumentBytes(bytes, "DocNexa_QR", "image/png", "png")
                                Toast.makeText(
                                    this,
                                    if (saved != null) "QR PNG downloaded to DocNexa app storage" else "Could not save QR image",
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
        appDialog()
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
                val up = nativeButton(this, ButtonRole.Secondary).apply {
                    text = "↑"; isAllCaps = false; textSize = 15f; minWidth = dp(38); minHeight = dp(48)
                    isEnabled = index > 0
                    setOnClickListener {
                        java.util.Collections.swap(orderedUris, index, index - 1)
                        refreshRows()
                    }
                }
                val down = nativeButton(this, ButtonRole.Secondary).apply {
                    text = "↓"; isAllCaps = false; textSize = 15f; minWidth = dp(38); minHeight = dp(48)
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
        showPremiumDialog(appDialog().setTitle("Merge PDFs • Preview")
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
                        showPremiumDialog(appDialog()
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
                    range.setText("1")
                    addVisualPageSelector(panel, uri, pageCount, range)
                    panel.addView(gap(5))
                    panel.addView(label("Enter pages to extract. The remaining pages are saved as a second PDF.", 11f, 0xFF718096.toInt()))
                    showPremiumDialog(appDialog().setTitle("DocNexa • Smart PDF Split")
                        .setView(toolDialogScroll(panel))
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Split & Save") { _, _ ->
                            val selected = parsePageRanges(range.text.toString(), pageCount)
                            if (selected.isEmpty()) {
                                Toast.makeText(this, "Enter a valid page range from 1 to $pageCount", Toast.LENGTH_LONG).show()
                            } else splitPdfPages(uri, selected)
                        }).also { bindPageSelectionAction(it, range, pageCount) }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Could not inspect PDF", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun parsePageRanges(value: String, total: Int): List<Int> =
        runCatching { PdfPagePlan.selection(value, total).sorted() }.getOrDefault(emptyList())

    private fun importPdfPageWithResources(output: PDDocument, original: com.tom_roush.pdfbox.pdmodel.PDPage): com.tom_roush.pdfbox.pdmodel.PDPage =
        output.importPage(original).apply {
            resources = original.resources
            mediaBox = original.mediaBox
            cropBox = original.cropBox
            rotation = original.rotation
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
                        if (i in selectedPages) importPdfPageWithResources(picked, source.getPage(i))
                        else importPdfPageWithResources(remaining, source.getPage(i))
                    }
                    val firstBytes = ByteArrayOutputStream().also { picked.save(it) }.toByteArray()
                    val savedA = savePdfBytes(firstBytes, "Selected_Pages") ?: error("Could not save the selected pages.")
                    val savedB = if (remaining.numberOfPages > 0) {
                        val secondBytes = ByteArrayOutputStream().also { remaining.save(it) }.toByteArray()
                        savePdfBytes(secondBytes, "Remaining_Pages") ?: error("Selected pages were saved, but the remaining pages could not be saved.")
                    } else null
                    runOnUiThread {
                        status.text = "Custom page split complete"
                        recent.text = if (savedB != null) "Saved selected pages and remaining pages" else "Saved extracted pages as a separate PDF"
                        val message = if (savedB != null) "Selected pages and remaining pages saved inside DocNexa" else "All selected pages saved as a PDF inside DocNexa"
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                } finally { picked.close(); remaining.close(); source.close() }
            } catch (e: Exception) {
                runOnUiThread { status.text = "PDF split failed"; Toast.makeText(this, e.localizedMessage ?: "Could not split PDF", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }


    private fun saveCloudPdf(fileName: String, bytes: ByteArray): Uri? =
        saveBytesPrivately(bytes, fileName.substringAfterLast('/').ifBlank { "Cloud_Document.pdf" })

    private fun savePdfBytes(bytes: ByteArray, prefix: String): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "${prefix}_${stamp}.pdf"
        val uri = saveBytesPrivately(bytes, name) ?: return null
        showExportSuccess(uri, uri.lastPathSegment ?: name, "application/pdf")
        return uri
    }

    private fun startDocumentScan() {
        if(scanExportBusy.get()){
            Toast.makeText(this,"A scan is already being saved.",Toast.LENGTH_SHORT).show()
            return
        }
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))}
        popupHeader(panel,"DOCNEXA • SMART SCAN","High-quality document scan","Capture, crop and preserve clear original pages.")
        val dialog=appDialog().setTitle("Scan style").setView(toolDialogScroll(panel,0.74f)).setNegativeButton("Cancel",null).create()
        addPopupOption(panel,"camera","Natural Color","Preserve colors and fine detail; best for photos and receipts.",true){
            dialog.dismiss();launchDocumentScan(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
        }
        addPopupOption(panel,"✦","Enhanced Document","Google scanner cleanup for printed documents and unwanted marks."){
            dialog.dismiss();launchDocumentScan(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        }
        addPopupOption(panel,"◐","Scan with Filters","Choose color, grayscale or other available scanner filters."){
            dialog.dismiss();launchDocumentScan(GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER)
        }
        panel.addView(label("Original JPEG pages are saved separately. A4 PDF export fits the full page without cropping. Quality depends on the camera and source; use good light and review focus before saving.",12f,0xFF52627A.toInt()))
        dialog.show()
    }

    private fun launchDocumentScan(scannerMode: Int) {
        status.text = "Opening scanner…"
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
            )
            .setScannerMode(scannerMode)
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
        addNativeAdCard(panel)
        showPremiumDialog(appDialog().setTitle("DocNexa • ID Photo Studio")
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
            row.addView(nativeButton(this, ButtonRole.Secondary).apply {
                text = "Crop"
                isAllCaps = false
                setOnClickListener {
                    val cropView = ImageCropView(this@MainActivity, originals[index])
                    val cropPanel = FrameLayout(this@MainActivity).apply {
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        addView(cropView, FrameLayout.LayoutParams(-1, dp(380)))
                    }
                    val dialog = appDialog().setTitle("Crop ${if (index == 0) "Front" else "Back"}")
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
        addNativeAdCard(panel)
        showPremiumDialog(appDialog().setTitle("ID Card Preview").setView(scroll)
            .setPositiveButton("Create ID PDF") { _, _ -> createPdfFromBitmaps(originals, "ID_Card") }
            .setNeutralButton("Share Images") { _, _ ->
                val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Share ID images"))
            }
            .setNegativeButton("Cancel", null))
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
            addNativeAdCard(panel)
            appDialog().setTitle("Passport Photo Maker")
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
                    showExportSuccess(jpgUri, jpgValues.getAsString(MediaStore.Images.Media.DISPLAY_NAME) ?: "Passport_Photos.jpg", "image/jpeg")
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.localizedMessage ?: "Passport photo sheet failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun decodeImageSafely(uri: Uri, maxEdge: Int = 2400, maxPixels: Long = 6_000_000): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (contentResolver.openInputStream(uri) ?: error("Cannot open this image.")).use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageDecodePlan.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge, maxPixels)
        }
        val bitmap = (contentResolver.openInputStream(uri) ?: error("Cannot open this image.")).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Cannot decode this image.")
        val orientation = try {
            contentResolver.openInputStream(uri)?.use {
                androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                )
            } ?: 1
        } catch (_: Exception) { 1 }
        val matrix = android.graphics.Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> { setRotate(180f); postScale(-1f, 1f) }
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } catch (e: Exception) { bitmap.recycle(); throw e }
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
                actions.getChildAt(1)?.isEnabled = index > 0
                actions.getChildAt(2)?.isEnabled = index < rows.lastIndex
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
                try { setImageBitmap(decodeImageSafely(uri, 360)) }
                catch (_: Exception) { setImageResource(android.R.drawable.ic_menu_report_image) }
                layoutParams = LinearLayout.LayoutParams(dp(170), dp(164))
            }
            row.addView(thumb)
            row.addView(label("Page ${index + 1}", 13f, 0xFF263957.toInt(), true))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            fun action(caption: String, callback: () -> Unit) = nativeButton(this@MainActivity, ButtonRole.Secondary).apply {
                text = caption; isAllCaps = false; textSize = 10f; setPadding(dp(3), 0, dp(3), 0)
                setOnClickListener { callback() }
            }
            actions.addView(action("Preview") {
                val p = rows.indexOf(row)
                if (p >= 0) {
                    try {
                        val source = croppedBitmaps[p] ?: decodeImageSafely(orderedUris[p]) ?: throw IllegalStateException("Preview unavailable")
                        val large = ImageView(this@MainActivity).apply {
                            setImageBitmap(source)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setBackgroundColor(android.graphics.Color.WHITE)
                            layoutParams = LinearLayout.LayoutParams(-1, dp(440))
                        }
                        showPremiumDialog(appDialog()
                            .setTitle("Page preview • ${p + 1}")
                            .setView(large).setPositiveButton("Done", null))
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, e.localizedMessage ?: "Preview unavailable", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            actions.addView(action("↑") { val p = rows.indexOf(row); movePage(p, p - 1) })
            actions.addView(action("↓") { val p = rows.indexOf(row); movePage(p, p + 1) })
            actions.addView(action("Rotate") {
                val p = rows.indexOf(row)
                try {
                    val source = croppedBitmaps[p] ?: decodeImageSafely(orderedUris[p])
                        ?: throw IllegalStateException("Could not read image")
                    val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                    if (source !== rotated && croppedBitmaps[p] == null) source.recycle()
                    croppedBitmaps[p] = rotated
                    thumb.setImageBitmap(rotated)
                } catch (e: Exception) { Toast.makeText(this@MainActivity, e.localizedMessage ?: "Rotate failed", Toast.LENGTH_LONG).show() }
            })
            actions.addView(action("Crop") {
                val p = rows.indexOf(row)
                try {
                    val original = croppedBitmaps[p] ?: decodeImageSafely(orderedUris[p])
                        ?: throw IllegalStateException("Could not open image")
                    val cropView = ImageCropView(this@MainActivity, original)
                    val panel = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
                        addView(cropView, LinearLayout.LayoutParams(-1, dp(330)))
                        addView(label("Drag the corner handles to select the crop area.", 12f, 0xFF53627A.toInt()))
                    }
                    val cropDialog = appDialog().setTitle("Crop page ${p + 1}").setView(panel)
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
            actions.addView(action("Duplicate") {
                val p = rows.indexOf(row)
                if (p >= 0) {
                    if (orderedUris.size >= 100) { Toast.makeText(this@MainActivity, "Maximum 100 pages", Toast.LENGTH_SHORT).show(); return@action }
                    orderedUris.add(p + 1, orderedUris[p])
                    croppedBitmaps.add(p + 1, croppedBitmaps[p]?.copy(Bitmap.Config.ARGB_8888, true))
                    val duplicateRow = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        background = rounded(0xFFF7F9FD.toInt(), 14, 0xFFE2EAF5.toInt())
                        layoutParams = LinearLayout.LayoutParams(-1, dp(278)).apply { bottomMargin = dp(12) }
                    }
                    val duplicateThumb = ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        background = rounded(0xFFFFFFFF.toInt(), 10, 0xFFDCE7FA.toInt())
                        clipToOutline = true
                        try {
                            val preview = croppedBitmaps[p + 1] ?: decodeImageSafely(orderedUris[p + 1], 360)
                            setImageBitmap(preview)
                        } catch (_: Exception) { setImageResource(android.R.drawable.ic_menu_report_image) }
                        layoutParams = LinearLayout.LayoutParams(dp(170), dp(164))
                    }
                    duplicateRow.addView(duplicateThumb)
                    duplicateRow.addView(label("Duplicated page", 13f, 0xFF263957.toInt(), true))
                    val duplicateActions = LinearLayout(this@MainActivity).apply {
                        gravity = Gravity.CENTER
                        addView(nativeButton(this@MainActivity, ButtonRole.Danger).apply {
                            text = "Remove duplicate"; isAllCaps = false; textSize = 10f
                            setOnClickListener {
                                val at = rows.indexOf(duplicateRow)
                                if (at >= 0) {
                                    orderedUris.removeAt(at)
                                    croppedBitmaps.removeAt(at)
                                    rows.removeAt(at)
                                    previewList.removeView(duplicateRow)
                                    refreshRows()
                                }
                            }
                        })
                    }
                    duplicateActions.addView(nativeButton(this@MainActivity, ButtonRole.Secondary).apply {
                            text = "↑"; isAllCaps = false
                            setOnClickListener { val at = rows.indexOf(duplicateRow); movePage(at, at - 1) }
                        })
                        duplicateActions.addView(nativeButton(this@MainActivity, ButtonRole.Secondary).apply {
                            text = "↓"; isAllCaps = false
                            setOnClickListener { val at = rows.indexOf(duplicateRow); movePage(at, at + 1) }
                        })
                    duplicateRow.addView(duplicateActions)
                    rows.add(p + 1, duplicateRow)
                    previewList.addView(duplicateRow, p + 3)
                    refreshRows()
                }
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
        previewList.addView(label("Tip: Preview pages, change order, rotate, crop, duplicate or remove images. Your original gallery photos are not modified.", 11f, 0xFF718096.toInt()))
        addNativeAdCard(previewList)
        val scroll = ScrollView(this).apply { addView(previewList); isFillViewport = false }
        val dialog = appDialog().setTitle("Image to PDF • Page manager").setView(scroll)
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
        val quality = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Standard • smaller file", "High • print quality (recommended)", "Maximum • preserve more detail"))
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
            addView(gap(7)); addView(label("Image quality", 11f, 0xFF263957.toInt(), true)); addView(quality)
        }
        showPremiumDialog(appDialog().setTitle("Image to PDF • Export settings")
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
                createPdfFromImages(uris, croppedBitmaps, base, width, height, margins, fit.selectedItemPosition == 1, autoFit, listOf(1600, 4200, 6000)[quality.selectedItemPosition.coerceIn(0, 2)])
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
        autoFit: Boolean = false,
        maxImageDimension: Int = 4200
    ) {
        status.text = "Creating high-quality PDF from ${uris.size} image(s)…"
        Thread {
            try {
                require(uris.isNotEmpty() && uris.size <= 100) { "Select 1–100 images" }
                val directory = privateDocumentsDir()
                val limit = maxImageDimension.coerceIn(800, 6000)
                val pixels = if (limit <= 1600) 3_000_000L else if (limit <= 4200) 12_000_000L else 16_000_000L
                val name = "${fileName}_${System.currentTimeMillis()}.pdf"
                val target = PrivateDocumentStore.save(directory, name) { stream ->
                    PDDocument(MemoryUsageSetting.setupMixed(16L * 1024 * 1024)).use { doc ->
                        uris.forEachIndexed { index, uri ->
                            val cached = croppedBitmaps.getOrNull(index)
                            require(cached == null || !cached.isRecycled) { "This image preview has expired. Select it again." }
                            val image = qualityPdfImage(doc, uri, cached, limit, pixels, 0)
                            val landscape = autoFit && image.width > image.height
                            val w = if (landscape) pageHeight else pageWidth
                            val h = if (landscape) pageWidth else pageHeight
                            addQualityImagePage(doc, image, w, h, margin.toFloat(), fillPage)
                        }
                        doc.save(stream)
                    }
                }
                val output = privateDocumentUri(target)
                assignPdf(output)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        lastPdf = output
                        notifyDocumentReady(output)
                        buildScreen()
                        status.text = "High-quality PDF created • ${uris.size} page(s)"
                        showExportSuccess(output, target.name, "application/pdf")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "PDF creation failed"
                    Toast.makeText(this, e.localizedMessage ?: "Unable to create PDF", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Keep source JPEG bytes when no rotation, crop, filter or downsampling is needed. */
    private fun qualityPdfImage(doc: PDDocument, uri: Uri, cached: Bitmap?, maxEdge: Int, maxPixels: Long, filter: Int): PDImageXObject {
        if (cached == null && filter == 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val orientation = runCatching {
                contentResolver.openInputStream(uri)?.use {
                    androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)
                } ?: 1
            }.getOrDefault(0)
            if (bounds.outMimeType == "image/jpeg" && orientation == 1 &&
                bounds.outWidth > 0 && bounds.outHeight > 0 &&
                maxOf(bounds.outWidth, bounds.outHeight) <= maxEdge &&
                bounds.outWidth.toLong() * bounds.outHeight <= maxPixels) {
                return contentResolver.openInputStream(uri)?.use {
                    com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromStream(doc, it)
                } ?: error("Cannot read original JPEG")
            }
        }
        val source = cached ?: decodeImageSafely(uri, maxEdge, maxPixels)
        var filtered: Bitmap? = null
        try {
            val image = if (filter == 0) source else filterScannedPage(source, filter).also { filtered = it }
            return com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, image)
        } finally {
            filtered?.let { if (it !== source) it.recycle() }
            if (cached == null) source.recycle()
        }
    }

    private fun addQualityImagePage(doc: PDDocument, image: PDImageXObject, width: Int, height: Int, margin: Float, fill: Boolean) {
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(width.toFloat(), height.toFloat()))
        doc.addPage(page)
        val availableW = (width - 2 * margin).coerceAtLeast(1f)
        val availableH = (height - 2 * margin).coerceAtLeast(1f)
        val sx = availableW / image.width
        val sy = availableH / image.height
        val scale = if (fill) maxOf(sx, sy) else minOf(sx, sy)
        val w = image.width * scale
        val h = image.height * scale
        PDPageContentStream(doc, page).use { content ->
            if (fill) {
                content.addRect(margin, margin, availableW, availableH)
                content.clip()
            }
            content.drawImage(image, (width - w) / 2f, (height - h) / 2f, w, h)
        }
    }

    private fun fitRect(w: Int, h: Int, maxW: Int, maxH: Int, left: Int, top: Int): Rect {
        val scale = minOf(maxW.toFloat() / w, maxH.toFloat() / h)
        val outW = (w * scale).toInt().coerceAtLeast(1)
        val outH = (h * scale).toInt().coerceAtLeast(1)
        val x = left + (maxW - outW) / 2
        val y = top + (maxH - outH) / 2
        return Rect(x, y, x + outW, y + outH)
    }

    private fun savePdf(document: PdfDocument): Uri? {
        val name = "Document_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
        return try {
            val bytes = ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray()
            saveBytesPrivately(bytes, name)?.also { uri ->
                showExportSuccess(uri, uri.lastPathSegment ?: name, "application/pdf")
            }
        } catch (_: Exception) { null }
    }

    private fun showScannerFilterSettings() {
        val names = arrayOf("Original color", "Grayscale", "High contrast B&W", "Enhanced color")
        var selected = prefs.getInt("scanner_filter_mode", 0).coerceIn(0, names.lastIndex)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        panel.addView(label("Choose a filter for new scanned PDFs", 15f, 0xFF172B4D.toInt(), true))
        panel.addView(gap(8))
        panel.addView(label("Preview below is a sample. The chosen filter is applied when the next scan is converted into a print-ready PDF.", 11f, 0xFF64748B.toInt()))
        val sample = Bitmap.createBitmap(480, 260, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sample)
        canvas.drawColor(android.graphics.Color.WHITE)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        ink.color = android.graphics.Color.rgb(28, 65, 130)
        ink.textSize = 36f
        ink.isFakeBoldText = true
        canvas.drawText("DocNexa Scan", 24f, 68f, ink)
        ink.isFakeBoldText = false
        ink.textSize = 23f
        ink.color = android.graphics.Color.rgb(70, 80, 90)
        canvas.drawText("Sample document preview", 24f, 118f, ink)
        ink.color = android.graphics.Color.rgb(235, 165, 60)
        canvas.drawRect(24f, 145f, 210f, 214f, ink)
        ink.color = android.graphics.Color.rgb(65, 170, 125)
        canvas.drawRect(225f, 145f, 450f, 214f, ink)
        val preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        panel.addView(preview, LinearLayout.LayoutParams(-1, dp(190)))
        val current = label("", 13f, 0xFF2563EB.toInt(), true)
        panel.addView(current)
        fun updatePreview() {
            val filtered = filterScannedPage(sample, selected)
            preview.setImageBitmap(filtered)
            current.text = "Preview: ${names[selected]}"
        }
        updatePreview()
        val choices = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        names.forEachIndexed { index, name ->
            choices.addView(android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = name
                isChecked = index == selected
                setOnClickListener {
                    selected = index
                    updatePreview()
                }
            })
        }
        panel.addView(choices)
        val dialog = appDialog().setTitle("Scanner Filters • Preview")
            .setView(toolDialogScroll(panel))
            .setPositiveButton("Apply filter") { _, _ ->
                prefs.edit().putInt("scanner_filter_mode", selected).apply()
                Toast.makeText(this, "Saved: ${names[selected]}. Applies to new scans.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener { sample.recycle() }
        dialog.show()
    }

    private fun filterScannedPage(source: Bitmap, mode: Int): Bitmap {
        if (mode == 0) return source
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = android.graphics.ColorMatrix()
        when (mode) {
            1 -> matrix.setSaturation(0f)
            2 -> {
                matrix.setSaturation(0f)
                val contrast = 2.3f
                val offset = 128f * (1f - contrast)
                val contrastMatrix = android.graphics.ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, offset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                ))
                matrix.postConcat(contrastMatrix)
            }
            3 -> {
                matrix.setSaturation(1.35f)
                val contrast = 1.18f
                val offset = 128f * (1f - contrast)
                matrix.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, offset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
        }
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        Canvas(output).drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun verifyScanPdf(file:java.io.File,expectedPages:Int):Uri {
        try {
            val fd=android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                PdfRenderer(fd).use{renderer->
                    require(renderer.pageCount>0 && (expectedPages<=0 || renderer.pageCount==expectedPages)){"Incomplete scanned PDF"}
                }
            }catch(e:Exception){runCatching{fd.close()};throw e}
            return privateDocumentUri(file)
        }catch(e:Exception){file.delete();throw e}
    }

    private fun createPrintReadyScanPdf(pageUris:List<Uri>,directory:java.io.File,mode:Int,maxEdge:Int,maxPixels:Long,stamp:Long):Uri {
        require(pageUris.isNotEmpty() && pageUris.size<=100){"Select 1–100 scanned pages"}
        val name=if(maxEdge<=2400)"DocNexa_Compatible_"+stamp+".pdf" else "DocNexa_High_Quality_"+stamp+".pdf"
        val target=PrivateDocumentStore.save(directory,name){stream->
            PDDocument(MemoryUsageSetting.setupMixed(16L*1024*1024).setTempDir(cacheDir)).use{doc->
                pageUris.forEach{uri->
                    // Embed eligible original JPEG bytes directly. Decode only when orientation,
                    // filters or size require it; cap decoded pixels to the current memory budget.
                    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                    contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
                    val orientation=runCatching{contentResolver.openInputStream(uri)?.use{
                        androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1)
                    }?:1}.getOrDefault(0)
                    val direct=mode==0 && orientation==1 && bounds.outMimeType=="image/jpeg" &&
                        bounds.outWidth>0 && bounds.outHeight>0 && maxOf(bounds.outWidth,bounds.outHeight)<=maxEdge &&
                        bounds.outWidth.toLong()*bounds.outHeight<=maxPixels
                    val image=if(direct){
                        contentResolver.openInputStream(uri)?.use{com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromStream(doc,it)}?:error("Cannot read scanned JPEG")
                    }else qualityPdfImage(doc,uri,null,maxEdge,exportPixelBudget(maxPixels,if(mode==0)16L else 24L),mode)
                    val landscape=image.width>image.height
                    addQualityImagePage(doc,image,if(landscape)842 else 595,if(landscape)595 else 842,18f,false)
                }
                doc.save(stream)
            }
        }
        return verifyScanPdf(target,pageUris.size)
    }

    private fun copyScannedPdf(source:Uri,directory:java.io.File,expectedPages:Int,stamp:Long):Uri {
        val file=PrivateDocumentStore.save(directory,"DocNexa_Original_"+stamp+".pdf"){stream->
            (contentResolver.openInputStream(source)?:error("Cannot read scanned PDF")).use{input->require(input.copyTo(stream)>0){"Empty scanned PDF"}}
        }
        return verifyScanPdf(file,expectedPages)
    }

    private fun createSearchableOcrPdf(uri: Uri) {
        if (!requirePremiumTool("Searchable OCR PDF")) return
        appDialog().setTitle("Searchable OCR PDF")
            .setMessage("Recognize printed Latin-script text on each PDF page and add an invisible searchable text layer. This may take time. Original PDF remains unchanged. Complex layouts and other scripts may not be recognized accurately.")
            .setPositiveButton("Start OCR") { _, _ ->
                status.text = "Creating searchable PDF…"
                Thread {
                    var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
                    try {
                        val ocrClient = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        recognizer = ocrClient
                        val source = contentResolver.openFileDescriptor(uri, "r")
                            ?: throw java.io.IOException("Cannot open PDF")
                        val texts = mutableListOf<List<Pair<String, android.graphics.Rect>>>()
                        PdfRenderer(source).use { renderer ->
                            if (renderer.pageCount > 100) throw java.io.IOException("Maximum 100 pages per OCR job")
                            for (index in 0 until renderer.pageCount) {
                                renderer.openPage(index).use { page ->
                                    val scale = minOf(2f, 1800f / maxOf(page.width, page.height).toFloat())
                                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    try {
                                        bitmap.eraseColor(android.graphics.Color.WHITE)
                                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        val result = com.google.android.gms.tasks.Tasks.await(
                                            ocrClient.process(InputImage.fromBitmap(bitmap, 0)))
                                        val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                            val box = line.boundingBox
                                            if (box == null || line.text.isBlank()) null
                                            else line.text to android.graphics.Rect(
                                                (box.left / scale).toInt(), (box.top / scale).toInt(),
                                                (box.right / scale).toInt(), (box.bottom / scale).toInt())
                                        }
                                        texts.add(lines)
                                    } finally { bitmap.recycle() }
                                }
                            }
                        }
                        val input = contentResolver.openInputStream(uri)
                            ?: throw java.io.IOException("Cannot read PDF")
                        val outputBytes = input.use { stream ->
                            PDDocument.load(stream).use { doc ->
                                doc.pages.forEachIndexed { index, page ->
                                    val lines = texts.getOrNull(index).orEmpty()
                                    if (lines.isNotEmpty()) {
                                        val pageHeight = page.mediaBox.height
                                        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                                            content.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER)
                                            lines.forEach { (line, box) ->
                                                val safe = line.replace(Regex("[^\\u0020-\\u007E]"), " ").trim()
                                                if (safe.isNotBlank()) {
                                                    val fontSize = box.height().toFloat().coerceIn(5f, 24f)
                                                    content.beginText()
                                                    content.setFont(PDType1Font.HELVETICA, fontSize)
                                                    content.newLineAtOffset(box.left.toFloat(), (pageHeight - box.bottom).coerceAtLeast(0f))
                                                    content.showText(safe.take(500))
                                                    content.endText()
                                                }
                                            }
                                        }
                                    }
                                }
                                ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                            }
                        }
                        val name = "Searchable_OCR_${System.currentTimeMillis()}.pdf"
                        val saved = saveBytesPrivately(outputBytes, name)
                            ?: throw java.io.IOException("Could not save searchable PDF")
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                status.text = "Searchable PDF created"
                                showExportSuccess(saved, name, "application/pdf")
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                status.text = "Searchable OCR failed"
                                appDialog().setTitle("OCR failed")
                                    .setMessage(e.localizedMessage ?: "Could not create searchable PDF")
                                    .setPositiveButton("OK", null).show()
                            }
                        }
                    } finally { recognizer?.close() }
                }.start()
            }.setNegativeButton("Cancel", null).show()
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
                    showPremiumDialog(appDialog()
                        .setTitle("DocNexa • Extracted Text")
                        .setView(textPanel)
                        .setPositiveButton("Copy text") { _, _ ->
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DocNexa OCR text", extracted))
                            Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show()
                        }
                        .setNeutralButton("Share text", null)
                        .setNegativeButton("Save TXT", null)
                    ).apply {
                        getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                            val name = "DocNexa_OCR_${System.currentTimeMillis()}.txt"
                            val saved = saveBytesPrivately(extracted.toByteArray(Charsets.UTF_8), name)
                            if (saved != null) {
                                showExportSuccess(saved, name, "text/plain")
                                dismiss()
                            } else {
                                Toast.makeText(this@MainActivity, "Could not save OCR text", Toast.LENGTH_LONG).show()
                            }
                        }
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

    private val documentViewerPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)openDocumentViewer(uri)
    }
    private var activeDocumentViewer:AlertDialog?=null
    private val viewerImportBusy=AtomicBoolean(false)
    private fun saveViewerLocal(uri:Uri,name:String){
        if(!viewerImportBusy.compareAndSet(false,true)){
            Toast.makeText(this,"A file is already being copied.",Toast.LENGTH_SHORT).show();return
        }
        val owner=signedInUserId();val folder=selectedFolder;val directory=privateDocumentsDir()
        Toast.makeText(this,"Saving a private copy…",Toast.LENGTH_SHORT).show()
        Thread{
            try{
                val target=PrivateDocumentStore.save(directory,name){out->
                    (contentResolver.openInputStream(uri)?:error("Cannot read file")).use{input->
                        val buffer=ByteArray(32768);var total=0L
                        while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=128L*1024*1024){"Private import supports up to 128 MB"};out.write(buffer,0,n)}
                    }
                }
                runOnUiThread{
                    if(!isFinishing&&!isDestroyed&&owner==signedInUserId()){
                        val local=privateDocumentUri(target)
                        if(target.extension.equals("pdf",true)){
                            folderAssignments[local.toString()]=folder
                            prefs.edit().putString(accountPrefKey("folder_assignments"),folderAssignmentSnapshot()).apply()
                        }
                        Toast.makeText(this,"Saved inside DocNexa",Toast.LENGTH_SHORT).show()
                        openDocumentViewer(local)
                    }
                }
            }catch(e:Exception){runOnUiThread{if(!isFinishing&&!isDestroyed&&owner==signedInUserId())Toast.makeText(this,e.message?:"Cannot save a private copy",Toast.LENGTH_LONG).show()}}
            finally{viewerImportBusy.set(false)}
        }.start()
    }
    private val viewerReadExecutor=Executors.newSingleThreadExecutor()
    private fun viewerName(uri:Uri):String=privateDocumentFile(uri)?.name ?: runCatching{
        contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}
    }.getOrNull()?.take(180) ?: "Document"
    private fun viewerMime(uri:Uri,name:String):String {
        val reported=runCatching{contentResolver.getType(uri)}.getOrNull().orEmpty()
        return DocumentViewerPlan.mime(name) ?: reported.ifBlank{"application/octet-stream"}
    }
    private fun viewerLocalFiles():List<java.io.File>{
        val trash=prefs.getStringSet("trash_uris",emptySet())?:emptySet()
        return privateDocumentsDir().listFiles().orEmpty().filter{it.isFile && !it.name.endsWith(".pending") && !trash.contains(privateDocumentUri(it).toString())}.sortedByDescending{it.lastModified()}
    }
    private fun viewerRemember(uri:Uri){
        val file=privateDocumentFile(uri)?:return
        val key=accountPrefKey("viewer_recent")
        val names=runCatching{org.json.JSONArray(prefs.getString(key,"[]"))}.getOrDefault(org.json.JSONArray())
        val ordered=(listOf(file.name)+(0 until names.length()).map{names.optString(it)}).distinct().take(40)
        prefs.edit().putString(key,org.json.JSONArray(ordered).toString()).apply()
    }
    private fun showDocumentViewerHub(){
        val panel=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(6),dp(12),dp(12))
        }
        popupHeader(panel,"DOCNEXA • DOCUMENT STUDIO","All Document Viewer","One place for documents, images and archives.")
        val dialog=appDialog().setTitle("Document Viewer").setView(toolDialogScroll(panel,0.78f)).setNegativeButton("Close",null).create()
        fun browse(mimes:Array<String>){
            try{
                documentViewerPicker.launch(mimes)
                dialog.dismiss()
            }catch(_:Exception){
                Toast.makeText(this@MainActivity,"No compatible file picker is available.",Toast.LENGTH_LONG).show()
            }
        }
        fun section(title:String,subtitle:String){
            panel.addView(gap(4))
            panel.addView(label(title,13f,0xFF172B4D.toInt(),true))
            panel.addView(label(subtitle,11f,0xFF52627A.toInt()))
            panel.addView(gap(7))
        }
        addPopupOption(panel,"document","Browse all files","Select from phone storage or a connected cloud provider.",true){browse(arrayOf("*/*"))}

        section("QUICK ACCESS","Your private DocNexa document library")
        data class LibraryShortcut(val symbol:String,val title:String,val mode:String)
        val shortcuts=listOf(
            LibraryShortcut("▤","My files","All"),
            LibraryShortcut("↻","Recent","Recent"),
            LibraryShortcut("♡","Favourites","Favourites")
        )
        val compact=resources.configuration.screenWidthDp>=360 && resources.configuration.fontScale<1.3f
        shortcuts.chunked(if(compact)3 else 1).forEach{group->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            group.forEach{shortcut->
                val tile=LinearLayout(this).apply{
                    orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
                    minimumHeight=dp(64);setPadding(dp(4),dp(8),dp(4),dp(8))
                    background=android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x223478F6),
                        rounded(0xFFF3F7FF.toInt(),14,0xFFDCE7F5.toInt()),null)
                    isClickable=true;isFocusable=true
                    contentDescription=shortcut.title
                    setOnClickListener{dialog.dismiss();showViewerLibrary(shortcut.mode)}
                }
                tile.addView(label(shortcut.symbol,20f,0xFF2563EB.toInt(),true).apply{
                    gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                tile.addView(label(shortcut.title,12f,0xFF172B4D.toInt(),true).apply{gravity=Gravity.CENTER})
                row.addView(tile,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(3),0,dp(3),dp(6))})
            }
            panel.addView(row)
        }

        section("BROWSE BY FORMAT","Choose the type of file you want to open")
        data class FormatCard(val symbol:String,val title:String,val extensions:String,val accent:Int,val tint:Int,val mimes:Array<String>)
        val categories=listOf(
            FormatCard("PDF","PDF documents","PDF",0xFFB42336.toInt(),0xFFFCE7EB.toInt(),arrayOf("application/pdf")),
            FormatCard("W","Word","DOC · DOCX",0xFF185ABD.toInt(),0xFFE5EEFF.toInt(),arrayOf("application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
            FormatCard("X","Excel","XLS · XLSX",0xFF16734A.toInt(),0xFFE2F4E9.toInt(),arrayOf("application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
            FormatCard("P","PowerPoint","PPT · PPTX",0xFFB84422.toInt(),0xFFFFEBE3.toInt(),arrayOf("application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.presentationml.presentation")),
            FormatCard("gallery","Images","JPG · PNG · more",0xFF6941B0.toInt(),0xFFF1E9FC.toInt(),arrayOf("image/*")),
            FormatCard("TXT","Text / CSV","TXT · CSV · RTF",0xFF087F91.toInt(),0xFFE1F4F6.toInt(),arrayOf("text/*","application/rtf")),
            FormatCard("ZIP","Archives","ZIP file listing",0xFF96600C.toInt(),0xFFFFF1D6.toInt(),arrayOf("application/zip","application/x-zip-compressed")),
            FormatCard("ALL","Other files","Any file type",0xFF475569.toInt(),0xFFE8EEF6.toInt(),arrayOf("*/*"))
        )
        categories.chunked(if(compact)2 else 1).forEach{pair->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP}
            pair.forEach{format->
                val tile=LinearLayout(this).apply{
                    orientation=LinearLayout.VERTICAL;gravity=Gravity.START
                    minimumHeight=dp(88);setPadding(dp(10),dp(9),dp(10),dp(9))
                    background=android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x223478F6),
                        rounded(0xFFF8FAFF.toInt(),15,0xFFDCE7F5.toInt()),null)
                    isClickable=true;isFocusable=true
                    contentDescription=format.title+", "+format.extensions
                    setOnClickListener{browse(format.mimes)}
                }
                val icon=FrameLayout(this).apply{
                    background=rounded(format.tint,10)
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    if(format.symbol=="gallery"){
                        addView(popupGlyph("gallery",format.accent),FrameLayout.LayoutParams(-1,-1))
                    }else{
                        addView(label(format.symbol,if(format.symbol.length>1)11f else 19f,format.accent,true).apply{
                            gravity=Gravity.CENTER
                        },FrameLayout.LayoutParams(-1,-1))
                    }
                }
                tile.addView(icon,LinearLayout.LayoutParams(dp(35),dp(35)))
                tile.addView(gap(4))
                tile.addView(label(format.title,13f,0xFF172B4D.toInt(),true))
                tile.addView(label(format.extensions,10f,0xFF52627A.toInt()))
                row.addView(tile,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(3),0,dp(3),dp(7))})
            }
            panel.addView(row)
        }
        val note=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(10),dp(12),dp(10))
            background=rounded(0xFFF1F6FF.toInt(),14,0xFFDCE7F5.toInt())
        }
        note.addView(label("Private & offline-friendly",12f,0xFF172B4D.toInt(),true))
        note.addView(label("Internet access is required to use DocNexa. Local document rendering stays on-device. Office previews show text and worksheet values; original layouts and unsupported formats need a compatible installed app.",11f,0xFF52627A.toInt()))
        panel.addView(gap(3));panel.addView(note)
        dialog.show()
    }

    private fun showViewerLibrary(mode:String){
        val files=viewerLocalFiles()
        val key=accountPrefKey("viewer_recent")
        val recent=runCatching{org.json.JSONArray(prefs.getString(key,"[]"))}.getOrDefault(org.json.JSONArray())
        val order=(0 until recent.length()).map{recent.optString(it)}
        val stars=prefs.getStringSet(accountPrefKey("viewer_favourites"),emptySet())?:emptySet()
        val available=when(mode){
            "Recent"->order.mapNotNull{name->files.firstOrNull{it.name==name}}
            "Favourites"->files.filter{stars.contains(it.name)}
            else->files
        }
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(8))}
        val search=EditText(this).apply{hint="Search file names";setSingleLine(true)}
        panel.addView(search);panel.addView(gap(8))
        val result=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        panel.addView(result)
        val dialog=appDialog().setTitle(mode+" files").setView(toolDialogScroll(panel,0.74f)).setNegativeButton("Close",null).create()
        fun refresh(){
            result.removeAllViews()
            val filtered=available.filter{it.name.contains(search.text.toString(),ignoreCase=true)}
            result.addView(label(filtered.size.toString()+" file(s)"+if(filtered.size>150)" • showing first 150" else "",12f,0xFF64748B.toInt()))
            if(filtered.isEmpty())result.addView(label("No matching saved files.",14f,0xFF52627A.toInt()))
            filtered.take(150).forEach{file->
                addPopupOption(result,file.extension.uppercase(Locale.ROOT).take(4),file.name,formatFileSize(file.length())){dialog.dismiss();openDocumentViewer(privateDocumentUri(file))}
            }
        }
        search.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){refresh()}
            override fun afterTextChanged(s:android.text.Editable?)=Unit
        })
        refresh();dialog.show()
    }

    private fun openViewerExternal(uri:Uri,mime:String){
        try{
            val view=Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply{clipData=android.content.ClipData.newRawUri("Document",uri)}
            val chooser=Intent.createChooser(view,"Open document").apply{
                putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,arrayOf(android.content.ComponentName(this@MainActivity,MainActivity::class.java)))
            }
            startActivity(chooser)
        }catch(_:Exception){Toast.makeText(this,"Install a compatible viewer for this format.",Toast.LENGTH_LONG).show()}
    }
    private fun shareViewerFile(uri:Uri,mime:String){
        try{
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri)
                .apply{clipData=android.content.ClipData.newRawUri("Document",uri)}.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share document"))
        }catch(_:Exception){Toast.makeText(this,"No compatible sharing app.",Toast.LENGTH_LONG).show()}
    }

    private fun readViewerText(uri:Uri):String {
        val bytes=contentResolver.openInputStream(uri)?.use{input->
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=2*1024*1024){"Text preview supports up to 2 MB"};out.write(buffer,0,n)}
            out.toByteArray()
        }?:error("Cannot open text")
        return DocumentViewerPlan.decodeText(bytes)
    }
    private fun readViewerArchive(uri:Uri):String {
        val temp=java.io.File.createTempFile("viewer_zip_",".tmp",cacheDir)
        try{
            contentResolver.openInputStream(uri)?.use{input->temp.outputStream().use{out->
                val buffer=ByteArray(32768);var total=0L
                while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=64L*1024*1024){"ZIP listing supports files up to 64 MB"};out.write(buffer,0,n)}
            }}?:error("Cannot read ZIP")
            return java.util.zip.ZipFile(temp).use{zip->
                val result=StringBuilder();val entries=zip.entries();var count=0
                while(entries.hasMoreElements()&&count<1000){val e=entries.nextElement();count++;result.append(if(e.isDirectory)"Folder  " else "File  ").append(e.name.take(240)).append('\n')}
                if(entries.hasMoreElements())result.append("\nOnly the first 1,000 entries are shown.")
                result.toString().ifBlank{"Empty archive"}
            }
        }finally{temp.delete()}
    }
    private fun openDocumentViewer(uri:Uri){
        if(uri.scheme!="content" || (uri.authority=="$packageName.fileprovider" && privateDocumentFile(uri)==null)){
            Toast.makeText(this,"This file is unavailable in the current account.",Toast.LENGTH_LONG).show();return
        }
        val owner=signedInUserId()
        val name=viewerName(uri)
        val mime=viewerMime(uri,name)
        val kind=DocumentViewerPlan.kind(name,mime)
        viewerRemember(uri)
        activeDocumentViewer?.dismiss()
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(8))}
        val detail=label(name,15f,0xFF172B4D.toInt(),true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.MIDDLE}
        panel.addView(detail)
        val state=label("Opening document…",12f,0xFF52627A.toInt())
        panel.addView(state);panel.addView(gap(8))
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        panel.addView(controls);panel.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        val actionRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val more=nativeButton(this,ButtonRole.Secondary).apply{text="More actions"}
        val share=nativeButton(this,ButtonRole.Primary).apply{text="Share"}
        actionRow.addView(share,LinearLayout.LayoutParams(0,-2,1f));actionRow.addView(more,LinearLayout.LayoutParams(0,-2,1f))
        panel.addView(gap(6));panel.addView(actionRow)
        val dialog=appDialog(fullScreen=true).setTitle("Original Document Viewer").setView(panel).setNegativeButton("Close",null).create()
        activeDocumentViewer=dialog
        var currentBitmap:Bitmap?=null
        var previewImage:ImageView?=null
        var generation=0
        var textContent:String?=null
        fun valid()=dialog.isShowing&&!isFinishing&&!isDestroyed&&owner==signedInUserId()
        share.setOnClickListener{if(valid())shareViewerFile(uri,mime)}
        more.setOnClickListener{
            if(!valid())return@setOnClickListener
            val actions=mutableListOf<Pair<String,()->Unit>>()
            actions.add("Download a copy" to {saveResultAs(ExportResultItem(uri.toString(),name,mime))})
            actions.add("Save to Google Drive" to {confirmDriveSave(ExportResultItem(uri.toString(),name,mime))})
            if(kind==DocumentViewerPlan.Kind.PDF){actions.add("Print PDF" to {printPdfDocument(uri)});actions.add("Edit PDF" to {dialog.dismiss();showPdfEditor(uri)})}
            if(kind==DocumentViewerPlan.Kind.IMAGE)actions.add("Create PDF from image" to {dialog.dismiss();showImageImportPreview(listOf(uri))})
            if(textContent!=null && kind!=DocumentViewerPlan.Kind.ZIP)actions.add("Create text PDF" to {
                if(requirePremiumTool("Document viewer text PDF")){val content=textContent.orEmpty();dialog.dismiss();createPdfFromText(content,name.substringBeforeLast('.').take(70))}
            })
            val local=privateDocumentFile(uri)
            if(local==null)actions.add("Save inside DocNexa" to {saveViewerLocal(uri,name)})
            if(local!=null){
                val key=accountPrefKey("viewer_favourites")
                val stars=(prefs.getStringSet(key,emptySet())?:emptySet()).toMutableSet()
                actions.add((if(stars.contains(local.name))"Remove from favourites" else "Add to favourites") to {
                    if(!stars.add(local.name))stars.remove(local.name)
                    prefs.edit().putStringSet(key,stars).apply()
                    Toast.makeText(this,"Favourites updated",Toast.LENGTH_SHORT).show()
                })
            }
            actions.add("Open in another app" to {openViewerExternal(uri,mime)})
            appDialog().setTitle("Document actions").setPremiumItems(actions.map{it.first}.toTypedArray()){_,index->if(valid())actions[index].second()}.setNegativeButton("Cancel",null).show()
        }
        fun showText(value:String,notice:String){
            textContent=value
            state.text=notice
            controls.removeAllViews()
            body.removeAllViews()
            val search=EditText(this).apply{
                hint="Find in document";setSingleLine(true);setTextSize(13f);minimumHeight=dp(48)
                background=rounded(0xFFF3F7FF.toInt(),12,0xFFD4E2F4.toInt())
                setPadding(dp(10),dp(8),dp(10),dp(8))
            }
            val find=nativeButton(this,ButtonRole.Secondary).apply{this.text="Find"}
            val searchRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            searchRow.addView(search,LinearLayout.LayoutParams(0,-2,1f));searchRow.addView(find,LinearLayout.LayoutParams(dp(72),-2))
            body.addView(searchRow)
            val result=label("",11f,0xFF52627A.toInt()).apply{accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
            body.addView(result)
            val readerText=label("",14f,0xFF172B4D.toInt()).apply{
                typeface=android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(12),dp(12),dp(12),dp(12))
            }
            val textScroll=ScrollView(this).apply{
                isFillViewport=true
                background=rounded(0xFFFFFFFF.toInt(),12,0xFFDCE7F5.toInt())
                addView(readerText,ViewGroup.LayoutParams(-1,-2))
            }
            body.addView(textScroll,LinearLayout.LayoutParams(-1,0,1f))
            val offsets=mutableListOf(0)
            var cursor=0
            while(cursor<value.length){
                var boundary=minOf(value.length,cursor+24000)
                if(boundary<value.length){
                    val newline=value.lastIndexOf('\n',boundary-1)
                    if(newline>=cursor+12000)boundary=newline+1
                    else if(boundary>cursor&&Character.isHighSurrogate(value[boundary-1]))boundary--
                }
                cursor=boundary
                offsets.add(cursor)
            }
            if(offsets.size==1)offsets.add(0)
            var section=0
            var font=14f
            var offset=0
            var lastQuery=""
            val previous=nativeButton(this).apply{this.text="‹";contentDescription="Previous text section"}
            val nextSection=nativeButton(this).apply{this.text="›";contentDescription="Next text section"}
            val smaller=nativeButton(this).apply{this.text="A−";contentDescription="Smaller text"}
            val larger=nativeButton(this).apply{this.text="A+";contentDescription="Larger text"}
            val sectionLabel=label("",11f,0xFF52627A.toInt()).apply{gravity=Gravity.CENTER}
            controls.addView(smaller,LinearLayout.LayoutParams(0,-2,1f));controls.addView(larger,LinearLayout.LayoutParams(0,-2,1f))
            if(offsets.size>2){
                controls.addView(previous,LinearLayout.LayoutParams(0,-2,1f))
                controls.addView(sectionLabel,LinearLayout.LayoutParams(0,-1,1.5f))
                controls.addView(nextSection,LinearLayout.LayoutParams(0,-2,1f))
            }
            fun renderSection(match:Int=-1,length:Int=0){
                val begin=offsets[section]
                val end=offsets[section+1]
                val fragment=value.substring(begin,end)
                if(match>=begin&&match<end){
                    val span=android.text.SpannableString(fragment)
                    span.setSpan(android.text.style.BackgroundColorSpan(0xFFFFDB75.toInt()),match-begin,minOf(end,match+length)-begin,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    readerText.text=span
                    readerText.post{val layout=readerText.layout;if(layout!=null)textScroll.scrollTo(0,layout.getLineTop(layout.getLineForOffset(match-begin)))}
                }else{
                    readerText.text=fragment
                    textScroll.scrollTo(0,0)
                }
                sectionLabel.text=(section+1).toString()+"/"+(offsets.size-1)
                previous.isEnabled=section>0;nextSection.isEnabled=section+2<offsets.size
            }
            previous.setOnClickListener{if(section>0){section--;renderSection()}}
            nextSection.setOnClickListener{if(section+2<offsets.size){section++;renderSection()}}
            smaller.setOnClickListener{font=(font-1f).coerceAtLeast(10f);readerText.textSize=font}
            larger.setOnClickListener{font=(font+1f).coerceAtMost(28f);readerText.textSize=font}
            find.setOnClickListener{
                val query=search.text.toString()
                if(query.isEmpty())return@setOnClickListener
                if(lastQuery!=query){offset=0;lastQuery=query}
                var at=value.indexOf(query,offset,ignoreCase=true)
                if(at<0)at=value.indexOf(query,0,ignoreCase=true)
                if(at<0){result.text="No match";return@setOnClickListener}
                section=(offsets.binarySearch(at).let{if(it>=0)it else -it-2}).coerceIn(0,offsets.size-2)
                offset=at+query.length
                result.text="Match at character "+(at+1)
                renderSection(at,query.length)
                (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(search.windowToken,0)
            }
            renderSection()
        }
        dialog.setOnDismissListener{
            generation++
            previewImage?.setImageDrawable(null)
            currentBitmap?.recycle();currentBitmap=null
            if(activeDocumentViewer===dialog)activeDocumentViewer=null
        }
        dialog.show()
        if(kind==DocumentViewerPlan.Kind.PDF||kind==DocumentViewerPlan.Kind.IMAGE){
            val image=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;adjustViewBounds=true;contentDescription="Document page preview"}
            previewImage=image
            val vertical=ScrollView(this).apply{isFillViewport=false;addView(image,ViewGroup.LayoutParams(-1,-2))}
            val horizontal=HorizontalScrollView(this).apply{addView(vertical,ViewGroup.LayoutParams(-2,-1));isFillViewport=true}
            body.addView(horizontal,LinearLayout.LayoutParams(-1,0,1f))
            var page=0;var count=1;var zoom=1f
            val prev=nativeButton(this).apply{text="‹";isEnabled=false;contentDescription="Previous page"}
            val next=nativeButton(this).apply{text="›";isEnabled=false;contentDescription="Next page"}
            val minus=nativeButton(this).apply{text="−";contentDescription="Zoom out"}
            val plus=nativeButton(this).apply{text="+";contentDescription="Zoom in"}
            if(kind==DocumentViewerPlan.Kind.PDF){controls.addView(prev,LinearLayout.LayoutParams(0,-2,1f));controls.addView(next,LinearLayout.LayoutParams(0,-2,1f))}
            controls.addView(minus,LinearLayout.LayoutParams(0,-2,1f));controls.addView(plus,LinearLayout.LayoutParams(0,-2,1f))
            fun applyZoom(){
                val available=if(horizontal.width>0)horizontal.width else resources.displayMetrics.widthPixels-dp(48)
                val width=(available*zoom).toInt().coerceAtLeast(dp(120))
                vertical.layoutParams=vertical.layoutParams.apply{this.width=width}
                image.layoutParams=image.layoutParams.apply{this.width=width}
            }
            fun render(){
                val request=++generation;val index=page
                prev.isEnabled=false;next.isEnabled=false
                state.text=if(kind==DocumentViewerPlan.Kind.PDF)"Rendering page…" else "Opening image…"
                viewerReadExecutor.execute{
                    var bitmap:Bitmap?=null
                    try{
                        var pages=1
                        if(kind==DocumentViewerPlan.Kind.PDF){
                            val fd=contentResolver.openFileDescriptor(uri,"r")?:error("Cannot open PDF")
                            try{
                                PdfRenderer(fd).use{renderer->
                                    pages=renderer.pageCount
                                    require(index in 0 until pages){"No PDF pages"}
                                    renderer.openPage(index).use{p->
                                        val scale=minOf(4f,3000f/maxOf(p.width,p.height).toFloat())
                                        val target=Bitmap.createBitmap((p.width*scale).toInt().coerceAtLeast(1),(p.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                                        bitmap=target;target.eraseColor(android.graphics.Color.WHITE)
                                        p.render(target,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }catch(e:Exception){runCatching{fd.close()};throw e}
                        }else bitmap=decodeImageSafely(uri,3000,exportPixelBudget(8_000_000L,12L))
                        val rendered=bitmap?:error("No preview")
                        runOnUiThread{
                            if(!valid()||request!=generation){rendered.recycle();return@runOnUiThread}
                            image.setImageBitmap(null);currentBitmap?.recycle();currentBitmap=rendered;image.setImageBitmap(rendered)
                            count=pages;state.text=if(kind==DocumentViewerPlan.Kind.PDF)"Page "+(index+1)+" of "+count else rendered.width.toString()+" × "+rendered.height+" preview"
                            prev.isEnabled=index>0;next.isEnabled=index+1<count;applyZoom()
                        }
                    }catch(e:Exception){
                        bitmap?.recycle();runOnUiThread{if(valid()&&request==generation)state.text="Preview unavailable. Use More actions to open in another app."}
                    }catch(_:OutOfMemoryError){bitmap?.recycle();runOnUiThread{if(valid()&&request==generation)state.text="Not enough memory for preview. Close other documents."}}
                }
            }
            prev.setOnClickListener{if(valid()&&page>0){page--;render()}}
            next.setOnClickListener{if(valid()&&page+1<count){page++;render()}}
            minus.setOnClickListener{zoom=(zoom-0.25f).coerceAtLeast(0.75f);applyZoom()}
            plus.setOnClickListener{zoom=(zoom+0.25f).coerceAtMost(3f);applyZoom()}
            horizontal.post{if(valid())applyZoom()};render()
        }else if(kind==DocumentViewerPlan.Kind.OFFICE||kind==DocumentViewerPlan.Kind.EXTERNAL){
            state.text="Original format • No file conversion"
            val options=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
            val scroll=ScrollView(this).apply{addView(options)}
            body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
            popupHeader(options,"DOCNEXA • ORIGINAL FILE","Open original document","Use a compatible installed app to preserve the document layout.")
            addPopupOption(options,"document","Open original file","Choose an installed viewer for this file type.",true){if(valid())openViewerExternal(uri,mime)}
            if(kind==DocumentViewerPlan.Kind.OFFICE){
                addPopupOption(options,"document","Offline text only","Optional text extraction; not the original Office layout."){
                    if(valid()){
                        state.text="Reading offline text…"
                        viewerReadExecutor.execute{
                            try{
                                val value=readOfficeViewerText(uri,DocumentViewerPlan.officeExtension(name,mime))
                                runOnUiThread{if(valid())showText(value,"Offline text only • Office layout and images not shown")}
                            }catch(_:Exception){runOnUiThread{if(valid())state.text="Text extraction unavailable. Open the original file instead."}}
                            catch(_:OutOfMemoryError){runOnUiThread{if(valid())state.text="Not enough memory for text extraction."}}
                        }
                    }
                }
            }
            options.addView(label("The original file is unchanged. A compatible app is required for this format; DocNexa does not include a full Office layout engine.",12f,0xFF52627A.toInt()))
        }else if(kind==DocumentViewerPlan.Kind.TEXT||kind==DocumentViewerPlan.Kind.ZIP){
            viewerReadExecutor.execute{
                try{
                    val value=when(kind){
                        DocumentViewerPlan.Kind.ZIP->readViewerArchive(uri)
                        else->readViewerText(uri)
                    }
                    runOnUiThread{if(valid())showText(value,when(kind){
                        DocumentViewerPlan.Kind.ZIP->"Archive contents only • no files extracted"
                        else->"Original text • Read-only • "+value.length+" characters"
                    })}
                }catch(_:Exception){runOnUiThread{if(valid())state.text="Preview unavailable or file exceeds preview limits. Open in another app."}}
                catch(_:OutOfMemoryError){runOnUiThread{if(valid())state.text="Not enough memory to preview this file."}}
            }
        }else{
            state.text="This format requires a compatible installed app for a full visual preview."
            addPopupOption(body,"document","Open in another app","Open the original document without conversion.",true){if(valid())openViewerExternal(uri,mime)}
        }
    }

    private fun showImportFileHub() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(4))
            background = rounded(0xFFF5F8FF.toInt(), 18)
        }
        popupHeader(panel,"DOCNEXA • IMPORT","Import documents","Choose a source. Your original file is not modified.")
        val dialog=appDialog().setTitle("Import File").setView(toolDialogScroll(panel,0.74f)).setNegativeButton("Close",null).create()
        fun option(title:String,detail:String,symbol:String,action:()->Unit){
            addPopupOption(panel,if(symbol=="☁")"drive" else symbol,title,detail, title=="Browse all files",action)
        }
        option("Browse all files", "Show images, PDFs, documents, downloads and other file types", "ALL") {
            dialog.dismiss()
            allFilesPicker.launch(arrayOf("*/*"))
        }
        option("Google Drive / Cloud storage", "Import a saved PDF or image using the Android file picker", "☁") {
            dialog.dismiss()
            launchDriveImport()
        }
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
        addNativeAdCard(panel)
        dialog.show()
    }

    private fun openPdf(uri: Uri) {
        if(uri.scheme=="content")openDocumentViewer(uri) else openViewerExternal(uri,"application/pdf")
    }
}
