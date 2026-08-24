package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.control.PermissionTier
import com.example.data.CommandLog
import com.example.data.Note
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted! Jarvis starting.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Some permissions denied. Jarvis will run in limited mode.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check and request runtime permissions on launch
        checkAndRequestPermissions()

        setContent {
            MyApplicationTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F0F12) // Pure sleek OLED background
                ) {
                    JarvisAppScreen()
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisAppScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    
    val serviceBound by viewModel.isServiceBound.collectAsState()
    val serviceStatus by viewModel.serviceStatus.collectAsState()
    val lastTranscript by viewModel.lastTranscript.collectAsState()
    val selectedTier by viewModel.selectedTier.collectAsState()
    val notes by viewModel.allNotes.collectAsState()
    val logs by viewModel.recentLogs.collectAsState()

    var manualCommandText by remember { mutableStateOf("") }
    var activeTab by remember { mutableIntStateOf(0) } // 0: Setup, 1: Console, 2: Notes, 3: Logs

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasAudioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-start and bind to service on load once permission is granted
    LaunchedEffect(hasAudioPermission) {
        if (hasAudioPermission) {
            viewModel.startAndBindService()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Microphone",
                            tint = if (serviceBound) Color(0xFF00E6FF) else Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "JARVIS SYSTEM",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF15151A)
                ),
                actions = {
                    // Pulsing light indicating engine status
                    Box(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(12.dp)
                            .background(
                                color = if (serviceBound) Color(0xFF00FF66) else Color(0xFFFF3366),
                                shape = RoundedCornerShape(50)
                            )
                    )
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF15151A),
                modifier = Modifier.navigationBarsPadding()
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Setup Wizard") },
                    label = { Text("Setup", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF00E6FF),
                        indicatorColor = Color(0xFF1F2937)
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Console") },
                    label = { Text("Console", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF00E6FF),
                        indicatorColor = Color(0xFF1F2937)
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.Edit, contentDescription = "Notes") },
                    label = { Text("Notes (${notes.size})", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF00E6FF),
                        indicatorColor = Color(0xFF1F2937)
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 3,
                    onClick = { activeTab = 3 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Logs") },
                    label = { Text("Telemetry", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF00E6FF),
                        indicatorColor = Color(0xFF1F2937)
                    )
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color(0xFF0F0F12))
        ) {
            // Visual Core Banner (Universal across tabs)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            ) {
                // Background generated image
                Image(
                    painter = painterResource(id = R.drawable.img_jarvis_hero),
                    contentDescription = "Jarvis AI Core Banner",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Smooth gradient overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xFF0F0F12)),
                                startY = 50f
                            )
                        )
                )
                
                // Hologram overlay details
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                ) {
                    Text(
                        text = serviceStatus,
                        fontSize = 12.sp,
                        color = Color(0xFF00E6FF),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "VOSK OFFLINE SPEECH ENGINE",
                        fontSize = 10.sp,
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF1F1F2E), thickness = 1.dp)

            // Content based on selected tab
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                when (activeTab) {
                    0 -> SetupWizardView(viewModel, selectedTier)
                    1 -> InteractiveConsoleView(viewModel, lastTranscript, manualCommandText, onCommandTextChange = { manualCommandText = it })
                    2 -> NotesManagerView(viewModel, notes)
                    3 -> TelemetryLogsView(viewModel, logs)
                }
            }
        }
    }
}

// --- TAB 1: SETUP WIZARD VIEW ---

