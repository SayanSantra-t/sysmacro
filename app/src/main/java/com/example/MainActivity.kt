package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import kotlin.math.max
import kotlin.math.sqrt
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyRow
import java.io.File
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) { innerPadding ->
                    SeismicMacroScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun SeismicMacroScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadAudioTones()
    }

    // Observe flows from ViewModel
    val isCalibrating by viewModel.isCalibrating.collectAsState()
    val calibrationProgress by viewModel.calibrationProgress.collectAsState()
    val calibration by viewModel.calibration.collectAsState()

    val isRecording by viewModel.isRecording.collectAsState()
    val recordingProgress by viewModel.recordingTimeoutProgress.collectAsState()
    val recordedPattern by viewModel.recordedPattern.collectAsState()
    val tempKnocks by viewModel.tempKnocks.collectAsState()

    val sensitivityK by viewModel.sensitivityK.collectAsState()
    val strictness by viewModel.strictness.collectAsState()
    val selectedPayload by viewModel.selectedPayload.collectAsState()
    val recentImages by viewModel.recentImages.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadRecentImages()
    }

    // Observe sensor live data streams
    val liveX by viewModel.liveX.collectAsState()
    val liveY by viewModel.liveY.collectAsState()
    val liveZ by viewModel.liveZ.collectAsState()
    val liveMag by viewModel.liveMag.collectAsState()

    // Service running state
    val isServiceRunning by SeismicTriggerService.isServiceRunning.collectAsState()

    // Sensor threshold percent ratio computed
    val thresholdPercent by SeismicTelemetry.thresholdPercent.collectAsState()

    // Logs Console
    val consoleLogs by SeismicTelemetry.consoleLogs.collectAsState()

    // Permissions handler
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var notificationPermissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val launcherCamera = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            cameraPermissionGranted = granted
            if (granted) {
                SeismicTelemetry.log("Camera permission manually granted.")
            } else {
                SeismicTelemetry.log("WARN: Camera permission denied. silent capture payload deactivated.")
            }
        }
    )

    val launcherNotifications = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            notificationPermissionGranted = granted
            if (granted) {
                SeismicTelemetry.log("Notification permission approved for active monitoring updates.")
            }
        }
    )

    // Request notifications permission on launch for Android 13+
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionGranted) {
            launcherNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Sleek Glowing Matrix Header
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SEISMIC MACRO",
                    color = Color(0xFF00FF88),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    modifier = Modifier.testTag("app_title")
                )
                Text(
                    text = "Vibrational Knock-Pattern Trigger",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Service Status Indicator Card
                Row(
                    modifier = Modifier
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = if (isServiceRunning) {
                                    listOf(Color(0x2200FF88), Color(0x1100FF00))
                                } else {
                                    listOf(Color(0x22FF3D00), Color(0x11FF0000))
                                }
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isServiceRunning) Color(0xFF00FF88) else Color(0xFFFF3D00),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .fillMaxWidth(0.9f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (isServiceRunning) Color(0xFF00FF88) else Color(0xFFFF3D00),
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isServiceRunning) "SENTRY RECON ACTIVE" else "STANDBY (MONITOR SUSPENDED)",
                        color = if (isServiceRunning) Color(0xFF00FF88) else Color(0xFFFF3D00),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        // 2. Master Sentry Guard Toggle
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("master_sentry_guard_card"),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101010)),
                border = BorderStroke(1.dp, Color(0xFF222222))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(0.7f)) {
                        Text(
                            text = "Seismic Trigger Service",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif
                        )
                        Text(
                            text = "Runs background accelerative lock in sleep mode",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = isServiceRunning,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                // Enforce surface is calibrated first before allowing background trigger to activate properly
                                val currentCalib = calibration
                                if (currentCalib.timestamp == 0L) {
                                    SeismicTelemetry.log("Aborted startup. Surface must be calibrated first!")
                                } else {
                                    // Make sure camera permissions exist if camera is selected payload
                                    if (selectedPayload == ActionPayload.CAMERA && !cameraPermissionGranted) {
                                        launcherCamera.launch(Manifest.permission.CAMERA)
                                    } else {
                                        viewModel.startService()
                                    }
                                }
                            } else {
                                viewModel.stopService()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF000000),
                            checkedTrackColor = Color(0xFF00FF88),
                            uncheckedThumbColor = Color(0xFF888888),
                            uncheckedTrackColor = Color(0xFF333333)
                        ),
                        modifier = Modifier.testTag("service_toggle")
                    )
                }
            }
        }

        // 3. Live 3-Axis Telemetry Dashboard
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("telemetry_card"),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
                border = BorderStroke(1.dp, Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "LIVE SENSOR TELEMETRY",
                        color = Color(0xFF00E5FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Axis rows: X, Y, Z raw values
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryMetricBox(label = "X", value = liveX, color = Color(0xFFFF5252))
                        TelemetryMetricBox(label = "Y", value = liveY, color = Color(0xFF1FF042))
                        TelemetryMetricBox(label = "Z", value = liveZ, color = Color(0xFF2979FF))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Vector force meter (calculated from gravity displacement map)
                    val devX = liveX - calibration.meanX
                    val devY = liveY - calibration.meanY
                    val devZ = liveZ - calibration.meanZ
                    val rawVibration = sqrt(devX * devX + devY * devY + devZ * devZ)
                    val stdDevFixed = max(0.04f, calibration.stdDev)
                    val currentThreshold = stdDevFixed * sensitivityK
                    val finalThreshold = max(0.28f, currentThreshold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Environmental Drift",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "${String.format("%.3f", rawVibration)} m/s²",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Adaptive Capture Limit",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "dev ≥ ${String.format("%.3f", finalThreshold)} m/s²",
                                color = Color(0xFF00FF88),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Simulated LED strip representing relative vibration energy
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(3.dp))
                    ) {
                        val animatedPercent by animateFloatAsState(targetValue = thresholdPercent)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(minOf(1f, animatedPercent))
                                .fillMaxHeight()
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = if (animatedPercent >= 1f) {
                                            listOf(Color(0xFF00FF88), Color(0xFFFF3D00))
                                        } else {
                                            listOf(Color(0xFF00E5FF), Color(0xFF00FF88))
                                        }
                                    ),
                                    shape = RoundedCornerShape(3.dp)
                                )
                        )
                    }
                }
            }
        }

        // 4. Calibration Cockpit Panel
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("calibration_card"),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101010)),
                border = BorderStroke(1.dp, Color(0xFF222222))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "SURFACE NOISE ENGINE",
                        color = Color(0xFF00FF88),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Show current calibration matrices
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SurfaceMatrixItem("μX", "${String.format("%.2f", calibration.meanX)}")
                        SurfaceMatrixItem("μY", "${String.format("%.2f", calibration.meanY)}")
                        SurfaceMatrixItem("μZ", "${String.format("%.2f", calibration.meanZ)}")
                        SurfaceMatrixItem("Noise Floor (σ)", "${String.format("%.4f", calibration.stdDev)}")
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Adjust sensitivity multiplier K slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sensitivity Factor K: ${String.format("%.1f", sensitivityK)}x",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (sensitivityK > 5f) "High Lock (Stiff)" else "Extra Alert (Soft)",
                            color = if (sensitivityK > 5f) Color.Gray else Color(0xFF00FF88),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Slider(
                        value = sensitivityK,
                        onValueChange = { viewModel.updateSensitivity(it) },
                        valueRange = 2.0f..10.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00FF88),
                            activeTrackColor = Color(0xFF00FF88),
                            inactiveTrackColor = Color(0xFF333333)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    HorizontalDivider(color = Color(0xFF282828))

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Pattern Match Strictness: ${String.format(java.util.Locale.US, "%.1f", strictness)}x",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = when {
                                strictness < 1.0f -> "Strict"
                                strictness == 1.0f -> "Normal"
                                else -> "Loose (Easy)"
                            },
                            color = if (strictness < 1.0f) Color(0xFFFF3D00) else Color(0xFF00FF88),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(
                        text = "Slide towards left to strictly enforce tap rhythm.",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    Slider(
                        value = strictness,
                        onValueChange = { viewModel.updateStrictness(it) },
                        valueRange = 0.5f..3.0f,
                        steps = 4, // 0.5, 1.0, 1.5, 2.0, 2.5, 3.0 (4 stops between 0.5 and 3.0)
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E5FF),
                            activeTrackColor = Color(0xFF00E5FF),
                            inactiveTrackColor = Color(0xFF333333)
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { viewModel.calibrateSurface() },
                        enabled = !isCalibrating && !isRecording,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FF88),
                            contentColor = Color.Black,
                            disabledContainerColor = Color(0xFF222222),
                            disabledContentColor = Color.DarkGray
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("calibrate_button"),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        if (isCalibrating) {
                            Text(
                                "CALIBRATING SURFACE (${(calibrationProgress * 100).toInt()}%)...",
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            Text("CALIBRATE SURFACE (3 SEC)", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }

                    if (isCalibrating) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { calibrationProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF00FF88),
                            trackColor = Color(0xFF222222),
                        )
                    }
                }
            }
        }

        // 5. Rhythm Profile & Sequence Manager
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pattern_manager_card"),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
                border = BorderStroke(1.dp, Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "RHYTHM PROFILE BUILDER",
                        color = Color(0xFF00E5FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Show active recording stream or empty state or midi visualizer of recorded knocks
                    if (isRecording) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "TAP PHONE SURFACE NOW!",
                                color = Color(0xFFFF3D00),
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Live Capture Count: ${tempKnocks.size} knocks (of max 5)",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            // Visual horizontal ribbon of recorded taps in current recording sequence
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(24.dp)
                                    .background(Color.Black, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (tempKnocks.isEmpty()) {
                                    Text(
                                        "Waiting for 1st tap...",
                                        color = Color.DarkGray,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                } else {
                                    tempKnocks.forEachIndexed { idx, knock ->
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFF00FF88), RoundedCornerShape(2.dp))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                "#${idx + 1}:${knock.relativeTimeMs}ms",
                                                color = Color.Black,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { recordingProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFFFF3D00),
                                trackColor = Color(0xFF222222),
                            )
                        }
                    } else {
                        if (recordedPattern.isEmpty()) {
                            // Tutorial empty state card
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF151515), RoundedCornerShape(6.dp))
                                    .border(1.dp, Color(0xFF252525), RoundedCornerShape(6.dp))
                                    .padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "NO CUSTOM PATTERN SAVED",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Calibrate first, then click Record. Gently tap your desk 3 to 5 times in a distinct sequence (e.g. shave-and-a-haircut rhythm) to capture signature intervals.",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                        } else {
                            // Midi sequencer view
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF151515), RoundedCornerShape(6.dp))
                                    .border(1.dp, Color(0xFF252525), RoundedCornerShape(6.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "RECORDED RHYTHM SEQUENCE",
                                        color = Color.LightGray,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Size: ${recordedPattern.size} Knocks",
                                        color = Color(0xFF00FF88),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Interactive scrollable items
                                recordedPattern.forEachIndexed { i, knock ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Knock #${i + 1}",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )

                                        Text(
                                            text = "${knock.relativeTimeMs} ms Delay",
                                            color = Color(0xFF00E5FF),
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )

                                        // Tiny vector composition bar (X, Y, Z ratios)
                                        Row(
                                            modifier = Modifier
                                                .width(80.dp)
                                                .height(8.dp)
                                                .background(Color.Black, RoundedCornerShape(2.dp))
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .weight(max(0.01f, knock.ratioX))
                                                    .background(Color(0xFFFF5252))
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .weight(max(0.01f, knock.ratioY))
                                                    .background(Color(0xFF1FF042))
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .weight(max(0.01f, knock.ratioZ))
                                                    .background(Color(0xFF2979FF))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { viewModel.startRecordingPattern() },
                            enabled = !isCalibrating && !isRecording,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00E5FF),
                                contentColor = Color.Black,
                                disabledContainerColor = Color(0xFF222222),
                                disabledContentColor = Color.DarkGray
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("record_pattern_button"),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("RECORD PROFILE", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { viewModel.clearPattern() },
                            enabled = recordedPattern.isNotEmpty() && !isCalibrating && !isRecording,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF3D00),
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFF222222),
                                disabledContentColor = Color.DarkGray
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("clear_pattern_button"),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("CLEAR PROFILE", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 6. Action Linked Trigger Selector
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("action_payload_card"),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101010)),
                border = BorderStroke(1.dp, Color(0xFF222222))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "LINKED SURVEILLANCE PAYLOAD",
                        color = Color(0xFFFF3D00),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option A: Headless camera
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.updateSelectedPayload(ActionPayload.CAMERA)
                                if (!cameraPermissionGranted) {
                                    launcherCamera.launch(Manifest.permission.CAMERA)
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedPayload == ActionPayload.CAMERA,
                            onClick = {
                                viewModel.updateSelectedPayload(ActionPayload.CAMERA)
                                if (!cameraPermissionGranted) {
                                    launcherCamera.launch(Manifest.permission.CAMERA)
                                }
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color(0xFFFF3D00),
                                unselectedColor = Color.Gray
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "Headless Camera Photo",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Silently snaps picture on pattern match. Saves in background.",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = selectedPayload == ActionPayload.CAMERA,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 48.dp, bottom = 12.dp, end = 16.dp)
                        ) {
                            Text(
                                text = "SELECT CAMERA TO USE",
                                color = Color(0xFFFF3D00),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            
                            val cameraLensFacing by viewModel.cameraLensFacing.collectAsState()
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = cameraLensFacing == 0,
                                    onClick = { viewModel.updateCameraLensFacing(0) },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00FF88))
                                )
                                Text("Back", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.clickable { viewModel.updateCameraLensFacing(0) })
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                RadioButton(
                                    selected = cameraLensFacing == 1,
                                    onClick = { viewModel.updateCameraLensFacing(1) },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00FF88))
                                )
                                Text("Front", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.clickable { viewModel.updateCameraLensFacing(1) })
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                RadioButton(
                                    selected = cameraLensFacing == 2,
                                    onClick = { viewModel.updateCameraLensFacing(2) },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00FF88))
                                )
                                Text("Both", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.clickable { viewModel.updateCameraLensFacing(2) })
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "RECENT CAPTURES",
                                    color = Color(0xFF00FF88),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        text = "REFRESH",
                                        color = Color.LightGray,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.clickable { viewModel.loadRecentImages() }
                                    )
                                    if (recentImages.isNotEmpty()) {
                                        Text(
                                            text = "CLEAR ALL",
                                            color = Color(0xFFFF3D00),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.clickable { viewModel.clearAllImages() }
                                        )
                                    }
                                }
                            }
                            
                            if (recentImages.isEmpty()) {
                                Text(
                                    text = "No images captured yet.",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            } else {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(recentImages) { imgFile ->
                                        Box(
                                            modifier = Modifier
                                                .size(100.dp)
                                                .border(BorderStroke(1.dp, Color(0xFF333333)), RoundedCornerShape(8.dp))
                                        ) {
                                            AsyncImage(
                                                model = imgFile,
                                                contentDescription = "Captured Image",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                            )
                                            
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(4.dp)
                                                    .size(22.dp)
                                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(11.dp))
                                                    .clickable { viewModel.deleteImage(imgFile) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Delete,
                                                    contentDescription = "Delete Image",
                                                    tint = Color(0xFFFF3D00),
                                                    modifier = Modifier.size(12.dp)
                                                )
                                             }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Option B: Media play
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.updateSelectedPayload(ActionPayload.AUDIO) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedPayload == ActionPayload.AUDIO,
                            onClick = { viewModel.updateSelectedPayload(ActionPayload.AUDIO) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color(0xFFFF3D00),
                                unselectedColor = Color.Gray
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "Trigger Loud Audio Sonification Alert",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Overrides vibration modes and rings chosen system URI.",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (selectedPayload == ActionPayload.AUDIO) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, end = 8.dp)
                                .background(Color(0xFF181818), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF282828), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "SELECT ALERT SOUND TYPE",
                                color = Color(0xFFFF3D00),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            
                            val selectedAudioType by viewModel.selectedAudioType.collectAsState()
                            
                            AudioType.values().forEach { type ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.updateSelectedAudioType(type) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedAudioType == type,
                                        onClick = { viewModel.updateSelectedAudioType(type) },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = Color(0xFF00FF88),
                                            unselectedColor = Color.Gray
                                        ),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = when(type) {
                                            AudioType.NOTIFICATION -> "Standard Notification Tone (Short alert)"
                                            AudioType.ALARM -> "Loud System Alarm (Repeated pulse alert)"
                                            AudioType.RINGTONE -> "System Ringtone (Continuous loop alert)"
                                        },
                                        color = if (selectedAudioType == type) Color(0xFF00FF88) else Color.LightGray,
                                        fontSize = 12.sp,
                                        fontWeight = if (selectedAudioType == type) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = Color(0xFF282828), thickness = 1.dp)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = "CHOOSE SPECIFIC SOUND TONE",
                                color = Color(0xFFFF3D00),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            
                            val systemAudioTones by viewModel.systemAudioTones.collectAsState()
                            val selectedAudioTitle by viewModel.selectedAudioTitle.collectAsState()
                            val selectedAudioUri by viewModel.selectedAudioUri.collectAsState()
                            
                            var showPickerMenu by remember { mutableStateOf(false) }
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showPickerMenu = true }
                                    .background(Color(0xFF222222), RoundedCornerShape(4.dp))
                                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = selectedAudioTitle,
                                            color = Color(0xFF00FF88),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = if (selectedAudioUri == null) "Using Default Category Tone" else "Custom Specific Tone",
                                            color = Color.Gray,
                                            fontSize = 10.sp
                                        )
                                    }
                                    
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Select Specific Tone",
                                        tint = Color.Gray
                                    )
                                }
                                
                                DropdownMenu(
                                    expanded = showPickerMenu,
                                    onDismissRequest = { showPickerMenu = false },
                                    modifier = Modifier
                                        .background(Color(0xFF181818))
                                        .border(1.dp, Color(0xFF333333))
                                        .fillMaxWidth(0.8f)
                                ) {
                                    DropdownMenuItem(
                                        text = { 
                                            Text(
                                                "Default Category Tone (Follow Category Selected Above)", 
                                                fontSize = 12.sp,
                                                color = Color.White
                                            ) 
                                        },
                                        onClick = {
                                            viewModel.updateSelectedAudioTone("Default System Tone", null)
                                            showPickerMenu = false
                                        },
                                        modifier = Modifier.background(if (selectedAudioUri == null) Color(0xFF282828) else Color.Transparent)
                                    )
                                    
                                    if (systemAudioTones.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("No custom tones found", fontSize = 11.sp, color = Color.Gray) },
                                            onClick = {},
                                            enabled = false
                                        )
                                    } else {
                                        systemAudioTones.forEach { tone ->
                                            val isSelected = selectedAudioUri == tone.second
                                            DropdownMenuItem(
                                                text = { 
                                                    Text(
                                                        tone.first, 
                                                        fontSize = 12.sp, 
                                                        color = if (isSelected) Color(0xFF00FF88) else Color.LightGray
                                                    ) 
                                                },
                                                onClick = {
                                                    viewModel.updateSelectedAudioTone(tone.first, tone.second)
                                                    showPickerMenu = false
                                                },
                                                modifier = Modifier.background(if (isSelected) Color(0xFF282828) else Color.Transparent)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (selectedPayload == ActionPayload.CAMERA && !cameraPermissionGranted) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "⚠ Camera Permission is current REVOKED! Click here to authorize.",
                            color = Color(0xFFFF3D00),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable { launcherCamera.launch(Manifest.permission.CAMERA) }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // 7. Security Terminal Logger
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("terminal_card"),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF000000)),
                border = BorderStroke(1.dp, Color(0xFF00FF88))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SECURITY CONSOLE LOGS",
                            color = Color(0xFF00FF88),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "CLEAR",
                            color = Color.LightGray,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable { SeismicTelemetry.clearLogs() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    HorizontalDivider(
                        color = Color(0x3300FF88),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    // Scrollable log lists
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    ) {
                        val listState = rememberLazyListState()
                        
                        // Force list to auto-scroll to top when a new log appears
                        LaunchedEffect(consoleLogs.size) {
                            listState.animateScrollToItem(0)
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(consoleLogs) { log ->
                                Text(
                                    text = log,
                                    color = if (log.contains("MATCH") || log.contains("SUCCESS")) Color(0xFF00FF88) else if (log.contains("KNOCK")) Color(0xFF00E5FF) else if (log.contains("WARN") || log.contains("ERROR")) Color(0xFFFF3D00) else Color.LightGray,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun TelemetryMetricBox(
    label: String,
    value: Float,
    color: Color
) {
    Card(
        modifier = Modifier
            .width(96.dp)
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151515))
    ) {
        Column(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$label Axis",
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = String.format("%.3f", value),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun SurfaceMatrixItem(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(76.dp)
    ) {
        Text(
            text = label,
            color = Color.Gray,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}


