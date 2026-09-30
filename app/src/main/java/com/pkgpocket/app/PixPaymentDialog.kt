package com.pkgpocket.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Base64
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

object PixPaymentDialog {

    fun show(
        activity: AppCompatActivity,
        payment: ProManager.PixPayment,
        onVerify: () -> Unit
    ) {
        fun dp(value: Int): Int =
            (
                value *
                    activity.resources
                        .displayMetrics
                        .density
            ).toInt()

        val content =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), dp(8))
            }

        val amount =
            TextView(activity).apply {
                text = ProManager.formatPrice(payment.currency, payment.amountMinor)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(8))
            }

        content.addView(amount)

        val instructions =
            TextView(activity).apply {
                text = activity.getString(R.string.pro_pix_instructions)
                textSize = 15f
                setPadding(0, 0, 0, dp(12))
            }

        content.addView(instructions)

        if (payment.qrCodeBase64.isNotBlank()) {
            val raw = payment.qrCodeBase64.substringAfter(',', payment.qrCodeBase64)

            val bitmap =
                runCatching {
                    val bytes = Base64.decode(raw, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()

            if (bitmap != null) {
                val qr =
                    ImageView(activity).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                    }

                content.addView(
                    qr,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(260)
                    )
                )
            }
        }

        val label =
            TextView(activity).apply {
                text = activity.getString(R.string.pro_pix_copy_paste)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(6))
            }

        content.addView(label)

        val code =
            TextView(activity).apply {
                text = payment.qrCode
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }

        content.addView(
            code,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val copy =
            Button(activity).apply {
                text = activity.getString(R.string.pro_pix_copy)
                setOnClickListener {
                    val clipboard =
                        activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Pix", payment.qrCode)
                    )

                    Toast.makeText(
                        activity,
                        R.string.pro_pix_copied,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        content.addView(
            copy,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val scroll = ScrollView(activity).apply { addView(content) }

        val dialog =
            AlertDialog.Builder(activity)
                .setTitle(R.string.pro_pix_title)
                .setView(scroll)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.pro_pix_verify, null)
                .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                onVerify()
            }
        }

        dialog.show()
    }
}
