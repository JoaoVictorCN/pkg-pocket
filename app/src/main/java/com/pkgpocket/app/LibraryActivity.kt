package com.pkgpocket.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var grid: GridLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(
                MaterialColors.getColor(
                    this@LibraryActivity,
                    com.google.android.material.R.attr.colorSurface,
                    0
                )
            )
        }

        val basePadding = dp(16)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                basePadding + safe.left,
                basePadding + safe.top,
                basePadding + safe.right,
                basePadding + safe.bottom
            )
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = MaterialButton(this).apply {
            text = "‹"
            textSize = 28f
            minWidth = dp(48)
            setOnClickListener { finish() }
        }

        val title = TextView(this).apply {
            text = getString(R.string.library_title)
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        }

        val clear = MaterialButton(this).apply {
            text = getString(R.string.clear_history_short)
            setOnClickListener { confirmClearHistory() }
        }

        header.addView(back)
        header.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(clear)
        root.addView(header)

        val note = TextView(this).apply {
            text = getString(R.string.library_local_note)
            textSize = 12f
            alpha = 0.72f
            setPadding(0, dp(2), 0, dp(10))
        }
        root.addView(note)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        grid = GridLayout(this).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }

        scroll.addView(
            grid,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        renderLibrary()
    }

    override fun onResume() {
        super.onResume()
        if (::grid.isInitialized) renderLibrary()
    }

    private fun renderLibrary() {
        grid.removeAllViews()
        val groups = LibraryHistory.groups(InstallHistoryStore.all(this))

        if (groups.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.library_empty)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(80), dp(20), dp(80))
            }

            grid.addView(
                empty,
                GridLayout.LayoutParams().apply {
                    width = GridLayout.LayoutParams.MATCH_PARENT
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(0, 2)
                }
            )
            return
        }

        val availableWidth =
            resources.displayMetrics.widthPixels -
                root.paddingLeft -
                root.paddingRight -
                dp(12)

        val cardWidth = (availableWidth / 2).coerceAtLeast(dp(140))
        val coverHeight = (cardWidth * 1.42f).toInt()

        groups.forEach { group ->
            val card = MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(2).toFloat()
                isClickable = true
                isFocusable = true
                contentDescription = group.title
                setOnClickListener {
                    startActivity(
                        Intent(this@LibraryActivity, GameDetailActivity::class.java)
                            .putExtra(GameDetailActivity.EXTRA_GROUP_KEY, group.key)
                    )
                }
            }

            val cover = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.pkg_pocket_logo)
                contentDescription = group.title
            }

            card.addView(
                cover,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    coverHeight
                )
            )

            grid.addView(
                card,
                GridLayout.LayoutParams().apply {
                    width = cardWidth
                    height = coverHeight
                    setMargins(dp(3), dp(6), dp(3), dp(6))
                }
            )

            loadCover(group, cover)
        }
    }

    private fun loadCover(group: LibraryGameGroup, target: ImageView) {
        val titleId = group.titleId.trim()
        if (titleId.isBlank()) return

        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                LibraryCoverStore.load(this@LibraryActivity, titleId)
                    ?: currentSelectionCover(titleId)
                    ?: fetchGameCover(group)
            }

            if (bytes != null && !isFinishing) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                    target.setImageBitmap(it)
                }
            }
        }
    }

    private fun currentSelectionCover(titleId: String): ByteArray? {
        val item = PkgRepository.items.firstOrNull {
            it.kind == PkgKind.GAME &&
                it.titleId.equals(titleId, ignoreCase = true) &&
                it.icon != null
        } ?: return null

        LibraryCoverStore.save(this, titleId, item.icon)
        return item.icon
    }

    private fun fetchGameCover(group: LibraryGameGroup): ByteArray? {
        val base = group.games.firstOrNull()
            ?: group.records.firstOrNull()
            ?: return null

        val item = PkgItem(
            token = "library:${group.key}",
            uri = Uri.EMPTY,
            fileName = base.fileName,
            size = 0L,
            title = group.title,
            titleId = group.titleId,
            contentId = base.contentId,
            version = base.version,
            category = "",
            kind = PkgKind.GAME,
            icon = null
        )

        val bytes = CoverResolver.resolve(
            this,
            item,
            allowTitleFallback = true
        )

        LibraryCoverStore.save(this, group.titleId, bytes)
        return bytes
    }

    private fun confirmClearHistory() {
        if (InstallHistoryStore.all(this).isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle(R.string.clear_history)
            .setMessage(R.string.clear_history_confirm)
            .setNegativeButton(R.string.cancel_selection, null)
            .setPositiveButton(R.string.clear_history) { _, _ ->
                InstallHistoryStore.clear(this)
                renderLibrary()
            }
            .show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
