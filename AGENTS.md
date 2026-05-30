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

- **Entrypoint**: `app/src/main/java/com/example/miauchat/MainActivity.kt` — contains ViewModel + all composables in one file.
- **ViewModel is manual** (no DI framework like Hilt/Koin). `MiauChatViewModel` takes a `Context` via a custom `ViewModelProvider.Factory` in `onCreate()`. Preserve the factory pattern if refactoring.
- **No XML layouts** for the UI — pure Jetpack Compose (Material 3) with a dark-terminal theme (`FontFamily.Monospace`, black background, blue accent).
- **Networking**: OkHttp direct calls (no Retrofit). Requests use `"stream": true` with SSE parsing (`data:` lines); non-streaming fallback via `parseNonStreaming()`.
- **Persistence**: Chat sessions serialized to JSON in `SharedPreferences` under key `"sessions"`. API URL/key/model also stored in prefs.

## Sensitive data

`app/src/androidTest/java/com/example/miauchat/MiauChatTest.kt` contains a **live API key** committed to the repo. Do not duplicate or expose it further.

## Toolchain

- Kotlin 2.0.21, AGP 9.0.1, Gradle 9.1.0 (wrapper), Java 11
- Compose Compiler via `org.jetbrains.kotlin.plugin.compose` (bundled plugin, no separate version)
- Version catalog at `gradle/libs.versions.toml`
- `local.properties` is gitignored (SDK path — expected)
