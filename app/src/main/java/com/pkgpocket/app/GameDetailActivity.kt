package com.pkgpocket.app

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
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
        val found = LibraryHistory.find(
            InstallHistoryStore.all(this),
            key
        )

        if (found == null) {
            finish()
            return
        }

        group = found

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(
                MaterialColors.getColor(
                    this@GameDetailActivity,
                    com.google.android.material.R.attr.colorSurface,
                    Color.BLACK
                )
            )
        }

        val base = dp(18)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                base + safe.left,
                dp(8) + safe.top,
                base + safe.right,
                dp(28) + safe.bottom
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
            minWidth = dp(46)
            minimumWidth = dp(46)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(
                MaterialColors.getColor(
                    this@GameDetailActivity,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.WHITE
                )
            )
            setOnClickListener { finish() }
        }

        val headerTitle = TextView(this).apply {
            text = getString(R.string.detail_title)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                MaterialColors.getColor(
                    this@GameDetailActivity,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.WHITE
                )
            )
        }

        header.addView(back)
        header.addView(
            headerTitle,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(4) }
        )
        root.addView(header)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }

        val coverCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#2D3441")
        }

        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.pkg_pocket_logo)
            contentDescription = group.title
        }
        coverCard.addView(
            cover,
            LinearLayout.LayoutParams(dp(120), dp(164))
        )

        val heroInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }

        heroInfo.addView(TextView(this).apply {
            text = group.title
            textSize = 20f
            maxLines = 3
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                MaterialColors.getColor(
                    this@GameDetailActivity,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.WHITE
                )
            )
        })

        val badge = TextView(this).apply {
            text = when {
                group.games.isNotEmpty() ->
                    getString(R.string.detail_game_badge)
                group.updates.isNotEmpty() ->
                    getString(R.string.detail_update_badge)
                else ->
                    getString(R.string.detail_dlc_badge)
            }
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = getDrawable(R.drawable.library_badge_bg)
            backgroundTintList = ColorStateList.valueOf(
                Color.parseColor("#6847A8")
            )
        }

        heroInfo.addView(
            badge,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        val version = displayVersion()
        heroInfo.addView(TextView(this).apply {
            text = buildString {
                if (group.titleId.isNotBlank()) append(group.titleId)
                if (version.isNotBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append("v")
                    append(version)
                }
            }.ifBlank { "—" }
            textSize = 12f
            alpha = 0.76f
            setPadding(0, dp(8), 0, 0)
        })

        hero.addView(
            coverCard,
            LinearLayout.LayoutParams(dp(120), dp(164))
        )
        hero.addView(
            heroInfo,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        root.addView(hero)

        root.addView(infoCard())

        val historyTitle = TextView(this).apply {
            text = getString(R.string.detail_history)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(9))
        }
        root.addView(historyTitle)

        addSection(
            root,
            getString(R.string.library_base_game),
            group.games,
            null
        )
        addSection(
            root,
            getString(R.string.library_updates),
            group.updates,
            group.latestUpdate?.key
        )
        addSection(
            root,
            getString(R.string.library_dlcs),
            group.dlcs,
            null
        )

        if (group.others.isNotEmpty()) {
            addSection(
                root,
                getString(R.string.library_other_pkgs),
                group.others,
                null
            )
        }

        val remove = MaterialButton(this).apply {
            text = getString(R.string.detail_remove)
            textSize = 13f
            cornerRadius = dp(16)
            backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#3A2025"))
            setTextColor(Color.parseColor("#FF818B"))
            setOnClickListener { confirmRemove() }
        }

        root.addView(
            remove,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply { topMargin = dp(18) }
        )

        scroll.addView(root)
        setContentView(scroll)

        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                LibraryCoverStore.load(
                    this@GameDetailActivity,
                    group.titleId
                )
            }

            if (bytes != null) {
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size
                )?.let(cover::setImageBitmap)
            }
        }
    }

    private fun infoCard(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#2D3441")
            setCardBackgroundColor(Color.parseColor("#151A23"))
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        addInfoRow(
            body,
            getString(R.string.detail_version),
            displayVersion().ifBlank { "—" }
        )
        addInfoRow(
            body,
            getString(R.string.detail_title_id),
            group.titleId.ifBlank { "—" }
        )
        addInfoRow(
            body,
            getString(R.string.detail_content_id),
            group.records.firstOrNull {
                it.contentId.isNotBlank()
            }?.contentId.orEmpty().ifBlank { "—" }
        )
        addInfoRow(
            body,
            getString(R.string.detail_packages),
            group.records.size.toString()
        )

        card.addView(body)
        return card
    }

    private fun addInfoRow(
        parent: LinearLayout,
        label: String,
        value: String
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(6), 0, dp(6))
        }

        row.addView(
            TextView(this).apply {
                text = label
                textSize = 12f
                alpha = 0.76f
            },
            LinearLayout.LayoutParams(dp(110), -2)
        )

        row.addView(
            TextView(this).apply {
                text = value
                textSize = 12f
                maxLines = 3
                setTextColor(
                    MaterialColors.getColor(
                        this@GameDetailActivity,
                        com.google.android.material.R.attr.colorOnSurface,
                        Color.WHITE
                    )
                )
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        parent.addView(row)
    }

    private fun displayVersion(): String {
        return group.latestUpdate?.version
            ?: group.games.firstOrNull()?.version
            ?: group.records.firstOrNull()?.version
            ?: ""
    }

    private fun addSection(
        parent: LinearLayout,
        sectionTitle: String,
        records: List<InstalledPkgRecord>,
        currentKey: String?
    ) {
        if (records.isEmpty()) return

        parent.addView(TextView(this).apply {
            text = sectionTitle
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(6))
        })

        records.forEach { record ->
            parent.addView(
                recordCard(
                    record,
                    record.key == currentKey
                )
            )
        }
    }

    private fun recordCard(
        record: InstalledPkgRecord,
        current: Boolean
    ): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(13).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#2D3441")
            setCardBackgroundColor(Color.parseColor("#151A23"))
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val rawName = record.title.ifBlank { record.fileName }
        val name = if (
            record.kind == PkgKind.DLC.name &&
            rawName.equals(group.title, true)
        ) {
            record.fileName
        } else {
            rawName
        }

        body.addView(TextView(this).apply {
            text = name
            textSize = 13f
            maxLines = 2
            setTypeface(typeface, Typeface.BOLD)
        })

        val date = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT
        ).format(Date(record.installedAt))

        body.addView(TextView(this).apply {
            text = buildString {
                if (record.version.isNotBlank()) {
                    append("v")
                    append(record.version)
                    if (current) append(" • atual")
                }
                if (isNotEmpty()) append(" • ")
                append(date)
            }
            textSize = 10f
            alpha = 0.72f
            setPadding(0, dp(4), 0, 0)
        })

        card.addView(body)
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
        }
    }

    private fun confirmRemove() {
        AlertDialog.Builder(this)
            .setTitle(R.string.library_remove_history_title)
            .setMessage(R.string.library_remove_history_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.detail_remove) { _, _ ->
                InstallHistoryStore.removeGroup(this, group.key)
                finish()
            }
            .show()
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
            ).toInt()
    }

    companion object {
        const val EXTRA_GROUP_KEY = "library_group_key"
    }
}
