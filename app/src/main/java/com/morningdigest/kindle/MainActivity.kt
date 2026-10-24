package com.morningdigest.kindle

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.morningdigest.kindle.data.SettingsStore
import com.morningdigest.kindle.model.AppSettings
import com.morningdigest.kindle.model.DigestRunState
import com.morningdigest.kindle.scraper.XFeedScraper
import com.morningdigest.kindle.worker.DigestOrchestrator
import com.morningdigest.kindle.worker.MorningDigestWorker
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1B1B1B),
                    onPrimary = Color.White,
                    surface = Color(0xFFFBFBF9),
                    background = Color(0xFFF4F3EF)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KindleDigestHomeScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KindleDigestHomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    val xScraper = remember { XFeedScraper(context) }
    val orchestrator = remember { DigestOrchestrator(context) }

    val initialSettings = remember { settingsStore.load() }

    var geminiApiKey by remember { mutableStateOf(initialSettings.geminiApiKey) }
    var kindleEmail by remember { mutableStateOf(initialSettings.kindleEmail) }
    var smtpEmail by remember { mutableStateOf(initialSettings.smtpEmail) }
    var smtpPassword by remember { mutableStateOf(initialSettings.smtpPassword) }
    var smtpHost by remember { mutableStateOf(initialSettings.smtpHost) }
    var smtpPort by remember { mutableStateOf(initialSettings.smtpPort.toString()) }
    var bodyFontSizePt by remember { mutableFloatStateOf(initialSettings.bodyFontSizePt) }
    var xScrolls by remember { mutableIntStateOf(initialSettings.xScrollIterations) }
    var maxNews by remember { mutableIntStateOf(initialSettings.maxManoramaArticles) }
    var scheduleEnabled by remember { mutableStateOf(initialSettings.scheduleEnabled) }
    var scheduleHour by remember { mutableStateOf(initialSettings.scheduleHour.toString()) }
    var scheduleMinute by remember { mutableStateOf(initialSettings.scheduleMinute.toString()) }

    var xLoggedIn by remember { mutableStateOf(xScraper.isLoggedInToX()) }
    var showXLoginDialog by remember { mutableStateOf(false) }
    var runState by remember { mutableStateOf<DigestRunState>(DigestRunState.Idle) }

    fun buildCurrentSettings(): AppSettings {
        return AppSettings(
            geminiApiKey = geminiApiKey,
            kindleEmail = kindleEmail,
            smtpEmail = smtpEmail,
            smtpPassword = smtpPassword,
            smtpHost = smtpHost,
            smtpPort = smtpPort.toIntOrNull() ?: 587,
            bodyFontSizePt = bodyFontSizePt,
            xScrollIterations = xScrolls,
            maxManoramaArticles = maxNews,
            scheduleEnabled = scheduleEnabled,
            scheduleHour = (scheduleHour.toIntOrNull() ?: 6).coerceIn(0, 23),
            scheduleMinute = (scheduleMinute.toIntOrNull() ?: 0).coerceIn(0, 59)
        )
    }

    fun saveAllSettings(showToast: Boolean = true) {
        val current = buildCurrentSettings()
        settingsStore.save(current)
        if (current.scheduleEnabled) {
            MorningDigestWorker.scheduleDaily(context, current.scheduleHour, current.scheduleMinute)
        } else {
            MorningDigestWorker.cancelDaily(context)
        }
        if (showToast) {
            Toast.makeText(context, "Settings saved!", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Kindle Morning Digest", fontWeight = FontWeight.Bold)
                        Text(
                            "X (Following) + Manorama Online (മലയാളം) → Paperwhite PDF",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ==================== 1. GENERATE & SEND NOW CARD ====================
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Morning Newspaper Generator",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Collects your X Following tab + Manorama Online top Malayalam stories, formats a 360×480pt zero-zoom Kindle Paperwhite PDF, and emails it to your @kindle.com address.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    val isRunning = runState is DigestRunState.Running
                    Button(
                        onClick = {
                            saveAllSettings(showToast = false)
                            scope.launch {
                                orchestrator.runFullPipeline { state ->
                                    runState = state
                                }
                            }
                        },
                        enabled = !isRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isRunning) "Generating Digest..." else "Generate & Send to Kindle Now")
                    }

                    when (val state = runState) {
                        is DigestRunState.Running -> {
                            LinearProgressIndicator(
                                progress = { state.progressFraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(state.stepMessage, style = MaterialTheme.typography.bodySmall)
                        }
                        is DigestRunState.Success -> {
                            Text(
                                "✓ ${state.message} (${state.xCount} X posts, ${state.newsCount} Manorama stories)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF1B5E20),
                                fontWeight = FontWeight.Medium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { openOrSharePdf(context, state.pdfFile, share = false) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Preview PDF")
                                }
                                OutlinedButton(
                                    onClick = { openOrSharePdf(context, state.pdfFile, share = true) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Share / Send to Kindle App")
                                }
                            }
                        }
                        is DigestRunState.Error -> {
                            Text(
                                "Error: ${state.errorMessage}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB71C1C)
                            )
                        }
                        DigestRunState.Idle -> {}
                    }
                }
            }

            // ==================== 2. X (TWITTER) LOGIN CARD ====================
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "1. X (Twitter) Account Session",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (xLoggedIn) "● Logged In" else "○ Not Logged In",
                            color = if (xLoggedIn) Color(0xFF2E7D32) else Color(0xFFC62828),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "Sign in once inside our embedded WebView. Your session cookie stays saved on your phone so the app can silently read your 'Following' tab every morning.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { showXLoginDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (xLoggedIn) "Open X WebView (Verify / Re-login)" else "Log in to X (One-Time Setup)")
                    }
                }
            }

            // ==================== 3. DAILY SCHEDULE CARD ====================
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "2. Daily Morning Auto-Delivery",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Run automatically before you wake up",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = scheduleEnabled,
                            onCheckedChange = {
                                scheduleEnabled = it
                                saveAllSettings()
                            }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = scheduleHour,
                            onValueChange = { scheduleHour = it },
                            label = { Text("Hour (0-23)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = scheduleMinute,
                            onValueChange = { scheduleMinute = it },
                            label = { Text("Minute (0-59)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
            }

            // ==================== 4. KINDLE PAPERWHITE & AI SETTINGS CARD ====================
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "3. Kindle Delivery & Gemini AI Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = geminiApiKey,
                        onValueChange = { geminiApiKey = it },
                        label = { Text("Gemini API Key (filters X & summarizes news)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    HorizontalDivider()

                    OutlinedTextField(
                        value = kindleEmail,
                        onValueChange = { kindleEmail = it },
                        label = { Text("Your Send-to-Kindle Email (e.g. name@kindle.com)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = smtpEmail,
                        onValueChange = { smtpEmail = it },
                        label = { Text("Approved Sender Email (e.g. yourname@gmail.com)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = smtpPassword,
                        onValueChange = { smtpPassword = it },
                        label = { Text("Gmail 16-Character App Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = smtpHost,
                            onValueChange = { smtpHost = it },
                            label = { Text("SMTP Host") },
                            modifier = Modifier.weight(2f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = smtpPort,
                            onValueChange = { smtpPort = it },
                            label = { Text("Port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    HorizontalDivider()

                    Text(
                        "Kindle Paperwhite Font Size: ${(bodyFontSizePt * 10).roundToInt() / 10f} pt (No zoom required)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = bodyFontSizePt,
                        onValueChange = { bodyFontSizePt = it },
                        valueRange = 11.5f..17.5f,
                        steps = 11
                    )

                    Button(
                        onClick = { saveAllSettings(showToast = true) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Settings")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showXLoginDialog) {
        XLoginWebViewDialog(
            onDismiss = {
                CookieManager.getInstance().flush()
                xLoggedIn = xScraper.isLoggedInToX()
                showXLoginDialog = false
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XLoginWebViewDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Sign in to X (Cookies auto-saved)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Button(onClick = onDismiss) {
                        Text("Done")
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                            webViewClient = WebViewClient()
                            loadUrl("https://x.com/home")
                        }
                    }
                )
            }
        }
    }
}

private fun openOrSharePdf(context: Context, pdfFile: File, share: Boolean) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile
        )
        val intent = if (share) {
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(Intent.createChooser(intent, "Kindle Morning Digest PDF"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open PDF: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