@Composable
fun SetupWizardView(viewModel: MainViewModel, selectedTier: PermissionTier) {
    val context = LocalContext.current
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        item {
            Text(
                text = "TIERED ACCESS SETUP",
                fontSize = 15.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Select your desired level of system capabilities. Grant only what you are comfortable with.",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // TIER 1: BASIC
        item {
            PermissionTierCard(
                title = "Tier 1: Basic Access (Default)",
                description = "Uses standard Android intents to launch settings panels, open dialing pads, trigger searches, and compose SMS text screens. Highly secure, zero special permissions.",
                statusText = "Fully Functional",
                statusColor = Color(0xFF00FF66),
                icon = Icons.Default.CheckCircle,
                isSelected = selectedTier == PermissionTier.BASIC,
                onClick = { viewModel.setPermissionTier(PermissionTier.BASIC) }
            )
        }

        // TIER 2: ACCESSIBILITY AUTOMATION
        item {
            PermissionTierCard(
                title = "Tier 2: Accessibility Automation",
                description = "Unlocks near-silent connectivity automation. An background Accessibility Service automates the swipe-downs and clicking of WiFi and Bluetooth tiles inside your Quick Settings on direct voice triggers.",
                statusText = "Needs Settings Enablement",
                statusColor = Color(0xFFFFBB00),
                icon = Icons.Outlined.Info,
                isSelected = selectedTier == PermissionTier.ACCESSIBILITY,
                onClick = { viewModel.setPermissionTier(PermissionTier.ACCESSIBILITY) },
                actionButton = {
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                Toast.makeText(context, "Locate 'Jarvis Assistant Automation' and switch ON", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open accessibility settings", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2937)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("grant_accessibility_button")
                    ) {
                        Icon(Icons.Default.Build, contentDescription = "Setup", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Grant Accessibility Services", fontSize = 12.sp, color = Color.White)
                    }
                }
            )
        }

        // TIER 3: DEVICE OWNER / MDM
        item {
            PermissionTierCard(
                title = "Tier 3: Device Owner Mode",
                description = "Advanced enterprise-grade network locks. Jarvis takes direct administration ownership of the device to programmatically manage wireless frequencies silently.",
                statusText = "ADB Setup Required",
                statusColor = Color(0xFFE53E3E),
                icon = Icons.Default.Warning,
                isSelected = selectedTier == PermissionTier.DEVICE_OWNER,
                onClick = { viewModel.setPermissionTier(PermissionTier.DEVICE_OWNER) },
                additionalContent = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF15151C), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "To provision, connect device to PC via USB debugging and run:",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F0F12), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF2E2E3E), RoundedCornerShape(4.dp))
                                .padding(8.dp)
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("ADB Command", "adb shell dpm set-device-owner com.example/com.example.service.JarvisDeviceAdminReceiver")
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Command copied to clipboard!", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "adb shell dpm set-device-owner com.example/com.example.service.JarvisDeviceAdminReceiver",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF00E6FF),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.Share, contentDescription = "Copy", tint = Color.Gray, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            )
        }

        // TIER 4: ROOT PRIVILEGES
        item {
            PermissionTierCard(
                title = "Tier 4: Superuser (Root) Access",
                description = "For rooted hardware. Executes instant background silent networking switches using 'su su svc wifi enable/disable' command lines. Unlocks complete absolute background automation.",
                statusText = "Root Authorization Only",
                statusColor = Color(0xFFE53E3E),
                icon = Icons.Default.Lock,
                isSelected = selectedTier == PermissionTier.ROOT,
                onClick = { viewModel.setPermissionTier(PermissionTier.ROOT) }
            )
        }
    }
}

@Composable
fun PermissionTierCard(
    title: String,
    description: String,
    statusText: String,
    statusColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    actionButton: @Composable (() -> Unit)? = null,
    additionalContent: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color(0xFF00E6FF) else Color(0xFF1E1E28),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF151821) else Color(0xFF111116)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) Color(0xFF00E6FF) else Color.White
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = statusText,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = statusText,
                        fontSize = 10.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color.LightGray,
                lineHeight = 16.sp
            )

            if (actionButton != null || additionalContent != null) {
                Spacer(modifier = Modifier.height(12.dp))
                actionButton?.invoke()
                Spacer(modifier = Modifier.height(6.dp))
                additionalContent?.invoke()
            }
        }
    }
}

// --- TAB 2: INTERACTIVE CONSOLE VIEW ---

@Composable
fun InteractiveConsoleView(
    viewModel: MainViewModel,
    lastTranscript: String,
    manualCommandText: String,
    onCommandTextChange: (String) -> Unit
) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "JARVIS INTERACTIVE CONSOLE",
            fontSize = 15.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        // Waveform Visualizer simulation
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(Color(0xFF111116), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF1E1E28), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "bars")
                // Simulated pulsing visual bars representing voice mic wave
                repeat(12) { index ->
                    val heightScale by infiniteTransition.animateFloat(
                        initialValue = 10f,
                        targetValue = 60f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 300 + (index * 60), easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "bar_$index"
                    )
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .height(heightScale.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color(0xFF00FFCC), Color(0xFF0099FF))
                                ),
                                shape = RoundedCornerShape(10)
                            )
                    )
                }
            }
        }

        // Live spoken response feedback text
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111116)),
            border = BorderStroke(1.dp, Color(0xFF1E1E28))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "LAST SPOKEN TRANSCRIPT / HEARD VOICE",
                    fontSize = 11.sp,
                    color = Color(0xFF00E6FF),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (lastTranscript.isNotBlank()) "\"$lastTranscript\"" else "\"No spoken triggers captured yet. Try saying: Jarvis open camera.\"",
                    fontSize = 14.sp,
                    color = if (lastTranscript.isNotBlank()) Color.White else Color.Gray,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Playground Command Box
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF15151C)),
            border = BorderStroke(1.dp, Color(0xFF00E6FF))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "VIRTUAL VOICE COMMAND (TESTING BOX)",
                    fontSize = 11.sp,
                    color = Color(0xFF00E6FF),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Type and execute any system command directly. Skip wake delays or testing noise.",
                    fontSize = 11.sp,
                    color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = manualCommandText,
                    onValueChange = onCommandTextChange,
                    placeholder = { Text("e.g. Jarvis turn on wifi", color = Color.Gray, fontSize = 13.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("simulate_command_input"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E6FF),
                        unfocusedBorderColor = Color(0xFF2E2E3E),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (manualCommandText.isNotBlank()) {
                            viewModel.submitSimulatedCommand(manualCommandText)
                            onCommandTextChange("")
                        } else {
                            Toast.makeText(context, "Command box is empty!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("execute_command_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E6FF)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Execute Command Offline", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Example trigger prompt ideas
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "SUGGESTED ALIAS COMMANDS:",
                fontSize = 11.sp,
                color = Color.Gray,
                fontFamily = FontFamily.Monospace
            )
            val tips = listOf(
                "\"Jarvis turn on wifi\" / \"Jarvis turn off wifi\"",
                "\"Jarvis turn on flashlight\"",
                "\"Jarvis write note Remember to buy milk\"",
                "\"Jarvis read notes\"",
                "\"Jarvis open settings\" / \"Jarvis open camera\"",
                "\"Jarvis search Samsung galaxy s26\"",
                "\"Jarvis volume up\" / \"Jarvis volume down\"",
                "\"Jarvis set alarm 7 am\""
            )
            tips.forEach { tip ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "bullet", tint = Color(0xFF00E6FF), modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = tip,
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        modifier = Modifier.clickable { onCommandTextChange(tip.replace("\"", "")) }
                    )
                }
            }
        }
    }
}

