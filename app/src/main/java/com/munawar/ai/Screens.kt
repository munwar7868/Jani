package com.munawar.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

enum class Screen { HOME, SETTINGS }

fun has(ctx: Context, perm: String): Boolean =
    ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val repo = remember { PrefsRepo(ctx) }
    val settings by repo.flow.collectAsState(initial = null)
    var screen by remember { mutableStateOf(Screen.HOME) }
    val s = settings

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            s == null -> Box(Modifier.fillMaxSize())
            !s.keyVerified || s.apiKey.isBlank() -> SetupScreen(repo, s)
            screen == Screen.SETTINGS -> SettingsScreen(repo, s) { screen = Screen.HOME }
            else -> HomeScreen(s) { screen = Screen.SETTINGS }
        }
    }
}

// ------------------------------------------------------------------ setup

@Composable
fun SetupScreen(repo: PrefsRepo, s: AppSettings) {
    val scope = rememberCoroutineScope()
    val client = remember { GeminiClient() }
    var key by remember { mutableStateOf(s.apiKey) }
    var model by remember { mutableStateOf(s.model) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Orb(Phase.OFF, Modifier.size(170.dp))
        Text("Munawar AI", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Pehle apni Gemini API key daalein. Test pass hone ke baad hi app chalegi.\n" +
                "Key yahan se milti hai: aistudio.google.com/apikey",
            color = Color(0xFFB8B8D8),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("Gemini API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it.trim() },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && key.length > 10 && model.isNotBlank(),
            onClick = {
                busy = true
                status = null
                scope.launch {
                    val r = client.testKey(key, model)
                    if (r.isSuccess) {
                        repo.saveKey(key, model)
                    } else {
                        status = "Test fail: " + (r.exceptionOrNull()?.message ?: "unknown error")
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Testing…" else "Test & Save") }
        status?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
    }
}

// ------------------------------------------------------------------ home

@Composable
fun HomeScreen(s: AppSettings, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val ui by Bus.state.collectAsState()
    val running = ui.phase != Phase.OFF

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(ctx, Manifest.permission.RECORD_AUDIO)) {
            VoiceService.start(ctx)
        } else {
            Toast.makeText(
                ctx,
                tr(s.lang, "مائیک کی اجازت ضروری ہے", "माइक की अनुमति ज़रूरी है", "Microphone permission is required"),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun toggle() {
        if (running) {
            VoiceService.stop(ctx)
            return
        }
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
        )
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter { !has(ctx, it) }
        if (missing.isEmpty()) VoiceService.start(ctx) else launcher.launch(missing.toTypedArray())
    }

    val label = when (ui.phase) {
        Phase.OFF -> tr(s.lang, "مائیک دبائیں", "माइक दबाएँ", "Tap the mic")
        Phase.LISTENING -> tr(s.lang, "سن رہا ہوں…", "सुन रहा हूँ…", "Listening…")
        Phase.THINKING -> tr(s.lang, "سوچ رہا ہوں…", "सोच रहा हूँ…", "Thinking…")
        Phase.SPEAKING -> tr(s.lang, "بول رہا ہوں…", "बोल रहा हूँ…", "Speaking…")
    }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Munawar AI", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onSettings) { Text("⚙ Settings") }
        }
        Orb(ui.phase, Modifier.size(250.dp))
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        if (ui.partial.isNotBlank()) {
            Text(ui.partial, color = Color(0xFFB8B8D8), modifier = Modifier.padding(top = 4.dp))
        }
        ui.notice?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp))
        }
        val scroll = rememberScrollState()
        LaunchedEffect(ui.log.size) { scroll.animateScrollTo(scroll.maxValue) }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.verticalScroll(scroll)) {
                ui.log.takeLast(8).forEach { Bubble(it) }
            }
        }
        MicButton(running) { toggle() }
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun SectionTitle(t: String) {
    Text(t, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun PermRow(label: String, ok: Boolean, onAllow: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        if (ok) Text("✅") else OutlinedButton(onClick = onAllow) { Text("Allow") }
    }
}

@Composable
fun SettingsScreen(repo: PrefsRepo, s: AppSettings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    var tick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    var rate by remember { mutableStateOf(s.rate) }
    var pitch by remember { mutableStateOf(s.pitch) }
    var cc by remember { mutableStateOf(s.countryCode) }

    val micOk = remember(tick) { has(ctx, Manifest.permission.RECORD_AUDIO) }
    val contactsOk = remember(tick) { has(ctx, Manifest.permission.READ_CONTACTS) }
    val callOk = remember(tick) { has(ctx, Manifest.permission.CALL_PHONE) }
    val notifOk = remember(tick) { Build.VERSION.SDK_INT < 33 || has(ctx, Manifest.permission.POST_NOTIFICATIONS) }
    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    val batteryOk = remember(tick) {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        SectionTitle("Personality")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Persona.values().forEach { p ->
                FilterChip(
                    selected = s.persona == p,
                    onClick = { scope.launch { repo.put(K.PERSONA, p.name) } },
                    label = { Text(p.label) },
                )
            }
        }

        SectionTitle("Language / زبان")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ur-PK" to "اردو", "hi-IN" to "हिन्दी", "en-US" to "English", "en-IN" to "English (IN)").forEach { (code, name) ->
                FilterChip(
                    selected = s.lang == code,
                    onClick = { scope.launch { repo.put(K.LANG, code) } },
                    label = { Text(name) },
                )
            }
        }

        SectionTitle("Wake word")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Sirf \"Hey Munawar\" kehne par jawab de", modifier = Modifier.weight(1f))
            Switch(checked = s.wakeWord, onCheckedChange = { scope.launch { repo.put(K.WAKE, it) } })
        }

        SectionTitle("Voice")
        Text("Speed: %.2f".format(rate))
        Slider(
            value = rate,
            onValueChange = { rate = it },
            onValueChangeFinished = { scope.launch { repo.put(K.RATE, rate) } },
            valueRange = 0.5f..1.5f,
        )
        Text("Pitch: %.2f".format(pitch))
        Slider(
            value = pitch,
            onValueChange = { pitch = it },
            onValueChangeFinished = { scope.launch { repo.put(K.PITCH, pitch) } },
            valueRange = 0.6f..1.6f,
        )

        SectionTitle("WhatsApp country code")
        OutlinedTextField(
            value = cc,
            onValueChange = { v ->
                val d = v.filter { it.isDigit() }.take(4)
                cc = d
                if (d.isNotEmpty()) scope.launch { repo.put(K.CC, d) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Gemini API")
        OutlinedButton(
            onClick = { scope.launch { repo.put(K.VERIFIED, false) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Change API key / model (${s.model})") }

        SectionTitle("Permissions")
        PermRow("Microphone", micOk) { permLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        PermRow("Contacts (naam se call/SMS)", contactsOk) { permLauncher.launch(Manifest.permission.READ_CONTACTS) }
        PermRow("Direct call", callOk) { permLauncher.launch(Manifest.permission.CALL_PHONE) }
        if (Build.VERSION.SDK_INT >= 33) {
            PermRow("Notifications", notifOk) { permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        PermRow("Display over other apps (background mein apps kholne ke liye)", overlayOk) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        PermRow("Battery optimisation off (24/7 listening ke liye)", batteryOk) {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        TextButton(
            onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        ) { Text("App settings kholein") }
        Spacer(Modifier.height(24.dp))
    }
}
