package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class SimpleLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    init {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

class SeismicTriggerService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "SeismicTriggerService"
        private const val NOTIFICATION_ID = 4224
        private const val CHANNEL_ID = "seismic_macro_channel"
        private const val DEBOUNCE_MS = 280L // Debounce period for table surface vibrations
        const val ACTION_STOP_AUDIO = "com.example.ACTION_STOP_AUDIO"

        val isServiceRunning = MutableStateFlow(false)
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    
    // Calibration cached locally for performance in loop
    private var calibration = CalibrationData()
    private var sensitivityK = 4.0f
    private var strictness = 1.0f
    
    // Matcher tracking state
    private val detectedKnocks = mutableListOf<Long>() // Timestamps of rolling detected knocks
    private val detectedRatiosX = mutableListOf<Float>()
    private val detectedRatiosY = mutableListOf<Float>()
    private val detectedRatiosZ = mutableListOf<Float>()
    private var lastKnockTime = 0L

    // For CameraX headless capture
    private var simpleLifecycleOwner: SimpleLifecycleOwner? = null

    // For Audio loop alert
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate service started")
        isServiceRunning.value = true
        SeismicTelemetry.log("Foreground Service Started.")

        // Setup notification channel and start foreground immediately
        createNotificationChannel()
        val notification = createNotification("Sensor monitoring active...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Initialize sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        // Load latest configuration from shared prefs
        calibration = SeismicConfig.getCalibration(this)
        sensitivityK = SeismicConfig.getSensitivity(this)
        strictness = SeismicConfig.getStrictness(this)

        SeismicTelemetry.log("Calibration loaded: MeanZ=${String.format(Locale.US, "%.3f", calibration.meanZ)}, StdDev=${String.format(Locale.US, "%.4f", calibration.stdDev)}")

        try {
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                SeismicTelemetry.log("Accelerometer registered successfully.")
            } ?: run {
                SeismicTelemetry.log("ERROR: Accelerometer not available!")
            }
        } catch (e: Exception) {
            SeismicTelemetry.log("ERROR: Failed to register accelerometer - ${e.message}")
        }

        // Init camera lifecycle
        simpleLifecycleOwner = SimpleLifecycleOwner()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_AUDIO) {
            Log.d(TAG, "Stopping audio via notification")
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            updateNotification("Audio Alert Stopped")
            return START_STICKY
        }

        // Refresh preferences values on service start / restart trigger
        calibration = SeismicConfig.getCalibration(this)
        sensitivityK = SeismicConfig.getSensitivity(this)
        strictness = SeismicConfig.getStrictness(this)
        
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy service stopping")
        isServiceRunning.value = false
        SeismicTelemetry.log("Foreground Service Stopped.")

        // Clean up sensors
        sensorManager.unregisterListener(this)

        // Clean up audio
        mediaPlayer?.release()
        mediaPlayer = null

        // Clean up camera
        simpleLifecycleOwner?.destroy()
        simpleLifecycleOwner = null

        stopForeground(true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val rawX = event.values[0]
        val rawY = event.values[1]
        val rawZ = event.values[2]

        // Update raw telemetry variables
        SeismicTelemetry.rawX.value = rawX
        SeismicTelemetry.rawY.value = rawY
        SeismicTelemetry.rawZ.value = rawZ

        // Calculated magnitude including baseline environmental gravity
        val currentMag = sqrt(rawX * rawX + rawY * rawY + rawZ * rawZ)
        SeismicTelemetry.rawMag.value = currentMag

        // Compute deviation from calibrated baseline mean (representing resting phone orientation)
        val devX = rawX - calibration.meanX
        val devY = rawY - calibration.meanY
        val devZ = rawZ - calibration.meanZ

        // 3D vector magnitude variation from reference resting state
        val devMag = sqrt(devX * devX + devY * devY + devZ * devZ)

        // Adaptive limit based on Standard Deviation and User Sensitivity multiplier
        // Floor standard deviation at 0.05f to avoid divide/zero hypersensitivity
        val stdDev = max(0.04f, calibration.stdDev)
        val dynamicThreshold = stdDev * sensitivityK
        
        // Ensure standard fallback threshold limit
        val finalThreshold = max(0.28f, dynamicThreshold)

        // Stream threshold percent ratio to visualization widget
        SeismicTelemetry.thresholdPercent.value = if (finalThreshold > 0L) {
            max(0f, devMag / finalThreshold)
        } else {
            0f
        }

        val currentTime = System.currentTimeMillis()

        // Check if transient vibrational spike breaks the adaptive threshold
        if (devMag >= finalThreshold) {
            // Apply lockout debounce period to isolate single distinct knocks
            if (currentTime - lastKnockTime >= DEBOUNCE_MS) {
                lastKnockTime = currentTime
                
                // Directional energy distribution ratio calculation
                val absoluteSum = abs(devX) + abs(devY) + abs(devZ)
                val ratioX = if (absoluteSum > 0.001f) abs(devX) / absoluteSum else 0f
                val ratioY = if (absoluteSum > 0.001f) abs(devY) / absoluteSum else 0f
                val ratioZ = if (absoluteSum > 0.001f) abs(devZ) / absoluteSum else 1f

                handleKnockDetected(currentTime, ratioX, ratioY, ratioZ, finalThreshold, devMag)
            }
        }
    }

    private fun handleKnockDetected(
        timestamp: Long,
        ratioX: Float,
        ratioY: Float,
        ratioZ: Float,
        threshold: Float,
        devMag: Float
    ) {
        // Log telemetry
        Log.d(TAG, "Knock detected: delta=$devMag, limit=$threshold, Ratios: X=${String.format(Locale.US, "%.2f", ratioX)}, Y=${String.format(Locale.US, "%.2f", ratioY)}, Z=${String.format(Locale.US, "%.2f", ratioZ)}")
        SeismicTelemetry.log("KNOCK! Force=${String.format(Locale.US, "%.2f", devMag)} (Ratios: X:${String.format(Locale.US, "%.2f", ratioX)}, Y:${String.format(Locale.US, "%.2f", ratioY)}, Z:${String.format(Locale.US, "%.2f", ratioZ)})")
        
        // Update live metrics
        SeismicTelemetry.latestDetectorKnockTime.value = timestamp
        SeismicTelemetry.detectedKnocksCount.value = SeismicTelemetry.detectedKnocksCount.value + 1

        // Record rolling buffer of knocks
        detectedKnocks.add(timestamp)
        detectedRatiosX.add(ratioX)
        detectedRatiosY.add(ratioY)
        detectedRatiosZ.add(ratioZ)

        // Prune older elements (+7.5 seconds) to keep rolling buffer clean
        while (detectedKnocks.isNotEmpty() && timestamp - detectedKnocks.first() > 7500L) {
            detectedKnocks.removeAt(0)
            detectedRatiosX.removeAt(0)
            detectedRatiosY.removeAt(0)
            detectedRatiosZ.removeAt(0)
        }

        // Try to match rolling inputs with custom recorded rhythm sequence
        checkForPatternMatch()
    }

    private fun checkForPatternMatch() {
        val recordedPattern = SeismicConfig.getRecordedPattern(this)
        if (recordedPattern.isEmpty()) {
            // No custom pattern recorded by user yet
            return
        }

        val n = recordedPattern.size
        if (detectedKnocks.size < n) {
            // Not enough rolling knocks to match sequence
            return
        }

        // Check subsets from latest back-to-front
        val totalBuffer = detectedKnocks.size
        // We match the last N elements in the buffer
        val startIdx = totalBuffer - n
        
        val sequenceTimestamps = detectedKnocks.subList(startIdx, totalBuffer)
        val seqRatiosX = detectedRatiosX.subList(startIdx, totalBuffer)
        val seqRatiosY = detectedRatiosY.subList(startIdx, totalBuffer)
        val seqRatiosZ = detectedRatiosZ.subList(startIdx, totalBuffer)

        // Normalize matching relative times
        val t0 = sequenceTimestamps[0]
        val matchedRelativeTimes = sequenceTimestamps.map { it - t0 }

        var rhythmMatched = true
        var directionalMatched = true

        for (i in 0 until n) {
            val recKnock = recordedPattern[i]
            val actualRelativeTime = matchedRelativeTimes[i]
            
            // 1. Time intervals must match recorded rhythm within +/- (15% * strictness) variance window
            // Since time is in integer ms, add a baseline threshold of (150ms * strictness) for reliable physical tap variation
            val targetTime = recKnock.relativeTimeMs
            // strictness 0.5 = more strict (half the deviation allowed)
            // strictness 2.0 = less strict (double the deviation allowed)
            val maxTimeDev = max((180L * strictness).toLong(), (targetTime * 0.15f * strictness).toLong())
            val timeError = abs(actualRelativeTime - targetTime)
            
            if (timeError > maxTimeDev) {
                rhythmMatched = false
                Log.v(TAG, "Rhythm mismatch at index $i: expected ${recKnock.relativeTimeMs}ms, actual ${actualRelativeTime}ms (Error ${timeError}ms > ${maxTimeDev}ms)")
                break
            }

            // 2. Directional vector ratios match within +/- (20% * strictness) variance window
            val errorX = abs(seqRatiosX[i] - recKnock.ratioX)
            val errorY = abs(seqRatiosY[i] - recKnock.ratioY)
            val errorZ = abs(seqRatiosZ[i] - recKnock.ratioZ)

            val maxRatioError = 0.20f * strictness
            if (errorX > maxRatioError || errorY > maxRatioError || errorZ > maxRatioError) {
                directionalMatched = false
                Log.v(TAG, "Directional mismatch at index $i: errX=${String.format(Locale.US, "%.2f", errorX)}, errY=${String.format(Locale.US, "%.2f", errorY)}, errZ=${String.format(Locale.US, "%.2f", errorZ)}")
                break
            }
        }

        if (rhythmMatched && directionalMatched) {
            Log.i(TAG, "PATTERN MATCH DETECTED SUCCESSFULLY!")
            SeismicTelemetry.log("MATCH DETECTED! Running custom action payload.")
            
            // Fire target trigger notification update
            updateNotification("Knock Pattern Matched recently! Firing trigger...")
            
            // Execute targeted action on matching thread context
            executeTriggerPayload()

            // Clear the buffer after a successful match to prevent immediate re-triggering on overlapping sequences
            detectedKnocks.clear()
            detectedRatiosX.clear()
            detectedRatiosY.clear()
            detectedRatiosZ.clear()
        }
    }

    private fun executeTriggerPayload() {
        val selected = SeismicConfig.getSelectedPayload(this)
        SeismicTelemetry.log("Executing Action: $selected")
        
        when (selected) {
            ActionPayload.CAMERA -> {
                executeCameraCapture()
            }
            ActionPayload.AUDIO -> {
                executeAudioAlert()
            }
        }
    }

    private fun executeCameraCapture() {
        val owner = simpleLifecycleOwner ?: return
        val currentContext = this
        val cameraProviderFuture = ProcessCameraProvider.getInstance(currentContext)
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val prefLane = SeismicConfig.getCameraLensFacing(currentContext)
                val selectors = mutableListOf<CameraSelector>()
                
                when (prefLane) {
                    0 -> selectors.add(CameraSelector.DEFAULT_BACK_CAMERA)
                    1 -> selectors.add(CameraSelector.DEFAULT_FRONT_CAMERA)
                    2 -> {
                        selectors.add(CameraSelector.DEFAULT_BACK_CAMERA)
                        selectors.add(CameraSelector.DEFAULT_FRONT_CAMERA)
                    }
                }

                fun captureNext(idx: Int) {
                    if (idx >= selectors.size) return
                    val cameraSelector = selectors[idx]
                    
                    cameraProvider.unbindAll()
                    try {
                        cameraProvider.bindToLifecycle(owner, cameraSelector, imageCapture)
                    } catch (e: Exception) {
                        SeismicTelemetry.log("CAMERA FAULT (bind): ${e.message}")
                        captureNext(idx + 1)
                        return
                    }

                    // Save securely inside the public application storage directory which requires NO write permissions
                    val targetDir = currentContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: currentContext.filesDir
                    val file = File(targetDir, "seismic_${System.currentTimeMillis()}_${idx}.jpg")
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(currentContext),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                Log.d(TAG, "Silent photo saved coordinates: ${file.absolutePath}")
                                SeismicTelemetry.log("CAMERA PHOTO CAPTURED! Saved to: ${file.name}")
                                updateNotification("Active: Photo captured successfully!")
                                captureNext(idx + 1)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Log.e(TAG, "CameraX save error: ${exception.message}", exception)
                                SeismicTelemetry.log("CAMERA ERROR: ${exception.message}")
                                updateNotification("Active: Trigger failed (Camera error)")
                                captureNext(idx + 1)
                            }
                        }
                    )
                }
                
                captureNext(0)
            } catch (e: Exception) {
                Log.e(TAG, "Image headless binding failed: ${e.message}", e)
                SeismicTelemetry.log("CAMERA FAULT: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(currentContext))
    }

    private fun executeAudioAlert() {
        try {
            // Clean up any preceding player instances
            mediaPlayer?.release()
            
            mediaPlayer = MediaPlayer().apply {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM) // Alarm override to sound regardless of system profile
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setAudioAttributes(audioAttributes)

                val savedUriStr = SeismicConfig.getSelectedAudioUri(applicationContext)
                val alertUri = if (!savedUriStr.isNullOrEmpty()) {
                    android.net.Uri.parse(savedUriStr)
                } else {
                    val selectedType = SeismicConfig.getSelectedAudioType(applicationContext)
                    val typeConstant = when (selectedType) {
                        AudioType.NOTIFICATION -> RingtoneManager.TYPE_NOTIFICATION
                        AudioType.ALARM -> RingtoneManager.TYPE_ALARM
                        AudioType.RINGTONE -> RingtoneManager.TYPE_RINGTONE
                    }
                    RingtoneManager.getDefaultUri(typeConstant)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }
                
                setDataSource(applicationContext, alertUri)
                
                prepare()
                start()
            }
            
            val savedTitle = SeismicConfig.getSelectedAudioTitle(applicationContext)
            SeismicTelemetry.log("AUDIO ACTION SUCCESS: Playing alert ($savedTitle).")
            updateNotification("Active: Playing Audio Alert")
        } catch (e: Exception) {
            Log.e(TAG, "Audio play error: ${e.message}", e)
            SeismicTelemetry.log("AUDIO ACTION ERROR: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Seismic Trigger Active Guard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors baseline accelerations for knock triggers"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SeismicMacro Guard Running")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        if (mediaPlayer?.isPlaying == true) {
            val stopIntent = Intent(this, SeismicTriggerService::class.java).apply {
                action = ACTION_STOP_AUDIO
            }
            val stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Stop Audio", stopPendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification(contentText: String) {
        val mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }
}