// --- TAB 3: LOCAL NOTES MANAGER ---

@Composable
fun NotesManagerView(viewModel: MainViewModel, notes: List<Note>) {
    var titleText by remember { mutableStateOf("") }
    var contentText by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "JARVIS LOCAL NOTES DATABASE (ROOM)",
            fontSize = 15.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        // Manual Add note expanding card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111116)),
            border = BorderStroke(1.dp, Color(0xFF1E1E28))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "CREATE NEW NOTE",
                    fontSize = 11.sp,
                    color = Color.LightGray,
                    fontFamily = FontFamily.Monospace
                )

                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    placeholder = { Text("Note Title", color = Color.Gray, fontSize = 12.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_input_title"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E6FF),
                        unfocusedBorderColor = Color(0xFF2E2E3E),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                OutlinedTextField(
                    value = contentText,
                    onValueChange = { contentText = it },
                    placeholder = { Text("Note description/content...", color = Color.Gray, fontSize = 12.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_input_content"),
                    minLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E6FF),
                        unfocusedBorderColor = Color(0xFF2E2E3E),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Button(
                    onClick = {
                        if (titleText.isNotBlank() && contentText.isNotBlank()) {
                            viewModel.addNote(titleText, contentText)
                            titleText = ""
                            contentText = ""
                            Toast.makeText(context, "Note created!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Please complete fields", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.End)
                        .testTag("add_note_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2937))
                ) {
                    Text("Add Note", fontSize = 12.sp, color = Color.White)
                }
            }
        }

        HorizontalDivider(color = Color(0xFF1F1F2E))

        // Notes List
        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Empty",
                        tint = Color.DarkGray,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No saved notes. Ask Jarvis to: 'write note [content]'.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(notes) { note ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF15151C))
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = note.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E6FF)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = note.content,
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                            }
                            
                            IconButton(onClick = { viewModel.deleteNoteById(note.id) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete note",
                                    tint = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- TAB 4: TELEMETRY LOGS VIEW ---

@Composable
fun TelemetryLogsView(viewModel: MainViewModel, logs: List<CommandLog>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "JARVIS TELEMETRY COMMAND LOGS",
                fontSize = 15.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            TextButton(
                onClick = { viewModel.clearAllLogs() },
                modifier = Modifier.testTag("clear_logs_button")
            ) {
                Text("Clear All", fontSize = 12.sp, color = Color(0xFFFF3366))
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = "No logs",
                        tint = Color.DarkGray,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Telemetry logs empty. Speak commands to load voice diagnostics.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs) { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111116)),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (log.success) Color(0xFF1F2E23) else Color(0xFF2E1F1F)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = log.actionType,
                                    fontSize = 11.sp,
                                    color = if (log.success) Color(0xFF00FF66) else Color(0xFFFF3366),
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                
                                Text(
                                    text = if (log.success) "SUCCESS" else "FAILED",
                                    fontSize = 10.sp,
                                    color = if (log.success) Color(0xFF00FF66) else Color(0xFFFF3366),
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Command: \"${log.command}\"",
                                fontSize = 13.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Feedback: ${log.feedback}",
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                        }
                    }
                }
            }
        }
    }
}
