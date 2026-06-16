package com.example.miauchat

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.miauchat.ui.theme.MiauChatTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.Locale
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.content.ContextCompat

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
    val logs: List<LogEntry>,
    val lastActive: Long = System.currentTimeMillis()
)

enum class ApiProvider(val label: String) {
    OpenAI("OpenAI"),
    Gemini("Gemini"),
    Claude("Claude")
}

data class ApiPreset(
    val url: String = "",
    val key: String = "",
    val model: String = "",
    val exaKey: String = "",
    val firecrawlKey: String = "",
    val provider: String = ApiProvider.OpenAI.name
)

class MiauChatViewModel(context: Context) : ViewModel() {
    private val prefs: SharedPreferences = context.getSharedPreferences("miauchat_prefs", Context.MODE_PRIVATE)

    var apiUrl by mutableStateOf(prefs.getString("api_url", "") ?: "")
    var apiKey by mutableStateOf(prefs.getString("api_key", "") ?: "")
    var apiModel by mutableStateOf(prefs.getString("api_model", "") ?: "")
    var apiProvider by mutableStateOf(ApiProvider.valueOf(prefs.getString("api_provider", ApiProvider.OpenAI.name) ?: ApiProvider.OpenAI.name))

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
    var isVoiceMode by mutableStateOf(false)
    var isListening by mutableStateOf(false)
    var isSpeaking by mutableStateOf(false)
    var showConfigDialog by mutableStateOf(false)
    var showGeneralConfig by mutableStateOf(false)
    var showHistoryDialog by mutableStateOf(false)
    var featureFileUpload by mutableStateOf(true)
    var featureWebSearch by mutableStateOf(true)
    var featureVoiceMode by mutableStateOf(true)
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
        featureFileUpload = prefs.getBoolean("feature_file_upload", true)
        featureWebSearch = prefs.getBoolean("feature_web_search", true)
        featureVoiceMode = prefs.getBoolean("feature_voice_mode", true)
    }

    fun saveFeatureToggles() {
        prefs.edit().apply {
            putBoolean("feature_file_upload", featureFileUpload)
            putBoolean("feature_web_search", featureWebSearch)
            putBoolean("feature_voice_mode", featureVoiceMode)
            apply()
        }
    }

    val providerDefaultUrls = mapOf(
        ApiProvider.OpenAI to "https://api.openai.com/v1/chat/completions",
        ApiProvider.Gemini to "https://generativelanguage.googleapis.com/v1beta/models",
        ApiProvider.Claude to "https://api.anthropic.com/v1/messages"
    )

    fun saveConfiguration(url: String, key: String, model: String, provider: ApiProvider) {
        apiUrl = url
        apiKey = key
        apiModel = model
        apiProvider = provider
        isConnected = url.isNotEmpty() && key.isNotEmpty() && model.isNotEmpty()
        prefs.edit().apply {
            putString("api_url", url)
            putString("api_key", key)
            putString("api_model", model)
            putString("api_provider", provider.name)
            apply()
        }
        apiPresets[activePresetIndex] = ApiPreset(url, key, model, exaApiKey, firecrawlApiKey, provider.name)
        persistPresets()
        showConfigDialog = false
    }

    fun saveExaConfiguration(key: String) {
        exaApiKey = key
        prefs.edit().putString("exa_api_key", key).apply()
        if (key.isNotEmpty()) exaSearchEnabled = true
        apiPresets[activePresetIndex] = ApiPreset(apiUrl, apiKey, apiModel, key, firecrawlApiKey, apiProvider.name)
        persistPresets()
    }

    fun saveFirecrawlConfiguration(key: String) {
        firecrawlApiKey = key
        prefs.edit().putString("firecrawl_api_key", key).apply()
        apiPresets[activePresetIndex] = ApiPreset(apiUrl, apiKey, apiModel, exaApiKey, key, apiProvider.name)
        persistPresets()
    }

    fun toggleExaSearch() {
        exaSearchEnabled = !exaSearchEnabled
    }

    fun saveCurrentSession() {
        if (chatLogs.isEmpty()) return
        val label = chatLogs.firstOrNull { it.sender == "USER" }?.content?.take(50) ?: "Chat"
        val now = System.currentTimeMillis()
        val session = ChatSession(label, chatLogs.toList(), now)
        val existing = sessions.indexOfFirst { it.label == label }
        if (existing >= 0) sessions[existing] = session
        else sessions.add(session)
        sessions.sortByDescending { it.lastActive }
        persistSessions()
    }

    fun loadSession(session: ChatSession) {
        chatLogs.clear()
        chatLogs.addAll(session.logs)
        val now = System.currentTimeMillis()
        val idx = sessions.indexOfFirst { it.label == session.label }
        if (idx >= 0) sessions[idx] = session.copy(lastActive = now)
        sessions.sortByDescending { it.lastActive }
        persistSessions()
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
                val lastActive = obj.optLong("lastActive", 0L)
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
                sessions.add(ChatSession(label, logs, lastActive))
            }
            sessions.sortByDescending { it.lastActive }
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
                    apiPresets.add(ApiPreset(
                        obj.optString("url", ""),
                        obj.optString("key", ""),
                        obj.optString("model", ""),
                        obj.optString("exaKey", ""),
                        obj.optString("firecrawlKey", ""),
                        obj.optString("provider", ApiProvider.OpenAI.name)
                    ))
                }
                if (apiPresets.isNotEmpty() && apiPresets[0].url.isNotEmpty()) {
                    val p = apiPresets[0]
                    apiUrl = p.url; apiKey = p.key; apiModel = p.model
                    apiProvider = try { ApiProvider.valueOf(p.provider) } catch (_: Exception) { ApiProvider.OpenAI }
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
                    put("url", p.url); put("key", p.key); put("model", p.model); put("exaKey", p.exaKey); put("firecrawlKey", p.firecrawlKey); put("provider", p.provider)
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
                put("lastActive", s.lastActive)
            })
        }
        prefs.edit().putString("sessions", arr.toString()).apply()
    }

    fun switchPreset(index: Int) {
        if (index < 0 || index >= apiPresets.size) return
        apiPresets[activePresetIndex] = ApiPreset(apiUrl, apiKey, apiModel, exaApiKey, firecrawlApiKey, apiProvider.name)
        val p = apiPresets[index]
        apiUrl = p.url; apiKey = p.key; apiModel = p.model
        apiProvider = try { ApiProvider.valueOf(p.provider) } catch (_: Exception) { ApiProvider.OpenAI }
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

    private fun providerUrl(provider: ApiProvider, baseUrl: String, model: String, apiKey: String): String {
        return when (provider) {
            ApiProvider.Gemini -> "$baseUrl/${model}:streamGenerateContent?alt=sse"
            else -> baseUrl
        }
    }

    private fun providerAuthHeaders(provider: ApiProvider, apiKey: String): List<Pair<String, String>> {
        return when (provider) {
            ApiProvider.OpenAI -> listOf("Authorization" to "Bearer $apiKey")
            ApiProvider.Gemini -> listOf("x-goog-api-key" to apiKey)
            ApiProvider.Claude -> listOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
        }
    }

    private fun convertToProviderMessages(
        provider: ApiProvider,
        aiEntryIndex: Int,
        file: PendingFile?,
        messageToSend: String
    ): Pair<JSONArray, String> {
        val systemPrompt = buildString {
            append("You are a helpful assistant. Keep responses appropriate and help the user as much as possible. Never generate DSML or any markup language.")
            if (exaSearchEnabled) {
                append(" You have two tools: 'exa' for general web searches, and 'firecrawl' for fetching specific URLs. There is no tool called 'web_search'. If the user asks for current info, look something up, or says 'search', use 'exa'. If they share a URL, use 'firecrawl'.")
            }
        }
        val messagesArray = JSONArray()
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
                    val role = when (provider) {
                        ApiProvider.Gemini -> "user"
                        else -> "user"
                    }
                    when (provider) {
                        ApiProvider.Gemini -> messagesArray.put(JSONObject().apply {
                            put("role", role)
                            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", content) }) })
                        })
                        else -> messagesArray.put(JSONObject().apply {
                            put("role", role)
                            put("content", content)
                        })
                    }
                }
                "AI" -> {
                    val role = when (provider) {
                        ApiProvider.Gemini -> "model"
                        else -> "assistant"
                    }
                    when (provider) {
                        ApiProvider.Gemini -> messagesArray.put(JSONObject().apply {
                            put("role", role)
                            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", log.content) }) })
                        })
                        else -> messagesArray.put(JSONObject().apply {
                            put("role", role)
                            put("content", log.content)
                        })
                    }
                }
            }
        }
        return Pair(messagesArray, systemPrompt)
    }

    private fun buildToolDefinitions(provider: ApiProvider): JSONArray? {
        if (!exaSearchEnabled) return null
        val tools = JSONArray()
        val addTool = { name: String, description: String, params: JSONObject ->
            when (provider) {
                ApiProvider.Gemini -> tools.put(JSONObject().apply {
                    put("functionDeclarations", JSONArray().apply {
                        put(JSONObject().apply {
                            put("name", name)
                            put("description", description)
                            put("parameters", params)
                        })
                    })
                })
                ApiProvider.Claude -> tools.put(JSONObject().apply {
                    put("name", name)
                    put("description", description)
                    put("input_schema", params)
                })
                else -> tools.put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", name)
                        put("description", description)
                        put("parameters", params)
                    })
                })
            }
        }
        addTool("exa", "Search the web for current information. Use this for general, semantic, or news queries — not for specific URLs.", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "The search query")
                })
            })
            put("required", JSONArray().apply { put("query") })
        })
        if (firecrawlApiKey.isNotEmpty()) {
            addTool("firecrawl", "Fetch the content of a specific URL. Use this when the user shares a link, asks to open or look up a specific web page.", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "The URL to fetch")
                    })
                })
                put("required", JSONArray().apply { put("url") })
            })
        }
        return if (tools.length() > 0) tools else null
    }

    private fun buildProviderBody(
        provider: ApiProvider,
        model: String,
        messagesArray: JSONArray,
        systemPrompt: String,
        stream: Boolean,
        tools: JSONArray? = null
    ): JSONObject {
        return when (provider) {
            ApiProvider.Gemini -> JSONObject().apply {
                put("contents", messagesArray)
                if (systemPrompt.isNotBlank()) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemPrompt) }) })
                    })
                }
                tools?.let { put("tools", it) }
            }
            ApiProvider.Claude -> JSONObject().apply {
                put("model", model)
                put("max_tokens", 4096)
                put("stream", stream)
                if (systemPrompt.isNotBlank()) put("system", systemPrompt)
                put("messages", messagesArray)
                tools?.let { put("tools", it) }
            }
            else -> JSONObject().apply {
                put("model", model)
                put("stream", stream)
                if (systemPrompt.isNotBlank()) {
                    messagesArray.put(0, JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                put("messages", messagesArray)
                tools?.let { put("tools", it) }
            }
        }
    }

    private data class SSEResult(
        val content: String?,
        val reasoning: String?,
        val finishReason: String?,
        val toolCallId: String?,
        val toolCallName: String?,
        val toolCallArgsDelta: String?
    )

    private fun parseProviderSSE(provider: ApiProvider, line: String): SSEResult? {
        if (!line.startsWith("data: ")) return null
        val data = line.removePrefix("data: ").trim()
        if (data == "[DONE]") return SSEResult(null, null, "stop", null, null, null)

        return try {
            val json = JSONObject(data)
            when (provider) {
                ApiProvider.OpenAI -> {
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")
                        val finish = choice.optString("finish_reason", null)
                        val tcArray = delta?.optJSONArray("tool_calls")
                        val tcId = if (tcArray != null && tcArray.length() > 0) tcArray.getJSONObject(0).optString("id", null) else null
                        val tcName = if (tcArray != null && tcArray.length() > 0) tcArray.getJSONObject(0).optJSONObject("function")?.optString("name", null) else null
                        val tcArgs = if (tcArray != null && tcArray.length() > 0) tcArray.getJSONObject(0).optJSONObject("function")?.optString("arguments", null) else null
                        SSEResult(
                            content = delta?.optString("content", null),
                            reasoning = delta?.optString("reasoning_content", null),
                            finishReason = if (finish == "tool_calls") "tool_calls" else finish,
                            toolCallId = tcId,
                            toolCallName = tcName,
                            toolCallArgsDelta = tcArgs
                        )
                    } else null
                }
                ApiProvider.Gemini -> {
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val candidate = candidates.getJSONObject(0)
                        val content = candidate.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        val text = if (parts != null && parts.length() > 0) parts.getJSONObject(0).optString("text", null) else null
                        val finish = candidate.optString("finishReason", null)
                        val fc = if (parts != null && parts.length() > 0) parts.getJSONObject(0).optJSONObject("functionCall") else null
                        if (fc != null) {
                            SSEResult(null, null, "tool_calls", fc.optString("name", null), fc.optString("name", null), fc.optJSONObject("args")?.toString())
                        } else {
                            SSEResult(text, null, finish, null, null, null)
                        }
                    } else null
                }
                ApiProvider.Claude -> {
                    val eventType = json.optString("type", null)
                    when (eventType) {
                        "content_block_start" -> {
                            val block = json.optJSONObject("content_block")
                            if (block != null && block.optString("type") == "tool_use") {
                                SSEResult(null, null, "tool_calls", block.optString("id", null), block.optString("name", null), block.optJSONObject("input")?.toString())
                            } else null
                        }
                        "content_block_delta" -> {
                            val delta = json.optJSONObject("delta")
                            SSEResult(delta?.optString("text", null), null, null, null, null, null)
                        }
                        "message_delta" -> {
                            val delta = json.optJSONObject("delta")
                            val stopReason = delta?.optString("stop_reason", null)
                            if (stopReason == "tool_use") {
                                SSEResult(null, null, "tool_calls", null, null, null)
                            } else {
                                SSEResult(null, null, stopReason, null, null, null)
                            }
                        }
                        "message_stop" -> SSEResult(null, null, "stop", null, null, null)
                        "error" -> SSEResult(json.optJSONObject("error")?.optString("message", "API error"), null, "error", null, null, null)
                        else -> null
                    }
                }
            }
        } catch (_: Exception) { null }
    }

    private fun addToolMessages(
        messages: JSONArray,
        provider: ApiProvider,
        toolCallId: String,
        toolCallName: String,
        toolCallArgs: String,
        toolResult: String
    ) {
        when (provider) {
            ApiProvider.Gemini -> {
                val argsJson = try { JSONObject(toolCallArgs) } catch (_: Exception) { JSONObject() }
                messages.put(JSONObject().apply {
                    put("role", "model")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("functionCall", JSONObject().apply {
                                put("name", toolCallName)
                                put("args", argsJson)
                            })
                        })
                    })
                })
                messages.put(JSONObject().apply {
                    put("role", "function")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("functionResponse", JSONObject().apply {
                                put("name", toolCallName)
                                put("response", JSONObject().apply {
                                    put("content", toolResult)
                                })
                            })
                        })
                    })
                })
            }
            ApiProvider.Claude -> {
                val argsJson = try { JSONObject(toolCallArgs) } catch (_: Exception) { JSONObject() }
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "tool_use")
                            put("id", toolCallId)
                            put("name", toolCallName)
                            put("input", argsJson)
                        })
                    })
                })
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "tool_result")
                            put("tool_use_id", toolCallId)
                            put("content", toolResult)
                        })
                    })
                })
            }
            else -> {
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("tool_calls", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", toolCallId)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", toolCallName)
                                put("arguments", toolCallArgs)
                            })
                        })
                    })
                })
                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", toolCallId)
                    put("content", toolResult)
                })
            }
        }
    }

    private fun parseProviderNonStreaming(provider: ApiProvider, body: String): String {
        return try {
            val root = JSONObject(body)
            when (provider) {
                ApiProvider.OpenAI -> {
                    if (root.has("choices")) {
                        val choices = root.getJSONArray("choices")
                        if (choices.length() > 0) {
                            val first = choices.getJSONObject(0)
                            if (first.has("message")) first.getJSONObject("message").getString("content")
                            else if (first.has("text")) first.getString("text")
                            else body
                        } else body
                    } else body
                }
                ApiProvider.Gemini -> {
                    val candidates = root.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) parts.getJSONObject(0).optString("text", body)
                        else body
                    } else root.optString("error", body)
                }
                ApiProvider.Claude -> {
                    val content = root.optJSONArray("content")
                    if (content != null && content.length() > 0) content.getJSONObject(0).optString("text", body)
                    else body
                }
            }
        } catch (_: Exception) { body }
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
                val provider = apiProvider
                val (messagesArray, systemPrompt) = convertToProviderMessages(provider, aiEntryIndex, file, messageToSend)

                val toolDefs = buildToolDefinitions(provider)
                val jsonBody = buildProviderBody(provider, apiModel, messagesArray, systemPrompt, true, toolDefs)

                val requestUrl = providerUrl(provider, apiUrl, apiModel, apiKey)
                val requestBuilder = Request.Builder()
                    .url(requestUrl)
                    .addHeader("Content-Type", "application/json")
                for ((name, value) in providerAuthHeaders(provider, apiKey)) {
                    requestBuilder.addHeader(name, value)
                }
                val request = requestBuilder
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
                    if (provider == ApiProvider.Claude) {
                        if (currentLine.startsWith("event: ")) continue
                    }
                    val result = parseProviderSSE(provider, currentLine)
                    if (result != null) {
                        isStreaming = true
                        if (result.finishReason == "stop" || result.finishReason == "STOP") break
                        if (result.finishReason == "tool_calls") {
                            toolCallId = result.toolCallId
                            toolCallFunctionName = result.toolCallName
                            if (result.toolCallArgsDelta != null) toolCallArgsBuilder.append(result.toolCallArgsDelta)
                            break
                        }
                        if (result.toolCallArgsDelta != null) {
                            toolCallArgsBuilder.append(result.toolCallArgsDelta)
                        }
                        if (result.content != null) {
                            fullContent += result.content
                            streamingContent = fullContent
                            chatLogs[aiEntryIndex] = LogEntry("AI", fullContent, fullReasoning)
                        }
                        if (result.reasoning != null) {
                            fullReasoning += result.reasoning
                            streamingReasoning = fullReasoning
                            chatLogs[aiEntryIndex] = LogEntry("AI", fullContent, fullReasoning)
                        }
                    } else if (currentLine.isNotBlank() && !isStreaming) {
                        fullContent += currentLine
                    }
                }
                reader.close()

                if (toolCallId != null && (toolCallFunctionName == "exa" || toolCallFunctionName == "firecrawl")) {
                    val argsJson = JSONObject(toolCallArgsBuilder.toString())
                    val toolResult = if (toolCallFunctionName == "exa") {
                        val searchQuery = argsJson.optString("query", messageToSend)
                        exaSearch(searchQuery)
                    } else {
                        val url = argsJson.optString("url", "")
                        if (url.isNotEmpty()) firecrawlFetch(url) else "No URL provided"
                    }

                    val secondMessagesArray = JSONArray()
                    val (prevMessages, _) = convertToProviderMessages(provider, aiEntryIndex, null, "")
                    for (i in 0 until prevMessages.length()) {
                        secondMessagesArray.put(prevMessages.getJSONObject(i))
                    }
                    addToolMessages(secondMessagesArray, provider, toolCallId!!, toolCallFunctionName!!, toolCallArgsBuilder.toString(), toolResult)
                    val secondBody = buildProviderBody(provider, apiModel, secondMessagesArray, "", true, toolDefs)

                    val secondRequestBuilder = Request.Builder()
                        .url(providerUrl(provider, apiUrl, apiModel, apiKey))
                        .addHeader("Content-Type", "application/json")
                    for ((name, value) in providerAuthHeaders(provider, apiKey)) {
                        secondRequestBuilder.addHeader(name, value)
                    }
                    val secondRequest = secondRequestBuilder
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
                                if (provider == ApiProvider.Claude && sCurrent.startsWith("event: ")) continue
                                val sResult = parseProviderSSE(provider, sCurrent)
                                if (sResult != null) {
                                    if (sResult.finishReason == "stop" || sResult.finishReason == "STOP") break
                                    if (sResult.content != null) {
                                        fullContent += sResult.content
                                        streamingContent = fullContent
                                        chatLogs[aiEntryIndex] = LogEntry("AI", fullContent, fullReasoning)
                                    }
                                    if (sResult.reasoning != null) {
                                        fullReasoning += sResult.reasoning
                                        streamingReasoning = fullReasoning
                                        chatLogs[aiEntryIndex] = LogEntry("AI", fullContent, fullReasoning)
                                    }
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
                    fullContent = parseProviderNonStreaming(provider, fullContent)
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
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val lazyListState = rememberLazyListState()
    var showSettingsMenu by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.chatLogs.size) {
        if (viewModel.chatLogs.isNotEmpty()) {
            lazyListState.animateScrollToItem(viewModel.chatLogs.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
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
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "MiauChat",
                    color = colorScheme.onSurface,
                    style = typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showSettingsMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = colorScheme.onSurface,
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

        if (showSettingsMenu) {
            Dialog(onDismissRequest = { showSettingsMenu = false }) {
                Card(
                    shape = MaterialTheme.shapes.small,
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colorScheme.outline, MaterialTheme.shapes.small)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Settings",
                            color = colorScheme.onSurface,
                            style = typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                showSettingsMenu = false
                                viewModel.showConfigDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Model Config", color = colorScheme.onPrimary, style = typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                showSettingsMenu = false
                                viewModel.showGeneralConfig = true
                            },
                            border = BorderStroke(1.dp, colorScheme.outline),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("General Config", color = colorScheme.onSurface, style = typography.labelMedium)
                        }
                    }
                }
            }
        }

        if (viewModel.showHistoryDialog) {
            HistoryDialog(viewModel)
        }

        if (viewModel.showConfigDialog) {
            ConfigDialog(viewModel)
        }
        if (viewModel.showGeneralConfig) {
            GeneralConfigDialog(viewModel)
        }
    }
}

