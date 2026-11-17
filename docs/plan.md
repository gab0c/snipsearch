# SnipSearch — Master Architecture & Implementation Plan (`docs/plan.md`)

## 1. Project Overview & Goals

**SnipSearch** (`com.snipsearch`) is an open-source, search-engine-agnostic "Circle to Search" assistant for **GrapheneOS** and **Android 14–16 (API 34–36)**.

### Core Value Proposition
- **Native Assistant Trigger Without Accessibility Abuse:** Uses Android's `VoiceInteractionService` (`ROLE_ASSISTANT`) to receive an on-demand screenshot (`onHandleScreenshot`) and view hierarchy (`onHandleAssist`) strictly at the moment the user invokes it—requiring **zero** `AccessibilityService`, **zero** `SYSTEM_ALERT_WINDOW`, and **zero** persistent background observation.
- **Coexistence with Secondary Voice Assistants:** Uses AOSP `AssistantInvocationEvent` trigger-source routing (`invocation_type`) so long-pressing the navigation handle opens SnipSearch while long-pressing the power button can delegate directly to Gemini, Google Assistant, Dicio, Home Assistant, or do nothing.
- **Unified In-App Bottom-Sheet UX:** Mounts a Jetpack Compose `BottomSheetScaffold` hosting the system `WebView` (Vanadium WebView on GrapheneOS) directly inside the `VoiceInteractionSession` window so users can resize the crop box, add follow-up text (*"Add to your search"*), and browse results seamlessly.
- **100% Offline Text, Entity & QR Extraction:** Extracts native UI text instantaneously from `AssistStructure`, decodes QR/barcodes offline via pure-Kotlin/JVM `ZXing`, and runs **PaddleOCR v4 Mobile** via **ONNX Runtime** inside an air-gapped `android:isolatedProcess="true"` sandbox for pixel-baked text.
- **GrapheneOS Hardened & Reproducible:** Supports synchronous hardware Memory Tagging (`memtagMode="sync"`), 16KB page sizes, graceful offline degradation when the GrapheneOS Network toggle is disabled, `AndroidKeyStore` StrongBox/TEE encryption for custom API keys, and reproducible multi-ABI GitHub Releases with SLSA Level 3 provenance and `apksigcopier` verification.

---

## 2. Locked Architectural Decisions Summary

