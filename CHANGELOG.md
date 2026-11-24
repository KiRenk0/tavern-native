# Changelog

All notable changes to **Tavern Native** from v0.84.0 through v1.0.0 (targeting full Android native parity with SillyTavern 1.19.1 `82b64a45`).

---

## [1.0.0] - 2026-11-24

### Signing & Upgrade Compatibility Notice
- **Release build signing**: `:app:assembleRelease` generates unsigned `arm64-v8a` and `universal` release APKs by default (`app/build.gradle.kts` does not hardcode keystore credentials). Sign them with your release certificate before installing on Android.
- **0.78.0–0.83.0 (`-legacy-key-*.apk`) vs 0.48.0**: Previous `dist/` APKs for 0.78.0–0.83.0 share the `CN=Tavern Native` signing certificate (`SHA-256: 57ef0b9aa91ed387bdf410c4cb415a757563d241a1c281b14532a7d505bfeba6`). Earlier 0.48.0 builds used a different certificate. Android requires the same signing certificate for in-place upgrades; if moving between certificates (e.g., 0.48.0 → 1.0.0 or legacy-key → newly signed 1.0.0), export an **Account Backup ZIP** (`导出本机账户备份 ZIP`) and/or **Settings Snapshot** first, uninstall the previous APK, install 1.0.0, and restore your backup.

### 1.0.0 Release Acceptance, Modularization & Hardening
- **Modularization**:
  - Split `MediaCoordinator.kt` (~54 KB) into 4 cohesive sub-coordinators under `app.tavern.nativeclient.media`: `VoiceNarrationCoordinator`, `MediaCaptionCoordinator`, `DrawingVideoCoordinator`, and `ExpressionBackgroundCoordinator`, fronted by a thin `MediaCoordinator` facade.
  - Split `ServicesProtocolTest.kt` (~101 KB, 39 tests) into 6 domain-focused test suites (`NovelAiProtocolTest`, `ScriptGenerationProtocolTest`, `TokenizationAndLogprobsProtocolTest`, `WebSearchAndMediaProtocolTest`, `ProviderProtocolsWireTest`, `TextCompletionFormattingProtocolTest`) with shared `ServicesProtocolTestSupport`.
- **Cross-Module Integration**:
  - Wired `TavernEventBus` across all 10 SillyTavern core events (`MESSAGE_SENT`, `MESSAGE_RECEIVED`, `MESSAGE_EDITED`, `MESSAGE_DELETED`, `MESSAGE_SWIPED`, `GENERATION_STARTED`, `GENERATION_ENDED`, `CHAT_CHANGED`, `WORLDINFO_UPDATED`, `CHARACTER_EDITED`).
  - Wired live `DynamicAudioEngine` (`/bgm`, `/ambient`) and `/websearch` into `TavernScriptHost`, `ScriptCoordinator`, and `ChatAutocomplete`.
  - Wired `ExtensionHubConfig` portable JSON export/import into both `SettingsSnapshots` and full Account Backup ZIP (`OriginalBackupExport` & `EmbeddedLibrary`).
- **Stress & Migration Verification**:
  - Added `LongChatAndMigrationStressTest` covering 10,000–12,000 message pagination windows, reverse depth computation, cross-message archive search, JSONL round-trip latency/memory bounds, WorldBook scanning, and full SQLite schema migration chain convergence (`v1..v9 -> v10`).
- **Release Metadata**: Bumped `versionName` to `1.0.0` (`versionCode = 100`) and updated `README.md` to reflect full 1.0.0 SillyTavern 1.19.1 parity.

---

## [0.88.0 – 0.91.0] - Extensions Ecosystem, TavernHelper, MVU & Android Native Enhancements
- **Event Bus & MVU Engine**:
  - Added `TavernEventBus` with `on`/`once`/`removeListener`/`emit`, `SharedFlow` stream, and event history ring buffer for all 10 core events + custom events.
  - Extracted and expanded `MvuEngine` with `[InitVar]` card/WorldBook initialization (JSON/YAML), `MvuSchema` validation, `<UpdateVariable>` (`_.set`, `_.add`, `_.assign`, `_.unset`, full RFC 6902 JSON Patch), and deterministic replay/rollback on swipe, edit, and delete.
- **Complete `window.TavernHelper` Card Sandbox**:
  - Added variable CRUD, message CRUD, WorldBook CRUD, generation triggers (`generate`, `generateRaw`), and event subscriptions with WebView lifecycle cleanup.
  - Added `CardSandboxPolicy` (default zero-network offline isolation with optional per-card permissions and domain allowlist) and `CardCssScoper` enforcing scoped CSS inside `#tavern-card-root` and ST 1.19.1 `<style>` hardening.
- **Unified Extensions Hub**:
  - Added `ExtensionRegistry` and `ExtensionHubScreen` in Settings with secret-stripping portable JSON export/import.
- **Built-in Extensions**:
  - **Web Search Extension** (`WebSearchExtension`, `WebSearchSettingsScreen`): SearXNG, Tavily, SerpApi, DuckDuckGo, and Brave Search backends with manual `/websearch`, keyword/regex, and always-on trigger modes plus configurable prompt injection.
  - **Dynamic Audio Engine** (`DynamicAudioEngine`, `DynamicAudioScreen`): dual-channel BGM & Ambient playback with loop/volume/crossfade, expression/background/keyword auto-switching rules, and `/bgm` / `/ambient` slash commands.
  - **Assets Downloader** (`AssetsManager`, `AssetsScreen`): browse/search/download official SillyTavern themes, backgrounds, characters, and sprites with HTTPS host allowlist, path-traversal checks, and SHA-256 verification.
