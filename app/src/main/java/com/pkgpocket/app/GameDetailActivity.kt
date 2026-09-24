package com.pkgpocket.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class GameDetailActivity : AppCompatActivity() {
    private lateinit var group: LibraryGameGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_GROUP_KEY).orEmpty()
        val found = LibraryHistory.find(InstallHistoryStore.all(this), key)

        if (found == null) {
            finish()
            return
        }

        group = found

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(
                MaterialColors.getColor(
                    this@GameDetailActivity,
                    com.google.android.material.R.attr.colorSurface,
                    0
                )
            )
        }

        val basePadding = dp(18)
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

        val back = MaterialButton(this).apply {
            text = getString(R.string.back_to_library)
            setOnClickListener { finish() }
        }
        root.addView(back)

        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.pkg_pocket_logo)
            contentDescription = group.title
        }

        root.addView(
            cover,
            LinearLayout.LayoutParams(dp(170), dp(242)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            }
        )

        val title = TextView(this).apply {
            text = group.title
            textSize = 26f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        }
        root.addView(title)

        val summary = TextView(this).apply {
            text = buildSummary()
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(summary)

        val titleId = TextView(this).apply {
            text = group.titleId.ifBlank { "—" }
            textSize = 13f
            gravity = Gravity.CENTER
            alpha = 0.72f
            setPadding(0, dp(4), 0, dp(18))
        }
        root.addView(titleId)

        addSection(root, getString(R.string.library_base_game), group.games, null)
        addSection(
            root,
            getString(R.string.library_updates),
            group.updates,
            group.latestUpdate?.key
        )
        addSection(root, getString(R.string.library_dlcs), group.dlcs, null)

        if (group.others.isNotEmpty()) {
            addSection(root, getString(R.string.library_other_pkgs), group.others, null)
        }

        val note = TextView(this).apply {
            text = getString(R.string.library_local_note_long)
            textSize = 12f
            alpha = 0.68f
            setPadding(0, dp(16), 0, dp(28))
        }
        root.addView(note)

        scroll.addView(root)
        setContentView(scroll)

        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                LibraryCoverStore.load(this@GameDetailActivity, group.titleId)
            }

            if (bytes != null) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                    cover.setImageBitmap(it)
                }
            }
        }
    }

    private fun buildSummary(): String {
        val dlcCount = group.dlcs.size
        val update = group.latestUpdate?.version.orEmpty()

        return if (update.isNotBlank()) {
            getString(R.string.library_game_summary, update, dlcCount)
        } else {
            val baseVersion = group.games.firstOrNull()?.version.orEmpty()
            if (baseVersion.isNotBlank()) {
                getString(R.string.library_game_summary_base, baseVersion, dlcCount)
            } else {
                getString(R.string.library_game_summary_no_version, dlcCount)
            }
        }
    }

    private fun addSection(
        parent: LinearLayout,
        sectionTitle: String,
        records: List<InstalledPkgRecord>,
        currentKey: String?
    ) {
        val title = TextView(this).apply {
            text = sectionTitle
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(8))
        }
        parent.addView(title)

        if (records.isEmpty()) {
            parent.addView(
                TextView(this).apply {
                    text = getString(R.string.library_none_recorded)
                    textSize = 14f
                    alpha = 0.7f
                    setPadding(dp(4), 0, dp(4), dp(8))
                }
            )
            return
        }

        records.forEach { record ->
            parent.addView(recordCard(record, record.key == currentKey))
        }
    }

    private fun recordCard(
        record: InstalledPkgRecord,
        isCurrentUpdate: Boolean
    ): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = dp(1).toFloat()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val rawName = record.title.ifBlank { record.fileName }
        val displayName = if (
            record.kind == PkgKind.DLC.name &&
            rawName.equals(group.title, ignoreCase = true)
        ) {
            record.fileName
        } else {
            rawName
        }

        val name = TextView(this).apply {
            text = displayName
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        content.addView(name)

        val versionText = when {
            record.version.isBlank() -> getString(R.string.history_no_version)
            isCurrentUpdate -> getString(R.string.library_current_update, record.version)
            else -> getString(R.string.history_version, record.version)
        }

        val installed = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT
        ).format(Date(record.installedAt))

        val meta = TextView(this).apply {
            text = buildString {
                append(versionText)
                append(" • ")
                append(installed)

                if (record.contentId.isNotBlank()) {
                    append("\n")
                    append(getString(R.string.content_id_format, record.contentId))
                }

                if (record.fileName.isNotBlank() && record.fileName != displayName) {
                    append("\n")
                    append(record.fileName)
                }
            }
            textSize = 12f
            alpha = 0.75f
            setPadding(0, dp(5), 0, 0)
        }
        content.addView(meta)

        card.addView(content)

        return card.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_GROUP_KEY = "library_group_key"
    }
}
