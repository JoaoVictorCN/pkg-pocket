package com.pkgpocket.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object RpiClient {
    data class InstallResult(val taskId: Int?, val raw: String)

    fun install(ps4Ip: String, pkgUrl: String): InstallResult {
        val body = JSONObject()
            .put("type", "direct")
            .put("packages", org.json.JSONArray().put(pkgUrl))

        val raw = post(ps4Ip, "/api/install", body)
        val json = JSONObject(normalizeJson(raw))
        val id = json.optInt("task_id", -1).takeIf { it >= 0 }

        return InstallResult(id, raw)
    }

    fun findTask(ps4Ip: String, contentId: String, subType: Int): Int? {
        val raw = post(
            ps4Ip,
            "/api/find_task",
            JSONObject()
                .put("content_id", contentId)
                .put("sub_type", subType)
        )

        val json = JSONObject(normalizeJson(raw))
        if (!json.optString("status").equals("success", ignoreCase = true)) return null
        return json.optInt("task_id", -1).takeIf { it >= 0 }
    }

    fun progress(ps4Ip: String, taskId: Int): JSONObject {
        val raw = post(
            ps4Ip,
            "/api/get_task_progress",
            JSONObject().put("task_id", taskId)
        )
        return JSONObject(normalizeJson(raw))
    }

    fun pause(ps4Ip: String, taskId: Int) =
        post(ps4Ip, "/api/pause_task", JSONObject().put("task_id", taskId))

    fun resume(ps4Ip: String, taskId: Int) =
        post(ps4Ip, "/api/resume_task", JSONObject().put("task_id", taskId))

    fun stop(ps4Ip: String, taskId: Int) =
        post(ps4Ip, "/api/stop_task", JSONObject().put("task_id", taskId))

    fun unregister(ps4Ip: String, taskId: Int) =
        post(ps4Ip, "/api/unregister_task", JSONObject().put("task_id", taskId))

    /*
     * Mantemos a implementação simples que já funcionou com o RPI.
     * Não forçamos Connection: close e não repetimos /api/install
     * automaticamente, pois a requisição pode ter sido aceita mesmo quando
     * a resposta se perde.
     */
    private fun post(ip: String, path: String, json: JSONObject): String {
        val conn = (URL("http://$ip:12800$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 3000
            readTimeout = 7000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        conn.outputStream.use {
            it.write(json.toString().toByteArray(Charsets.UTF_8))
            it.flush()
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (code !in 200..299) {
            error("RPI respondeu HTTP $code: $text")
        }
        if (text.isBlank()) {
            error("RPI respondeu sem corpo")
        }

        return text
    }

    /*
     * O RPI usa literais hexadecimais sem aspas em alguns campos
     * (ex.: 0x1A2B), o que não é JSON padrão. Convertemos para decimal
     * antes de entregar ao org.json.
     */
    private fun normalizeJson(raw: String): String {
        return raw.replace(Regex("""0[xX][0-9a-fA-F]+""")) { match ->
            val hex = match.value.substring(2)
            runCatching { hex.toULong(16).toString() }.getOrElse { "0" }
        }
    }

    fun bytesTotal(j: JSONObject): Long =
        j.optLong("length_total", j.optLong("length", 0L))

    fun bytesDone(j: JSONObject): Long =
        j.optLong("transferred_total", j.optLong("transferred", 0L))

    fun percent(j: JSONObject): Int {
        val total = bytesTotal(j)
        val done = bytesDone(j)

        return if (total > 0) {
            ((done * 100L) / total).toInt().coerceIn(0, 100)
        } else {
            j.optInt("preparing_percent", 0).coerceIn(0, 100)
        }
    }

    fun isFinished(j: JSONObject): Boolean {
        val total = bytesTotal(j)
        val done = bytesDone(j)
        return total > 0 && done >= total
    }
}
