package com.example.miauchat

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
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
    val content: String,
    val reasoning: String = ""
)

data class PendingFile(
    val fileName: String,
    val content: String
)

data class ChatSession(
    val label: String,
    val logs: List<LogEntry>
)

data class ApiPreset(
    val url: String = "",
    val key: String = "",
    val model: String = "",
    val exaKey: String = "",
    val firecrawlKey: String = ""
)

class MiauChatViewModel(context: Context) : ViewModel() {
    private val prefs: SharedPreferences = context.getSharedPreferences("miauchat_prefs", Context.MODE_PRIVATE)

    var apiUrl by mutableStateOf(prefs.getString("api_url", "") ?: "")
    var apiKey by mutableStateOf(prefs.getString("api_key", "") ?: "")
    var apiModel by mutableStateOf(prefs.getString("api_model", "") ?: "")

    var exaApiKey by mutableStateOf(prefs.getString("exa_api_key", "") ?: "")
    var firecrawlApiKey by mutableStateOf(prefs.getString("firecrawl_api_key", "") ?: "")
    var exaSearchEnabled by mutableStateOf(false)
    var activePresetIndex by mutableStateOf(0)
    val apiPresets = mutableStateListOf(*Array(5) { ApiPreset() })

    var isConnected by mutableStateOf(apiUrl.isNotEmpty() && apiKey.isNotEmpty() && apiModel.isNotEmpty())
    val chatLogs = mutableStateListOf<LogEntry>()
    var currentInput by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var pendingFile by mutableStateOf<PendingFile?>(null)
    var showConfigDialog by mutableStateOf(false)
    var showHistoryDialog by mutableStateOf(false)
    var streamingContent by mutableStateOf("")
    var streamingReasoning by mutableStateOf("")
    var sessions = mutableStateListOf<ChatSession>()

