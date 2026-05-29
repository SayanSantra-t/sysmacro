package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Vibrator
import android.os.VibrationEffect
import android.media.RingtoneManager
import android.util.Log
import java.util.Locale
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import java.io.File
import android.os.Environment

class MainViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val context = application.applicationContext
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    companion object {
        private const val TAG = "MainViewModel"
        private const val RECORD_DURATION_MS = 6000L
        private const val DEBOUNCE_MS = 280L
    }

    // Calibration UI State
    private val _isCalibrating = MutableStateFlow(false)
    val isCalibrating = _isCalibrating.asStateFlow()

    private val _calibrationProgress = MutableStateFlow(0f)
    val calibrationProgress = _calibrationProgress.asStateFlow()

    private val _calibration = MutableStateFlow(SeismicConfig.getCalibration(context))
    val calibration = _calibration.asStateFlow()

    // Recording UI State
    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _recordingTimeoutProgress = MutableStateFlow(0f)
    val recordingTimeoutProgress = _recordingTimeoutProgress.asStateFlow()

    private val _recordedPattern = MutableStateFlow<List<Knock>>(SeismicConfig.getRecordedPattern(context))
    val recordedPattern = _recordedPattern.asStateFlow()

    // Temporary list for currently being recorded sequence
    private val _tempKnocks = MutableStateFlow<List<Knock>>(emptyList())
    val tempKnocks = _tempKnocks.asStateFlow()

    // Sensitivity state
    private val _sensitivityK = MutableStateFlow(SeismicConfig.getSensitivity(context))
    val sensitivityK = _sensitivityK.asStateFlow()

    private val _strictness = MutableStateFlow(SeismicConfig.getStrictness(context))
    val strictness = _strictness.asStateFlow()

    // Camera images state
    private val _recentImages = MutableStateFlow<List<File>>(emptyList())
    val recentImages = _recentImages.asStateFlow()

    fun loadRecentImages() {
        val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val files = targetDir.listFiles { file -> file.name.startsWith("seismic_") && file.name.endsWith(".jpg") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        _recentImages.value = files
    }

    fun deleteImage(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file: ${file.name}", e)
        }
        loadRecentImages()
    }

    fun clearAllImages() {
        try {
            val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
            val files = targetDir.listFiles { file -> file.name.startsWith("seismic_") && file.name.endsWith(".jpg") }
            files?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear images", e)
        }
        loadRecentImages()
    }

    // Action payload setting
    private val _selectedPayload = MutableStateFlow(SeismicConfig.getSelectedPayload(context))
    val selectedPayload = _selectedPayload.asStateFlow()

    private val _cameraLensFacing = MutableStateFlow(SeismicConfig.getCameraLensFacing(context))
    val cameraLensFacing = _cameraLensFacing.asStateFlow()

    // Selected audio type setting
    private val _selectedAudioType = MutableStateFlow(SeismicConfig.getSelectedAudioType(context))
    val selectedAudioType = _selectedAudioType.asStateFlow()

    // Custom audio alert selection states
    private val _systemAudioTones = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val systemAudioTones = _systemAudioTones.asStateFlow()

    private val _selectedAudioUri = MutableStateFlow(SeismicConfig.getSelectedAudioUri(context))
    val selectedAudioUri = _selectedAudioUri.asStateFlow()

    private val _selectedAudioTitle = MutableStateFlow(SeismicConfig.getSelectedAudioTitle(context))
    val selectedAudioTitle = _selectedAudioTitle.asStateFlow()

    fun loadAudioTones() {
        viewModelScope.launch {
            try {
                val list = mutableListOf<Pair<String, String>>()
                val manager = RingtoneManager(context)
                manager.setType(RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM)
                val cursor = manager.cursor
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                        val uri = manager.getRingtoneUri(cursor.position)?.toString()
                        if (uri != null) {
                            list.add(Pair(title, uri))
                        }
                    }
                }
                _systemAudioTones.value = list
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load system ringtones", e)
            }
        }
    }

    fun updateSelectedAudioTone(title: String, uri: String?) {
        _selectedAudioUri.value = uri
        _selectedAudioTitle.value = title
        SeismicConfig.saveSelectedAudioUri(context, uri)
        SeismicConfig.saveSelectedAudioTitle(context, title)
        
        // Notify background service
        if (SeismicTriggerService.isServiceRunning.value) {
            val intent = Intent(context, SeismicTriggerService::class.java)
            context.startService(intent)
        }
    }

    // Active calibration sample cache
    private val calibrationSamples = mutableListOf<FloatArray>()
    
    // Live sensor values when UI is active and service is NOT running
    private val _liveX = MutableStateFlow(0f)
    val liveX = _liveX.asStateFlow()
    private val _liveY = MutableStateFlow(0f)
    val liveY = _liveY.asStateFlow()
    private val _liveZ = MutableStateFlow(0f)
    val liveZ = _liveZ.asStateFlow()
    private val _liveMag = MutableStateFlow(0f)
    val liveMag = _liveMag.asStateFlow()

    // Temporary knock tracker for active recording session
    private var recordFirstKnockTime = 0L
    private var recordLastKnockTime = 0L

    private var activeJob: Job? = null

    // Vibration feedback helper
    private fun triggerVibration() {
        try {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(80)
                }
            }
        } catch (e: Exception) {
            // Ignore vibration errors
        }
    }

    init {
        // Register transient accelerometer listener for UI-level live telemetry
        try {
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register sensor in ViewModel", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Unregister local sensor listener to prevent memory leaks
        sensorManager.unregisterListener(this)
        activeJob?.cancel()
    }

    // Start background Foreground service
    fun startService() {
        val intent = Intent(context, SeismicTriggerService::class.java)
        ContextCompat.startForegroundService(context, intent)
        SeismicTelemetry.log("Dispatched request to start background monitor service.")
    }

    // Stop background Foreground service
    fun stopService() {
        val intent = Intent(context, SeismicTriggerService::class.java)
        context.stopService(intent)
        SeismicTelemetry.log("Dispatched request to terminate background monitor service.")
    }

    // Adjust parameters
    fun updateStrictness(value: Float) {
        _strictness.value = value
        SeismicConfig.saveStrictness(context, value)
        
        if (SeismicTriggerService.isServiceRunning.value) {
            val intent = Intent(context, SeismicTriggerService::class.java)
            context.startService(intent)
        }
    }

    fun updateSensitivity(value: Float) {
        _sensitivityK.value = value
        SeismicConfig.saveSensitivity(context, value)
        
        // If foreground service is running, it will read updated attributes on startCommand refresh trigger
        if (SeismicTriggerService.isServiceRunning.value) {
            val intent = Intent(context, SeismicTriggerService::class.java)
            context.startService(intent)
        }
    }

    fun updateSelectedPayload(payload: ActionPayload) {
        _selectedPayload.value = payload
        SeismicConfig.saveSelectedPayload(context, payload)
        
        // Notify background service
        if (SeismicTriggerService.isServiceRunning.value) {
            val intent = Intent(context, SeismicTriggerService::class.java)
            context.startService(intent)
        }
    }

    fun updateCameraLensFacing(lensId: Int) {
        _cameraLensFacing.value = lensId
        SeismicConfig.saveCameraLensFacing(context, lensId)
        
        if (SeismicTriggerService.isServiceRunning.value) {
            val intent = Intent(context, SeismicTriggerService::class.java)
            context.startService(intent)
        }
    }

    fun updateSelectedAudioType(type: AudioType) {
        _selectedAudioType.value = type
        SeismicConfig.saveSelectedAudioType(context, type)
        
        // Notify background service
        if (SeismicTriggerService.isServiceRunning.value) {
            val intent = Intent(context, SeismicTriggerService::class.java)
            context.startService(intent)
        }
    }

    // Clean current pattern from preferences
    fun clearPattern() {
        _recordedPattern.value = emptyList()
        _tempKnocks.value = emptyList()
        SeismicConfig.saveRecordedPattern(context, emptyList())
        SeismicTelemetry.log("Stored knock pattern cleared.")
    }

    // Part A: 3-Axis Calibration Routine
    fun calibrateSurface() {
        if (_isCalibrating.value || _isRecording.value) return
        
        viewModelScope.launch {
            _isCalibrating.value = true
            _calibrationProgress.value = 0f
            synchronized(calibrationSamples) {
                calibrationSamples.clear()
            }
            
            SeismicTelemetry.log("3-second surface calibration started. Keep device STILL.")

            val totalDuration = 3000L
            val pollingInterval = 30L
            var elapsed = 0L

            while (elapsed < totalDuration) {
                delay(pollingInterval)
                elapsed += pollingInterval
                _calibrationProgress.value = elapsed.toFloat() / totalDuration
            }

            // Calculations
            val samplesCopy = synchronized(calibrationSamples) {
                calibrationSamples.toList()
            }

            if (samplesCopy.isNotEmpty()) {
                val size = samplesCopy.size
                var sumX = 0.0
                var sumY = 0.0
                var sumZ = 0.0

                for (sample in samplesCopy) {
                    sumX += sample[0]
                    sumY += sample[1]
                    sumZ += sample[2]
                }

                val meanX = (sumX / size).toFloat()
                val meanY = (sumY / size).toFloat()
                val meanZ = (sumZ / size).toFloat()

                // Standard deviations computed on variations of combined 3D Vector Magnitude deviation from average orientation
                var varianceSum = 0.0
                for (sample in samplesCopy) {
                    val devX = sample[0] - meanX
                    val devY = sample[1] - meanY
                    val devZ = sample[2] - meanZ
                    val magnitudeDev = sqrt(devX * devX + devY * devY + devZ * devZ)
                    varianceSum += magnitudeDev * magnitudeDev
                }

                val stdDev = sqrt(varianceSum / size).toFloat()

                val newCalibration = CalibrationData(
                    meanX = meanX,
                    meanY = meanY,
                    meanZ = meanZ,
                    stdDev = stdDev,
                    timestamp = System.currentTimeMillis()
                )

                SeismicConfig.saveCalibration(context, newCalibration)
                _calibration.value = newCalibration
                
                SeismicTelemetry.log("Calibration complete: Samples=$size, MeanX=${String.format(Locale.US, "%.2f", meanX)}, MeanY=${String.format(Locale.US, "%.2f", meanY)}, MeanZ=${String.format(Locale.US, "%.2f", meanZ)}, StdDev=${String.format(Locale.US, "%.4f", stdDev)}")
            } else {
                SeismicTelemetry.log("ERROR: Received 0 calibration samples. Try again.")
            }

            _isCalibrating.value = false
        }
    }

    // Part B: Pattern Recording Routine
    fun startRecordingPattern() {
        if (_isCalibrating.value || _isRecording.value) return

        viewModelScope.launch {
            _isRecording.value = true
            _recordingTimeoutProgress.value = 1f
            _tempKnocks.value = emptyList()
            recordFirstKnockTime = 0L
            recordLastKnockTime = 0L

            SeismicTelemetry.log("Pattern Recording started (6s timeout). Tap custom rhythm on resting surface!")
            triggerVibration()

            val totalDuration = RECORD_DURATION_MS
            val updateInterval = 100L
            var remaining = totalDuration

            while (remaining > 0L) {
                delay(updateInterval)
                remaining -= updateInterval
                _recordingTimeoutProgress.value = remaining.toFloat() / totalDuration

                // Auto stop recording if they tap maximum of 5 knocks
                if (_tempKnocks.value.size >= 5) {
                    break
                }
            }

            // Process collected sequence
            val finalKnocks = _tempKnocks.value
            if (finalKnocks.size >= 3) {
                // Successful recording!
                SeismicConfig.saveRecordedPattern(context, finalKnocks)
                _recordedPattern.value = finalKnocks
                SeismicTelemetry.log("SUCCESS: Saved rhythm profile of ${finalKnocks.size} knocks.")
            } else {
                SeismicTelemetry.log("FAILED: Captured only ${finalKnocks.size} knocks. Minimum of 3 required.")
            }

            _isRecording.value = false
            triggerVibration()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val valX = event.values[0]
        val valY = event.values[1]
        val valZ = event.values[2]

        // Populate basic live data
        _liveX.value = valX
        _liveY.value = valY
        _liveZ.value = valZ
        _liveMag.value = sqrt(valX * valX + valY * valY + valZ * valZ)

        // Capture calibration samples dynamically if active
        if (_isCalibrating.value) {
            synchronized(calibrationSamples) {
                calibrationSamples.add(floatArrayOf(valX, valY, valZ))
            }
            return
        }

        // Handle recording triggers if active
        if (_isRecording.value) {
            val devX = valX - _calibration.value.meanX
            val devY = valY - _calibration.value.meanY
            val devZ = valZ - _calibration.value.meanZ
            val devMag = sqrt(devX * devX + devY * devY + devZ * devZ)

            val stdDev = max(0.04f, _calibration.value.stdDev)
            val threshold = max(0.28f, stdDev * _sensitivityK.value)

            if (devMag >= threshold) {
                val currentTime = System.currentTimeMillis()
                // Prevent micro-reverberation bouncing
                if (currentTime - recordLastKnockTime >= DEBOUNCE_MS) {
                    recordLastKnockTime = currentTime

                    // Compute clean vibration ratio distribution
                    val absSum = abs(devX) + abs(devY) + abs(devZ)
                    val ratioX = if (absSum > 0.001f) abs(devX) / absSum else 0f
                    val ratioY = if (absSum > 0.001f) abs(devY) / absSum else 0f
                    val ratioZ = if (absSum > 0.001f) abs(devZ) / absSum else 1f

                    val relativeTime: Long
                    if (recordFirstKnockTime == 0L) {
                        recordFirstKnockTime = currentTime
                        relativeTime = 0L
                    } else {
                        relativeTime = currentTime - recordFirstKnockTime
                    }

                    val newKnock = Knock(
                        relativeTimeMs = relativeTime,
                        ratioX = ratioX,
                        ratioY = ratioY,
                        ratioZ = ratioZ
                    )

                    val currentList = _tempKnocks.value.toMutableList()
                    currentList.add(newKnock)
                    _tempKnocks.value = currentList

                    triggerVibration()
                    SeismicTelemetry.log("Recorded Knock #${currentList.size}! RelTime=${relativeTime}ms (Z-Ratio=${String.format(Locale.US, "%.2f", ratioZ)})")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
