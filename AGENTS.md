# AGENTS.md — SnipSearch Engineering & Security Constitution

This file defines non-negotiable architectural, security, privacy, and build constraints for **SnipSearch** (`com.snipsearch`), a privacy-first, search-engine-agnostic "Circle to Search" assistant designed primarily for **GrapheneOS** and compatible with **Android 14–16 (API 34–36)**.

All AI coding agents (**Cursor**, **Codex**, and **Hermes**) and human contributors MUST strictly adhere to these rules on every task, commit, and pull request.

---

## 1. Core Architectural Invariants

1. **Trigger & Role Mechanism (`VoiceInteractionService` Only):**
   - SnipSearch operates strictly as a Digital Assistant (`android.permission.BIND_VOICE_INTERACTION` / `ROLE_ASSISTANT`) with a companion Quick Settings `TileService` that invokes `showSession()`.
   - **FORBIDDEN:** Never declare or use `AccessibilityService` (`BIND_ACCESSIBILITY_SERVICE`), `SYSTEM_ALERT_WINDOW`, or `MediaProjection` APIs.
2. **AOSP Trigger-Source Routing (Android 14–16):**
   - Inspect `args?.getInt("invocation_type", -1)` inside `VoiceInteractionSession.onShow(args: Bundle?, showFlags: Int)` using AOSP `AssistantInvocationEvent` constants:
     - `1` (`INVOCATION_TYPE_GESTURE`) & `7` (`INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS`): Launch the SnipSearch interactive overlay.
     - `5` (`INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS`): Route according to user preference:
       1. *Ignore / Disabled* (`hide()` immediately),
       2. *Delegate to Secondary Voice Assistant* (fire `Intent(Intent.ACTION_VOICE_COMMAND)` to user-selected package and call `hide()`), or
       3. *Trigger SnipSearch Overlay*.
3. **Single-APK + Scoped Network + GrapheneOS Network Toggle Degradation:**
   - The app is a single APK declaring `android.permission.INTERNET` to support the in-app Vanadium/System `WebView` bottom sheet and visual search uploads.
   - If the user revokes the **GrapheneOS Network toggle** (removing `AID_INET` / `GID 3003`), the app MUST NOT crash. It must detect offline state via `ConnectivityManager` / `SocketException`, disable the embedded `WebView` and remote image uploads, and degrade gracefully to offline text/QR extraction (`AssistStructure` + `PaddleOCR` + `ZXing`) and external browser `Intent.ACTION_VIEW` handoff.
4. **Isolated Process On-Device OCR (`android:isolatedProcess="true"`):**
   - Primary text extraction uses `AssistStructure.ViewNode` bounds intersection (zero ML overhead).
   - Pixel-baked text extraction runs **PaddleOCR v4 Mobile** (`det` + `rec` `.onnx` models bundled uncompressed in `src/main/assets/ocr/`) via `onnxruntime-android` (`>= 1.22.0`) inside an `android:isolatedProcess="true"` bound `Service` (`OcrIsolatedService`).
   - Pass cropped `Bitmap` data and `.onnx` model descriptors across the Binder boundary using `SharedMemory` (`ashmem` / `ParcelFileDescriptor`) and `AssetFileDescriptor`.

---

## 2. Strict Memory, Storage & Privacy Rules

1. **Zero Disk Persistence for Screen Captures:**
   - **NEVER** write full screenshots, cropped bitmaps, `AssistStructure` dumps, or extracted screen text to disk (`cacheDir`, `filesDir`, `externalCacheDir`, `File.createTempFile()`, `Context.openFileOutput()`, or SQLite/Room).
   - Keep all screen capture buffers strictly in RAM (`Bitmap`, `ByteArrayOutputStream`, `SharedMemory`).
2. **Immediate Uncropped Buffer Scrubbing:**
   - As soon as the user completes or adjusts a crop—or immediately when delegating a power-button press—release and zero out references to uncropped intermediate buffers.
   - Inside `VoiceInteractionSession.onHide()` and `onDestroy()`, explicitly recycle/nullify all `Bitmap` references and zero out any `ByteArray` image buffers (`bytes.fill(0)`).
3. **Metadata Stripping on Image Upload:**
   - Before uploading any cropped image, re-encode the cropped `Bitmap` into a fresh in-memory `WebP` or `PNG` byte array (`Bitmap.compress()`), ensuring zero EXIF or device metadata is transmitted.
