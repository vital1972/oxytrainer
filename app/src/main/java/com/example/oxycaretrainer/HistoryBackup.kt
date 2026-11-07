package com.example.oxycaretrainer

import org.json.JSONArray
import org.json.JSONObject

/** Экспорт и импорт всей истории тренировок одним JSON-файлом. */
object HistoryBackup {
    const val FORMAT_VERSION = 1
    private const val APP_ID = "oxycaretrainer"

    data class ImportResult(val sessions: List<TrainingSession>, val skipped: Int)

    fun toJson(sessions: List<TrainingSession>, exportedAt: Long = System.currentTimeMillis()): String =
        JSONObject().apply {
            put("app", APP_ID)
            put("format", FORMAT_VERSION)
            put("exportedAt", exportedAt)
            put("sessions", JSONArray().apply { sessions.forEach { put(it.toJson()) } })
        }.toString()

    /** Бросает [IllegalArgumentException], если файл не является экспортом OxyCare Trainer. */
    fun parse(text: String): ImportResult {
        val root = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("Файл не является JSON", e) }
        require(root.optString("app") == APP_ID) { "Это не файл истории OxyCare Trainer" }
        require(root.optInt("format", 0) in 1..FORMAT_VERSION) { "Неподдерживаемая версия файла" }
        val array = root.optJSONArray("sessions") ?: throw IllegalArgumentException("В файле нет списка сеансов")
        val sessions = ArrayList<TrainingSession>()
        var skipped = 0
        for (i in 0 until array.length()) {
            val parsed = runCatching { TrainingSession.fromJson(array.getJSONObject(i)) }.getOrNull()
            if (parsed == null || !isSafeId(parsed.id)) skipped++ else sessions.add(parsed)
        }
        return ImportResult(sessions, skipped)
    }

    /** Идентификатор становится именем файла, поэтому допускаем только безопасные символы. */
    fun isSafeId(id: String) = id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
