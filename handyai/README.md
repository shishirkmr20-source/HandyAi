# HandyAi

On-device AI chat for Android. Runs Gemma/Llama models locally via MediaPipe LLM Inference API — no internet required, no accounts, no cloud.

## Features

- **ChatGPT-style UI** — multiple conversations, rename/delete, streaming responses
- **On-device inference** via MediaPipe LLM Inference API (Gemma 2B/7B, Falcon, Phi)
- **File upload** — attach PDF, DOCX, DOC, TXT, MD, CSV, JSON; HandyAi reads the text and answers questions about it
- **Web search toggle** — when enabled, queries DuckDuckGo (no API key) and feeds results as context
- **Text-to-speech** — global toggle for auto-read, plus per-message Speak button
- **Beautiful Material 3 UI** — light/dark/system themes, custom teal palette
- **Fully private** — conversations, files, and inference never leave your device

## Building

### Prerequisites

- JDK 17
- Android SDK 35 (compileSdk), NDK not required
- Gradle 8.10+ (wrapper included)

### Local dev

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK (signed with debug key for sideloading)
```

APKs land in `app/build/outputs/apk/{debug,release}/`.

### CI

GitHub Actions workflow at `.github/workflows/build-apk.yml` produces a signed release APK on every push to `main`.

## Tech stack

| Concern         | Library                                     |
|-----------------|---------------------------------------------|
| UI              | Jetpack Compose, Material 3                 |
| Navigation      | androidx.navigation-compose                 |
| Database        | Room (chat history + messages)              |
| Settings        | DataStore Preferences                       |
| LLM             | MediaPipe tasks-genai (LlmInference)        |
| TTS             | Android TextToSpeech                        |
| PDF parsing     | PdfBox-Android                              |
| DOCX parsing    | Apache POI XWPF / HWPF                      |
| Web search      | OkHttp + Jsoup (DuckDuckGo HTML endpoint)   |

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for third-party attributions.
