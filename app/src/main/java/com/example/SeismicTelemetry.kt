package com.example

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SeismicTelemetry {
    val rawX = MutableStateFlow(0f)
    val rawY = MutableStateFlow(0f)
    val rawZ = MutableStateFlow(0f)
    val rawMag = MutableStateFlow(0f)
    
    val thresholdPercent = MutableStateFlow(0f) // Current percentage compared to threshold
    
    val latestDetectorKnockTime = MutableStateFlow(0L)
    val detectedKnocksCount = MutableStateFlow(0)
    
    val consoleLogs = MutableStateFlow<List<String>>(listOf("System ready. Standby."))

    fun log(message: String) {
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val current = consoleLogs.value.toMutableList()
        current.add(0, "[$timeStr] $message")
        if (current.size > 40) {
            current.removeAt(current.lastIndex)
        }
        consoleLogs.value = current
    }
    
    fun clearLogs() {
        consoleLogs.value = listOf("Logs cleared.")
    }
}