@Composable
fun ChatLine(log: LogEntry) {
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
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
                color = colorScheme.onSurfaceVariant,
                style = typography.bodySmall
            )
        } else {
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .background(
                        color = if (isUser) colorScheme.surface else colorScheme.background,
                        shape = MaterialTheme.shapes.small
                    )
                    .border(
                        width = 1.dp,
                        color = if (isUser) colorScheme.primary else colorScheme.outline,
                        shape = MaterialTheme.shapes.small
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
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (stillThinking) "thinking..." else "reasoning",
                                color = colorScheme.onSurfaceVariant,
                                style = typography.labelSmall
                            )
                        }

                        AnimatedVisibility(visible = expanded) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colorScheme.surface)
                                    .border(1.dp, colorScheme.outline)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = log.reasoning,
                                    color = colorScheme.onSurfaceVariant,
                                    style = typography.bodySmall
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Text(
                        text = log.content,
                        color = colorScheme.onSurface,
                        style = typography.bodyLarge,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { clipboardManager.setText(AnnotatedString(log.content)) },
                            border = BorderStroke(1.dp, colorScheme.outline),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.onSurfaceVariant)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", style = typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InputCard(viewModel: MiauChatViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }

    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.getDefault()
            }
        }
        engine!!
    }

    remember {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post { viewModel.isSpeaking = false }
            }
            override fun onError(utteranceId: String?) {
                handler.post { viewModel.isSpeaking = false }
            }
        })
    }

    val listener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                viewModel.isListening = true
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                viewModel.isListening = false
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    viewModel.currentInput = matches[0]
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    viewModel.currentInput = matches[0]
                }
                viewModel.isListening = false
                handler.post {
                    if (viewModel.isVoiceMode && viewModel.currentInput.isNotBlank()) {
                        viewModel.sendMessage()
                    }
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    DisposableEffect(Unit) {
        speechRecognizer.setRecognitionListener(listener)
        onDispose {
            speechRecognizer.destroy()
            tts.stop()
            tts.shutdown()
        }
    }

    var lastSpokenContent by remember {
        mutableStateOf(viewModel.chatLogs.filter { it.sender == "AI" }.lastOrNull()?.content ?: "")
    }
    LaunchedEffect(viewModel.isLoading, viewModel.chatLogs.size) {
        if (!viewModel.isLoading && viewModel.isVoiceMode && viewModel.chatLogs.isNotEmpty()) {
            val lastEntry = viewModel.chatLogs.last()
            if (lastEntry.sender == "AI" && lastEntry.content.isNotEmpty() && lastEntry.content != lastSpokenContent) {
                lastSpokenContent = lastEntry.content
                val utteranceId = "speak_${System.currentTimeMillis()}"
                tts.speak(lastEntry.content, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                viewModel.isSpeaking = true
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val micScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_scale"
    )

    var startListening: () -> Unit = {}
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
    }
    startListening = {
        if (viewModel.isVoiceMode) {
            tts.stop()
            viewModel.isSpeaking = false
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                }
                speechRecognizer.startListening(intent)
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileName(context, uri) ?: "file"
            val rawContent = try {
                context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
            } catch (_: Exception) { null }
            if (rawContent != null) {
                viewModel.pendingFile = PendingFile(fileName = fileName, content = rawContent)
            } else {
                viewModel.chatLogs.add(LogEntry("USER", "[📎 $fileName]"))
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.surface, shape = MaterialTheme.shapes.small)
            .border(width = 1.dp, color = colorScheme.outline)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(colorScheme.primary)
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
                            color = colorScheme.primary,
                            style = typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.pendingFile = null },
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove file",
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (viewModel.currentInput.isEmpty() && !viewModel.isLoading && !viewModel.isListening) {
                        Text(
                            text = if (viewModel.isVoiceMode) "Speak now..." else "Ask anything...",
                            color = colorScheme.onSurfaceVariant,
                            style = typography.bodyMedium
                        )
                    }

                    BasicTextField(
                        value = viewModel.currentInput,
                        onValueChange = { viewModel.currentInput = it },
                        textStyle = typography.bodyMedium.copy(color = colorScheme.onSurface),
                        cursorBrush = SolidColor(colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            if (!viewModel.isLoading) viewModel.sendMessage()
                        }),
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = viewModel.isLoading || viewModel.isListening
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val label = if (viewModel.isConnected) viewModel.apiModel else "Offline"
                    Text(
                        text = label,
                        color = if (viewModel.isConnected) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                        style = typography.labelSmall
                    )
                }
            }
        }

        if (viewModel.featureFileUpload) {
            IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach",
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (viewModel.featureWebSearch && viewModel.exaApiKey.isNotEmpty()) {
            IconButton(onClick = { viewModel.toggleExaSearch() }) {
                Icon(
                    imageVector = if (viewModel.exaSearchEnabled) Icons.Default.Search else Icons.Default.Language,
                    contentDescription = "Web search",
                    tint = if (viewModel.exaSearchEnabled) colorScheme.primary else colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (viewModel.featureVoiceMode) {
            IconButton(
                onClick = {
                    viewModel.isVoiceMode = !viewModel.isVoiceMode
                    if (!viewModel.isVoiceMode) {
                        tts.stop()
                        viewModel.isSpeaking = false
                        if (viewModel.isListening) {
                            speechRecognizer.cancel()
                            viewModel.isListening = false
                        }
                    }
                },
                modifier = Modifier.padding(end = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = if (viewModel.isVoiceMode) "Text mode" else "Voice mode",
                    tint = if (viewModel.isVoiceMode) colorScheme.primary else colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        IconButton(
            onClick = {
                keyboardController?.hide()
                focusManager.clearFocus()
                if (viewModel.isLoading) {
                    viewModel.stopGeneration()
                } else if (viewModel.isVoiceMode) {
                    if (viewModel.isListening) {
                        speechRecognizer.stopListening()
                    } else {
                        startListening()
                    }
                } else {
                    if (viewModel.isSpeaking) {
                        tts.stop()
                        viewModel.isSpeaking = false
                    }
                    viewModel.sendMessage()
                }
            },
            modifier = Modifier.padding(end = 4.dp)
        ) {
            if (viewModel.isLoading) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Stop",
                    tint = colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
            } else if (viewModel.isVoiceMode && viewModel.isListening) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Stop recording",
                    tint = colorScheme.secondary,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer(scaleX = micScale, scaleY = micScale)
                )
            } else if (viewModel.isVoiceMode) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Voice input",
                    tint = colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (viewModel.currentInput.isNotBlank()) colorScheme.primary else colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun HistoryDialog(viewModel: MiauChatViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Dialog(onDismissRequest = { viewModel.showHistoryDialog = false }) {
        Card(
            shape = MaterialTheme.shapes.small,
            colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .border(1.dp, colorScheme.outline, MaterialTheme.shapes.small)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "History",
                        color = colorScheme.onSurface,
                        style = typography.headlineSmall
                    )
                    TextButton(onClick = { viewModel.newChat(); viewModel.showHistoryDialog = false }) {
                        Text(
                            "New chat",
                            color = colorScheme.primary,
                            style = typography.labelMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (viewModel.sessions.isEmpty()) {
                    Text(
                        text = "No previous chats",
                        color = colorScheme.onSurfaceVariant,
                        style = typography.bodySmall,
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
                                    color = colorScheme.onSurface,
                                    style = typography.titleMedium,
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
                                        tint = colorScheme.onSurfaceVariant,
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

private fun getFileName(context: Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    return cursor?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment
    } ?: uri.lastPathSegment
}

@Composable
fun GeneralConfigDialog(viewModel: MiauChatViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Dialog(onDismissRequest = { viewModel.showGeneralConfig = false }) {
        Card(
            shape = MaterialTheme.shapes.small,
            colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colorScheme.outline, MaterialTheme.shapes.small)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "General Config",
                    color = colorScheme.onSurface,
                    style = typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("File upload", color = colorScheme.onSurface, style = typography.bodyMedium)
                    Switch(
                        checked = viewModel.featureFileUpload,
                        onCheckedChange = {
                            viewModel.featureFileUpload = it
                            viewModel.saveFeatureToggles()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colorScheme.primary,
                            checkedTrackColor = colorScheme.primary.copy(alpha = 0.4f),
                            uncheckedThumbColor = colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Web search", color = colorScheme.onSurface, style = typography.bodyMedium)
                    Switch(
                        checked = viewModel.featureWebSearch,
                        onCheckedChange = {
                            viewModel.featureWebSearch = it
                            viewModel.saveFeatureToggles()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colorScheme.primary,
                            checkedTrackColor = colorScheme.primary.copy(alpha = 0.4f),
                            uncheckedThumbColor = colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Voice mode", color = colorScheme.onSurface, style = typography.bodyMedium)
                    Switch(
                        checked = viewModel.featureVoiceMode,
                        onCheckedChange = {
                            viewModel.featureVoiceMode = it
                            viewModel.saveFeatureToggles()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colorScheme.primary,
                            checkedTrackColor = colorScheme.primary.copy(alpha = 0.4f),
                            uncheckedThumbColor = colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.showGeneralConfig = false },
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done", color = colorScheme.onPrimary, style = typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun ConfigDialog(viewModel: MiauChatViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    var urlInput by remember(viewModel.activePresetIndex) { mutableStateOf(viewModel.apiUrl) }
    var keyInput by remember(viewModel.activePresetIndex) { mutableStateOf(viewModel.apiKey) }
    var modelInput by remember(viewModel.activePresetIndex) { mutableStateOf(viewModel.apiModel) }
    var exaKeyInput by remember(viewModel.activePresetIndex) { mutableStateOf(viewModel.exaApiKey) }
    var firecrawlKeyInput by remember(viewModel.activePresetIndex) { mutableStateOf(viewModel.firecrawlApiKey) }
    var providerInput by remember(viewModel.activePresetIndex) { mutableStateOf(viewModel.apiProvider) }

    Dialog(onDismissRequest = { viewModel.showConfigDialog = false }) {
        Card(
            shape = MaterialTheme.shapes.small,
            colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colorScheme.primary, MaterialTheme.shapes.small)
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
                        Icon(Icons.Default.KeyboardArrowLeft, "Previous", tint = colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                    Text(
                        text = "Preset ${viewModel.activePresetIndex + 1} / 5",
                        color = colorScheme.onSurface,
                        style = typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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
                        Icon(Icons.Default.KeyboardArrowRight, "Next", tint = colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                }

                Text(
                    text = "API URL",
                    color = colorScheme.onSurface,
                    style = typography.labelSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("https://api.endpoint/v1", color = colorScheme.onSurfaceVariant, style = typography.bodySmall) },
                    textStyle = typography.bodySmall.copy(color = colorScheme.onSurface),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colorScheme.primary, unfocusedBorderColor = colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                Text(
                    text = "API Key",
                    color = colorScheme.onSurface,
                    style = typography.labelSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    placeholder = { Text("sk-...", color = colorScheme.onSurfaceVariant, style = typography.bodySmall) },
                    textStyle = typography.bodySmall.copy(color = colorScheme.onSurface),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colorScheme.primary, unfocusedBorderColor = colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                Text(
                    text = "Model",
                    color = colorScheme.onSurface,
                    style = typography.labelSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = modelInput,
                    onValueChange = { modelInput = it },
                    placeholder = { Text("gpt-4", color = colorScheme.onSurfaceVariant, style = typography.bodySmall) },
                    textStyle = typography.bodySmall.copy(color = colorScheme.onSurface),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colorScheme.primary, unfocusedBorderColor = colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                Text(
                    text = "Provider",
                    color = colorScheme.onSurface,
                    style = typography.labelSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ApiProvider.entries.forEach { p ->
                        val isSelected = providerInput == p
                        OutlinedButton(
                            onClick = {
                                providerInput = p
                                urlInput = viewModel.providerDefaultUrls[p] ?: urlInput
                            },
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) colorScheme.primary else colorScheme.outline
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(p.label, style = typography.labelSmall)
                        }
                    }
                }

                Text(
                    text = "Exa API Key (web search)",
                    color = colorScheme.onSurface,
                    style = typography.labelSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = exaKeyInput,
                    onValueChange = { exaKeyInput = it },
                    placeholder = { Text("exa-...", color = colorScheme.onSurfaceVariant, style = typography.bodySmall) },
                    textStyle = typography.bodySmall.copy(color = colorScheme.onSurface),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colorScheme.primary, unfocusedBorderColor = colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                Text(
                    text = "Firecrawl API Key (URL scraping)",
                    color = colorScheme.onSurface,
                    style = typography.labelSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    value = firecrawlKeyInput,
                    onValueChange = { firecrawlKeyInput = it },
                    placeholder = { Text("fc-...", color = colorScheme.onSurfaceVariant, style = typography.bodySmall) },
                    textStyle = typography.bodySmall.copy(color = colorScheme.onSurface),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colorScheme.primary, unfocusedBorderColor = colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { viewModel.showConfigDialog = false }) {
                        Text("Cancel", color = colorScheme.onSurfaceVariant, style = typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                        shape = MaterialTheme.shapes.small,
                        onClick = {
                            viewModel.saveConfiguration(
                                url = urlInput.trim(), key = keyInput.trim(), model = modelInput.trim(), provider = providerInput
                            )
                            viewModel.saveExaConfiguration(key = exaKeyInput.trim())
                            viewModel.saveFirecrawlConfiguration(key = firecrawlKeyInput.trim())
                        }
                    ) {
                        Text(
                            "Save", color = colorScheme.onPrimary,
                            style = typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}
