package com.example.oxycaretrainer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val sha256Url: String?
)

/** Сравнение версий вида 1.2, v1.3.0, 1.10.1-beta (числовые части). */
object Versions {
    fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { it.toIntOrNull() ?: 0 }

    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest); val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/**
 * Проверка и установка обновлений из GitHub Releases.
 * Android не позволяет обычному приложению ставить обновления молча: приложение скачивает APK,
 * проверяет размер и контрольную сумму, а установку подтверждает пользователь в системном окне.
 * Система принимает обновление только с той же подписью, что и у установленного приложения.
 */
object UpdateChecker {
    const val REPO = "vital1972/oxytrainer"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val CHECK_INTERVAL_MS = 24L * 3600 * 1000

    /** Скачивать разрешено только из релизов этого репозитория. */
    fun isAllowedDownloadUrl(url: String) = url.startsWith(DOWNLOAD_PREFIX)

    fun parseLatest(json: String): ReleaseInfo? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null; var sha: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name"); val url = a.optString("browser_download_url")
            if (!isAllowedDownloadUrl(url)) continue
            if (name.endsWith(".apk") && apk == null) apk = a
            if (name.endsWith(".apk.sha256")) sha = url
        }
        val chosen = apk ?: return null
        return ReleaseInfo(tag, tag.removePrefix("v"), o.optString("body"), chosen.getString("browser_download_url"), chosen.getString("name"), chosen.optLong("size"), sha)
    }

    /** Блокирующий сетевой вызов — запускать не в главном потоке. */
    fun fetchLatest(): ReleaseInfo? {
        val c = URL(API).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000; c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) return null
            return parseLatest(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally { c.disconnect() }
    }

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    fun isUpdateAvailable(context: Context, info: ReleaseInfo) = Versions.isNewer(info.version, currentVersion(context))

    /** Скачивает APK в кэш, проверяя размер и SHA-256 (если опубликован). Блокирующий вызов. */
    fun download(context: Context, info: ReleaseInfo, onProgress: (Int) -> Unit): File {
        require(isAllowedDownloadUrl(info.apkUrl)) { "Недопустимый адрес загрузки" }
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "oxycare-update.apk")
        val c = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 30000
            check(c.responseCode == 200) { "Сервер вернул ${c.responseCode}" }
            val host = c.url.host
            check(host == "github.com" || host.endsWith(".githubusercontent.com")) { "Неожиданный адрес загрузки" }
            val total = info.apkSize.takeIf { it > 0 } ?: c.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L; var last = -1
            c.inputStream.use { input -> out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    output.write(buf, 0, n); digest.update(buf, 0, n); done += n
                    if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                }
            } }
            if (info.apkSize > 0) check(done == info.apkSize) { "Размер файла не совпал" }
            info.sha256Url?.let { url ->
                val expected = fetchText(url).trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(expected == actual) { "Контрольная сумма не совпала" }
            }
            return out
        } catch (e: Throwable) { out.delete(); throw e } finally { c.disconnect() }
    }

    private fun fetchText(url: String): String {
        require(isAllowedDownloadUrl(url))
        val c = URL(url).openConnection() as HttpURLConnection
        try { c.connectTimeout = 10000; c.readTimeout = 15000; return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { c.disconnect() }
    }

    /** Открывает системный установщик. Если у приложения нет права ставить пакеты — открывает соответствующие настройки. */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Раз в сутки при запуске проверяет обновления и сообщает о новой версии. Ошибки сети молча игнорируются. */
    fun autoCheck(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("update_state", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < CHECK_INTERVAL_MS) return
        Thread {
            runCatching {
                val info = fetchLatest()
                prefs.edit().putLong("last_check", now).apply()
                if (info != null && isUpdateAvailable(app, info)) {
                    prefs.edit().putString("available", info.version).apply()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(app, "Доступна версия ${info.version}: Настройки → Обновления", Toast.LENGTH_LONG).show()
                    }
                } else prefs.edit().remove("available").apply()
            }
        }.start()
    }
}