4. **Hardware-Backed Secret Storage (`AndroidKeyStore`):**
   - Custom provider API keys, Bearer tokens, and custom authentication headers MUST be encrypted using a zero-dependency `AndroidKeyStore` (`AES/GCM/NoPadding`, 256-bit) wrapper with `.setUnlockedDeviceRequired(true)` and StrongBox (`PackageManager.FEATURE_STRONGBOX_KEYSTORE`) with automatic fallback to standard hardware TEE.
   - **FORBIDDEN:** Do not use the deprecated `androidx.security:security-crypto` library, and never store plaintext credentials in Jetpack `DataStore` or `SharedPreferences`.
5. **Respect `FLAG_SECURE`:**
   - When `onHandleScreenshot(bitmap: Bitmap?)` receives `null` (because the foreground window set `FLAG_SECURE`), display a brief non-intrusive toast/banner explaining that the screen is protected by `FLAG_SECURE` and call `hide()`.

---

## 3. Dependency & Supply-Chain Guardrails

1. **Forbidden Dependencies:**
   - **NO** Google Play Services (`com.google.android.gms.*`), Firebase (`com.google.firebase.*`), ML Kit (`com.google.mlkit.*`), or proprietary telemetry/analytics/crash-reporting SDKs (Sentry, Crashlytics, Bugsnag, Amplitude, etc.).
   - **NO** third-party HTTP client frameworks (`OkHttp`, `Retrofit`, `Ktor`) unless explicitly approved by the user. Use Android's built-in `javax.net.ssl.HttpsURLConnection` for REST/JSON Vision API calls and the System `WebView` for browser-based multipart searches.
   - **NO** native JNI libraries other than `com.microsoft.onnxruntime:onnxruntime-android` (`>= 1.22.0`, 16KB page-aligned) and `libandroidx.graphics.path.so`, which Jetpack Compose pulls in through `androidx.graphics:graphics-path` and loads at runtime (do not exclude it from packaging). QR/barcode decoding must use pure JVM/Kotlin `com.google.zxing:core`.
2. **Dependency Pinning & Checksum Verification:**
   - All dependencies must be declared in `gradle/libs.versions.toml` with exact version pins (no dynamic `+` versions or snapshot repositories).
   - Any addition or update in `gradle/libs.versions.toml` MUST be accompanied by updating `gradle/verification-metadata.xml` via:
     ```bash
     export GRADLE_USER_HOME="$(mktemp -d)"
     ./gradlew --write-verification-metadata sha256 help build
     ./gradlew --write-verification-metadata pgp,sha256 --export-keys help build
     ```
   - Both passes and the empty `GRADLE_USER_HOME` are required: with a warm cache, or with the `pgp,sha256` pass alone, Gradle omits some parent POMs and BOMs and the result fails on a clean machine. Confirm the result with `./gradlew --dependency-verification strict help build` from a second empty `GRADLE_USER_HOME`.
   - Commit `gradle/verification-metadata.xml` and `gradle/verification-keyring.keys` together. Do not hand-edit checksums or add `<trusted-artifacts>` / `<ignored-keys>` entries to make a build pass; an `<ignored-key>` may only be removed (when the key has become available and is in the keyring).
3. **16KB Page Size & MTE Compatibility (Android 15/16 + GrapheneOS):**
   - Every native `.so` packaged in the APK must have 16KB ELF segment alignment (`-Wl,-z,max-page-size=16384`).
   - `AndroidManifest.xml` must keep `android:memtagMode="sync"` enabled on `<application>`.
4. **Release Signing Isolation (Protected `release` Environment Only):**
   - Gradle MUST only ever produce **unsigned** release APKs. Release signing happens exclusively in the `sign-and-attest` job of `release.yml`, inside the protected GitHub `release` Environment (see `docs/plan.md` §5.2 and `SECURITY.md`).
   - **FORBIDDEN:** Never add a release `signingConfigs` block (or `storeFile` / `storePassword` / `keyAlias` / `keyPassword` values) to any Gradle file, and never make a Gradle build read signing material from `local.properties`, `keystore.properties`, `gradle.properties`, or environment variables. Debug builds use the Android Gradle Plugin's auto-generated debug keystore.
   - **FORBIDDEN:** AI coding agents must never generate, import, request, read, print, or commit a release keystore, its passwords, or any `release` Environment secret. The release key is created and held only by the human maintainer.

