# MiauChat

> A terminal-styled Android chat client that connects to any OpenAI-compatible API.

Dark theme, monospace UI, streaming responses, and session history — all in a single-activity Jetpack Compose app.

## Features

- **Terminal aesthetic** — black background, blue accent, monospace font throughout
- **Streaming responses** — real-time SSE token parsing from any OpenAI-compatible API
- **Multi-session history** — chats auto-saved to `SharedPreferences`, browsable and deletable
- **Configurable endpoint** — set API URL, key, and model at runtime with persistent storage
- **Stop generation** — cancel in-flight requests with the amber stop button
- **Offline detection** — clear indicator when no API is configured

## Tech Stack

| Layer | Choice |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Theme | Dark-only (`darkColorScheme`) |
| Networking | OkHttp 4.12 (direct, no Retrofit) |
| Persistence | SharedPreferences (sessions as JSON) |
| Architecture | Single-file ViewModel + Composables |
| Min SDK | 24 / Target SDK 36 |
| Kotlin | 2.0.21 / AGP 9.0.1 / Gradle 9.1.0 |

## Getting Started

### Prerequisites

- Android Studio Ladybug (or newer)
- JDK 11
- An OpenAI-compatible API endpoint and key

### Download

[Download MiauChat.apk (v1.1.0)](https://github.com/CocoKitten2026/MiauChat/releases/download/v1.1.0/MiauChat.apk) — debug build, ready to sideload.

SHA256: `f50819d3401c2a3ce638e0cbdfab51710e49b82f542bed120ab2ae3025097756`

### Build & Run

```bash
# Build debug APK
gradlew assembleDebug

# Run unit tests
gradlew test

# Run instrumented tests (emulator/device required)
gradlew connectedAndroidTest
```

### Configuration

When the app launches, tap the **+** icon and enter:

- **API URL** — e.g. `https://api.openai.com/v1/chat/completions`
- **API Key** — your bearer token (`sk-...`)
- **Model** — e.g. `gpt-4`, `deepseek-v4-flash-free`

These are persisted and restored on next launch.

## Project Structure

```
app/src/main/java/com/example/miauchat/
├── MainActivity.kt          # ViewModel + all composables (~718 lines)
└── ui/theme/
    ├── Color.kt             # Terminal color palette
    ├── Theme.kt             # MiauChatTheme (dark scheme)
    └── Type.kt              # Monospace typography
```

All UI lives in a single file — no XML layouts, no Fragments, no DI framework.

## License

GNU General Public License v3.0
