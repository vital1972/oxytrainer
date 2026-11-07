package com.example.oxycaretrainer

import org.json.JSONArray
import org.json.JSONObject

data class TrainingSample(val elapsedSeconds: Long, val spo2: Int, val pulse: Int)

data class TrainingSession(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val plannedMinutes: Int,
    val phaseMinutes: Int,
    val lowSpo2: Int,
    val samples: List<TrainingSample>,
    val activeDurationSeconds: Long? = null
) {
    val durationSeconds get() = activeDurationSeconds ?: ((endTime - startTime) / 1000).coerceAtLeast(0)
    val averageSpo2 get() = samples.map { it.spo2 }.average().takeIf { !it.isNaN() }
    val minSpo2 get() = samples.minOfOrNull { it.spo2 }
    val maxSpo2 get() = samples.maxOfOrNull { it.spo2 }
    val averagePulse get() = samples.map { it.pulse }.average().takeIf { !it.isNaN() }
    val minPulse get() = samples.minOfOrNull { it.pulse }
    val maxPulse get() = samples.maxOfOrNull { it.pulse }

    fun toJson() = JSONObject().apply {
        put("id", id); put("start", startTime); put("end", endTime)
        put("planned", plannedMinutes); put("phase", phaseMinutes); put("low", lowSpo2)
        activeDurationSeconds?.let { put("activeDuration", it) }
        put("samples", JSONArray().apply { samples.forEach { put(JSONObject().put("t", it.elapsedSeconds).put("s", it.spo2).put("p", it.pulse)) } })
    }
    companion object {
        fun fromJson(o: JSONObject): TrainingSession {
            val a = o.getJSONArray("samples")
            val samples = (0 until a.length()).map { a.getJSONObject(it) }.map { TrainingSample(it.getLong("t"), it.getInt("s"), it.getInt("p")) }
            val activeDuration = if (o.has("activeDuration")) o.getLong("activeDuration") else null
            return TrainingSession(o.getString("id"), o.getLong("start"), o.getLong("end"), o.optInt("planned"), o.optInt("phase"), o.optInt("low"), samples, activeDuration)
        }
    }
}