| Area | Locked Decision | Rationale |
| :--- | :--- | :--- |
| **Target Platform** | Android 14–16 (API 34–36), 100% Kotlin + Jetpack Compose | Modern `VoiceInteractionSession`, `SharedMemory`, and 16KB page-alignment support; zero legacy bloat. |
| **Assistant Role & Trigger** | `VoiceInteractionService` (`ROLE_ASSISTANT`) + AOSP `invocation_type` routing + Quick Settings `TileService` (`showSession()`) | Zero `AccessibilityService` or `MediaProjection` permissions; coexists with power-button voice assistants. |
| **Process & Network Model** | Single APK with `INTERNET` + `android:isolatedProcess="true"` OCR service + GrapheneOS Network toggle degradation | Enables native single-window bottom-sheet `WebView` UX while sandboxing native C++ OCR inference and supporting air-gapped OS network revocation. |
| **Text & Entity Extraction** | Tier 1: `AssistStructure` ViewNode tree<br>Tier 2: Bundled **PaddleOCR v4 Mobile** (`onnxruntime-android >= 1.22.0`) in `/assets/ocr/`<br>Tier 3: Offline `com.google.zxing:core` QR/barcode + Regex URL/Email/Phone chips | Zero disk I/O, zero Play Services, 16KB page-aligned, instant offline utility even without network. |
| **Visual Upload Pipeline** | **Hybrid Pipeline:**<br>1. In-WebView Chromium `<form>` POST (via `shouldInterceptRequest` RAM stream) for browser engines<br>2. `HttpsURLConnection` for REST/JSON Vision APIs | Preserves authentic Vanadium/Chromium TLS (JA3/JA4) & HTTP/2/3 fingerprint to prevent VPN CAPTCHAs and allows inline CAPTCHA solving. |
| **Cookie & Anti-CAPTCHA Policy** | **Smart Cookie Retention (Default)** + Pre-seeded `SOCS` consent cookie + 1-tap "Clear Session" broom button + "Strict Ephemeral" toggle | Prevents endless CAPTCHA loops on Mullvad/Proton VPNs and bypasses EU GDPR consent walls that drop image POST payloads. |
| **Post-Gesture UX** | **Immediate Visual Search by Default** (Stock Pixel UX) + Unified *"Add to your search"* multimodal bar + selectable OCR/Assist text overlay (with *"Confirm Before Upload"* toggle in Settings) | Matches native Circle to Search responsiveness while keeping offline text selection and multimodal query refinement front-and-center. |
| **Search Providers** | Built-in Presets (Google Lens, Yandex, Bing Visual, SearXNG, DuckDuckGo, Brave, Startpage, Kagi) + Custom Provider & Vision LLM Builder | Works out-of-the-box for all users while remaining 100% search-engine agnostic. |
| **Secret Storage** | Zero-dependency `AndroidKeyStore` (`AES-256-GCM`, StrongBox with TEE fallback, `setUnlockedDeviceRequired(true)`) stored in Jetpack `DataStore` | Protects custom API keys/headers against AFU lock-screen state, LFI, and rooted/recovery file extraction across GrapheneOS and Stock Android. |
| **Release & Supply Chain** | ABI Splits (`arm64-v8a`, `x86_64`) + Universal APK, signed in a protected GitHub `release` Environment + SLSA v1.0 provenance + `apksigcopier` reproducibility | Keeps Pixel/GrapheneOS APK ~18 MB smaller, isolates signing keys from coding agents, and enables cryptographic verification via Obtainium/AppVerifier. |

---

## 3. System Architecture & Module Layout

```
snipsearch/
├── AGENTS.md
├── docs/
│   └── plan.md
├── gradle/
│   ├── libs.versions.toml
│   └── verification-metadata.xml
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── aidl/com/snipsearch/ipc/
│       │   │   ├── IOcrIsolatedService.aidl
│       │   │   └── OcrTextBlockParcelable.aidl
│       │   ├── assets/ocr/
│       │   │   ├── ch_PP-OCRv4_det_infer.onnx
│       │   │   ├── ch_PP-OCRv4_rec_infer.onnx
│       │   │   └── ppocr_keys_v1.txt
│       │   ├── kotlin/com/snipsearch/
│       │   │   ├── SnipSearchApp.kt
│       │   │   ├── assistant/
│       │   │   │   ├── SnipVoiceInteractionService.kt
│       │   │   │   ├── SnipVoiceInteractionSessionService.kt
│       │   │   │   ├── SnipVoiceInteractionSession.kt
│       │   │   │   ├── SnipRecognitionServiceStub.kt
│       │   │   │   ├── InvocationRouter.kt
│       │   │   │   └── SnipQuickSettingsTileService.kt
│       │   │   ├── extraction/
│       │   │   │   ├── AssistStructureParser.kt
│       │   │   │   ├── EntityAndQrDetector.kt
│       │   │   │   ├── OcrClientCoordinator.kt
│       │   │   │   └── isolated/
│       │   │   │       ├── OcrIsolatedService.kt
│       │   │   │       ├── PaddleOcrEngine.kt
│       │   │   │       └── CtcBeamDecoder.kt
│       │   │   ├── search/
│       │   │   │   ├── SearchProvider.kt
│       │   │   │   ├── BuiltInProviders.kt
│       │   │   │   ├── CustomProviderSchema.kt
│       │   │   │   ├── WebViewFormUploadBridge.kt
│       │   │   │   ├── RestVisionApiClient.kt
│       │   │   │   └── CookieSessionManager.kt
│       │   │   ├── security/
│       │   │   │   ├── KeystoreSecretCipher.kt
│       │   │   │   ├── MemoryScrubber.kt
│       │   │   │   └── NetworkCapabilityMonitor.kt
│       │   │   ├── storage/
│       │   │   │   ├── SettingsRepository.kt
│       │   │   │   └── ProviderConfigStore.kt
│       │   │   └── ui/
│       │   │       ├── overlay/
│       │   │       │   ├── OverlayScreen.kt
│       │   │       │   ├── GestureLassoCanvas.kt
│       │   │       │   ├── AdjustableCropBox.kt
│       │   │       │   ├── SelectableTextLayer.kt
│       │   │       │   ├── EntityActionChipsRow.kt
│       │   │       │   ├── MultimodalSearchBar.kt
│       │   │       │   └── SearchWebViewBottomSheet.kt
│       │   │       └── settings/
│       │   │           ├── SettingsActivity.kt
│       │   │           ├── AssistantSetupCard.kt
│       │   │           ├── PowerButtonRoutingSection.kt
│       │   │           ├── ProviderManagerScreen.kt
│       │   │           └── PrivacyAndSecurityScreen.kt
│       │   └── res/
│       │       └── xml/
│       │           ├── interaction_service.xml
│       │           ├── recognition_service.xml
│       │           ├── network_security_config.xml
│       │           └── data_extraction_rules.xml
│       └── test/
│           └── kotlin/com/snipsearch/...
```

