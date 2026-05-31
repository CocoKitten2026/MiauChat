# MiauChat — Agent Guidance

## Build & Test

Use the **Windows batch wrapper** (this is a Windows host):

| Command | Action |
|---|---|
| `gradlew assembleDebug` | Build debug APK |
| `gradlew test` | Run unit tests (`src/test/`) |
| `gradlew connectedAndroidTest` | Run instrumented tests (needs emulator/device) |
| `gradlew lint` | Android lint |

No formatter or typecheck tooling is configured. Kotlin code style is `official` per `gradle.properties`.

## Architecture (single module `:app`)

- **Entrypoint**: `app/src/main/java/com/example/miauchat/MainActivity.kt` — contains ViewModel + all composables in one file (~1176 lines).
- **ViewModel is manual** (no DI framework like Hilt/Koin). `MiauChatViewModel` takes a `Context` via a custom `ViewModelProvider.Factory` in `onCreate()`. Preserve the factory pattern if refactoring.
- **No XML layouts** for the UI — pure Jetpack Compose (Material 3) with a dark-terminal theme (`FontFamily.Monospace`, black background, blue accent). Uses `material-icons-extended` for `AttachFile`, `Language`, `Search` icons.
- **Networking**: OkHttp direct calls (no Retrofit). Requests use `"stream": true` with SSE parsing (`data:` lines); non-streaming fallback via `parseNonStreaming()`. Also supports OpenAI-compatible **tool/function calling** — includes a `web_search` tool definition for Exa when `exaSearchEnabled` is on.
- **Exa web search**: Configurable API key. When enabled, adds a toggle button (globe/search icon) next to the attach button. The model decides when to call `web_search` via tool calling; results are fetched from `POST https://api.exa.ai/search` and fed back in a second streaming request.
- **Model thinking**: SSE `reasoning_content` field is captured in `LogEntry.reasoning` and displayed in a muted collapsible block within AI message containers.
- **Persistence**: Chat sessions serialized to JSON in `SharedPreferences` under key `"sessions"`. API URL/key/model, Exa key also stored in prefs.

## Sensitive data

`app/src/androidTest/java/com/example/miauchat/MiauChatTest.kt` contains a **live API key** committed to the repo. Do not duplicate or expose it further. These tests require network access to a real API and have a 60 s timeout — they are not hermetic.

## App identity quirk

`namespace = "com.example.miauchat"` but `applicationId = "com.opencode.client"` in `app/build.gradle.kts`. The APK is published as `com.opencode.client`.

## Toolchain

- Kotlin 2.0.21, AGP 9.0.1, Gradle 9.1.0 (wrapper), Java 11
- Compose Compiler via `org.jetbrains.kotlin.plugin.compose` (bundled plugin, no separate version)
- Version catalog at `gradle/libs.versions.toml`
- `local.properties` is gitignored (SDK path — expected)
