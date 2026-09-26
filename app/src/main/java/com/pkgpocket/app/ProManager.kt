package com.pkgpocket.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object ProManager {
    private const val PREFS = "pkg_pocket_pro"
    private const val KEY_EMAIL = "email"
    private const val KEY_PURCHASE_ID = "purchase_id"
    private const val KEY_PRO_ACTIVE = "pro_active"

    private val apiBase: String
        get() = BuildConfig.PRO_API_URL.trimEnd('/')

    data class Checkout(
        val purchaseId: String,
        val checkoutUrl: String,
        val provider: String,
        val country: String,
        val currency: String,
        val amountMinor: Int
    )
    data class Status(val active: Boolean, val status: String)
    data class Reconcile(val activated: Boolean, val status: String, val paymentId: String?)

    fun savedEmail(context: Context): String =
        prefs(context).getString(KEY_EMAIL, "").orEmpty()

    fun savedPurchaseId(context: Context): String =
        prefs(context).getString(KEY_PURCHASE_ID, "").orEmpty()

    fun isProCached(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PRO_ACTIVE, false)

    fun savePurchaseId(context: Context, purchaseId: String) {
        prefs(context).edit().putString(KEY_PURCHASE_ID, purchaseId).apply()
    }

    suspend fun createCheckout(context: Context, email: String): Checkout =
        withContext(Dispatchers.IO) {
            val normalized = email.trim().lowercase()

            val payload =
                JSONObject()
                    .put("email", normalized)

            val response = request(
                "POST",
                "$apiBase/v1/pro/checkout",
                payload.toString()
            )

            val purchaseId = response.optString("purchase_id")
            val checkoutUrl = response.optString("checkout_url")

            if (purchaseId.isBlank() || checkoutUrl.isBlank()) {
                throw IOException("Invalid checkout response")
            }

            prefs(context).edit()
                .putString(KEY_EMAIL, normalized)
                .putString(KEY_PURCHASE_ID, purchaseId)
                .apply()

            Checkout(
                purchaseId = purchaseId,
                checkoutUrl = checkoutUrl,
                provider = response.optString("provider"),
                country = response.optString("country"),
                currency = response.optString("currency"),
                amountMinor = response.optInt("amount_minor", 0)
            )
        }

    suspend fun reconcile(context: Context, purchaseId: String): Reconcile =
        withContext(Dispatchers.IO) {
            val response = request(
                "POST",
                "$apiBase/v1/pro/reconcile",
                JSONObject().put("purchase_id", purchaseId).toString()
            )
            val activated = response.optBoolean("activated", false)
            val status = response.optString("status", "unknown")
            val paymentId = response.optString("payment_id").takeIf { it.isNotBlank() }

            if (activated) {
                prefs(context).edit()
                    .putBoolean(KEY_PRO_ACTIVE, true)
                    .remove(KEY_PURCHASE_ID)
                    .apply()
            }

            Reconcile(activated, status, paymentId)
        }

    suspend fun refreshStatus(context: Context, email: String): Status =
        withContext(Dispatchers.IO) {
            val normalized = email.trim().lowercase()
            val encoded = URLEncoder.encode(normalized, "UTF-8")
            val response = request(
                "GET",
                "$apiBase/v1/pro/status?email=$encoded"
            )
            val active = response.optBoolean("pro", false)
            val status = response.optString("status", if (active) "active" else "free")

            prefs(context).edit()
                .putString(KEY_EMAIL, normalized)
                .putBoolean(KEY_PRO_ACTIVE, active)
                .apply()

            Status(active, status)
        }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun request(method: String, url: String, body: String? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "PKG-Pocket/${BuildConfig.VERSION_NAME}")

            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }

            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val obj = runCatching {
                if (raw.isBlank()) JSONObject() else JSONObject(raw)
            }.getOrElse { JSONObject().put("raw", raw) }

            if (code !in 200..299) {
                val msg = obj.optString("message")
                    .ifBlank { obj.optString("code") }
                    .ifBlank { "HTTP $code" }
                throw IOException(msg)
            }
            return obj
        } finally {
            c.disconnect()
        }
    }
}
