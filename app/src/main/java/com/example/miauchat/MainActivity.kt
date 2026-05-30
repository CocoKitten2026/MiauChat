package com.example.miauchat

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.miauchat.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LogEntry(
    val sender: String,
    val content: String
)

data class ChatSession(
    val label: String,
    val logs: List<LogEntry>
)

class MiauChatViewModel(context: Context) : ViewModel() {
    private val prefs: SharedPreferences = context.getSharedPreferences("miauchat_prefs", Context.MODE_PRIVATE)

    var apiUrl by mutableStateOf(prefs.getString("api_url", "") ?: "")
    var apiKey by mutableStateOf(prefs.getString("api_key", "") ?: "")
    var apiModel by mutableStateOf(prefs.getString("api_model", "") ?: "")

    var isConnected by mutableStateOf(apiUrl.isNotEmpty() && apiKey.isNotEmpty() && apiModel.isNotEmpty())
    val chatLogs = mutableStateListOf<LogEntry>()
    var currentInput by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var showConfigDialog by mutableStateOf(false)
    var showHistoryDialog by mutableStateOf(false)
    var streamingContent by mutableStateOf("")
    var sessions = mutableStateListOf<ChatSession>()

    private var generationJob: Job? = null
    private var currentCall: okhttp3.Call? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        loadSessions()
    }

    fun saveConfiguration(url: String, key: String, model: String) {
        apiUrl = url
        apiKey = key
        apiModel = model
        isConnected = url.isNotEmpty() && key.isNotEmpty() && model.isNotEmpty()
        prefs.edit().apply {
            putString("api_url", url)
            putString("api_key", key)
            putString("api_model", model)
            apply()
        }
        showConfigDialog = false
    }

    fun saveCurrentSession() {
        if (chatLogs.isEmpty()) return
        val label = chatLogs.firstOrNull { it.sender == "USER" }?.content?.take(50) ?: "Chat"
        sessions.add(0, ChatSession(label, chatLogs.toList()))
        persistSessions()
    }

    fun loadSession(session: ChatSession) {
        chatLogs.clear()
        chatLogs.addAll(session.logs)
    }

    fun deleteSession(index: Int) {
        sessions.removeAt(index)
        persistSessions()
    }

    fun newChat() {
        if (chatLogs.isNotEmpty()) saveCurrentSession()
        chatLogs.clear()
        currentInput = ""
    }

    private fun loadSessions() {
        try {
            val json = prefs.getString("sessions", null) ?: return
            val arr = JSONArray(json)
            sessions.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val label = obj.getString("label")
                val logsArr = obj.getJSONArray("logs")
                val logs = mutableListOf<LogEntry>()
                for (j in 0 until logsArr.length()) {
                    val logObj = logsArr.getJSONObject(j)
                    logs.add(LogEntry(logObj.getString("sender"), logObj.getString("content")))
                }
                sessions.add(ChatSession(label, logs))
            }
        } catch (_: Exception) { }
    }

    private fun persistSessions() {
        val arr = JSONArray()
        for (s in sessions) {
            val logsArr = JSONArray()
            for (l in s.logs) {
                logsArr.put(JSONObject().apply {
                    put("sender", l.sender)
                    put("content", l.content)
                })
            }
            arr.put(JSONObject().apply {
                put("label", s.label)
                put("logs", logsArr)
            })
        }
        prefs.edit().putString("sessions", arr.toString()).apply()
    }

    fun stopGeneration() {
        currentCall?.cancel()
        generationJob?.cancel()
        generationJob = null
        currentCall = null
    }

    fun sendMessage() {
        val messageToSend = currentInput.trim()
        if (messageToSend.isEmpty() || isLoading) return

        chatLogs.add(LogEntry("USER", messageToSend))
        currentInput = ""

        if (!isConnected) {
            chatLogs.add(LogEntry("SYSTEM", "Not connected. Tap + to configure API."))
            return
        }

        isLoading = true
        val aiEntryIndex = chatLogs.size
        chatLogs.add(LogEntry("AI", ""))
        streamingContent = ""

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            var fullContent = ""
            try {
                val jsonBody = JSONObject().apply {
                    put("model", apiModel)
                    put("stream", true)
                    val messagesArray = JSONArray()
                    for (i in 0 until aiEntryIndex) {
                        val log = chatLogs[i]
                        when (log.sender) {
                            "USER" -> messagesArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", log.content)
                                }
                            )
                            "AI" -> messagesArray.put(
                                JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", log.content)
                                }
                            )
                        }
                    }
                    messagesArray.put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", messageToSend)
                        }
                    )
                    put("messages", messagesArray)
                }

                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val call = client.newCall(request)
                currentCall = call
                val response = call.execute()
                currentCall = null

                if (!response.isSuccessful) {
                    chatLogs[aiEntryIndex] = LogEntry("AI", "HTTP ${response.code}")
                    return@launch
                }

                val reader = response.body?.charStream()?.buffered()
                if (reader == null) {
                    chatLogs[aiEntryIndex] = LogEntry("AI", "Empty response")
                    return@launch
                }

                var isStreaming = false
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (currentLine.startsWith("data: ")) {
                        isStreaming = true
                        val data = currentLine.removePrefix("data: ").trim()
                        if (data == "[DONE]") break
                        try {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                if (delta != null && delta.has("content") && !delta.isNull("content")) {
                                    val chunk = delta.getString("content")
                                    fullContent += chunk
                                    withContext(Dispatchers.Main) {
                                        streamingContent = fullContent
                                        chatLogs[aiEntryIndex] = LogEntry("AI", fullContent)
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    } else if (currentLine.isNotBlank() && !isStreaming) {
                        fullContent += currentLine
                    }
                }
                reader.close()

                if (!isStreaming && fullContent.isBlank()) {
                    fullContent = parseNonStreaming(fullContent)
                }

                chatLogs[aiEntryIndex] = LogEntry("AI", fullContent)
                saveCurrentSession()
            } catch (e: CancellationException) {
                chatLogs[aiEntryIndex] = LogEntry("AI", fullContent.takeIf { it.isNotEmpty() } ?: "Cancelled")
                if (fullContent.isNotEmpty()) saveCurrentSession()
            } catch (e: Exception) {
                chatLogs[aiEntryIndex] = LogEntry("AI", "Error [${e::class.simpleName}]: ${e.message ?: e.localizedMessage ?: "No message"}")
            } finally {
                isLoading = false
                streamingContent = ""
                currentCall = null
            }
        }
    }

    private fun parseNonStreaming(body: String): String {
        return try {
            val root = JSONObject(body)
            if (root.has("choices")) {
                val choices = root.getJSONArray("choices")
                if (choices.length() > 0) {
                    val firstChoice = choices.getJSONObject(0)
                    if (firstChoice.has("message")) {
                        firstChoice.getJSONObject("message").getString("content")
                    } else if (firstChoice.has("text")) {
                        firstChoice.getString("text")
                    } else body
                } else body
            } else body
        } catch (_: Exception) { body }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiauChatTheme {
                val viewModel: MiauChatViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return MiauChatViewModel(applicationContext) as T
                        }
                    }
                )
                MiauChatMainScreen(viewModel)
            }
        }
    }
}