- **Android Native Enhancements**:
  - Added `GenerationForegroundService` (`dataSync` foreground service with ongoing notification and Stop action) so long LLM/image/video generations survive backgrounding.
  - Added `ShareImportHandler` and `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent filters to import `.png`, `.json`, `.charx`, and `.byaf` character cards directly from the Android Share Sheet.

---

## [0.87.0] - Extended Image Backends, Voice/RVC/Local Whisper, External Sources & Media Migration
- **Extended Image Generation Backends** (`ExtendedImageBackends`): Added Pollinations, Stability AI, Draw Things, SD.Next, Together AI, Imagen / Vertex, xAI, NanoGPT, and HuggingFace Inference adapters, selectable in the drawing dialog and via `/sd source=...`.
- **Voice & Speech Expansion**:
  - Added **Azure Speech TTS**, **AllTalk TTS**, and **Kokoro TTS** (`ExtendedVoiceBackends`) with voice listing, preview, per-character assignment, and Keystore-encrypted keys.
  - Added **RVC Voice Conversion** (`RvcVoiceConverter`) post-processing pipeline with configurable endpoint, model, pitch shift, index rate, and filter radius.
  - Added on-device **Local Whisper STT** (`LocalWhisperStt`) using ONNX Runtime with model weight management and graceful fallback.
- **Data Bank External Sources** (`ExternalWikiSources`): Added importers for **YouTube** subtitles/transcripts, **Fandom Wiki**, and **MediaWiki** (Wikipedia and custom wikis).
- **Backup & Migration Completeness**: Added automatic restore/export of user persona avatars (`User Avatars/`) and additional SillyTavern media files with ST 1.19.1 path-traversal, Windows reserved-name, and symlink safeguards.

---

## [0.86.0] - Text/Chat Backends, Multi-Key Rotation & Additional Tokenizers
- **Additional Text Completion Backends** (`AdditionalTextBackends`, `TabbyModelControlScreen`): Native protocol adapters, model discovery, remote tokenization, and sampling maps for **TabbyAPI** (including `/v1/model/load` & `/v1/model/unload` Compose control dialog), **vLLM**, **Aphrodite**, **text-generation-webui** (Oobabooga), **Featherless**, **Infermatic**, **DreamGen**, **Mancer**, and **HuggingFace / TGI**.
- **Chat Completion Provider Templates** (`ChatProviderTemplates`): Dedicated presets for **Mistral**, **Cohere**, **Vertex AI**, **Azure OpenAI**, **xAI (Grok)**, **Groq**, **Perplexity**, **Moonshot**, **Zhipu (GLM)**, and **NanoGPT**.
- **Multi-Key Rotation** (`MultiKeyManager`): Encrypted multi-key storage per provider with Round-Robin and Failover-on-Error (HTTP 401/402/403/429 & quota cooldown) policies and full management dialog.
- **Additional Tokenizers** (`AdditionalTokenizers`): Calibrated native tokenizers with deterministic IDs and model-ID auto-matching for **Mistral/Nemo**, **Qwen**, **Gemma**, **Claude**, **Command-R**, **Llama 1/2**, and **Yi**.

---

## [0.84.0 – 0.85.0] - SillyTavern 1.19.x Parity & Complete STscript / Quick Reply Tools
- **SillyTavern 1.19.x Parity**:
  - Added `{{maxContextTokens}}`, `{{maxResponseTokens}}`, `{{defaultExpression}}`, `{{lastExpression}}`, and `{{availableExpressions}}` macros.
  - Added `/reasoning-collapse` (`/reasoning-expand`) command and reasoning block collapse state.
  - Extended `/input`, `/popup`, and `/buttons` with `placeholder`, `tooltip`, and `icon` arguments.
  - Added Background Library virtual folders with grid/list view, breadcrumb navigation, folder CRUD, move-to-folder, and thumbnails.
  - Added native WCAG AAA high-contrast mode and reduced-motion accessibility options.
  - Added automatic WorldBook rename relinking across characters, groups, personas, and chats.
- **Complete STscript & Quick Reply Tools**:
  - Added prompt injections (`/inject`, `/listinjects`, `/flushinject` with `ephemeral`, `scan`, `filter`, `outlet`).
  - Added `/abort`, `/fuzzy`, and `/breakpoint` integrated with `QuickReplyDebugger` step-debugger UI and STscript syntax highlighting in the Quick Reply editor.
  - Added persona/chat/tag commands (`/persona`, `/persona-lock`, `/tempchat`, `/tag-add`, `/tag-remove`, `/tag-exists`, `/tag-list`), Quick Reply CRUD (`/qr-create`, `/qr-update`, `/qr-delete`, `/qr-get`, `/qr-list`, `/qrset-create`, `/qrset-delete`, `/qrset-list`, `/qr-arg`), config/UI commands (`/api`, `/context`, `/instruct`, `/tokenizer`, `/vn`, `/theme`), and complete string/math utilities (`/upper`, `/lower`, `/trim`, `/len`, `/split`, `/join`, `/floor`, `/ceil`, `/abs`, `/min`, `/max`, `/round`, `/rand`, `/add`, `/sub`, `/mul`, `/div`, `/mod`, `/pow`).
  - Registered all new commands and macros in `ChatAutocomplete`.
