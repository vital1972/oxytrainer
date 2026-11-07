package com.example.oxycaretrainer

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class ActiveTrainingCheckpoint(
    val id: String,
    val startTime: Long,
    val lastCheckpointWall: Long,
    val plannedMinutes: Int,
    val phaseMinutes: Int,
    val lowSpo2: Int,
    val activeElapsedMs: Long,
    val phaseElapsedMs: Long,
    val currentHypoxic: Boolean,
    val awaitingPhaseConfirmation: Boolean,
    val timerOnlyMode: Boolean,
    val sensorDisconnected: Boolean,
    val samples: List<TrainingSample>
)

class ActiveSessionStore(context: Context) {
    private val directory = File(context.filesDir, "active_training").apply { mkdirs() }
    private val metadataFile = AtomicFile(File(directory, "active_session.json"))
    private val samplesFile = File(directory, "active_samples.jsonl")

    @Synchronized
    fun begin(checkpoint: ActiveTrainingCheckpoint) {
        samplesFile.delete()
        saveMetadata(checkpoint)
    }

    @Synchronized
    fun checkpoint(checkpoint: ActiveTrainingCheckpoint) {
        saveMetadata(checkpoint)
    }

    @Synchronized
    fun appendSample(sample: TrainingSample) {
        val line = JSONObject()
            .put("t", sample.elapsedSeconds)
            .put("s", sample.spo2)
            .put("p", sample.pulse)
            .toString() + "\n"
        FileOutputStream(samplesFile, true).use { stream ->
            stream.write(line.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    @Synchronized
    fun load(): ActiveTrainingCheckpoint? {
        if (!metadataFile.baseFile.exists()) return null
        return runCatching {
            val metadata = JSONObject(metadataFile.openRead().bufferedReader().use { it.readText() })
            val samples = if (samplesFile.exists()) {
                samplesFile.useLines { lines ->
                    lines.filter { it.isNotBlank() }.mapNotNull { line ->
                        runCatching {
                            val item = JSONObject(line)
                            TrainingSample(item.getLong("t"), item.getInt("s"), item.getInt("p"))
                        }.getOrNull()
                    }.toList()
                }
            } else {
                emptyList()
            }
            ActiveTrainingCheckpoint(
                id = metadata.getString("id"),
                startTime = metadata.getLong("startTime"),
                lastCheckpointWall = metadata.getLong("lastCheckpointWall"),
                plannedMinutes = metadata.getInt("plannedMinutes"),
                phaseMinutes = metadata.getInt("phaseMinutes"),
                lowSpo2 = metadata.getInt("lowSpo2"),
                activeElapsedMs = metadata.getLong("activeElapsedMs"),
                phaseElapsedMs = metadata.getLong("phaseElapsedMs"),
                currentHypoxic = metadata.getBoolean("currentHypoxic"),
                awaitingPhaseConfirmation = metadata.getBoolean("awaitingPhaseConfirmation"),
                timerOnlyMode = metadata.optBoolean("timerOnlyMode", false),
                sensorDisconnected = metadata.optBoolean("sensorDisconnected", false),
                samples = samples
            )
        }.onFailure {
            DiagnosticLogger.error("checkpoint_load_failed", it)
        }.getOrNull()
    }

    @Synchronized
    fun clear() {
        metadataFile.delete()
        samplesFile.delete()
    }

    private fun saveMetadata(checkpoint: ActiveTrainingCheckpoint) {
        val json = JSONObject()
            .put("id", checkpoint.id)
            .put("startTime", checkpoint.startTime)
            .put("lastCheckpointWall", checkpoint.lastCheckpointWall)
            .put("plannedMinutes", checkpoint.plannedMinutes)
            .put("phaseMinutes", checkpoint.phaseMinutes)
            .put("lowSpo2", checkpoint.lowSpo2)
            .put("activeElapsedMs", checkpoint.activeElapsedMs)
            .put("phaseElapsedMs", checkpoint.phaseElapsedMs)
            .put("currentHypoxic", checkpoint.currentHypoxic)
            .put("awaitingPhaseConfirmation", checkpoint.awaitingPhaseConfirmation)
            .put("timerOnlyMode", checkpoint.timerOnlyMode)
            .put("sensorDisconnected", checkpoint.sensorDisconnected)

        var output: FileOutputStream? = null
        try {
            output = metadataFile.startWrite()
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            metadataFile.finishWrite(output)
        } catch (error: Throwable) {
            output?.let { metadataFile.failWrite(it) }
            DiagnosticLogger.error("checkpoint_write_failed", error)
        }
    }
}