    private var generationJob: Job? = null
    private var currentCall: okhttp3.Call? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        loadSessions()
        loadPresets()
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
        apiPresets[activePresetIndex] = ApiPreset(url, key, model, exaApiKey, firecrawlApiKey)
        persistPresets()
        showConfigDialog = false
    }

    fun saveExaConfiguration(key: String) {
        exaApiKey = key
        prefs.edit().putString("exa_api_key", key).apply()
        if (key.isNotEmpty()) exaSearchEnabled = true
        apiPresets[activePresetIndex] = ApiPreset(apiUrl, apiKey, apiModel, key, firecrawlApiKey)
        persistPresets()
    }

    fun saveFirecrawlConfiguration(key: String) {
        firecrawlApiKey = key
        prefs.edit().putString("firecrawl_api_key", key).apply()
        apiPresets[activePresetIndex] = ApiPreset(apiUrl, apiKey, apiModel, exaApiKey, key)
        persistPresets()
    }

    fun toggleExaSearch() {
        exaSearchEnabled = !exaSearchEnabled
    }

    fun saveCurrentSession() {
        if (chatLogs.isEmpty()) return
        val label = chatLogs.firstOrNull { it.sender == "USER" }?.content?.take(50) ?: "Chat"
        val session = ChatSession(label, chatLogs.toList())
        val existing = sessions.indexOfFirst { it.label == label }
        if (existing >= 0) sessions[existing] = session
        else sessions.add(0, session)
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
                    logs.add(LogEntry(
                        logObj.getString("sender"),
                        logObj.getString("content"),
                        logObj.optString("reasoning", "")
                    ))
                }
                sessions.add(ChatSession(label, logs))
            }
        } catch (_: Exception) { }
    }

    private fun loadPresets() {
        val json = prefs.getString("api_presets", null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                apiPresets.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    apiPresets.add(ApiPreset(obj.optString("url", ""), obj.optString("key", ""), obj.optString("model", ""), obj.optString("exaKey", ""), obj.optString("firecrawlKey", "")))
                }
                if (apiPresets.isNotEmpty() && apiPresets[0].url.isNotEmpty()) {
                    val p = apiPresets[0]
                    apiUrl = p.url; apiKey = p.key; apiModel = p.model
                    exaApiKey = p.exaKey; firecrawlApiKey = p.firecrawlKey; exaSearchEnabled = p.exaKey.isNotEmpty()
                    isConnected = p.url.isNotEmpty() && p.key.isNotEmpty() && p.model.isNotEmpty()
                }
                return
            } catch (_: Exception) { }
        }
        apiPresets.clear()
        apiPresets.add(ApiPreset(apiUrl, apiKey, apiModel, exaApiKey))
        for (i in 1 until 5) apiPresets.add(ApiPreset())
        persistPresets()
    }

    private fun persistPresets() {
        val arr = JSONArray().apply {
                for (p in apiPresets) put(JSONObject().apply {
                    put("url", p.url); put("key", p.key); put("model", p.model); put("exaKey", p.exaKey); put("firecrawlKey", p.firecrawlKey)
                })
        }
        prefs.edit().putString("api_presets", arr.toString()).apply()
    }

    private fun persistSessions() {
        val arr = JSONArray()
        for (s in sessions) {
            val logsArr = JSONArray()
            for (l in s.logs) {
                logsArr.put(JSONObject().apply {
                    put("sender", l.sender)
                    put("content", l.content)
                    if (l.reasoning.isNotEmpty()) put("reasoning", l.reasoning)
                })
            }
            arr.put(JSONObject().apply {
                put("label", s.label)
                put("logs", logsArr)
            })
        }
        prefs.edit().putString("sessions", arr.toString()).apply()
    }

    fun switchPreset(index: Int) {
        if (index < 0 || index >= apiPresets.size) return
        apiPresets[activePresetIndex] = ApiPreset(apiUrl, apiKey, apiModel, exaApiKey, firecrawlApiKey)
        val p = apiPresets[index]
        apiUrl = p.url; apiKey = p.key; apiModel = p.model
        exaApiKey = p.exaKey; firecrawlApiKey = p.firecrawlKey; exaSearchEnabled = p.exaKey.isNotEmpty()
        isConnected = p.url.isNotEmpty() && p.key.isNotEmpty() && p.model.isNotEmpty()
        activePresetIndex = index
        persistPresets()
    }

    fun stopGeneration() {
        currentCall?.cancel()
        generationJob?.cancel()
        generationJob = null
        currentCall = null
    }

    fun sendMessage() {
        val messageToSend = currentInput.trim()
        val file = pendingFile
        if (messageToSend.isEmpty() && file == null) return
        if (messageToSend.isEmpty() && file != null && isLoading) return

        val displayMessage = if (file != null) {
            if (messageToSend.isNotEmpty()) "[📎 ${file.fileName}]\n$messageToSend" else "[📎 ${file.fileName}]"
        } else {
            messageToSend
        }
        pendingFile = null

        chatLogs.add(LogEntry("USER", displayMessage))
        currentInput = ""

        if (!isConnected) {
            chatLogs.add(LogEntry("SYSTEM", "Not connected. Tap + to configure API."))
            return
        }

        isLoading = true
        val aiEntryIndex = chatLogs.size
        chatLogs.add(LogEntry("AI", ""))
        streamingContent = ""
        streamingReasoning = ""

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            var fullContent = ""
            var fullReasoning = ""
            try {
                val jsonBody = JSONObject().apply {
                    put("model", apiModel)
                    put("stream", true)
                    val messagesArray = JSONArray()
                    val systemPrompt = buildString {
                        append("You are a helpful assistant. Keep responses appropriate and help the user as much as possible.")
                        if (exaSearchEnabled) {
                            append(" You have access to two search tools: Firecrawl and Exa. Use Firecrawl when the user gives a specific URL or asks to look up a link, open, or visit a page. Use Exa when the user asks about random, general, or semantic topics. If unsure, default to Firecrawl for direct links and Exa for general questions.")
                        }
                    }
                    messagesArray.put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    for (i in 0 until aiEntryIndex) {
                        val log = chatLogs[i]
                        when (log.sender) {
                            "USER" -> {
                                val content = if (i == aiEntryIndex - 1 && file != null) {
                                    val fileBlock = "<uploaded_file name=\"${file.fileName}\">\n${file.content}\n</uploaded_file>"
                                    if (messageToSend.isNotEmpty()) "$fileBlock\n\n$messageToSend" else fileBlock
                                } else {
                                    log.content
                                }
                                messagesArray.put(
                                    JSONObject().apply {
                                        put("role", "user")
                                        put("content", content)
                                    }
                                )
                            }
                            "AI" -> messagesArray.put(
                                JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", log.content)
                                }
                            )
                        }
                    }
                    put("messages", messagesArray)
                    if (exaSearchEnabled) {
                        put("tools", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", "web_search")
                                    put("description", "Search the web using Exa. Use this for general, semantic, or news queries — not for specific URLs.")
                                    put("parameters", JSONObject().apply {
                                        put("type", "object")
                                        put("properties", JSONObject().apply {
                                            put("query", JSONObject().apply {
                                                put("type", "string")
                                                put("description", "The search query")
                                            })
                                        })
                                        put("required", JSONArray().apply {
                                            put("query")
                                        })
                                    })
                                })
                            })
                            if (firecrawlApiKey.isNotEmpty()) {
                                put(JSONObject().apply {
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", "firecrawl")
                                        put("description", "Fetch the content of a specific URL. Use this when the user shares a link, asks to open or look up a specific web page.")
                                        put("parameters", JSONObject().apply {
                                            put("type", "object")
                                            put("properties", JSONObject().apply {
                                                put("url", JSONObject().apply {
                                                    put("type", "string")
                                                    put("description", "The URL to fetch")
                                                })
                                            })
                                            put("required", JSONArray().apply {
                                                put("url")
                                            })
                                        })
                                    })
                                })
                            }
                        })
                    }
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
                var toolCallId: String? = null
                var toolCallFunctionName: String? = null
                val toolCallArgsBuilder = StringBuilder()

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
                                val choice = choices.getJSONObject(0)
                                val delta = choice.optJSONObject("delta")

                                val tcArray = delta?.optJSONArray("tool_calls")
                                if (tcArray != null && tcArray.length() > 0) {
                                    val tc = tcArray.getJSONObject(0)
                                    if (tc.has("id")) toolCallId = tc.getString("id")
                                    if (tc.has("function")) {
                                        val fn = tc.getJSONObject("function")
                                        if (fn.has("name")) toolCallFunctionName = fn.getString("name")
                                        if (fn.has("arguments")) toolCallArgsBuilder.append(fn.getString("arguments"))
                                    }
                                }

                                if (delta != null && delta.has("content") && !delta.isNull("content")) {
                                    val chunk = delta.getString("content")
                                    fullContent += chunk
                                    withContext(Dispatchers.Main) {
                                        streamingContent = fullContent
                                        chatLogs[aiEntryIndex] = LogEntry("AI", fullContent, fullReasoning)
                                    }
                                }
                                if (delta != null && delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                                    fullReasoning += delta.getString("reasoning_content")
                                    withContext(Dispatchers.Main) {
                                        streamingReasoning = fullReasoning
                                        chatLogs[aiEntryIndex] = LogEntry("AI", fullContent, fullReasoning)
                                    }
                                }

                                if (choice.optString("finish_reason") == "tool_calls") {
                                    break
                                }
                            }
                        } catch (_: Exception) { }
                    } else if (currentLine.isNotBlank() && !isStreaming) {
                        fullContent += currentLine
                    }
                }
                reader.close()

                if (toolCallId != null && (toolCallFunctionName == "web_search" || toolCallFunctionName == "firecrawl")) {
                    val argsJson = JSONObject(toolCallArgsBuilder.toString())
                    val toolResult = if (toolCallFunctionName == "web_search") {
                        val searchQuery = argsJson.optString("query", messageToSend)
                        exaSearch(searchQuery)
                    } else {
                        val url = argsJson.optString("url", "")
                        if (url.isNotEmpty()) firecrawlFetch(url) else "No URL provided"
                    }

                    val secondBody = JSONObject().apply {
                        put("model", apiModel)
                        put("stream", true)
                        val messagesArray = JSONArray()
                        for (i in 0 until aiEntryIndex) {
                            val log = chatLogs[i]
                            when (log.sender) {
                                "USER" -> messagesArray.put(
                                    JSONObject().apply { put("role", "user"); put("content", log.content) }
                                )
                                "AI" -> messagesArray.put(
                                    JSONObject().apply { put("role", "assistant"); put("content", log.content) }
                                )
                            }
                        }
                        messagesArray.put(
                            JSONObject().apply { put("role", "user"); put("content", messageToSend) }
                        )
                        messagesArray.put(
                            JSONObject().apply {
                                put("role", "assistant")
                                put("tool_calls", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("id", toolCallId)
                                        put("type", "function")
                                        put("function", JSONObject().apply {
                                            put("name", toolCallFunctionName)
                                            put("arguments", toolCallArgsBuilder.toString())
                                        })
                                    })
                                })
                            }
                        )
                        messagesArray.put(
                            JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", toolCallId)
                                put("content", toolResult)
                            }
                        )
                        put("messages", messagesArray)
                    }

                    val secondRequest = Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .post(secondBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    val secondCall = client.newCall(secondRequest)
                    currentCall = secondCall
                    val secondResponse = secondCall.execute()
                    currentCall = null

                    if (secondResponse.isSuccessful) {
                        fullContent = ""
                        val secondReader = secondResponse.body?.charStream()?.buffered()
                        if (secondReader != null) {
                            var sLine: String?
                            while (secondReader.readLine().also { sLine = it } != null) {
                                val sCurrent = sLine ?: continue
                                if (sCurrent.startsWith("data: ")) {
                                    val sData = sCurrent.removePrefix("data: ").trim()
                                    if (sData == "[DONE]") break
                                    try {
                                        val sJson = JSONObject(sData)
                                        val sChoices = sJson.optJSONArray("choices")
                                        if (sChoices != null && sChoices.length() > 0) {
                                            val sDelta = sChoices.getJSONObject(0).optJSONObject("delta")
                                            if (sDelta != null && sDelta.has("content") && !sDelta.isNull("content")) {
                                                fullContent += sDelta.getString("content")
                                                withContext(Dispatchers.Main) {
                                                    streamingContent = fullContent
                                                    chatLogs[aiEntryIndex] = LogEntry("AI", fullContent, fullReasoning)
                                                }
                                            }
                                            if (sDelta != null && sDelta.has("reasoning_content") && !sDelta.isNull("reasoning_content")) {
                                                fullReasoning += sDelta.getString("reasoning_content")
                                                withContext(Dispatchers.Main) {
                                                    streamingReasoning = fullReasoning
                                                    chatLogs[aiEntryIndex] = LogEntry("AI", fullContent, fullReasoning)
                                                }
                                            }
                                        }
                                    } catch (_: Exception) { }
                                }
                            }
                            secondReader.close()
                        }
                    }

                    chatLogs[aiEntryIndex] = LogEntry("AI", fullContent.ifEmpty { "Search completed but no response generated." }, fullReasoning)
                    saveCurrentSession()
                    return@launch
                }

                if (!isStreaming && fullContent.isBlank()) {
                    fullContent = parseNonStreaming(fullContent)
                }

                chatLogs[aiEntryIndex] = LogEntry("AI", fullContent, fullReasoning)
                saveCurrentSession()
            } catch (e: CancellationException) {
                chatLogs[aiEntryIndex] = LogEntry("AI", fullContent.takeIf { it.isNotEmpty() } ?: "Cancelled", fullReasoning)
                if (fullContent.isNotEmpty()) saveCurrentSession()
            } catch (e: Exception) {
                chatLogs[aiEntryIndex] = LogEntry("AI", "Error [${e::class.simpleName}]: ${e.message ?: e.localizedMessage ?: "No message"}")
            } finally {
                isLoading = false
                streamingContent = ""
                streamingReasoning = ""
                currentCall = null
            }
        }
    }

    private fun exaSearch(query: String): String {
        return try {
            val jsonBody = JSONObject().apply {
                put("query", query)
                put("type", "auto")
                put("numResults", 5)
                put("contents", JSONObject().apply {
                    put("highlights", true)
                })
            }
            val request = Request.Builder()
                .url("https://api.exa.ai/search")
                .addHeader("x-api-key", exaApiKey)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return "No results"
            val root = JSONObject(body)
            val results = root.optJSONArray("results")
            if (results == null || results.length() == 0) return "No results"
            val sb = StringBuilder()
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                sb.appendLine("Title: ${r.optString("title")}")
                sb.appendLine("URL: ${r.optString("url")}")
                val highlights = r.optJSONArray("highlights")
                if (highlights != null) {
                    for (j in 0 until highlights.length()) {
                        sb.appendLine(highlights.getString(j))
                    }
                }
                sb.appendLine()
            }
            sb.toString()
        } catch (e: Exception) {
            "Search error: ${e.message}"
        }
    }

    private fun firecrawlFetch(url: String): String {
        return try {
            val jsonBody = JSONObject().apply {
                put("url", url)
                put("formats", JSONArray().apply { put("markdown") })
            }
            val request = Request.Builder()
                .url("https://api.firecrawl.dev/v1/scrape")
                .addHeader("Authorization", "Bearer $firecrawlApiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return "No content"
            val root = JSONObject(body)
            if (!root.optBoolean("success", false)) return "Error: ${root.optString("error", "unknown")}"
            val data = root.optJSONObject("data")
            data?.optString("markdown", "")?.takeIf { it.isNotBlank() } ?: data?.optString("content", "") ?: "No content found at URL"
        } catch (e: Exception) {
            "Firecrawl error: ${e.message}"
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
    val isUser = log.sender == "USER"
    val isSystem = log.sender == "SYSTEM"
    val clipboardManager = LocalClipboardManager.current

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = when {
            isSystem -> Alignment.Center
            isUser -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
    ) {
        if (isSystem) {
            Text(
                text = log.content,
                color = ColorTextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        } else {
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .background(
                        color = if (isUser) ColorSurfaceDark else ColorBackground,
                        shape = RoundedCornerShape(0.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isUser) ColorAccentBlue else ColorBorderDim,
                        shape = RoundedCornerShape(0.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    if (log.reasoning.isNotEmpty()) {
                        val stillThinking = log.content.isEmpty()
                        var expanded by remember(stillThinking) { mutableStateOf(stillThinking) }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded }
                                .padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (expanded) "Collapse" else "Show",
                                tint = ColorTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (stillThinking) "thinking..." else "reasoning",
                                color = ColorTextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }

                        AnimatedVisibility(visible = expanded) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ColorSurfaceDark)
                                    .border(1.dp, ColorBorderDim)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = log.reasoning,
                                    color = ColorTextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Text(
                        text = log.content,
                        color = ColorTextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { clipboardManager.setText(AnnotatedString(log.content)) },
                            border = BorderStroke(1.dp, ColorBorderDim),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ColorTextMuted)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InputCard(viewModel: MiauChatViewModel) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileName(context, uri) ?: "file"
            val ext = getFileExtension(fileName)
            when {
                ext != null && ext in TEXT_FILE_EXTENSIONS -> {
                    val textContent = readTextContent(context, uri)
                    if (textContent != null) {
                        viewModel.pendingFile = PendingFile(fileName = fileName, content = textContent)
                    } else {
                        viewModel.chatLogs.add(LogEntry("USER", "[📎 $fileName]"))
                    }
                }
                ext == "png" || ext == "jpg" || ext == "jpeg" || ext == "webp" || ext == "gif" || ext == "bmp" -> {
                    val mimeType = when (ext) {
                        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
                        "webp" -> "image/webp"; "gif" -> "image/gif"
                        "bmp" -> "image/bmp"; else -> "image/png"
                    }
                    try {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes != null) {
                            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            viewModel.pendingFile = PendingFile(fileName = fileName, content = "data:$mimeType;base64,$b64")
                        } else {
                            viewModel.chatLogs.add(LogEntry("USER", "[📎 $fileName]"))
                        }
                    } catch (_: Exception) {
                        viewModel.chatLogs.add(LogEntry("USER", "[📎 $fileName]"))
                    }
                }
                else -> {
                    viewModel.chatLogs.add(LogEntry("USER", "[📎 $fileName]"))
                }
            }
        }
    }

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
            Column(modifier = Modifier.fillMaxWidth()) {
                if (viewModel.pendingFile != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📎 ${viewModel.pendingFile!!.fileName}",
                            color = ColorAccentBlue,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.pendingFile = null },
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove file",
                                tint = ColorTextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
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
                    if (viewModel.chatLogs.isNotEmpty()) {
                        val totalChars = viewModel.chatLogs.sumOf { it.content.length }
                        val estTokens = totalChars / 4
                        val tokenText = if (estTokens >= 1000) "${estTokens / 1000}.${(estTokens % 1000) / 100}k" else estTokens.toString()
                        Text(
                            text = " · ~${tokenText}tk",
                            color = ColorTextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = "Attach",
                tint = ColorTextMuted,
                modifier = Modifier.size(20.dp)
            )
        }

        if (viewModel.exaApiKey.isNotEmpty()) {
            IconButton(onClick = { viewModel.toggleExaSearch() }) {
                Icon(
                    imageVector = if (viewModel.exaSearchEnabled) Icons.Default.Search else Icons.Default.Language,
                    contentDescription = "Web search",
                    tint = if (viewModel.exaSearchEnabled) ColorAccentBlue else ColorTextMuted,
                    modifier = Modifier.size(20.dp)
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

private val TEXT_FILE_EXTENSIONS = setOf(
    "txt", "svg", "html", "htm", "js", "ts", "jsx", "tsx",
    "css", "scss", "json", "xml", "md", "csv", "py", "rb",
    "sh", "yaml", "yml", "log", "env", "sql", "java", "c",
    "cpp", "h", "rs", "go", "php", "swift", "kt"
)

private fun getFileExtension(name: String): String? {
    val dot = name.lastIndexOf('.')
    return if (dot >= 0) name.substring(dot + 1).lowercase() else null
}

private fun readTextContent(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    } catch (_: Exception) { null }
}

private fun getFileName(context: Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    return cursor?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment
    } ?: uri.lastPathSegment
}

@Composable
fun ConfigDialog(viewModel: MiauChatViewModel) {
    var urlInput by remember(viewModel.activePresetIndex) { mutableStateOf(viewModel.apiUrl) }
    var keyInput by remember(viewModel.activePresetIndex) { mutableStateOf(viewModel.apiKey) }
    var modelInput by remember(viewModel.activePresetIndex) { mutableStateOf(viewModel.apiModel) }
    var exaKeyInput by remember(viewModel.activePresetIndex) { mutableStateOf(viewModel.exaApiKey) }
    var firecrawlKeyInput by remember(viewModel.activePresetIndex) { mutableStateOf(viewModel.firecrawlApiKey) }

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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(
                        onClick = {
                            val idx = viewModel.activePresetIndex - 1
                            if (idx >= 0) {
                                viewModel.apiUrl = urlInput.trim()
                                viewModel.apiKey = keyInput.trim()
                                viewModel.apiModel = modelInput.trim()
                                viewModel.exaApiKey = exaKeyInput.trim()
                                viewModel.firecrawlApiKey = firecrawlKeyInput.trim()
                                viewModel.switchPreset(idx)
                            }
                        },
                        enabled = viewModel.activePresetIndex > 0
                    ) {
                        Icon(Icons.Default.KeyboardArrowLeft, "Previous", tint = ColorAccentBlue, modifier = Modifier.size(24.dp))
                    }
                    Text(
                        text = "Preset ${viewModel.activePresetIndex + 1} / 5",
                        color = ColorTextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    IconButton(
                        onClick = {
                            val idx = viewModel.activePresetIndex + 1
                            if (idx < 5) {
                                viewModel.apiUrl = urlInput.trim()
                                viewModel.apiKey = keyInput.trim()
                                viewModel.apiModel = modelInput.trim()
                                viewModel.exaApiKey = exaKeyInput.trim()
                                viewModel.firecrawlApiKey = firecrawlKeyInput.trim()
                                viewModel.switchPreset(idx)
                            }
                        },
                        enabled = viewModel.activePresetIndex < 4
                    ) {
                        Icon(Icons.Default.KeyboardArrowRight, "Next", tint = ColorAccentBlue, modifier = Modifier.size(24.dp))
                    }
                }

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
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                Text(
                    text = "Exa API Key (web search)",
                    color = ColorTextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = exaKeyInput,
                    onValueChange = { exaKeyInput = it },
                    placeholder = { Text("exa-...", color = ColorTextMuted, fontSize = 12.sp) },
                    textStyle = TextStyle(color = ColorTextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ColorAccentBlue, unfocusedBorderColor = ColorTextMuted
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                Text(
                    text = "Firecrawl API Key (URL scraping)",
                    color = ColorTextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = firecrawlKeyInput,
                    onValueChange = { firecrawlKeyInput = it },
                    placeholder = { Text("fc-...", color = ColorTextMuted, fontSize = 12.sp) },
                    textStyle = TextStyle(color = ColorTextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
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
                            viewModel.saveExaConfiguration(key = exaKeyInput.trim())
                            viewModel.saveFirecrawlConfiguration(key = firecrawlKeyInput.trim())
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