@Composable
fun MiauChatMainScreen(viewModel: MiauChatViewModel) {
    val lazyListState = rememberLazyListState()

    LaunchedEffect(viewModel.chatLogs.size) {
        if (viewModel.chatLogs.isNotEmpty()) {
            lazyListState.animateScrollToItem(viewModel.chatLogs.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBackground)
            .padding(top = 16.dp, start = 16.dp, end = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.showHistoryDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "History",
                        tint = ColorTextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "MiauChat",
                    color = ColorTextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.showConfigDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Configure",
                        tint = ColorTextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 8.dp)
            ) {
                items(viewModel.chatLogs) { log ->
                    ChatLine(log)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            InputCard(viewModel)
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (viewModel.showHistoryDialog) {
            HistoryDialog(viewModel)
        }

        if (viewModel.showConfigDialog) {
            ConfigDialog(viewModel)
        }
    }
}

@Composable
fun ChatLine(log: LogEntry) {
    val prefix = when (log.sender) {
        "USER" -> "> "
        "SYSTEM" -> "# "
        else -> ""
    }

    val textColor = when (log.sender) {
        "USER" -> ColorTextPrimary
        "SYSTEM" -> ColorTextMuted
        else -> ColorTextPrimary
    }

    Text(
        text = prefix + log.content,
        color = textColor,
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
}

@Composable
fun InputCard(viewModel: MiauChatViewModel) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorSurfaceDark, shape = RoundedCornerShape(0.dp))
            .border(width = 1.dp, color = ColorBorderDim)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(ColorAccentBlue)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (viewModel.currentInput.isEmpty() && !viewModel.isLoading) {
                    Text(
                        text = "Ask anything...",
                        color = ColorTextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }

                BasicTextField(
                    value = viewModel.currentInput,
                    onValueChange = { viewModel.currentInput = it },
                    textStyle = TextStyle(
                        color = ColorTextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    cursorBrush = SolidColor(ColorTextPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        keyboardController?.hide()
                        if (!viewModel.isLoading) viewModel.sendMessage()
                    }),
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = viewModel.isLoading
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = if (viewModel.isConnected) viewModel.apiModel else "Offline"
                Text(
                    text = label,
                    color = if (viewModel.isConnected) ColorTextPrimary else ColorTextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }

        IconButton(
            onClick = {
                keyboardController?.hide()
                if (viewModel.isLoading) viewModel.stopGeneration()
                else viewModel.sendMessage()
            },
            modifier = Modifier.padding(end = 4.dp)
        ) {
            if (viewModel.isLoading) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Stop",
                    tint = ColorAccentAmber,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (viewModel.currentInput.isNotBlank()) ColorAccentBlue else ColorTextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun HistoryDialog(viewModel: MiauChatViewModel) {
    Dialog(onDismissRequest = { viewModel.showHistoryDialog = false }) {
        Card(
            shape = RoundedCornerShape(0.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceDark),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .border(1.dp, ColorBorderDim, RoundedCornerShape(0.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "History",
                        color = ColorTextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { viewModel.newChat(); viewModel.showHistoryDialog = false }) {
                        Text(
                            "New chat",
                            color = ColorAccentBlue,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (viewModel.sessions.isEmpty()) {
                    Text(
                        text = "No previous chats",
                        color = ColorTextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(viewModel.sessions.size) { i ->
                            val session = viewModel.sessions[i]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.loadSession(session)
                                        viewModel.showHistoryDialog = false
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = session.label,
                                    color = ColorTextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                IconButton(
                                    onClick = { viewModel.deleteSession(i) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete",
                                        tint = ColorTextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfigDialog(viewModel: MiauChatViewModel) {
    var urlInput by remember { mutableStateOf(viewModel.apiUrl) }
    var keyInput by remember { mutableStateOf(viewModel.apiKey) }
    var modelInput by remember { mutableStateOf(viewModel.apiModel) }

    Dialog(onDismissRequest = { viewModel.showConfigDialog = false }) {
        Card(
            shape = RoundedCornerShape(0.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceDark),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ColorAccentBlue, RoundedCornerShape(0.dp))
                .padding(1.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "API URL",
                    color = ColorTextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("https://api.endpoint/v1", color = ColorTextMuted, fontSize = 12.sp) },
                    textStyle = TextStyle(color = ColorTextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ColorAccentBlue, unfocusedBorderColor = ColorTextMuted
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                Text(
                    text = "API Key",
                    color = ColorTextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    placeholder = { Text("sk-...", color = ColorTextMuted, fontSize = 12.sp) },
                    textStyle = TextStyle(color = ColorTextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ColorAccentBlue, unfocusedBorderColor = ColorTextMuted
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                Text(
                    text = "Model",
                    color = ColorTextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = modelInput,
                    onValueChange = { modelInput = it },
                    placeholder = { Text("gpt-4", color = ColorTextMuted, fontSize = 12.sp) },
                    textStyle = TextStyle(color = ColorTextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ColorAccentBlue, unfocusedBorderColor = ColorTextMuted
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { viewModel.showConfigDialog = false }) {
                        Text("Cancel", color = ColorTextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = ColorAccentBlue),
                        shape = RoundedCornerShape(0.dp),
                        onClick = {
                            viewModel.saveConfiguration(
                                url = urlInput.trim(), key = keyInput.trim(), model = modelInput.trim()
                            )
                        }
                    ) {
                        Text(
                            "Save", color = ColorBackground,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