---

## 4. Detailed Subsystem Specifications

### 4.1 Assistant Lifecycle, Trigger Routing & Quick Settings Tile

#### Manifest Declarations (`AndroidManifest.xml`)
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- Visibility query so user can pick installed voice assistants for Power Button delegation -->
    <queries>
        <intent>
            <action android:name="android.intent.action.VOICE_COMMAND" />
        </intent>
        <intent>
            <action android:name="android.intent.action.ASSIST" />
        </intent>
    </queries>

    <application
        android:name=".SnipSearchApp"
        android:label="@string/app_name"
        android:memtagMode="sync"
        android:allowBackup="false"
        android:fullBackupContent="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:networkSecurityConfig="@xml/network_security_config"
        android:usesCleartextTraffic="false"
        android:supportsRtl="true">

        <!-- 1. VoiceInteractionService (ROLE_ASSISTANT entry point) -->
        <service
            android:name=".assistant.SnipVoiceInteractionService"
            android:label="@string/app_name"
            android:permission="android.permission.BIND_VOICE_INTERACTION"
            android:exported="true">
            <meta-data
                android:name="android.voice_interaction"
                android:resource="@xml/interaction_service" />
            <intent-filter>
                <action android:name="android.service.voice.VoiceInteractionService" />
            </intent-filter>
        </service>

        <!-- 2. VoiceInteractionSessionService -->
        <service
            android:name=".assistant.SnipVoiceInteractionSessionService"
            android:permission="android.permission.BIND_VOICE_INTERACTION"
            android:exported="false" />

        <!-- 3. Mandatory Stub RecognitionService required by Android RoleManager -->
        <service
            android:name=".assistant.SnipRecognitionServiceStub"
            android:permission="android.permission.BIND_REMOTEVIEWS"
            android:exported="true">
            <intent-filter>
                <action android:name="android.speech.RecognitionService" />
            </intent-filter>
            <meta-data
                android:name="android.speech"
                android:resource="@xml/recognition_service" />
        </service>

        <!-- 4. Isolated Process On-Device OCR Sandbox (Zero Network / Zero Storage) -->
        <service
            android:name=".extraction.isolated.OcrIsolatedService"
            android:isolatedProcess="true"
            android:useAppZygote="false"
            android:exported="false" />

        <!-- 5. Quick Settings Tile (triggers showSession() when ROLE_ASSISTANT is active) -->
        <service
            android:name=".assistant.SnipQuickSettingsTileService"
            android:label="@string/tile_label"
            android:icon="@drawable/ic_qs_snipsearch"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>

        <activity
            android:name=".ui.settings.SettingsActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

