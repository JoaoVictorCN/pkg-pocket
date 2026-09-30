package com.pkgpocket.app

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
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
    private const val KEY_QUOTE_PROVIDER = "quote_provider"
    private const val KEY_QUOTE_COUNTRY = "quote_country"
    private const val KEY_QUOTE_CURRENCY = "quote_currency"
    private const val KEY_QUOTE_AMOUNT_MINOR = "quote_amount_minor"
    private const val KEY_LAST_QUOTE_CHECK = "last_quote_check"

    /*
     * A licença Pro já possui tolerância offline.
     * Não há motivo para consultar /status a cada troca de Activity.
     */
    private const val STATUS_CACHE_MS = 12L * 60L * 60L * 1000L

    /*
     * Depois de uma validação online bem-sucedida,
     * o Pro continua disponível offline por até 72 horas.
     */
    private const val OFFLINE_GRACE_MS =
        72L * 60L * 60L * 1000L

    /*
     * Quote depende do país/IP atual.
     * Cache curto evita chamadas repetidas entre Home e Configurações
     * sem manter preço/região antigo por muito tempo após troca de rede/VPN.
     */
    private const val QUOTE_CACHE_MS = 15L * 60L * 1000L
    private val quoteMutex = Mutex()

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

    fun isProCached(context: Context): Boolean {
        val preferences = prefs(context)

        if (
            !preferences.getBoolean(
                KEY_PRO_ACTIVE,
                false
            )
        ) {
            return false
        }

        val lastCheck =
            preferences.getLong(
                KEY_LAST_STATUS_CHECK,
                0L
            )

        if (lastCheck <= 0L) {
            return false
        }

        val age =
            System.currentTimeMillis() -
                lastCheck

        return age in
            0 until OFFLINE_GRACE_MS
    }

    fun cachedStatus(
        context: Context
    ): Status {
        val preferences =
            prefs(context)

        val rawActive =
            preferences.getBoolean(
                KEY_PRO_ACTIVE,
                false
            )

        val active =
            isProCached(context)

        val rawStatus =
            preferences.getString(
                KEY_PRO_STATUS,
                if (rawActive) {
                    "active"
                } else {
                    "free"
                }
            )
                .orEmpty()
                .ifBlank {
                    if (rawActive) {
                        "active"
                    } else {
                        "free"
                    }
                }

        val status =
            if (
                rawActive &&
                !active
            ) {
                "offline_expired"
            } else {
                rawStatus
            }

        return Status(
            active,
            status
        )
    }

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

    /*
     * ANDROID_ID bruto nunca sai do aparelho.
     * O backend recebe somente um SHA-256.
     */
    fun deviceHash(
        context: Context
    ): String {
        val androidId =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
                ?.trim()
                .orEmpty()

        if (androidId.isBlank()) {
            throw IllegalStateException(
                "Android device identifier unavailable"
            )
        }

        val material =
            "pkg-pocket-pro:v1:" +
                context.packageName +
                ":" +
                androidId

        return MessageDigest
            .getInstance("SHA-256")
            .digest(
                material.toByteArray(
                    Charsets.UTF_8
                )
            )
            .joinToString("") {
                "%02x".format(
                    it.toInt() and 0xff
                )
            }
    }

    suspend fun getQuote(context: Context): Quote =
        withContext(Dispatchers.IO) {
            quoteMutex.withLock {
                val preferences = prefs(context)
                val lastCheck =
                    preferences.getLong(KEY_LAST_QUOTE_CHECK, 0L)
                val age =
                    System.currentTimeMillis() - lastCheck

                val cachedCurrency =
                    preferences.getString(KEY_QUOTE_CURRENCY, "")
                        .orEmpty()
                val cachedAmount =
                    preferences.getInt(KEY_QUOTE_AMOUNT_MINOR, 0)

                if (
                    lastCheck > 0L &&
                    age in 0 until QUOTE_CACHE_MS &&
                    cachedCurrency.isNotBlank() &&
                    cachedAmount > 0
                ) {
                    return@withLock Quote(
                        provider = preferences
                            .getString(KEY_QUOTE_PROVIDER, "")
                            .orEmpty(),
                        country = preferences
                            .getString(KEY_QUOTE_COUNTRY, "")
                            .orEmpty(),
                        currency = cachedCurrency,
                        amountMinor = cachedAmount
                    )
                }

                val response = request(
                    "GET",
                    "$apiBase/v1/pro/quote"
                )

                val quote = Quote(
                    provider = response.optString("provider"),
                    country = response.optString("country"),
                    currency = response.optString("currency"),
                    amountMinor = response.optInt("amount_minor", 0)
                )

                if (
                    quote.currency.isNotBlank() &&
                    quote.amountMinor > 0
                ) {
                    preferences.edit()
                        .putString(KEY_QUOTE_PROVIDER, quote.provider)
                        .putString(KEY_QUOTE_COUNTRY, quote.country)
                        .putString(KEY_QUOTE_CURRENCY, quote.currency)
                        .putInt(KEY_QUOTE_AMOUNT_MINOR, quote.amountMinor)
                        .putLong(
                            KEY_LAST_QUOTE_CHECK,
                            System.currentTimeMillis()
                        )
                        .commit()
                }

                quote
            }
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
                    .put(
                        "email",
                        normalized
                    )
                    .put(
                        "device_hash",
                        deviceHash(context)
                    )

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
                JSONObject()
                    .put(
                        "purchase_id",
                        purchaseId
                    )
                    .put(
                        "device_hash",
                        deviceHash(context)
                    )
                    .toString()
            )
            val activated = response.optBoolean("activated", false)
            val status = response.optString("status", "unknown")
            val paymentId = response.optString("payment_id").takeIf { it.isNotBlank() }

            if (activated) {
                prefs(context)
                    .edit()
                    .putBoolean(
                        KEY_PRO_ACTIVE,
                        true
                    )
                    .putString(
                        KEY_PRO_STATUS,
                        "active"
                    )
                    .putLong(
                        KEY_LAST_STATUS_CHECK,
                        System.currentTimeMillis()
                    )
                    .remove(
                        KEY_PURCHASE_ID
                    )
                    .apply()
            } else if (
                status.lowercase() in
                    setOf(
                        "device_mismatch",
                        "device_unbound",
                        "revoked",
                        "refunded",
                        "charged_back",
                        "cancelled",
                        "canceled"
                    )
            ) {
                prefs(context)
                    .edit()
                    .putBoolean(
                        KEY_PRO_ACTIVE,
                        false
                    )
                    .putString(
                        KEY_PRO_STATUS,
                        status
                    )
                    .putLong(
                        KEY_LAST_STATUS_CHECK,
                        System.currentTimeMillis()
                    )
                    .apply()
            }

            Reconcile(
                activated,
                status,
                paymentId
            )
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
                    URLEncoder.encode(
                        normalized,
                        "UTF-8"
                    )

                val encodedDevice =
                    URLEncoder.encode(
                        deviceHash(context),
                        "UTF-8"
                    )

                val response =
                    request(
                        "GET",
                        "$apiBase/v1/pro/status?email=$encoded&device_hash=$encodedDevice"
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
