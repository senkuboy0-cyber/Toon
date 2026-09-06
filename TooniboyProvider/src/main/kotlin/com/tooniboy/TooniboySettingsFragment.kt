package com.tooniboy

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.plugins.Plugin

class TooniboySettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

    private lateinit var ctx: Context
    private lateinit var logTextView: TextView

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        ctx = requireContext()

        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#111111"))
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ── Header ──────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text = "Tooniboy Debug Log"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        })

        root.addView(TextView(ctx).apply {
            text = "Shows exactly what happens when loadLinks runs.\nPlay a video then come back here to see the log."
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        })

        // ── Divider ─────────────────────────────────────────────
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { bottomMargin = dp(12) }
            setBackgroundColor(Color.parseColor("#333333"))
        })

        // ── Button row ──────────────────────────────────────────
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            gravity = Gravity.CENTER_VERTICAL
        }

        val copyBtn = Button(ctx).apply {
            text = "Copy Log"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(8) }
            setOnClickListener { copyLogToClipboard() }
        }

        val clearBtn = Button(ctx).apply {
            text = "Clear Log"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                TooniboyLogger.clear()
                refreshLog()
                Toast.makeText(ctx, "Log cleared", Toast.LENGTH_SHORT).show()
            }
        }

        btnRow.addView(copyBtn)
        btnRow.addView(clearBtn)
        root.addView(btnRow)

        // ── Log box ─────────────────────────────────────────────
        logTextView = TextView(ctx).apply {
            text = buildLogText()
            textSize = 11f
            setTextColor(Color.parseColor("#CCCCCC"))
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(logTextView)

        scroll.addView(root)
        return scroll
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun refreshLog() {
        if (::logTextView.isInitialized) {
            logTextView.text = buildLogText()
        }
    }

    private fun buildLogText(): String {
        val entries = TooniboyLogger.getEntries()
        if (entries.isEmpty()) return "[No log yet]\n\nPlay a video first, then open settings to see the debug log here."
        return entries.joinToString("\n")
    }

    private fun copyLogToClipboard() {
        val text = buildLogText()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Tooniboy Debug Log", text))
        Toast.makeText(ctx, "Log copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