#### AOSP Invocation Routing (`InvocationRouter.kt`)
Android 14–16 passes `"invocation_type"` inside `args: Bundle?` to `VoiceInteractionSession.onShow(args, showFlags)`:
- `INVOCATION_TYPE_GESTURE = 1` (Navigation pill long-press / corner swipe)
- `INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS = 7` (3-button navigation Home long-press)
- `INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 5` (Long-press Power button)
- `INVOCATION_TYPE_QUICK_SETTINGS_TILE = 100` (Custom internal constant passed when triggered via `SnipQuickSettingsTileService`)

When `invocationType == 5` (`POWER_BUTTON_LONG_PRESS`):
1. If user setting is `PowerButtonAction.IGNORE`: immediately scrub any queued screenshot/assist references and call `hide()`.
2. If user setting is `PowerButtonAction.DELEGATE`:
   - Scrub any queued screenshot/assist references.
   - Launch `Intent(Intent.ACTION_VOICE_COMMAND).setPackage(delegatedPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)` (with fallback to `Intent.ACTION_ASSIST`).
   - Call `hide()`.
3. If user setting is `PowerButtonAction.OPEN_SNIPSEARCH`: proceed to render the SnipSearch overlay.

---

### 4.2 Interactive Overlay, Gesture Capture & Post-Gesture UX

1. **Frozen Screen Presentation:**
   - `onHandleScreenshot(bitmap: Bitmap?)` receives the hardware-composited screen bitmap.
   - If `bitmap == null` (`FLAG_SECURE` active on foreground window), show a non-intrusive security pill (*"Screen capture blocked by app security policy (FLAG_SECURE)"*) for 1.5s and call `hide()`.
   - Otherwise, render the frozen screenshot full-screen inside a Compose `Canvas` with a subtle dim scrim and animated shimmer border.
2. **Freeform Circle / Lasso / Tap Gesture (`GestureLassoCanvas.kt`):**
   - Tracks `pointerInput` drag paths (`androidx.compose.ui.graphics.Path`).
   - Computes the bounding `RectF` of the user's stroke (padded slightly and coerced to screen bounds) and transitions smoothly into an `AdjustableCropBox` with 4 draggable corner handles.
   - **Memory Hygiene:** Once the user lifts their finger and the initial crop is created, keep only the full screenshot in a guarded session holder while the user is actively adjusting crop handles; as soon as the session hides or the user locks the crop, recycle and zero-out the uncropped buffer.
3. **Post-Gesture Immediate Dual Pipeline:**
   - **Default Mode (`Immediate Auto-Search`):**
     - Immediately crops the selected `Rect` into an in-memory `Bitmap` and strips metadata by encoding to an in-memory `WebP` (`quality = 90`) or `PNG` `ByteArray`.
     - Slides up the `SearchWebViewBottomSheet` to a 55% peek height and initiates the visual search upload to the active provider.
     - Simultaneously runs the **Offline Extraction Pipeline** (`AssistStructureParser` -> `EntityAndQrDetector` -> `OcrClientCoordinator`) and overlays:
       - Interactive selectable text highlights directly over the cropped region (`SelectableTextLayer`).
       - Floating `EntityActionChipsRow` (e.g., **"Open URL"**, **"Copy QR"**, **"Copy All Text"**, **"Email"**, **"Call"**).
       - The unified **"Add to your search"** (`MultimodalSearchBar`) docked at the top of the bottom sheet.
   - **Privacy Toggle (`Confirm Before Uploading Pixels`):**
     - Available in Settings for users who only want network activity when explicitly confirmed. When enabled, circling runs offline text/QR extraction immediately and waits for a tap on the **"Visual Search"** pill before opening the `WebView` bottom sheet.

---

### 4.3 Three-Tier Offline Text, QR & Entity Extraction Pipeline

