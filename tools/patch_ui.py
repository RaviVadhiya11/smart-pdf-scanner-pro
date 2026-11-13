from pathlib import Path
p = Path("app/src/main/java/com/pixelfire/smartpdfscanner/MainActivity.kt")
s = p.read_text()
needle = "        // Main navigation tabs requested: Home, PDF Tools, Import ID Photo.\n        val topTabs = LinearLayout(this).apply {"
insert = """        // Fixed horizontal quick tools row, directly below the app header.
        val topQuickScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(14), dp(4), dp(14), dp(8))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val topQuickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun addTopQuick(title: String, icon: String, action: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(16), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            item.addView(label(icon, 18f, blue, true))
            item.addView(TextView(this).apply {
                text = title
                textSize = 12f
                setTextColor(0xFF5F6877.toInt())
                setPadding(dp(7), 0, 0, 0)
            })
            topQuickRow.addView(item)
        }
        addTopQuick("PDF Editor", "▤") { showPdfTools() }
        addTopQuick("Signature", "✍") { showSignaturePad() }
        addTopQuick("Image To Text", "OCR") { ocrPicker.launch("image/*") }
        topQuickScroll.addView(topQuickRow)
        root.addView(topQuickScroll, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
            bottomMargin = dp(4)
        })

        // Main navigation tabs requested: Home, PDF Tools, Import ID Photo.
        val topTabs = LinearLayout(this).apply {"""
if needle not in s: raise SystemExit("Top navigation insertion point not found")
s=s.replace(needle,insert,1)
needle2 = "    private fun showComingSoon(feature: String) {"
method = """    private fun showSignaturePad() {
        val field = android.widget.EditText(this).apply {
            hint = "Type your name/signature"
            textSize = 22f
            minLines = 2
        }
        AlertDialog.Builder(this).setTitle("Create signature")
            .setMessage("Enter your signature text. It will be saved as a PDF.")
            .setView(field)
            .setPositiveButton("Create PDF") { _, _ ->
                val signature = field.text.toString().trim()
                if (signature.isNotEmpty()) createPdfFromText(signature)
                else Toast.makeText(this, "Enter a signature first", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showComingSoon(feature: String) {"""
if needle2 not in s: raise SystemExit("Signature insertion point not found")
s=s.replace(needle2,method,1)
p.write_text(s)
