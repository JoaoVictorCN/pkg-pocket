package com.pkgpocket.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object ProManager {
    /*
     * Single-flight da validação Pro.
     *
     * Se duas Activities pedirem /status ao mesmo tempo,
     * apenas a primeira consulta o servidor.
     * A segunda espera e reutiliza o cache gravado.
     */
    private val statusMutex = Mutex()

    private const val PREFS = "pkg_pocket_pro"
    private const val KEY_EMAIL = "email"
    private const val KEY_PURCHASE_ID = "purchase_id"
    private const val KEY_PRO_ACTIVE = "pro_active"
    private const val KEY_PRO_STATUS = "pro_status"
    private const val KEY_LAST_STATUS_CHECK = "last_status_check"

    /*
     * A licença Pro já possui tolerância offline.
     * Não há motivo para consultar /status a cada troca de Activity.
     */
    private const val STATUS_CACHE_MS = 12L * 60L * 60L * 1000L

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
    data class Quote(
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

    fun cachedStatus(context: Context): Status =
        Status(
            active = isProCached(context),
            status = prefs(context)
                .getString(
                    KEY_PRO_STATUS,
                    if (isProCached(context)) "active" else "free"
                )
                .orEmpty()
                .ifBlank {
                    if (isProCached(context)) "active" else "free"
                }
        )

    fun isStatusCacheFresh(context: Context): Boolean {
        val lastCheck =
            prefs(context).getLong(KEY_LAST_STATUS_CHECK, 0L)

        if (lastCheck <= 0L) return false

        val age = System.currentTimeMillis() - lastCheck

        return age in 0 until STATUS_CACHE_MS
    }

    fun savePurchaseId(context: Context, purchaseId: String) {
        prefs(context).edit().putString(KEY_PURCHASE_ID, purchaseId).apply()
    }

    fun clearPurchaseId(context: Context) {
        prefs(context)
            .edit()
            .remove(KEY_PURCHASE_ID)
            .apply()
    }

    suspend fun getQuote(): Quote =
        withContext(Dispatchers.IO) {
            val response = request(
                "GET",
                "$apiBase/v1/pro/quote"
            )

            Quote(
                provider = response.optString("provider"),
                country = response.optString("country"),
                currency = response.optString("currency"),
                amountMinor = response.optInt("amount_minor", 0)
            )
        }

    fun formatPrice(currency: String, amountMinor: Int): String {
        val amount = amountMinor / 100.0

        return when (currency.uppercase()) {
            "BRL" -> "R$ %.2f".format(Locale.US, amount).replace(".", ",")
            "EUR" -> "€%.2f".format(Locale.US, amount)
            "USD" -> "$%.2f".format(Locale.US, amount)
            "GBP" -> "£%.2f".format(Locale.US, amount)
            else -> "%.2f %s".format(
                Locale.US,
                amount,
                currency.uppercase()
            )
        }
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
                    .putString(KEY_PRO_STATUS, "active")
                    .putLong(
                        KEY_LAST_STATUS_CHECK,
                        System.currentTimeMillis()
                    )
                    .remove(KEY_PURCHASE_ID)
                    .apply()
            }

            Reconcile(activated, status, paymentId)
        }

    suspend fun refreshStatus(
        context: Context,
        email: String,
        force: Boolean = false
    ): Status =
        withContext(Dispatchers.IO) {
            statusMutex.withLock {
                val normalized = email.trim().lowercase()
                val savedEmail = savedEmail(context).trim().lowercase()

                /*
                 * Esta verificação acontece DENTRO do lock.
                 *
                 * Isso é importante: se outra coroutine acabou de
                 * consultar o servidor enquanto esta aguardava,
                 * o cache já estará fresco e evitamos outro HTTP.
                 */
                if (
                    !force &&
                    normalized == savedEmail &&
                    isStatusCacheFresh(context)
                ) {
                    return@withLock cachedStatus(context)
                }

                val encoded =
                    URLEncoder.encode(normalized, "UTF-8")

                val response = request(
                    "GET",
                    "$apiBase/v1/pro/status?email=$encoded"
                )

                val active =
                    response.optBoolean("pro", false)

                val status =
                    response.optString(
                        "status",
                        if (active) "active" else "free"
                    )

                /*
                 * commit() é intencional aqui.
                 *
                 * O valor precisa estar persistido antes de liberar
                 * o Mutex para a próxima coroutine.
                 */
                prefs(context).edit()
                    .putString(KEY_EMAIL, normalized)
                    .putBoolean(KEY_PRO_ACTIVE, active)
                    .putString(KEY_PRO_STATUS, status)
                    .putLong(
                        KEY_LAST_STATUS_CHECK,
                        System.currentTimeMillis()
                    )
                    .commit()

                Status(active, status)
            }
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