#### Tier 1: `AssistStructureParser` (Zero-Overhead Native UI Text)
- Traverses `AssistStructure.WindowNode` and `AssistStructure.ViewNode` trees captured in `onHandleAssist(state: AssistState)`.
- Computes absolute screen coordinates `(left, top, right, bottom)` for each `ViewNode` by accumulating parent offsets (`node.left + parentX - parentScrollX`).
- Filters visible nodes (`visibility == View.VISIBLE`, `alpha > 0f`) whose bounding box intersects the user's crop `Rect` by at least 30% of the node's area.
- Extracts `node.text` and `node.contentDescription` with word/line bounding boxes.

#### Tier 2: `EntityAndQrDetector` (Instant Offline QR & Entities)
- **QR & Barcode Decoding:** Passes the cropped `Bitmap`'s `IntArray` luminance pixels to `com.google.zxing.MultiFormatReader` (configured for `QR_CODE`, `DATA_MATRIX`, `AZTEC`, `EAN_13`, `CODE_128`) on `Dispatchers.Default`. Zero native C++ code.
- **Entity Regex / `TextClassifier`:** Scans extracted text (from Tier 1 and Tier 3) for URLs (`https?://...`), email addresses, phone numbers, and coupon/tracking codes, surfacing instant 1-tap action chips.

#### Tier 3: `OcrIsolatedService` (Sandboxed PaddleOCR v4 Mobile via ONNX Runtime)
- Invoked automatically when Tier 1 finds no `AssistStructure` text nodes inside the crop (e.g., images, videos, canvas, games, PDFs) or when the user taps **"Force OCR"**.
- **IPC Contract (`IOcrIsolatedService.aidl`):**
  ```aidl
  package com.snipsearch.ipc;

  import android.os.SharedMemory;
  import android.content.res.AssetFileDescriptor;
  import com.snipsearch.ipc.OcrTextBlockParcelable;

  interface IOcrIsolatedService {
      void initializeModels(
          in AssetFileDescriptor detModelFd,
          in AssetFileDescriptor recModelFd,
          in AssetFileDescriptor keysDictFd
      );
      List<OcrTextBlockParcelable> recognizeCrop(
          in SharedMemory argbPixelsAshmem,
          int width,
          int height,
          int stride
      );
  }
  ```
- **Why `SharedMemory` + `AssetFileDescriptor`?**
  - An `android:isolatedProcess="true"` service runs under an ephemeral isolated UID with **zero network access**, **zero filesystem access**, and **zero permissions**.
  - Passing uncompressed `.onnx` model `AssetFileDescriptor`s (`noCompress += "onnx"`) and a read-only `SharedMemory` buffer containing the cropped ARGB pixels allows the isolated process to run `OrtEnvironment` and `OrtSession` purely in RAM with **zero temp files** and **zero disk I/O**.

---

### 4.4 Search-Engine Agnostic Provider Architecture & Multimodal Bar

#### Declarative Provider Schema (`SearchProvider.kt`)
```kotlin
@Serializable
sealed interface SearchProviderConfig {
    val id: String
    val displayName: String
    val supportsText: Boolean
    val supportsVisual: Boolean
    val supportsMultimodalPrompt: Boolean

    @Serializable
    data class BrowserFormVisualProvider(
        override val id: String,
        override val displayName: String,
        val uploadEndpointUrl: String,
        val originUrl: String,
        val fileFormFieldName: String,
        val extraFormFields: Map<String, String> = emptyMap(),
        val multimodalQueryFieldName: String? = null,
        val textSearchUrlTemplate: String? = null,
        override val supportsText: Boolean = textSearchUrlTemplate != null,
        override val supportsVisual: Boolean = true,
        override val supportsMultimodalPrompt: Boolean = multimodalQueryFieldName != null
    ) : SearchProviderConfig

    @Serializable
    data class RestVisionProvider(
        override val id: String,
        override val displayName: String,
        val endpointUrl: String,
        val requestFormat: VisionRequestFormat, // MULTIPART_REDIRECT_JSON or OPENAI_CHAT_COMPLETIONS
        val modelName: String? = null,
        val systemPrompt: String? = null,
        val jsonRedirectPath: String? = null,
        val encryptedApiKeyAlias: String? = null,
        val customHeaders: Map<String, String> = emptyMap(),
        override val supportsText: Boolean = true,
        override val supportsVisual: Boolean = true,
        override val supportsMultimodalPrompt: Boolean = true
    ) : SearchProviderConfig

    @Serializable
    data class TextOnlyProvider(
        override val id: String,
        override val displayName: String,
        val urlTemplate: String, // e.g., "https://duckduckgo.com/?q=%s"
        override val supportsText: Boolean = true,
        override val supportsVisual: Boolean = false,
        override val supportsMultimodalPrompt: Boolean = false
    ) : SearchProviderConfig
}
```

