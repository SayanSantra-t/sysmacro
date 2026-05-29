package com.example

import android.content.Context
import android.util.Log

data class Knock(
    val relativeTimeMs: Long,  // Relative to first knock
    val ratioX: Float,
    val ratioY: Float,
    val ratioZ: Float
)

data class CalibrationData(
    val meanX: Float = 0f,
    val meanY: Float = 0f,
    val meanZ: Float = 9.8f,
    val stdDev: Float = 0.1f,
    val timestamp: Long = 0L
)

enum class ActionPayload {
    CAMERA,
    AUDIO
}

enum class AudioType {
    NOTIFICATION,
    ALARM,
    RINGTONE
}

object SeismicConfig {
    private const val TAG = "SeismicConfig"
    private const val PREFS_NAME = "seismic_macro_prefs"
    private const val KEY_CALIBRATION = "calibration"
    private const val KEY_PATTERN = "recorded_pattern"
    private const val KEY_SENSITIVITY = "sensitivity_k"
    private const val KEY_PAYLOAD = "selected_payload"
    private const val KEY_AUDIO_TYPE = "selected_audio_type"
    private const val KEY_AUDIO_URI = "selected_audio_uri"
    private const val KEY_AUDIO_TITLE = "selected_audio_title"
    private const val KEY_STRICTNESS = "pattern_strictness"
    private const val KEY_CAMERA_LENS = "camera_lens_facing"

    fun getCameraLensFacing(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 0 = Back, 1 = Front, 2 = Both
        return prefs.getInt(KEY_CAMERA_LENS, 0)
    }

    fun saveCameraLensFacing(context: Context, lens: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CAMERA_LENS, lens).apply()
    }

    fun getStrictness(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_STRICTNESS, 1.0f)
    }

    fun saveStrictness(context: Context, strictness: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_STRICTNESS, strictness).apply()
    }

    fun getSelectedAudioUri(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AUDIO_URI, null)
    }

    fun saveSelectedAudioUri(context: Context, uri: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AUDIO_URI, uri).apply()
    }

    fun getSelectedAudioTitle(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AUDIO_TITLE, "Default System Tone") ?: "Default System Tone"
    }

    fun saveSelectedAudioTitle(context: Context, title: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AUDIO_TITLE, title).apply()
    }

    fun getSensitivity(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_SENSITIVITY, 4.0f)
    }

    fun saveSensitivity(context: Context, k: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_SENSITIVITY, k).apply()
        Log.d(TAG, "Saved sensitivity: $k")
    }

    fun getSelectedPayload(context: Context): ActionPayload {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_PAYLOAD, ActionPayload.AUDIO.name) ?: ActionPayload.AUDIO.name
        return try {
            ActionPayload.valueOf(name)
        } catch (e: Exception) {
            ActionPayload.AUDIO
        }
    }

    fun saveSelectedPayload(context: Context, payload: ActionPayload) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PAYLOAD, payload.name).apply()
        Log.d(TAG, "Saved payload: $payload")
    }

    fun getSelectedAudioType(context: Context): AudioType {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_AUDIO_TYPE, AudioType.NOTIFICATION.name) ?: AudioType.NOTIFICATION.name
        return try {
            AudioType.valueOf(name)
        } catch (e: Exception) {
            AudioType.NOTIFICATION
        }
    }

    fun saveSelectedAudioType(context: Context, type: AudioType) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AUDIO_TYPE, type.name).apply()
        Log.d(TAG, "Saved audio type: $type")
    }

    fun getCalibration(context: Context): CalibrationData {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CALIBRATION, null) ?: return CalibrationData()
        return try {
            val parts = raw.split(",")
            if (parts.size >= 5) {
                CalibrationData(
                    meanX = parts[0].toFloat(),
                    meanY = parts[1].toFloat(),
                    meanZ = parts[2].toFloat(),
                    stdDev = parts[3].toFloat(),
                    timestamp = parts[4].toLong()
                )
            } else {
                CalibrationData()
            }
        } catch (e: Exception) {
            CalibrationData()
        }
    }

    fun saveCalibration(context: Context, data: CalibrationData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = "${data.meanX},${data.meanY},${data.meanZ},${data.stdDev},${data.timestamp}"
        prefs.edit().putString(KEY_CALIBRATION, raw).apply()
        Log.d(TAG, "Saved calibration: $data")
    }

    fun getRecordedPattern(context: Context): List<Knock> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PATTERN, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            raw.split(";").filter { it.isNotBlank() }.map { knockStr ->
                val parts = knockStr.split(",")
                Knock(
                    relativeTimeMs = parts[0].toLong(),
                    ratioX = parts[1].toFloat(),
                    ratioY = parts[2].toFloat(),
                    ratioZ = parts[3].toFloat()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRecordedPattern(context: Context, pattern: List<Knock>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = pattern.joinToString(";") { "${it.relativeTimeMs},${it.ratioX},${it.ratioY},${it.ratioZ}" }
        prefs.edit().putString(KEY_PATTERN, raw).apply()
        Log.d(TAG, "Saved recorded pattern with size: ${pattern.size}")
    }
}
