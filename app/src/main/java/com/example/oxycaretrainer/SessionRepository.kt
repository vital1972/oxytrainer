package com.example.oxycaretrainer

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream

class SessionRepository(context: Context) {
    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }
    fun save(session: TrainingSession) {
        val target = AtomicFile(File(dir, "${session.id}.json"))
        var output: FileOutputStream? = null
        try {
            output = target.startWrite()
            output.write(session.toJson().toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
            DiagnosticLogger.info("session_saved", "id=${session.id} samples=${session.samples.size}")
        } catch (error: Throwable) {
            output?.let { target.failWrite(it) }
            DiagnosticLogger.error("session_save_failed", error, "id=${session.id}")
            throw error
        }
    }
    fun get(id: String): TrainingSession? = runCatching { TrainingSession.fromJson(org.json.JSONObject(File(dir, "$id.json").readText())) }.getOrNull()
    fun all(): List<TrainingSession> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { runCatching { TrainingSession.fromJson(org.json.JSONObject(it.readText())) }.getOrNull() }
        .sortedByDescending { it.startTime }

    /** Импорт: сеансы с совпадающим id перезаписываются. Возвращает число сохранённых. */
    fun importAll(sessions: List<TrainingSession>): Int = sessions.count { runCatching { save(it) }.isSuccess }

    fun delete(id: String): Boolean {
        val deleted = File(dir, "$id.json").delete()
        if (deleted) DiagnosticLogger.info("session_deleted", "id=$id")
        else DiagnosticLogger.warn("session_delete_failed", "id=$id")
        return deleted
    }
}