#### Built-In Out-of-the-Box Presets (`BuiltInProviders.kt`)
1. **Google Lens (Visual + Multimodal + Text):**
   - Upload Endpoint: `https://lens.google.com/v3/upload`
   - File Field: `encoded_image`
   - Multimodal Follow-Up Query Field: `q` (passes the user's *"Add to your search"* text alongside the image upload or updates the query inside the bottom sheet).
   - Pre-seeds stateless `SOCS=CAESHAgBEhJnd3NfMjAyMzA4MTAtMF9SQzIaAmVuIAEaBgiAo_CmBg` cookie on `.google.com` to bypass EU/VPN GDPR interstitials.
2. **Yandex Visual Search:**
   - Upload Endpoint: `https://yandex.com/images/touch/search?rpt=imageview` (Multipart field `upfile`).
3. **Bing Visual Search:**
   - Upload Endpoint: `https://www.bing.com/images/search?view=detailv2&iss=sbiupload` (Multipart field `imageBin`).
4. **SearXNG (Configurable Instance URL — Visual + Text):**
   - Supports standard SearXNG `GET /search?q=%s` and visual/reverse-image proxy endpoints.
5. **OpenAI-Compatible Vision API (Ollama / OpenWebUI / vLLM / Custom):**
   - Sends base64 WebP image + optional *"Add to your search"* prompt to `/v1/chat/completions` and renders formatted Markdown responses natively inside the bottom sheet.
6. **Text Search Presets:**
   - **DuckDuckGo**, **Brave Search**, **Startpage**, **Kagi**.

#### Hybrid Upload Pipeline & Anti-CAPTCHA Execution (`WebViewFormUploadBridge.kt`)
To avoid `HttpsURLConnection` TLS fingerprint mismatches (JA3/JA4) that trigger Google/Yandex/Cloudflare CAPTCHAs on VPNs:
1. When executing a `BrowserFormVisualProvider` upload, `WebViewFormUploadBridge` registers a one-time in-memory token (`https://<provider-origin>/__snipsearch_internal_blob__/<random_uuid>.webp`) holding the cropped `ByteArray`.
2. It calls `webView.loadDataWithBaseURL(providerOrigin, bootstrapHtml, "text/html", "UTF-8", null)`.
3. Inside `bootstrapHtml`, a minimal script fetches `/__snipsearch_internal_blob__/<uuid>.webp` (intercepted strictly in RAM by `shouldInterceptRequest`, which immediately deletes the one-time token upon read), populates a `DataTransfer` file input on `<form method="POST" enctype="multipart/form-data">`, and calls `form.submit()`.
4. Chromium executes a real top-level navigation with authentic Vanadium BoringSSL TLS & HTTP/2/3 framing. If a CAPTCHA is presented on a shared VPN IP, the user solves it directly inside the bottom sheet and the clearance cookie is preserved according to the **Smart Cookie Retention** policy.

---

### 4.5 Hardware-Backed Secret Storage (`KeystoreSecretCipher.kt`)

For Custom Providers and Vision APIs requiring API keys or custom headers:
1. Generate a 256-bit AES-GCM master key in `"AndroidKeyStore"` under alias `"snipsearch_provider_secrets_v1"`:
   ```kotlin
   val specBuilder = KeyGenParameterSpec.Builder(
       KEY_ALIAS,
       KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
   )
       .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
       .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
       .setKeySize(256)
       .setUnlockedDeviceRequired(true)

   if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
       specBuilder.setIsStrongBoxBacked(true)
   }
   ```
2. Include a safe fallback catch for `StrongBoxUnavailableException` to regenerate in standard hardware TEE on non-Pixel devices.
3. Encrypt each secret with a fresh 12-byte IV (`Cipher.getInstance("AES/GCM/NoPadding")`, 128-bit auth tag) and persist `base64(iv + ciphertext)` inside Jetpack `DataStore`.

---

## 5. Supply Chain, Reproducible Builds & GitHub CI/CD Architecture

### 5.1 Gradle Reproducibility & ABI Split Configuration (`app/build.gradle.kts`)
```kotlin
android {
    namespace = "com.snipsearch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.snipsearch"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Keep ONNX models uncompressed for zero-copy AssetFileDescriptor IPC to isolatedProcess
    androidResources {
        noCompress += listOf("onnx", "txt")
    }

    // Disable Google Play encrypted dependency block for F-Droid / Obtainium / apksigcopier reproducibility
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Multi-ABI Splits + Universal APK (~18 MB smaller arm64-v8a APK for Pixel/GrapheneOS)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // Enforce 16KB ELF page alignment & uncompressed page-aligned .so packaging
            useLegacyPackaging = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        error += setOf(
            "ExportedService",
            "UnsafeImplicitIntentLaunch",
            "TrustAllX509TrustManager",
            "BadHostnameVerifier",
            "SetJavaScriptEnabled",
            "HardcodedDebugMode"
        )
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
```

### 5.2 GitHub Actions Workflows (`.github/workflows/`)

All GitHub Actions MUST be pinned to full 40-character commit SHAs with top-level `permissions: contents: read`.

1. **`ci.yml` (Every Push & Pull Request):**
   - Verifies Gradle dependency checksums (`./gradlew --verification-mode strict help`).
   - Runs Android Lint security gate (`./gradlew lintRelease`).
   - Runs JUnit / Robolectric unit & security regression tests (`./gradlew testDebugUnitTest`).
   - Verifies 16KB ELF alignment on all packaged `.so` files using `llvm-objdump -p` (`align 2**14` or `2**16`).
   - Runs `actions/dependency-review-action` on PRs.
2. **`codeql.yml` (Push to `main`, PRs, Weekly Schedule):**
   - Runs GitHub CodeQL (`java-kotlin` security-extended suite).
3. **`release.yml` (Tag Push `v*` — Protected `release` Environment):**
   - **Job 1 (`build-unsigned`):** Runs in a clean runner with zero secrets. Builds deterministic unsigned release APKs (`arm64-v8a`, `x86_64`, `universal`) and uploads them with their SHA-256 hashes.
   - **Job 2 (`sign-and-attest`):** Runs inside the protected GitHub Environment `release` (restricted to `refs/tags/v*` tags).
     - Decodes the release keystore from environment secrets in `RUNNER_TEMP` (`tmpfs`), signs APKs with `apksigner sign --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false`, and immediately shreds the keystore file.
     - Verifies reproducibility using `apksigcopier compare` against the unsigned APK from Job 1.
     - Generates SLSA v1.0 build provenance attestations via `actions/attest-build-provenance`.
     - Publishes signed APKs, `.sha256` checksums, and the public signing certificate fingerprint (`SHA-256`) to GitHub Releases for **Obtainium** and **AppVerifier**.

---

## 6. Phased Implementation Roadmap (For Agent Execution)

### Milestone 1: Project Scaffold, Reproducible Gradle & CI Guardrails
- Initialize Gradle Kotlin DSL project (`app/build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`).
- Generate `gradle/verification-metadata.xml` (`pgp,sha256`).
- Configure `AndroidManifest.xml`, `network_security_config.xml`, `data_extraction_rules.xml`, and SHA-pinned GitHub Actions (`ci.yml`, `codeql.yml`, `release.yml`).
- **Verification:** `./gradlew assembleRelease lintRelease` succeeds and outputs 16KB-aligned `arm64-v8a`, `x86_64`, and `universal` APKs.

### Milestone 2: Assistant Role, AOSP Trigger Routing & Memory Scrubber
- Implement `SnipVoiceInteractionService`, `SnipVoiceInteractionSessionService`, `SnipVoiceInteractionSession`, `SnipRecognitionServiceStub`, and `SnipQuickSettingsTileService`.
- Implement `InvocationRouter` for AOSP `invocation_type` (`1` Gesture, `7` Home Long-Press, `5` Power Button with delegation to Gemini/external voice assistant or ignore).
- Implement `MemoryScrubber` and `FLAG_SECURE` null-screenshot handling.
- **Verification:** Unit tests for `InvocationRouter` and memory scrubbing lifecycle (`onHide()` zeroes byte arrays and recycles bitmaps).

### Milestone 3: Compose Overlay, Lasso Gesture & Tier 1/2 Offline Extraction
- Build `OverlayScreen`, `GestureLassoCanvas`, and `AdjustableCropBox` with corner resize handles.
- Implement `AssistStructureParser` (ViewNode spatial intersection) and `EntityAndQrDetector` (ZXing QR/barcode + URL/email/phone regex).
- Render `SelectableTextLayer` and `EntityActionChipsRow` over the cropped region.
- **Verification:** Unit tests for `AssistStructureParser` coordinate math and `EntityAndQrDetector` QR/URL extraction.

### Milestone 4: Sandboxed PaddleOCR v4 (`android:isolatedProcess="true"`)
- Define `IOcrIsolatedService.aidl` and `OcrTextBlockParcelable.aidl`.
- Bundle quantized PaddleOCR v4 Mobile `.onnx` models in `src/main/assets/ocr/`.
- Implement `OcrIsolatedService`, `PaddleOcrEngine` (Kotlin `Bitmap -> FloatBuffer` preprocessing + `OrtSession` inference + `CtcBeamDecoder`), and `OcrClientCoordinator` (`SharedMemory` + `AssetFileDescriptor` IPC).
- **Verification:** Instrumentation/Robolectric tests verifying zero temp files created during OCR and clean recovery if the isolated service is killed.

### Milestone 5: Hybrid Visual Upload, Bottom-Sheet WebView & Multimodal Search
- Implement `SearchProviderConfig` schema, `BuiltInProviders` (Google Lens, Yandex, Bing, SearXNG, DuckDuckGo, Brave, Startpage, Kagi, Vision LLM), and `KeystoreSecretCipher` (`AndroidKeyStore` AES-256-GCM).
- Implement `WebViewFormUploadBridge` (`shouldInterceptRequest` in-memory multipart `<form>` submission), `RestVisionApiClient` (`HttpsURLConnection`), and `CookieSessionManager` (pre-seeded `SOCS` cookie + Smart Retention + 1-tap Clear Session).
- Build `SearchWebViewBottomSheet`, `MultimodalSearchBar` (*"Add to your search"*), and `NetworkCapabilityMonitor` (graceful fallback when GrapheneOS Network toggle is OFF).
- **Verification:** Unit tests for `KeystoreSecretCipher`, provider config serialization, one-time RAM blob interception, and offline degradation.

### Milestone 6: Settings UI, Onboarding & Release Hardening
- Build `SettingsActivity`:
  1. Digital Assistant role status card & 1-tap shortcut to `Settings.ACTION_VOICE_INPUT_SETTINGS`.
  2. Power Button Behavior selector (Ignore / Delegate to installed Voice Assistant / Trigger SnipSearch).
  3. Default Visual & Text Provider selector + Custom Provider / Vision API builder.
  4. Privacy & Security controls (*"Confirm Before Uploading Pixels"* toggle, *"Strict Ephemeral Cookies"* toggle, Network status indicator).
- Configure R8 `proguard-rules.pro` and verify full release build shrinking and reproducibility.
