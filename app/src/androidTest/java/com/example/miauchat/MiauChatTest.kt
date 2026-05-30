package com.example.miauchat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiauChatTest {

    private lateinit var viewModel: MiauChatViewModel
    private val timeoutMs = 60_000L
    private val pollIntervalMs = 100L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        viewModel = MiauChatViewModel(context)
        viewModel.saveConfiguration(
            url = "https://opencode.ai/zen/v1/chat/completions",
            key = "sk-1c5hjx79QfxWWGQnD5yrhPYPv6fxgX1EqCVioXRD2F4QlGTlX9Kl4BuaxGxJyvpK",
            model = "deepseek-v4-flash-free"
        )
    }

    @Test
    fun testSendMessageProducesResponse() {
        viewModel.currentInput = "Hello"
        viewModel.sendMessage()

        val response = waitForAiResponse()
        assertNotNull("No AI response received after sendMessage()", response)
        assertTrue("Response should not be empty", response!!.isNotEmpty())
    }

    @Test
    fun testChatMemory() {
        viewModel.currentInput = "My favorite color is teal"
        viewModel.sendMessage()
        val firstResp = waitForAiResponse()
        assertNotNull("No response to first message", firstResp)
        assertTrue("First response should not be empty", firstResp!!.isNotEmpty())

        viewModel.currentInput = "What is my favorite color?"
        viewModel.sendMessage()
        val secondResp = waitForAiResponse()
        assertNotNull("No response to second message", secondResp)
        assertTrue("Second response should not be empty", secondResp!!.isNotEmpty())
    }

    @Test
    fun testInputClearsOnSend() {
        viewModel.currentInput = "hello"
        viewModel.sendMessage()
        assertEquals("Input should clear after sendMessage()", "", viewModel.currentInput)
    }

    @Test
    fun testPreWritesDuringLoadingStayIntact() {
        viewModel.currentInput = "first message"
        viewModel.sendMessage()

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5000 && !viewModel.isLoading) {
            Thread.sleep(100)
        }
        assertTrue("Generation should be running", viewModel.isLoading)

        viewModel.currentInput = "pre-typed text"
        assertEquals("Pre-typed text should stay during loading", "pre-typed text", viewModel.currentInput)
    }

    @Test
    fun testStopGeneration() {
        viewModel.currentInput = "hi"
        viewModel.sendMessage()

        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < 10000 && viewModel.isLoading) {
            Thread.sleep(100)
        }

        if (viewModel.isLoading) {
            viewModel.stopGeneration()
            val stopped = System.currentTimeMillis()
            while (System.currentTimeMillis() - stopped < 5000 && viewModel.isLoading) {
                Thread.sleep(100)
            }
            if (viewModel.isLoading) {
                val logs = viewModel.chatLogs.joinToString(" | ") { "${it.sender}:${it.content.take(30)}" }
                fail("isLoading still true after stop. Logs: $logs")
            }
        }

        val lastEntry = viewModel.chatLogs.lastOrNull()
        assertNotNull("Should have an AI entry after stop or response", lastEntry)
        assertEquals("Last entry should be AI", "AI", lastEntry!!.sender)
    }

    @Test
    fun testNewChatClearsLogs() {
        viewModel.currentInput = "Hello"
        viewModel.sendMessage()
        waitForResponseNotCrash()

        viewModel.newChat()
        assertTrue("chatLogs should be empty after newChat()", viewModel.chatLogs.isEmpty())
    }

    @Test
    fun testHistorySessionPersistence() {
        viewModel.currentInput = "Test message"
        viewModel.sendMessage()
        waitForResponseNotCrash()

        viewModel.saveCurrentSession()
        assertTrue("Should have at least one saved session", viewModel.sessions.isNotEmpty())
        assertEquals("Saved session label should match first user message",
            "Test message", viewModel.sessions.first().label)
    }

    @Test
    fun testLoadRestoresSession() {
        viewModel.currentInput = "Hello world"
        viewModel.sendMessage()
        waitForResponseNotCrash()

        val originalSize = viewModel.chatLogs.size
        viewModel.saveCurrentSession()
        viewModel.newChat()
        assertTrue("Chat should be empty after newChat", viewModel.chatLogs.isEmpty())

        viewModel.loadSession(viewModel.sessions.first())
        assertEquals("Chat logs should be restored to original size", originalSize, viewModel.chatLogs.size)
        assertEquals("First log should be USER", "USER", viewModel.chatLogs.first().sender)
    }

    private fun waitForAiResponse(): String? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val lastEntry = viewModel.chatLogs.lastOrNull()
            if (lastEntry != null && lastEntry.sender == "AI" && lastEntry.content.isNotEmpty()) {
                Thread.sleep(500)
                val finalEntry = viewModel.chatLogs.lastOrNull()
                if (finalEntry != null && finalEntry.sender == "AI" && !viewModel.isLoading) {
                    return finalEntry.content
                }
            }
            Thread.sleep(pollIntervalMs)
        }
        fail("Timed out waiting for AI response after ${timeoutMs}ms. Logs: ${viewModel.chatLogs.joinToString(" | ")}")
        return null
    }

    private fun waitForResponseNotCrash() {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (!viewModel.isLoading) return
            Thread.sleep(pollIntervalMs)
        }
    }
}