---

## 4. WebView & Network Security Rules

1. **Dual-Mode Visual Upload Pipeline (Anti-CAPTCHA & Agnostic):**
   - **Mode 1 — In-WebView Chromium `<form>` POST (for Browser Engines: Google Lens, Yandex, Bing, SearXNG):**
     - Load a minimal in-memory HTML bootstrap via `webView.loadDataWithBaseURL(providerOrigin, ...)` and serve the cropped image bytes from RAM via `WebViewClient.shouldInterceptRequest` into a `<form method="POST" enctype="multipart/form-data">`.
     - This preserves the authentic Vanadium/Chromium TLS (JA3/JA4) and HTTP/2/3 fingerprint and allows inline CAPTCHA challenges to be solved directly in the bottom sheet.
   - **Mode 2 — Scoped `HttpsURLConnection` (for REST / JSON / Vision LLM APIs):**
     - Execute `multipart/form-data` or JSON base64 `POST` requests strictly in memory with `HttpsURLConnection`, enforce TLS 1.3 / 1.2 via system trust anchors, and render the response or redirect URL in the bottom sheet.
2. **Smart Cookie & Session Isolation:**
   - Pre-seed stateless consent cookies (such as Google's `SOCS` cookie) in `CookieManager` to prevent EU/VPN GDPR consent walls from dropping image uploads.
   - Support two user-configurable cookie modes:
     - **Smart Retention (Default):** Retain anti-bot/CAPTCHA clearance cookies across searches with a configurable idle TTL and a 1-tap **"Clear Session"** broom button in the bottom-sheet header.
     - **Strict Ephemeral Mode (Opt-In):** Call `CookieManager.getInstance().removeAllCookies(null)`, `WebStorage.getInstance().deleteAllData()`, and `webView.clearCache(true)` on every `onHide()`.
3. **WebView Hardening:**
   - Disable file and content access inside the `WebView`:
     - `settings.allowFileAccess = false`
     - `settings.allowContentAccess = false`
     - `settings.javaScriptCanOpenWindowsAutomatically = false`
     - `settings.mediaPlaybackRequiresUserGesture = true`
   - Never register `@JavascriptInterface` bridges on untrusted remote origins.

---

## 5. Cursor, Codex & Hermes Workflow & Verification Rules

1. **Before Writing Code:**
   - Read `docs/plan.md` to confirm module boundaries, package structure, and current milestone requirements.
   - Keep changes scoped to a single milestone or package boundary (`assistant`, `extraction`, `extraction.isolated`, `search`, `security`, `storage`, `ui`) to enable conflict-free parallel execution across Cursor subagents, Codex tasks, and Hermes runs.
2. **Standard Verification Commands (Run Before Finishing Any Task):**
   - **Unit & Security Regression Tests:** `./gradlew testDebugUnitTest`
   - **Android Lint Security Gate:** `./gradlew lintRelease`
   - **Dependency Integrity Check:** `./gradlew --dependency-verification strict help`
   - **Release APK Assembly & 16KB Alignment Check:** `./gradlew assembleRelease`
3. **Testing Requirements:**
   - Every new domain parser (`AssistStructure` spatial tree, provider schema, URL/QR entity extractor, `AndroidKeyStore` cipher wrapper) must include deterministic JUnit / Robolectric unit tests.
   - Security invariants (zero disk writes during session lifecycle, buffer zeroization on `onHide()`, `WebView` file-access lockdown, and graceful fallback when `AID_INET` is revoked) must have dedicated regression tests.
4. **Harness-Specific Guidelines:**
   - **Cursor:** Leverage parallel subagents (`Task` / worktrees) per package after Milestone 1 is merged, and run `security-review` / `bugbot` subagents on branch diffs before merging.
   - **Codex:** Because Codex environments often run with network disabled after setup, never introduce unpinned dependencies mid-task without updating `gradle/libs.versions.toml` and `gradle/verification-metadata.xml`.
   - **Hermes:** Execute one milestone or package module at a time, using `./gradlew testDebugUnitTest lintRelease` as the mandatory completion gate before committing.
