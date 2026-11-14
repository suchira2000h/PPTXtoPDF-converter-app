package com.example.pptx2pdf

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private val reqPick = 1
    private val reqSave = 2

    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var pickBtn: Button
    private lateinit var openBtn: Button

    private var inputUri: Uri? = null
    private var outputUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val title = TextView(this).apply {
            text = "SlideToPDF"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        pickBtn = Button(this).apply {
            text = "Choose PowerPoint (.pptx)"
            setOnClickListener { pickFile() }
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
        }
        status = TextView(this).apply {
            text = "Pick a .pptx file, then choose where to save the PDF."
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
        }
        openBtn = Button(this).apply {
            text = "Open PDF"
            visibility = View.GONE
            setOnClickListener { openPdf() }
        }

        root.addView(title)
        root.addView(pickBtn)
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(status)
        root.addView(openBtn)
        setContentView(root)
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIncoming(intent)
        }
    }

    /** Called when a .pptx is opened from Chrome downloads, a file manager, Gmail, WhatsApp, etc. */
    private fun handleIncoming(i: Intent?) {
        i ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            else -> null
        }
        if (uri != null) {
            i.action = null // don't handle the same intent twice (e.g. on rotation)
            chooseOutput(uri)
        }
    }

    private fun chooseOutput(uri: Uri) {
        val name = displayName(uri)
        if (name.lowercase().endsWith(".ppt")) {
            Toast.makeText(this, "Old .ppt files aren't supported. Save as .pptx first.", Toast.LENGTH_LONG).show()
            return
        }
        inputUri = uri
        status.text = "Selected: $name"
        val out = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, name.substringBeforeLast('.') + ".pdf")
        }
        startActivityForResult(out, reqSave)
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/octet-stream"
                )
            )
        }
        startActivityForResult(i, reqPick)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!

        when (requestCode) {
            reqPick -> {
                val name = displayName(uri)
                if (!name.lowercase().endsWith(".pptx")) {
                    Toast.makeText(this, "Please choose a .pptx file (not .ppt)", Toast.LENGTH_LONG).show()
                    return
                }
                chooseOutput(uri)
            }
            reqSave -> {
                outputUri = uri
                startConversion()
            }
        }
    }

    private fun startConversion() {
        val inUri = inputUri ?: return
        val outUri = outputUri ?: return
        pickBtn.isEnabled = false
        openBtn.visibility = View.GONE
        bar.visibility = View.VISIBLE
        bar.isIndeterminate = true
        status.text = "Reading file…"

        Thread {
            try {
                val files = contentResolver.openInputStream(inUri)!!.use { PptxConverter.readZip(it) }
                contentResolver.openOutputStream(outUri)!!.use { out ->
                    PptxConverter(files).convert(out) { done, total ->
                        runOnUiThread {
                            bar.isIndeterminate = false
                            bar.max = total
                            bar.progress = done
                            status.text = "Converting slide ${done + 1} of $total…"
                        }
                    }
                }
                runOnUiThread {
                    bar.visibility = View.GONE
                    status.text = "Done! PDF saved."
                    openBtn.visibility = View.VISIBLE
                    pickBtn.isEnabled = true
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    bar.visibility = View.GONE
                    status.text = "Failed: ${t.message ?: t.javaClass.simpleName}"
                    pickBtn.isEnabled = true
                }
            }
        }.start()
    }

    private fun openPdf() {
        val uri = outputUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No PDF viewer installed", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i)
            }
        }
        return "presentation.pptx"
    }
}
