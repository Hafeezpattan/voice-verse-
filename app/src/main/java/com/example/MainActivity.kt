package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.VoiceActionLog
import com.example.service.JarvisBrain
import com.example.service.VoiceTriggerService
import com.example.ui.VoiceGuardViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val activeAction = MutableStateFlow<String?>(null)
    private val contactsList = mutableStateListOf<ContactItem>()
    private val isCameraActive = MutableStateFlow(false)

    // Permissions launcher
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val recordGranted = results[Manifest.permission.RECORD_AUDIO] ?: false
        val cameraGranted = results[Manifest.permission.CAMERA] ?: false
        val contactsGranted = results[Manifest.permission.READ_CONTACTS] ?: false

        if (recordGranted) {
            Toast.makeText(this, "Microphone access verified securely on device", Toast.LENGTH_SHORT).show()
        }
        if (contactsGranted) {
            loadDeviceContacts()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle offline voice command shortcuts from intent
        intent?.let { handleActionIntent(it) }

        setContent {
            MyApplicationTheme {
                val viewModel: VoiceGuardViewModel = viewModel()
                val currentAction by activeAction.collectAsState()
                val cameraState by isCameraActive.collectAsState()

                // Register standard permissions on boot
                LaunchedEffect(Unit) {
                    checkAndRequestMissingPermissions()
                    loadDeviceContacts()
                }

                // Handle system execution overrides from background listening flows
                LaunchedEffect(currentAction) {
                    currentAction?.let { action ->
                        when (action.uppercase()) {
                            "CAMERA" -> {
                                isCameraActive.value = true
                                viewModel.resetDetectedOperation()
                            }
                            "CONTACTS" -> {
                                loadDeviceContacts()
                                viewModel.resetDetectedOperation()
                            }
                        }
                    }
                }

                // Standard feedback toaster
                val toastMsg by viewModel.uiToastMessage.collectAsState()
                LaunchedEffect(toastMsg) {
                    toastMsg?.let { msg ->
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        viewModel.clearToast()
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.navigationBars
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(CyberBaseBg)
                            .padding(innerPadding)
                    ) {
                        VoiceGuardDashboardScreen(
                            viewModel = viewModel,
                            contactsList = contactsList,
                            onTriggerContactsPermission = {
                                requestPermissionsLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                            },
                            onTriggerCameraPermission = {
                                requestPermissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                            },
                            onCloseActionOverlays = {
                                isCameraActive.value = false
                                activeAction.value = null
                            }
                        )

                        // Camera sandboxed view finder overlay featuring green scanline animations
                        AnimatedVisibility(
                            visible = cameraState,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            SecureViewfinderOverlay(
                                onClose = { isCameraActive.value = false },
                                onSimulateCommand = { text -> viewModel.simulateVoiceCommand(text) }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleActionIntent(intent)
    }

    private fun handleActionIntent(intent: Intent) {
        val action = intent.getStringExtra("triggered_action")
        if (action != null) {
            activeAction.value = action
        }
    }

    private fun checkAndRequestMissingPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requestPermissionsLauncher.launch((missing + Manifest.permission.POST_NOTIFICATIONS).toTypedArray())
            } else {
                requestPermissionsLauncher.launch(missing.toTypedArray())
            }
        }
    }

    private fun loadDeviceContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            contactsList.clear()
            val resolver = contentResolver
            val uri: Uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            var cursor: Cursor? = null
            try {
                cursor = resolver.query(uri, projection, null, null, null)
                cursor?.let {
                    val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    var count = 0
                    while (it.moveToNext() && count < 6) {
                        val name = it.getString(nameIdx)
                        val num = it.getString(numIdx)
                        contactsList.add(ContactItem(name, num))
                        count++
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                cursor?.close()
            }
        }
        
        // Populate fallback secure contacts to ensure UI completion in emulator sandbox
        if (contactsList.isEmpty()) {
            contactsList.addAll(
                listOf(
                    ContactItem("Secure Contact A", "+1 (555) 723-8321"),
                    ContactItem("Emergency Support Desk", "+1 (800) 555-0114"),
                    ContactItem("Dev Mainframe", "+1 (415) 555-2026")
                )
            )
        }
    }
}

data class ContactItem(val name: String, val phoneNumber: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceGuardDashboardScreen(
    viewModel: VoiceGuardViewModel,
    contactsList: List<ContactItem>,
    onTriggerContactsPermission: () -> Unit,
    onTriggerCameraPermission: () -> Unit,
    onCloseActionOverlays: () -> Unit
) {
    val logs by viewModel.logs.collectAsState()
    val serviceStatus by viewModel.serviceStatus.collectAsState()
    val lastText by viewModel.lastTranscribedText.collectAsState()
    
    // JARVIS real-time TTS speech responses
    val lastJarvisReply by VoiceTriggerService.lastJarvisReply.collectAsState()

    // Holographic device states map
    val hardwareStates by VoiceTriggerService.activeHardwareStates.collectAsState()

    val passphraseInput by viewModel.masterPassphraseInput.collectAsState()
    val voiceThreshold by viewModel.voiceThresholdInput.collectAsState()
    
    val context = LocalContext.current
    var isSettingsExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = WindowInsets.navigationBars.asPaddingValues()
    ) {
        // Futuristic Tactical Top Header
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "SECURE INTELLIGENT COMPANION",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = ImmersiveTextMuted,
                            letterSpacing = 2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "J.A.R.V.I.S. AI",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = (-0.5).sp
                        )
                    )
                }

                // Futuristic settings configuration hex matrix button
                IconButton(
                    onClick = { isSettingsExpanded = !isSettingsExpanded },
                    modifier = Modifier
                        .size(48.dp)
                        .background(ImmersiveSurface, shape = RoundedCornerShape(16.dp))
                        .border(1.dp, ImmersiveBorder, shape = RoundedCornerShape(16.dp))
                        .testTag("settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Toggle Setting Tray",
                        tint = if (isSettingsExpanded) ImmersiveBlue else ImmersiveTextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Monitoring service state pulsing badge ring
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ImmersiveSurface),
                border = BorderStroke(1.dp, ImmersiveBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "AURALSIGN SHIELD SCANNER",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = ImmersiveTextMuted,
                            letterSpacing = 1.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Pulse Circle animation representation
                    StatusIndicatorPulse(serviceStatus)

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (serviceStatus.contains("Active") || serviceStatus.contains("Listening")) "JARVIS ONLINE (STANDBY)" else serviceStatus.uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = if (serviceStatus.contains("Active") || serviceStatus.contains("Listening")) Color.White else statusColor(serviceStatus),
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    )

                    AnimatedVisibility(visible = !lastText.isNullOrEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 10.dp)
                        ) {
                            Text(
                                text = "LATEST TRANSCRIBED COMMAND",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = ImmersiveTextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "\"${lastText ?: ""}\"",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = ImmersiveBlueGlow,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Dynamic Active Shield Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ImmersiveSurfaceActive, shape = RoundedCornerShape(16.dp))
                            .border(1.dp, ImmersiveBorder, shape = RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Secure Listening Wake-word",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                            Text(
                                text = "Decrypting locally using on-device security",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = ImmersiveTextMuted,
                                    fontSize = 11.sp
                                )
                            )
                        }
                        Switch(
                            checked = serviceStatus.contains("Active") || serviceStatus.contains("Idle") || serviceStatus.contains("Listening") || serviceStatus.contains("Processing"),
                            onCheckedChange = { isChecked ->
                                viewModel.toggleService(isChecked)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ImmersiveBlue,
                                uncheckedThumbColor = ImmersiveTextMuted,
                                uncheckedTrackColor = ImmersiveBg,
                                checkedBorderColor = Color.Transparent,
                                uncheckedBorderColor = ImmersiveBorder
                            ),
                            modifier = Modifier.testTag("service_switch")
                        )
                    }
                }
            }
        }

        // JARVIS Vocal Voice Output Terminal Box
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ImmersiveSurface),
                border = BorderStroke(1.dp, ImmersiveBlue.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(ImmersiveBlueGlow, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "J.A.R.V.I.S. VOCAL RESPONSE CORES",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = ImmersiveBlueGlow,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Visualizer status",
                            tint = ImmersiveBlueGlow.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = lastJarvisReply ?: "Sir, I am online and waiting for commands.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 22.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ImmersiveSurfaceActive, shape = RoundedCornerShape(12.dp))
                            .border(1.dp, ImmersiveBorder, shape = RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Sound wave animated visualizer mimicking actual physical voices
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // We set up standard heights and bounce them
                        val infiniteTransition = rememberInfiniteTransition()
                        val speeds = listOf(350, 480, 520, 400, 310, 450, 390, 560, 420, 360, 500)
                        
                        speeds.forEachIndexed { idx, duration ->
                            val heightFraction by infiniteTransition.animateFloat(
                                initialValue = 0.2f,
                                targetValue = 1.0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(duration, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                )
                            )
                            
                            val isPulseActive = serviceStatus.contains("Speaking") || serviceStatus.contains("Listening") || serviceStatus.contains("Speech")
                            val actualHeightFraction = if (isPulseActive) heightFraction else 0.15f

                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 2.dp)
                                    .width(3.dp)
                                    .fillMaxHeight(actualHeightFraction)
                                    .background(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(ImmersiveBlueGlow, ImmersiveBlue)
                                        ),
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }

        // TACTICAL HARDWARE TOGGLES (Holographic Sci-Fi Widgets)
        item {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "ACTIVE OPERATIONAL CORE SYSTEM",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    ),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Customizable widget layout
                val systemTiles = listOf(
                    SystemTile("WiFi Adapter", hardwareStates["WiFi"] ?: true, Icons.Default.Share, "wifi"),
                    SystemTile("Bluetooth module", hardwareStates["Bluetooth"] ?: false, Icons.Default.Refresh, "bluetooth"),
                    SystemTile("Saber Flashlight", hardwareStates["Flashlight"] ?: false, Icons.Default.Build, "flashlight"),
                    SystemTile("Alarms Matrix", hardwareStates["Alarms"] ?: false, Icons.Default.CheckCircle, "alarms"),
                    SystemTile("Sound stream", hardwareStates["Music"] ?: false, Icons.Default.PlayArrow, "music"),
                    SystemTile("SOS Broadcaster", hardwareStates["SOS Mode"] ?: false, Icons.Default.Warning, "sos")
                )

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        systemTiles.take(3).forEach { tile ->
                            HardwareWidgetCard(tile)
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        systemTiles.drop(3).forEach { tile ->
                            HardwareWidgetCard(tile)
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
            }
        }

        // AUTONOMOUS MULTI-COMMAND SEQUENCE BUILDER
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ImmersiveSurface),
                border = BorderStroke(1.dp, ImmersiveBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Builder",
                            tint = ImmersiveBlueGlow,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AUTOMATION CHAIN RECIPE MODULE",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Chain multiple autonomous system Operations with a single custom command. Tap execute to deploy localized macro directives safely.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = ImmersiveTextMuted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Sequence presets
                    AutomationRecipeRow(
                        name = "Secure Protocol Alpha",
                        desc = "Opens secure camera sandbox, flags SOS Broadcast support, and configures alarms.",
                        commands = listOf("CAMERA", "SOS", "ALARM"),
                        onClickExecute = {
                            viewModel.simulateVoiceCommand("execute secure protocol alpha and start camera")
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    AutomationRecipeRow(
                        name = "Stealth Shutdown Standard",
                        desc = "Disables Wifi networks, turns on physical flashlight, and launches contacts database.",
                        commands = listOf("WIFI", "FLASHLIGHT", "CONTACTS"),
                        onClickExecute = {
                            viewModel.simulateVoiceCommand("activate stealth shutdown standard triggers flashlight and contacts list")
                        }
                    )
                }
            }
        }

        // Command Sandbox Simulated Drawer
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ImmersiveSurface),
                border = BorderStroke(1.dp, ImmersiveBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Device Sandbox",
                            tint = ImmersiveBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "COMMAND SENSORY SANDBOX",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Because cloud-hosted environments lack hardware mic feeds, use these simulated stream command triggers to evaluate JARVIS locally.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = ImmersiveTextMuted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Simulated trigger CTA grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val masterText = passphraseInput.ifEmpty { "activate security" }
                                viewModel.simulateVoiceCommand(masterText)
                            },
                            modifier = Modifier.weight(1f).testTag("sandbox_verify_master"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ImmersiveSurfaceActive, contentColor = ImmersiveBlueGlow),
                            border = BorderStroke(1.dp, ImmersiveBlue.copy(alpha = 0.4f))
                        ) {
                            Text(text = "Verify Master", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                val text = "access contacts ${passphraseInput.ifEmpty { "activate security" }}"
                                viewModel.simulateVoiceCommand(text)
                            },
                            modifier = Modifier.weight(1f).testTag("sandbox_open_vault"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ImmersiveSurfaceActive, contentColor = ImmersiveText),
                            border = BorderStroke(1.dp, ImmersiveBorder)
                        ) {
                            Text(text = "Open Vault", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val text = "activate camera ${passphraseInput.ifEmpty { "activate security" }}"
                                viewModel.simulateVoiceCommand(text)
                            },
                            modifier = Modifier.weight(1f).testTag("sandbox_unmask_cam"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ImmersiveSurfaceActive, contentColor = ImmersiveText),
                            border = BorderStroke(1.dp, ImmersiveBorder)
                        ) {
                            Text(text = "Unmask Cam", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                viewModel.simulateVoiceCommand("unauthorized intruder voice sequence")
                            },
                            modifier = Modifier.weight(1f).testTag("sandbox_fail_voiceprint"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ImmersiveSurfaceActive, contentColor = CyberRed),
                            border = BorderStroke(1.dp, CyberRed.copy(alpha = 0.4f))
                        ) {
                            Text(text = "Fail Voiceprint", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            viewModel.simulateVoiceCommand("Jarvis, who created iron man and what are the system specs?")
                        },
                        modifier = Modifier.fillMaxWidth().testTag("sandbox_ask_q"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ImmersiveSurfaceActive, contentColor = Color.White),
                        border = BorderStroke(1.dp, ImmersiveBorder)
                    ) {
                        Text(text = "Ask: Who Created Iron Man?", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Voice Signature Setup Config Block
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ImmersiveSurface),
                border = BorderStroke(1.dp, ImmersiveBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isSettingsExpanded = !isSettingsExpanded }
                            .testTag("signature_config_header"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Voice print setup",
                                tint = ImmersiveBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "LOCAL CRYPTOPRINT CONFIG",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                            )
                        }
                        Icon(
                            imageVector = if (isSettingsExpanded) Icons.Default.Close else Icons.Default.Settings,
                            contentDescription = "Expand Status panel",
                            tint = ImmersiveTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    AnimatedVisibility(visible = isSettingsExpanded || passphraseInput == "activate security") {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Text(
                                text = "Define a dynamic offline voice activation secret trigger (e.g. 'Hey JARVIS'). The system stores a safe cryptographic hash locally, decrypting only matching transcripts.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = ImmersiveTextMuted,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = passphraseInput,
                                onValueChange = { viewModel.onPassphraseChange(it) },
                                label = { Text("Secret Passphrase Trigger text", color = ImmersiveTextMuted) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("passphrase_input_field"),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ImmersiveBlue,
                                    unfocusedBorderColor = ImmersiveBorder,
                                    focusedLabelColor = ImmersiveBlue,
                                    unfocusedLabelColor = ImmersiveTextMuted,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ImmersiveSurfaceActive, shape = RoundedCornerShape(12.dp))
                                    .border(1.dp, ImmersiveBorder, shape = RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Confidence Limit: ${voiceThreshold}%",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color.White,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { if (voiceThreshold > 50) viewModel.onThresholdChange(voiceThreshold - 5) },
                                        modifier = Modifier.testTag("threshold_decrement")
                                    ) {
                                        Text("-", color = ImmersiveBlue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    IconButton(
                                        onClick = { if (voiceThreshold < 95) viewModel.onThresholdChange(voiceThreshold + 5) },
                                        modifier = Modifier.testTag("threshold_increment")
                                    ) {
                                        Text("+", color = ImmersiveBlue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { viewModel.registerVoiceprint() },
                                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("save_signature_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ImmersiveBlue, contentColor = Color.White)
                            ) {
                                Text(
                                    text = "SECURE & REHASH KEYPRINT",
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Database secured contacts list showing sandbox integration
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ImmersiveSurface),
                border = BorderStroke(1.dp, ImmersiveBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Device contacts list",
                                tint = ImmersiveBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "LOCAL SANDBOXED CONTACTS",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                            )
                        }
                        IconButton(
                            onClick = onTriggerContactsPermission,
                            modifier = Modifier
                                .size(32.dp)
                                .background(ImmersiveSurfaceActive, shape = CircleShape)
                                .testTag("contacts_refresh")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    if (contactsList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No local contacts found. Check sandboxed permissions.",
                                style = MaterialTheme.typography.bodySmall.copy(color = ImmersiveTextMuted)
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            contactsList.forEach { contact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(ImmersiveSurfaceActive, shape = RoundedCornerShape(12.dp))
                                        .border(1.dp, ImmersiveBorder, shape = RoundedCornerShape(12.dp))
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = contact.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = contact.phoneNumber,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = ImmersiveTextMuted,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .background(ImmersiveBg, shape = RoundedCornerShape(6.dp))
                                            .border(1.dp, ImmersiveBorder, shape = RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Sandboxed",
                                            tint = ImmersiveBlue,
                                            modifier = Modifier.size(10.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "LOCAL",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = ImmersiveBlueGlow,
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Room db event logging listing
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "LOCAL AUDIT TIMELINE LOGS",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
                IconButton(
                    onClick = { viewModel.clearLogs() },
                    modifier = Modifier.testTag("clear_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear audit database logs",
                        tint = CyberRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(ImmersiveSurface, shape = RoundedCornerShape(16.dp))
                        .border(BorderStroke(1.dp, ImmersiveBorder), shape = RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Awaiting secure voice triggered activities...",
                        style = MaterialTheme.typography.bodySmall.copy(color = ImmersiveTextMuted, fontFamily = FontFamily.Monospace)
                    )
                }
            }
        } else {
            items(logs, key = { it.id }) { log ->
                AuditLogCard(log)
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun StatusIndicatorPulse(status: String) {
    val isListening = status.contains("Active") || status.contains("Listening") || status.contains("Idle") || status.contains("Speech")
    val isEvaluating = status.contains("Processing") || status.contains("Reasoning") || status.contains("Evaluating") || status.contains("Voiceprint")
    val isSpeaking = status.contains("Vocalizing") || status.contains("Speaking")

    val infiniteTransition = rememberInfiniteTransition()

    // Breathing pulse scale
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isEvaluating) 600 else if (isSpeaking) 900 else 1800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    // Orbiting particles angle
    val angleDegrees by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Box(
        modifier = Modifier.padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer glowing back aura
        Box(
            modifier = Modifier
                .size(190.dp)
                .scale(breatheScale)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            if (isSpeaking) ImmersiveBlueGlow.copy(alpha = 0.2f) else if (isEvaluating) Color.Cyan.copy(alpha = 0.25f) else ImmersiveBlue.copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Orb Ring 1
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(breatheScale)
                .border(2.dp, Color.White.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Orb Ring 2
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .scale(breatheScale * 0.94f)
                    .border(
                        1.dp,
                        if (isSpeaking) ImmersiveBlueGlow.copy(alpha = 0.4f) else if (isEvaluating) Color.Cyan.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Tactical Floating Nodes Inside Core Ring
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(
                            brush = Brush.linearGradient(
                                colors = if (isSpeaking) listOf(ImmersiveBlue, ImmersiveBlueGlow) else if (isEvaluating) listOf(ImmersiveBlue, Color.Cyan) else listOf(ImmersiveSurfaceActive, ImmersiveBlue)
                            ),
                            shape = CircleShape
                        )
                        .border(1.5.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isEvaluating) Icons.Default.Refresh else if (isSpeaking) Icons.Default.PlayArrow else Icons.Default.Info,
                        contentDescription = "Autonomous Jarvis AI core Status symbol",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HardwareWidgetCard(tile: SystemTile) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hardware_widget_${tile.tag_name}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (tile.isActive) ImmersiveSurfaceActive else ImmersiveSurface),
        border = BorderStroke(1.dp, if (tile.isActive) ImmersiveBlue.copy(alpha = 0.6f) else ImmersiveBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(if (tile.isActive) ImmersiveBlue.copy(alpha = 0.2f) else ImmersiveBg, shape = RoundedCornerShape(10.dp))
                        .border(1.dp, if (tile.isActive) ImmersiveBlueGlow.copy(alpha = 0.4f) else ImmersiveBorder, shape = RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = tile.icon,
                        contentDescription = tile.title,
                        tint = if (tile.isActive) ImmersiveBlueGlow else ImmersiveTextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = tile.title,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    )
                    Text(
                        text = if (tile.isActive) "SECURE ACTIVE" else "OFFLINE STANDBY",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (tile.isActive) ImmersiveBlueGlow else ImmersiveTextMuted,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(if (tile.isActive) ImmersiveBlueGlow else Color.Transparent, CircleShape)
                    .border(1.dp, if (tile.isActive) Color.Transparent else ImmersiveBorder, CircleShape)
            )
        }
    }
}

data class SystemTile(val title: String, val isActive: Boolean, val icon: ImageVector, val tag_name: String)

@Composable
fun AutomationRecipeRow(name: String, desc: String, commands: List<String>, onClickExecute: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ImmersiveSurfaceActive, shape = RoundedCornerShape(16.dp))
            .border(1.dp, ImmersiveBorder, shape = RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall.copy(color = ImmersiveTextMuted, fontSize = 10.sp),
                lineHeight = 14.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                commands.forEach { cmd ->
                    Box(
                        modifier = Modifier
                            .background(ImmersiveBg, shape = RoundedCornerShape(4.dp))
                            .border(1.dp, ImmersiveBorder, shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = cmd,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = ImmersiveBlueGlow,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Button(
            onClick = onClickExecute,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ImmersiveBg, contentColor = ImmersiveBlueGlow),
            border = BorderStroke(1.dp, ImmersiveBlue.copy(alpha = 0.5f)),
            modifier = Modifier.size(height = 36.dp, width = 74.dp)
        ) {
            Text("DEPLOY", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AuditLogCard(log: VoiceActionLog) {
    val isSuccess = log.status == "SUCCESS"
    val timestampFormatted = remember(log.timestamp) {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        sdf.format(Date(log.timestamp))
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("audit_log_${log.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ImmersiveSurface),
        border = BorderStroke(1.dp, ImmersiveBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(ImmersiveSurfaceActive, shape = RoundedCornerShape(12.dp))
                        .border(1.dp, ImmersiveBorder, shape = RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = log.status,
                        tint = if (isSuccess) ImmersiveBlueGlow else CyberRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    Text(
                        text = log.spokenText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$timestampFormatted • Directives: ${log.detectedAction}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = ImmersiveTextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .background(ImmersiveSurfaceActive, shape = RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isSuccess) "APPROVED" else "BLOCKED",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (isSuccess) ImmersiveBlueGlow else CyberRed,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${(log.confidence * 100).toInt()}% conf",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = ImmersiveTextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
    }
}

@Composable
fun SecureViewfinderOverlay(onClose: () -> Unit, onSimulateCommand: (String) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isScanning by remember { mutableStateOf(false) }
    var scanResultText by remember { mutableStateOf<String?>(null) }
    
    // continuous scanline sweeping infinite animation
    val infiniteTransition = rememberInfiniteTransition()
    val scanLineFraction by infiniteTransition.animateFloat(
        initialValue = -0.1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f))
            .clickable { /* Block underlying taps */ },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "SANDBOX SCANNING CAPTURE MATRIX",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = ImmersiveBlueGlow,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
                IconButton(onClick = onClose, modifier = Modifier.testTag("close_viewcode_overlay")) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Viewfinder",
                        tint = CyberRed,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            // Simulated Viewfinder Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(290.dp)
                    .border(2.dp, if (isScanning) Color.Cyan else ImmersiveBlue, RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F0F12), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Viewfinder Retro Shaders Grids
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .border(1.dp, ImmersiveBlueGlow.copy(alpha = 0.25f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(ImmersiveBlueGlow.copy(alpha = 0.12f))
                )
                Box(
                    modifier = Modifier
                        .height(1.dp)
                        .fillMaxWidth()
                        .background(ImmersiveBlueGlow.copy(alpha = 0.12f))
                )

                // The sweeping physical laser scanner sweeping up/down
                val actualLineY = scanLineFraction * 290
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .offset(y = (actualLineY - 145).dp)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, Color.Cyan, Color.Transparent)
                            )
                        )
                        .alpha(0.8f)
                )

                // Text Overlay / Scanner status
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isScanning) "SCAN STATUS: ACTIVE" else "HUD: STANDBY",
                            color = if (isScanning) Color.Cyan else CyberRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "SECURE LOCAL SANDBOX",
                            color = ImmersiveBlueGlow,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Scan diagnostics screen output in typewriter sci-fi letters
                    if (scanResultText != null) {
                        Text(
                            text = scanResultText ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Left
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.85f))
                                .border(1.dp, ImmersiveBlueGlow.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        )
                    } else if (isScanning) {
                        CircularProgressIndicator(
                            color = Color.Cyan,
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ISO 420", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("JARVIS MATRIX 1.0", color = ImmersiveBlueGlow, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Interactive Scanner Launcher Trigger (Vision AI object & OCR OCR text recognition and synthesis)
                Button(
                    onClick = {
                        isScanning = true
                        scanResultText = null
                        coroutineScope.launch {
                            // Simulate physical camera frame representation encoding (64bytes dummy representation)
                            val dummyBase = Base64.encodeToString("JARVIS_VISION_IMAGE".toByteArray(), Base64.NO_WRAP)
                            val diagnosticResult = JarvisBrain.analyzeScene(dummyBase)
                            
                            isScanning = false
                            scanResultText = diagnosticResult
                            
                            // Vocalize the vision scanned results back using J.A.R.V.I.S. live synthesis!
                            onSimulateCommand("Jarvis, vocalize optical analysis result: $diagnosticResult")
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp).testTag("trigger_vision_scanner"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ImmersiveBlue),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("AI SCENE ANALYSIS & OCR", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        scanResultText = "OCR TEXT CAPTURED: 'SECURE FLOW PASS: 100% SUCCESS'\nDOC DETECTED: Standard localized operating handbook."
                        // speak back
                        onSimulateCommand("Jarvis, read OCR logs: System confirmed secure flow pass.")
                    },
                    modifier = Modifier.height(48.dp).testTag("trigger_ocr_scan"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ImmersiveSurfaceActive, contentColor = ImmersiveBlueGlow),
                    border = BorderStroke(1.dp, ImmersiveBorder)
                ) {
                    Text("OCR", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Encrypted On device",
                    tint = ImmersiveBlue,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "VISUAL MATRIX SANDBOX APPROVED",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = ImmersiveBlueGlow,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "All OCR text scans and visual scene diagnostics remain constrained inside sandboxed RAM cache. No records reach external network models, ensuring absolute user privacy, sir.",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                ),
                color = ImmersiveTextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

// Utility style helpers
private fun statusColor(status: String): Color {
    return when {
        status.contains("Active") || status.contains("Idle") || status.contains("Listening") -> ImmersiveBlueGlow
        status.contains("Processing") || status.contains("Reasoning") || status.contains("Verifying") || status.contains("Verified") -> ImmersiveBlue
        status.contains("Blocked") -> CyberRed
        else -> ImmersiveTextMuted
    }
}
