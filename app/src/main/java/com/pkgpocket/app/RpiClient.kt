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
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val id = json?.optInt("task_id", -1)?.takeIf { it >= 0 }

        return InstallResult(id, raw)
    }

    fun progress(ps4Ip: String, taskId: Int): JSONObject {
        val raw = post(
            ps4Ip,
            "/api/get_task_progress",
            JSONObject().put("task_id", taskId)
        )
        return JSONObject(raw)
    }

    fun pause(ps4Ip: String, taskId: Int) =
        post(ps4Ip, "/api/pause_task", JSONObject().put("task_id", taskId))

    fun resume(ps4Ip: String, taskId: Int) =
        post(ps4Ip, "/api/resume_task", JSONObject().put("task_id", taskId))

    fun stop(ps4Ip: String, taskId: Int) =
        post(ps4Ip, "/api/stop_task", JSONObject().put("task_id", taskId))

    fun unregister(ps4Ip: String, taskId: Int) =
        post(ps4Ip, "/api/unregister_task", JSONObject().put("task_id", taskId))

    private fun post(ip: String, path: String, json: JSONObject): String {
        val conn = (URL("http://$ip:12800$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2500
            readTimeout = 5000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
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

        return text
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
