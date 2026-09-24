package com.pkgpocket.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LibraryActivity : AppCompatActivity() {
    private enum class Filter { ALL, GAMES, UPDATES, DLCS }

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var allChip: MaterialButton
    private lateinit var gamesChip: MaterialButton
    private lateinit var updatesChip: MaterialButton
    private lateinit var dlcsChip: MaterialButton

    private var currentFilter = Filter.ALL
    private var searchQuery = ""
    private var librarySyncJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply {
            setBackgroundColor(
                MaterialColors.getColor(
                    this@LibraryActivity,
                    com.google.android.material.R.attr.colorSurface,
                    Color.BLACK
                )
            )
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(104))
        }

        buildHeader()
        buildFilterChips()

        grid = GridLayout(this).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }

        content.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )

        scroll.addView(content)
        root.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(buildBottomNav())

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )

            // Mesmo comportamento visual da Home:
            // root absorve laterais + barra inferior;
            // conteúdo recebe apenas o inset superior.
            root.setPadding(
                safe.left,
                0,
                safe.right,
                safe.bottom
            )
            scroll.setPadding(0, safe.top, 0, 0)
            insets
        }

        setContentView(root)
        renderLibrary()
    }

    override fun onResume() {
        super.onResume()

        if (::grid.isInitialized) {
            renderLibrary()
        }

        if (
            ProManager.isProCached(this) &&
            ProManager.savedEmail(this).isNotBlank()
        ) {
            librarySyncJob?.cancel()
            librarySyncJob = lifecycleScope.launch {
                runCatching {
                    LibrarySyncManager.restoreAndMerge(
                        this@LibraryActivity
                    )
                }

                if (::grid.isInitialized) {
                    renderLibrary()
                }
            }
        }
    }

    private fun buildHeader() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = getString(R.string.library_title)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                MaterialColors.getColor(
                    this@LibraryActivity,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.WHITE
                )
            )
        }

        val search = iconButton(R.drawable.ic_search_24).apply {
            contentDescription = getString(R.string.library_search_title)
            setOnClickListener { showSearchDialog() }
        }

        val filter = iconButton(R.drawable.ic_filter_24).apply {
            contentDescription = getString(R.string.library_filter_title)
            setOnClickListener { showFilterDialog() }
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        header.addView(search)
        header.addView(filter)
        content.addView(header)

        val note = TextView(this).apply {
            text = getString(R.string.library_local_note)
            textSize = 11f
            alpha = 0.68f
            setTextColor(
                MaterialColors.getColor(
                    this@LibraryActivity,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    Color.LTGRAY
                )
            )
        }

        content.addView(
            note,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )
    }

    private fun buildFilterChips() {
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        allChip = filterChip(getString(R.string.library_all), Filter.ALL)
        gamesChip = filterChip(getString(R.string.library_games_filter), Filter.GAMES)
        updatesChip = filterChip(getString(R.string.library_updates_filter), Filter.UPDATES)
        dlcsChip = filterChip(getString(R.string.library_dlcs_filter), Filter.DLCS)

        listOf(allChip, gamesChip, updatesChip, dlcsChip).forEach(row::addView)
        scroller.addView(row)

        content.addView(
            scroller,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )

        updateChipStyles()
    }

    private fun filterChip(label: String, filter: Filter): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minHeight = dp(38)
            minimumHeight = dp(38)
            cornerRadius = dp(18)
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener {
                currentFilter = filter
                updateChipStyles()
                renderLibrary()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(40)
            ).apply { marginEnd = dp(7) }
        }
    }

    private fun updateChipStyles() {
        val activeBg = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimary,
            Color.WHITE
        )
        val activeFg = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnPrimary,
            Color.BLACK
        )
        val inactiveFg = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.LTGRAY
        )

        listOf(
            allChip to Filter.ALL,
            gamesChip to Filter.GAMES,
            updatesChip to Filter.UPDATES,
            dlcsChip to Filter.DLCS
        ).forEach { (button, filter) ->
            val active = currentFilter == filter
            button.backgroundTintList = ColorStateList.valueOf(
                if (active) activeBg else Color.parseColor("#151A23")
            )
            button.setTextColor(if (active) activeFg else inactiveFg)
            button.strokeWidth = if (active) 0 else dp(1)
            button.strokeColor = ColorStateList.valueOf(Color.parseColor("#2D3441"))
        }
    }

    private fun renderLibrary() {
        grid.removeAllViews()

        val allGroups = LibraryHistory.groups(InstallHistoryStore.all(this))
        val groups = allGroups.filter { matchesFilter(it) && matchesSearch(it) }

        if (groups.isEmpty()) {
            val empty = TextView(this).apply {
                text = if (allGroups.isEmpty()) {
                    getString(R.string.library_empty)
                } else {
                    getString(R.string.library_no_results)
                }
                textSize = 15f
                gravity = Gravity.CENTER
                alpha = 0.72f
                setPadding(dp(12), dp(70), dp(12), dp(70))
            }
            grid.addView(
                empty,
                GridLayout.LayoutParams().apply {
                    width = resources.displayMetrics.widthPixels - dp(36)
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(0, 2)
                }
            )
            return
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val cardWidth = ((screenWidth - dp(46)) / 2).coerceAtLeast(dp(145))

        groups.forEachIndexed { index, group ->
            grid.addView(
                buildLibraryCard(group, cardWidth),
                GridLayout.LayoutParams().apply {
                    width = cardWidth
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    rowSpec = GridLayout.spec(index / 2)
                    columnSpec = GridLayout.spec(index % 2)
                    setMargins(
                        if (index % 2 == 0) 0 else dp(5),
                        dp(5),
                        if (index % 2 == 0) dp(5) else 0,
                        dp(7)
                    )
                }
            )
        }
    }

    private fun buildLibraryCard(
        group: LibraryGameGroup,
        cardWidth: Int
    ): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#2D3441")
            setCardBackgroundColor(Color.parseColor("#151A23"))
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

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.pkg_pocket_logo)
            contentDescription = group.title
        }

        body.addView(
            cover,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (cardWidth * 0.72f).toInt().coerceAtLeast(dp(105))
            )
        )

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(8), dp(9), dp(10))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        val titleView = TextView(this).apply {
            text = group.title
            textSize = 12f
            minLines = 2
            maxLines = 2
            includeFontPadding = false
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.04f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                MaterialColors.getColor(
                    this@LibraryActivity,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.WHITE
                )
            )
        }

        val overflow = TextView(this).apply {
            text = "⋮"
            textSize = 22f
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.library_filter_title)
            setTextColor(
                MaterialColors.getColor(
                    this@LibraryActivity,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    Color.LTGRAY
                )
            )
            setOnClickListener { anchor ->
                val menu = android.widget.PopupMenu(
                    this@LibraryActivity,
                    anchor
                )
                menu.menu.add(0, 101, 0, R.string.library_open_details)
                menu.menu.add(0, 102, 1, R.string.library_remove_history)
                menu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        101 -> {
                            startActivity(
                                Intent(
                                    this@LibraryActivity,
                                    GameDetailActivity::class.java
                                ).putExtra(
                                    GameDetailActivity.EXTRA_GROUP_KEY,
                                    group.key
                                )
                            )
                            true
                        }
                        102 -> {
                            confirmRemoveGroup(group)
                            true
                        }
                        else -> false
                    }
                }
                menu.show()
            }
        }

        titleView.minLines = 2
        titleView.maxLines = 2

        titleRow.addView(
            titleView,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        titleRow.addView(
            overflow,
            LinearLayout.LayoutParams(dp(28), dp(34))
        )
        info.addView(titleRow)

        val badge = TextView(this).apply {
            text = badgeText(group)
            textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = ContextCompat.getDrawable(
                this@LibraryActivity,
                R.drawable.library_badge_bg
            )
            backgroundTintList = ColorStateList.valueOf(badgeColor(group))
        }

        info.addView(
            badge,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(5) }
        )

        info.addView(
            TextView(this).apply {
                text = metaText(group)
                textSize = 10f
                alpha = 0.74f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(
                    MaterialColors.getColor(
                        this@LibraryActivity,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        Color.LTGRAY
                    )
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(5) }
        )

        body.addView(info)
        card.addView(body)
        loadCover(group, cover)
        return card
    }

    private fun badgeText(group: LibraryGameGroup): String {
        return when (currentFilter) {
            Filter.GAMES -> getString(R.string.library_card_game)
            Filter.UPDATES -> getString(R.string.library_card_update)
            Filter.DLCS -> getString(R.string.library_card_dlc)
            Filter.ALL -> when {
                group.games.isNotEmpty() -> getString(R.string.library_card_game)
                group.updates.isNotEmpty() -> getString(R.string.library_card_update)
                group.dlcs.isNotEmpty() -> getString(R.string.library_card_dlc)
                else -> getString(R.string.library_card_pkg)
            }
        }
    }

    private fun badgeColor(group: LibraryGameGroup): Int {
        return when (badgeText(group)) {
            getString(R.string.library_card_dlc) -> Color.parseColor("#8A5B20")
            getString(R.string.library_card_update) -> Color.parseColor("#59408C")
            getString(R.string.library_card_game) -> Color.parseColor("#563B8A")
            else -> Color.parseColor("#3E4655")
        }
    }

    private fun metaText(group: LibraryGameGroup): String {
        return when (currentFilter) {
            Filter.UPDATES -> {
                val version = group.latestUpdate?.version.orEmpty()
                if (version.isNotBlank()) "v$version" else group.titleId
            }
            Filter.DLCS -> {
                "${group.dlcs.size} DLC" + if (group.dlcs.size == 1) "" else "s"
            }
            else -> {
                val version = group.latestUpdate?.version
                    ?: group.games.firstOrNull()?.version
                    ?: ""
                buildString {
                    if (version.isNotBlank()) append("v$version")
                    if (group.titleId.isNotBlank()) {
                        if (isNotEmpty()) append(" • ")
                        append(group.titleId)
                    }
                }.ifBlank {
                    "${group.records.size} PKG" +
                        if (group.records.size == 1) "" else "s"
                }
            }
        }
    }

    private fun matchesFilter(group: LibraryGameGroup): Boolean {
        return when (currentFilter) {
            Filter.ALL -> true
            Filter.GAMES -> group.games.isNotEmpty()
            Filter.UPDATES -> group.updates.isNotEmpty()
            Filter.DLCS -> group.dlcs.isNotEmpty()
        }
    }

    private fun matchesSearch(group: LibraryGameGroup): Boolean {
        val q = searchQuery.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return true
        return group.title.lowercase(Locale.ROOT).contains(q) ||
            group.titleId.lowercase(Locale.ROOT).contains(q)
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.library_search_hint)
            setText(searchQuery)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.library_search_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.clear_selection) { _, _ ->
                searchQuery = ""
                renderLibrary()
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                searchQuery = input.text?.toString().orEmpty()
                renderLibrary()
            }
            .show()
    }

    private fun showFilterDialog() {
        val labels = arrayOf(
            getString(R.string.library_all),
            getString(R.string.library_games_filter),
            getString(R.string.library_updates_filter),
            getString(R.string.library_dlcs_filter),
            getString(R.string.library_clear_history_action)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.library_filter_title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> currentFilter = Filter.ALL
                    1 -> currentFilter = Filter.GAMES
                    2 -> currentFilter = Filter.UPDATES
                    3 -> currentFilter = Filter.DLCS
                    4 -> {
                        confirmClearHistory()
                        return@setItems
                    }
                }
                updateChipStyles()
                renderLibrary()
            }
            .show()
    }

    private fun buildBottomNav(): MaterialCardView {
        val bar = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            cardElevation = dp(8).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#262D38")
            setCardBackgroundColor(Color.parseColor("#11151D"))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val home = MaterialButton(this).apply {
            text = getString(R.string.nav_home)
            textSize = 11f
            icon = ContextCompat.getDrawable(
                this@LibraryActivity,
                R.drawable.ic_home_24
            )
            iconGravity = MaterialButton.ICON_GRAVITY_TOP
            setOnClickListener { finish() }
        }

        val library = MaterialButton(this).apply {
            text = getString(R.string.nav_library)
            textSize = 11f
            icon = ContextCompat.getDrawable(
                this@LibraryActivity,
                R.drawable.ic_library_24
            )
            iconGravity = MaterialButton.ICON_GRAVITY_TOP
        }

        row.addView(
            home,
            LinearLayout.LayoutParams(0, dp(58), 1f)
        )
        row.addView(
            library,
            LinearLayout.LayoutParams(0, dp(58), 1f)
        )

        bar.addView(row)

        bar.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(70),
            Gravity.BOTTOM
        ).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(8)
        }

        BottomNavStyler.apply(
            this,
            home,
            library,
            BottomNavStyler.Tab.LIBRARY
        )

        return bar
    }

    private fun iconButton(iconRes: Int): MaterialButton {
        return MaterialButton(this).apply {
            text = ""
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(0, 0, 0, 0)
            icon = ContextCompat.getDrawable(this@LibraryActivity, iconRes)
            iconTint = ColorStateList.valueOf(
                MaterialColors.getColor(
                    this@LibraryActivity,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.WHITE
                )
            )
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
    }

    private fun loadCover(group: LibraryGameGroup, target: ImageView) {
        val titleId = group.titleId.trim()
        if (titleId.isBlank()) return

        // Se a capa já foi carregada nesta sessão, aplica na mesma hora.
        // Assim não há flash do logo ao sair e voltar para a Biblioteca.
        LibraryCoverStore.peekBitmap(titleId)?.let {
            target.setImageBitmap(it)
            return
        }

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                LibraryCoverStore.loadBitmap(
                    this@LibraryActivity,
                    titleId
                ) ?: run {
                    val bytes =
                        currentSelectionCover(titleId)
                            ?: fetchGameCover(group)

                    if (bytes != null) {
                        LibraryCoverStore.loadBitmap(
                            this@LibraryActivity,
                            titleId
                        ) ?: BitmapFactory.decodeByteArray(
                            bytes,
                            0,
                            bytes.size
                        )
                    } else {
                        null
                    }
                }
            }

            if (
                bitmap != null &&
                !isFinishing &&
                !isDestroyed
            ) {
                target.setImageBitmap(bitmap)
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

        val bytes = CoverResolver.resolve(this, item, allowTitleFallback = true)
        LibraryCoverStore.save(this, group.titleId, bytes)
        return bytes
    }

    private fun confirmRemoveGroup(group: LibraryGameGroup) {
        AlertDialog.Builder(this)
            .setTitle(R.string.library_remove_history_title)
            .setMessage(R.string.library_remove_history_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.library_remove_history) { _, _ ->
                InstallHistoryStore.removeGroup(this, group.key)
                android.widget.Toast.makeText(
                    this,
                    R.string.library_removed_history,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                renderLibrary()
            }
            .show()
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
