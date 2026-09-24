package com.pkgpocket.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

object BottomNavStyler {
    enum class Tab {
        HOME,
        LIBRARY
    }

    fun apply(
        context: Context,
        home: MaterialButton,
        library: MaterialButton,
        selected: Tab
    ) {
        val activeBackground = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorPrimaryContainer,
            Color.parseColor("#332947")
        )

        val activeForeground = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnPrimaryContainer,
            Color.WHITE
        )

        val inactiveForeground = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorPrimary,
            Color.parseColor("#B8A7E8")
        )

        styleButton(
            context,
            home,
            selected == Tab.HOME,
            activeBackground,
            activeForeground,
            inactiveForeground
        )

        styleButton(
            context,
            library,
            selected == Tab.LIBRARY,
            activeBackground,
            activeForeground,
            inactiveForeground
        )
    }

    private fun styleButton(
        context: Context,
        button: MaterialButton,
        active: Boolean,
        activeBackground: Int,
        activeForeground: Int,
        inactiveForeground: Int
    ) {
        val foreground =
            if (active) activeForeground
            else inactiveForeground

        button.isAllCaps = false
        button.cornerRadius = dp(context, 16)

        // Remove diferenças internas entre Button e TextButton.
        button.setInsetTop(0)
        button.setInsetBottom(0)
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minimumHeight = 0
        button.strokeWidth = 0

        button.backgroundTintList =
            ColorStateList.valueOf(
                if (active) {
                    activeBackground
                } else {
                    Color.TRANSPARENT
                }
            )

        button.setTextColor(foreground)
        button.iconTint =
            ColorStateList.valueOf(foreground)

        button.iconGravity =
            MaterialButton.ICON_GRAVITY_TOP
    }

    private fun dp(
        context: Context,
        value: Int
    ): Int {
        return (
            value * context.resources.displayMetrics.density
            ).toInt()
    }
}
