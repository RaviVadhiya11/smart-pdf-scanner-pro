from pathlib import Path

p = Path("app/src/main/java/com/pixelfire/smartpdfscanner/MainActivity.kt")
s = p.read_text()

old = 'setOnClickListener { showComingSoon("Search documents") }'
new = 'setOnClickListener { showSearchDocuments() }'
if old not in s:
    if new not in s:
        raise SystemExit("Search icon handler not found; refusing to patch")
else:
    s = s.replace(old, new, 1)

marker = '    private fun showComingSoon(feature: String) {'
method = r'''    private fun showSearchDocuments() {
        val allDocs = getSavedPdfs()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val query = EditText(this).apply {
            hint = "Search by document name"
            singleLine = true
            textSize = 16f
        }
        val list = android.widget.ListView(this)
        val names = allDocs.map { it.first }.toMutableList()
        val adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_list_item_1, names
        )
        list.adapter = adapter
        container.addView(query, LinearLayout.LayoutParams(-1, -2))
        container.addView(list, LinearLayout.LayoutParams(-1, dp(300)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Search documents")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            val selectedName = names[position]
            allDocs.firstOrNull { it.first == selectedName }?.let { openPdf(it.second) }
            dialog.dismiss()
        }
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val term = s?.toString()?.trim().orEmpty()
                names.clear()
                names.addAll(allDocs.filter { it.first.contains(term, ignoreCase = true) }.map { it.first })
                adapter.notifyDataSetChanged()
                list.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        if (allDocs.isEmpty()) {
            query.isEnabled = false
            query.hint = "No saved PDFs yet"
        }
        dialog.show()
    }

'''
if '    private fun showSearchDocuments() {' not in s:
    if marker not in s:
        raise SystemExit("Search method insertion point not found")
    s = s.replace(marker, method + marker, 1)

p.write_text(s)
