# SnipSearch — Master Architecture & Implementation Plan (`docs/plan.md`)

## 1. Project Overview & Goals

**SnipSearch** (`com.snipsearch`) is an open-source, search-engine-agnostic "Circle to Search" assistant for **GrapheneOS** and **Android 16–17 (API 36–37)**.

### Core Value Proposition
- **Native Assistant Trigger Without Accessibility Abuse:** Uses Android's `VoiceInteractionService` (`ROLE_ASSISTANT`) to receive an on-demand screenshot (`onHandleScreenshot`) and view hierarchy (`onHandleAssist`) strictly at the moment the user invokes it—requiring **zero** `AccessibilityService`, **zero** `SYSTEM_ALERT_WINDOW`, and **zero** persistent background observation.
- **Coexistence with Secondary Voice Assistants:** Uses AOSP `invocation_type` trigger-source routing so swiping up from a bottom corner (or long-pressing Home in 3-button navigation) opens SnipSearch while long-pressing the power button (the stock Android assistant trigger; GrapheneOS keeps the power menu there) can delegate directly to Gemini, Google Assistant, Dicio, Home Assistant, or do nothing.
- **Unified In-App Bottom-Sheet UX:** Mounts a Jetpack Compose `BottomSheetScaffold` hosting the system `WebView` (Vanadium WebView on GrapheneOS) directly inside the `VoiceInteractionSession` window so users can resize the crop box, add follow-up text (*"Add to your search"*), and browse results seamlessly.
- **100% Offline Text, Entity & QR Extraction:** Uses **PP-OCRv6 Tiny** via **ONNX Runtime** on background workers in the main app process for on-screen text and its positions. `AssistStructure` supplies correction hints and fallback text. Pure JVM `ZXing` decodes QR codes and barcodes.
- **GrapheneOS Hardened & Reproducible:** Supports synchronous hardware Memory Tagging (`memtagMode="sync"`), 16KB page sizes, graceful offline degradation when the GrapheneOS Network toggle is disabled, `AndroidKeyStore` StrongBox/TEE encryption for custom API keys, and reproducible multi-ABI GitHub Releases with SLSA Level 3 provenance and `apksigcopier` verification.

---

## 2. Locked Architectural Decisions Summary

| Area | Locked Decision | Rationale |
| :--- | :--- | :--- |
| **Target Platform** | Android 16–17 (API 36–37): `minSdk` 36, `targetSdk` 37, `compileSdk` 37. 100% Kotlin + Jetpack Compose | Covers current GrapheneOS (Android 17) and the previous release; modern `VoiceInteractionSession`, in-memory OCR, and 16KB page-alignment support; zero legacy bloat. See §2.1 for what targeting API 37 changes. |
| **Assistant Role & Trigger** | `VoiceInteractionService` (`ROLE_ASSISTANT`) + AOSP `invocation_type` routing + Quick Settings `TileService` (`showSession()`) | Zero `AccessibilityService` or `MediaProjection` permissions; coexists with power-button voice assistants. |
| **Process & Network Model** | Single APK with `INTERNET` + in-process OCR on background workers + GrapheneOS Network toggle degradation | Enables native single-window bottom-sheet `WebView` UX and offline OCR. OCR shares the app UID and address space; the OS Network toggle applies to the app as a whole. No OCR isolation guarantee is made. |
| **Text & Entity Extraction** | Primary: Bundled **PP-OCRv6 Tiny** (`onnxruntime-android >= 1.22.0`) in `/assets/ocr/`<br>Hints and fallback: `AssistStructure` ViewNode tree<br>Offline `com.google.zxing:core` QR/barcode and regex URL/email/phone chips | Screenshot-based word positions, zero disk I/O, zero Play Services, and 16KB page alignment. |
| **Visual Upload Pipeline** | **Hybrid Pipeline:**<br>1. In-WebView Chromium `<form>` POST (via `shouldInterceptRequest` RAM stream) for browser engines<br>2. `HttpsURLConnection` for REST/JSON Vision APIs | Preserves authentic Vanadium/Chromium TLS (JA3/JA4) & HTTP/2/3 fingerprint to prevent VPN CAPTCHAs and allows inline CAPTCHA solving. |
| **Cookie & Anti-CAPTCHA Policy** | **Smart Cookie Retention (Default)** + Pre-seeded `SOCS` consent cookie + 1-tap "Clear Session" broom button + "Strict Ephemeral" toggle | Prevents endless CAPTCHA loops on Mullvad/Proton VPNs and bypasses EU GDPR consent walls that drop image POST payloads. |
| **Post-Gesture UX** | Text selection on the frozen screen, an explicit **Search** action for image crops, and a unified *"Add to your search"* bar. The *"Confirm Before Uploading Pixels"* setting gates image upload. | Keeps offline text selection separate from image upload and supports multimodal refinement. |
| **Search Providers** | Built-in Presets (Google Lens, Yandex, Bing Visual, SearXNG, Google, DuckDuckGo, Brave, Startpage) + Custom Provider & Vision LLM Builder | Works out-of-the-box for all users while remaining 100% search-engine agnostic. |
| **Secret Storage** | Zero-dependency `AndroidKeyStore` (`AES-256-GCM`, StrongBox with TEE fallback, `setUnlockedDeviceRequired(true)`) stored in Jetpack `DataStore` | Protects custom API keys/headers against AFU lock-screen state, LFI, and rooted/recovery file extraction across GrapheneOS and Stock Android. |
| **Release & Supply Chain** | ABI Splits (`arm64-v8a`, `x86_64`) + Universal APK, signed in a protected GitHub `release` Environment + SLSA v1.0 provenance + `apksigcopier` reproducibility | Keeps Pixel/GrapheneOS APK ~18 MB smaller, isolates signing keys from coding agents, and enables cryptographic verification via Obtainium/AppVerifier. |

### 2.1 What Targeting Android 17 (API 37) Changes
`targetSdk = 37` opts the app into these Android 17 behaviours. Later milestones must account for them:
- **Local network endpoints are not supported for now:** on Android 17, connecting to a host on the local network requires the `ACCESS_LOCAL_NETWORK` runtime permission, which SnipSearch does not declare or request. A user-configured SearXNG or OpenAI-compatible endpoint on the LAN (for example a self-hosted SearXNG or Ollama) is therefore not supported on Android 17. Built-in presets and internet-hosted endpoints are unaffected. No milestone covers this.
- **Certificate Transparency is on by default** for the app's TLS connections. Do not opt out in `network_security_config.xml`.
- **Orientation, resizability and aspect-ratio locks are ignored on large screens (sw >= 600dp).** The overlay and settings UI must lay out correctly at any window size and must not rely on `screenOrientation`.
- **Native libraries loaded with `System.load()` must be read-only.** Bundled libraries loaded from the APK with `System.loadLibrary()` are unaffected; never extract and load a `.so` from writable storage.

---

## 3. System Architecture & Module Layout

The layout and OCR specification below describe the intended in-process architecture. At this documentation change, production code still uses `extraction.isolated`, `OcrIsolatedService`, and OCR AIDL. Migrating that code is the next implementation task; this document does not claim the migration has already shipped. OCR isolation has been removed from the security model by maintainer decision.

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
│       │   ├── assets/ocr/
│       │   │   ├── PP-OCRv6_tiny_det_infer.onnx
│       │   │   ├── PP-OCRv6_tiny_rec_infer.onnx
│       │   │   └── ppocrv6_tiny_keys.txt
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
│       │   │   │   └── ocr/
│       │   │   │       ├── OcrCoordinator.kt
│       │   │   │       ├── SessionOcrRunner.kt
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

        <!-- OCR runs in-process on background workers; no OCR service is declared. -->

        <!-- 4. Quick Settings Tile (triggers showSession() when ROLE_ASSISTANT is active) -->
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
Android 16–17 passes `"invocation_type"` inside `args: Bundle?` to `VoiceInteractionSession.onShow(args, showFlags)`:
- `INVOCATION_TYPE_GESTURE = 1` (Swipe up from a bottom corner in gesture navigation)
- `INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS = 5` (3-button navigation Home long-press)
- `INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6` (Long-press Power button. This is the AOSP and stock Pixel default; GrapheneOS reverts long-press power to the power menu and removes the setting, so this value never arrives there)
- `INVOCATION_TYPE_QUICK_SETTINGS_TILE = 100` (Custom internal constant passed when triggered via `SnipQuickSettingsTileService`)

The first three values come from the hidden `com.android.internal.app.AssistUtils` class and are identical on the AOSP `android16-release` branch and on GrapheneOS's Android 17 branch, so `InvocationRouter` defines them itself. Two neighbouring values are **not** SnipSearch triggers: `7` is `INVOCATION_TYPE_ASSIST_BUTTON` (a physical assistant key) and `8` is `INVOCATION_TYPE_NAV_HANDLE_LONG_PRESS`.

Long-pressing the navigation handle does not open the `ROLE_ASSISTANT` app. AOSP's launcher sends it to Contextual Search (`config_defaultContextualSearchPackageName`), which is Circle to Search on stock Pixel and is unset in the AOSP and GrapheneOS framework configuration.

When `invocationType == 6` (`POWER_BUTTON_LONG_PRESS`):
1. If user setting is `PowerButtonAction.IGNORE`: immediately scrub any queued screenshot/assist references and call `hide()`.
2. If user setting is `PowerButtonAction.DELEGATE`:
   - Scrub any queued screenshot/assist references.
   - Launch `Intent(Intent.ACTION_VOICE_COMMAND).setPackage(delegatedPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)` (with fallback to `Intent.ACTION_ASSIST`).
   - Call `hide()`.
3. If user setting is `PowerButtonAction.OPEN_SNIPSEARCH`: proceed to render the SnipSearch overlay.

---

### 4.2 Interactive Overlay, Gesture Capture & Post-Gesture UX

1. **Frozen screen and floating controls**
   - Use the session's full drawing area for the screenshot. Anchor translucent controls above it and apply system insets to the controls. Moving or resizing a card must preserve the screenshot's scale, position, and selection coordinates.
   - Treat protected and unavailable captures according to §4.1.
2. **Text selection**
   - Start in text mode. Tap a word to select it, drag across words to select a range, and adjust either endpoint with a handle. Draw the highlight over the screenshot pixels at their existing positions.
   - Offer **Copy text** for the selected range and entity actions based on that range. Put explicitly copied text on the standard Android clipboard as plain text. Keep the assistant and current selection open so the user can search after copying. Keep extracted text local until the user chooses an action.
   - On-device follow-up for the Android clipboard preview. Check whether the preview can receive taps while the assistant remains open and the selection is preserved.
   - Keep the frozen screen in RAM for the duration of text selection and scrub session buffers on hide.
3. **Image selection**
   - **Select image** activates the lasso and corner handles for choosing image pixels. Scan that region for QR codes and barcodes offline. When the crop locks, keep the crop at its original screen coordinates and scrub the full screenshot.
   - In milestone 5, replace **Lock selection** with **Search**. Tapping **Search** commits the crop and starts the visual search flow. Show the bottom sheet at a 55% peek height with **Add to your search**. Honour **Confirm Before Uploading Pixels** by waiting for a separate upload confirmation when enabled. Re-encode the crop in RAM before upload. Send extracted text only through an explicit user action.

#### Glowing feedback and gesture intent

- Use the same thick white finger trail and blue fingertip halo for text and image drags. Keep the full white stroke until release, including while the finger pauses. Ignore small touch jitter. Outline the white trail so it stays visible on light backgrounds. Each blue halo sample fades over 240 ms. Acceleration narrows newly drawn samples. Clear the trail and halo when the finger lifts or the gesture is cancelled. A completed text gesture leaves the regular highlight and handles. A completed image lasso leaves a thick white border with 64 dp corner touch targets. Choose the nearest corner when targets overlap. Keep the screenshot's position and scale fixed throughout.
- Give an existing text or crop handle first claim on the gesture. For a new stroke, show a neutral preview while intent is unclear. On release, treat a closed or nearly closed path that encloses enough area as an image lasso, including a rough square or rectangle. Treat an open path that follows visible words or crosses adjacent text lines as text selection. Use word hit tests and reading order so horizontal drags work in both directions and vertical drags can span lines.
- Keep the explicit text and image controls available for ambiguous gestures, including diagonal drags that could describe either a text range or an image crop. Gesture detection can suggest an adjustable crop. **Lock selection** commits that crop and releases the full screenshot.
- Verify tap and handle behavior, left-to-right and right-to-left text drags, vertical multiline drags, and rough rectangles even when they enclose text. Verify that short jitter and ambiguous diagonal strokes do not create a crop. Prototype with the current word bounds and verify text hit accuracy in Android Files and browser pages after OCR lands.

---

### 4.3 Offline Text, QR & Entity Extraction

#### `AssistStructureParser` (OCR hints and fallback text)
- Traverses `AssistStructure.WindowNode` and `AssistStructure.ViewNode` trees captured in `onHandleAssist(state: AssistState)`.
- Computes absolute screen coordinates `(left, top, right, bottom)` for each `ViewNode` by accumulating parent offsets (`node.left + parentX - parentScrollX`).
- Include visible screen text from `node.text` and exclude password fields. Accessibility descriptions can label icons and whole rows and have no reliable on-screen text position.
- Use view bounds and available line metadata to estimate word positions without shifting text origins at viewport edges. The hierarchy can supply a correct string with an approximate or displaced position. Use those estimates as a fallback when OCR is unavailable and the bounds are usable. Phone testing reported large offsets in Android Files and browser text.
- **Do not expose `AssistStructure` text before OCR finishes:** surfacing view-hierarchy word selection while OCR is still running caused displaced highlights and a jarring layout swap on device and was intentionally reverted.
- **Future opportunities (non-layout metadata):** Because `AssistStructure` arrives alongside the screenshot without ML cost, its non-layout metadata can be leveraged later without relying on noisy word bounds—for example, using view bounds/widget structure to help filter non-text UI-icon false positives in OCR, or enriching user-triggered entity/action chips with metadata such as `ViewNode.webDomain` or non-truncated strings (strictly on explicit user action).

#### `EntityAndQrDetector` (Offline QR and entities)
- **QR & Barcode Decoding:** Passes the cropped `Bitmap`'s `IntArray` luminance pixels to `com.google.zxing.MultiFormatReader` (configured for `QR_CODE`, `DATA_MATRIX`, `AZTEC`, `EAN_13`, `CODE_128`) on `Dispatchers.Default`. Zero native C++ code.
- **Entity Regex / `TextClassifier`:** Scans the selected OCR or fallback text for URLs (`https?://...`), email addresses, phone numbers, and coupon/tracking codes and offers matching action chips.

#### In-process OCR (Primary text selection using PP-OCRv6 Tiny)
- Once OCR is available, run it first for on-screen text selection, including when `AssistStructure` also returns text. Return recognized words and their bounds in screenshot coordinates. Use those bounds for highlights, hit tests, and selection handles.
- Use `AssistStructure` strings as local hints to correct likely OCR misreads when there is a unique, strong match in the same screen area. Keep the OCR bounds and preserve the visibly recognized span. Skip corrections for ambiguous matches, conflicting positions, or text extending past what is visible, such as a truncated filename. Avoid duplicate highlights.
- **Execution and ownership:** Run detector/recognizer inference on bounded background workers inside the main app process. A coordinator owns model sessions and initialization; the session runner owns scan generations and cancellation. Use ordinary Kotlin result types rather than AIDL parcelables. Keep existing model settings, confidence filtering, correction hints, and screenshot-coordinate bounds.
- **RAM-only input:** Convert the screenshot or crop to an explicitly owned pixel buffer before asynchronous work can outlive the source bitmap. Avoid redundant copies while preventing bitmap recycling races. Scrub pixel arrays and direct/tensor buffers on completion, cancellation, hide, and destroy as applicable. Retained model sessions must not retain prior screen pixels or text.
- **Model access:** Keep `.onnx` assets uncompressed (`noCompress += "onnx"`) and read them through local asset descriptors. Model assets may be read from the APK; screen content must never be written to disk. Remove OCR service binding, Binder/AIDL transport and SharedMemory plumbing as part of the migration.
- **Trust boundary:** OCR shares the app's memory, UID and permissions. It must not make network requests or persist screen content, but no OS-enforced OCR network/storage sandbox or native-crash containment is promised. A native OCR failure may terminate the app; a native compromise may gain app privileges. Memory tagging, dependency verification, explicit user action before search, and all other privacy controls remain required.
- **Lifecycle and recovery:** Initialize outside the main thread, serialize model ownership against active recognition, bound concurrency, reject stale results, and close resources safely on idle eviction/destroy. Reuse only screen-independent model state. Handle recoverable initialization/inference errors with existing AssistStructure fallback; do not claim coroutine cancellation can interrupt an arbitrary stuck native call.

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
   - **Google**, **DuckDuckGo**, **Brave Search**, **Startpage**.

User-configured endpoints (a SearXNG instance URL, an OpenAI-compatible API, or a custom provider) on the local network are not supported on Android 17 for now, because the app does not request `ACCESS_LOCAL_NETWORK` (see §2.1). Built-in presets and internet-hosted endpoints are unaffected.

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
    compileSdk = 37

    defaultConfig {
        applicationId = "com.snipsearch"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // Keep ONNX models uncompressed for direct local asset-descriptor access
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
   - Verifies Gradle dependency checksums (`./gradlew --dependency-verification strict help`).
   - Runs Android Lint security gate (`./gradlew lintRelease`).
   - Runs JUnit / Robolectric unit & security regression tests (`./gradlew testDebugUnitTest`).
   - Verifies 16KB ELF alignment on all packaged `.so` files with `scripts/check-elf-alignment.sh` (`readelf -lW`, every `LOAD` segment aligned to a multiple of 16384).
   - Runs `actions/dependency-review-action` on PRs.
2. **`codeql.yml` (Push to `main`, PRs, Weekly Schedule):**
   - Runs GitHub CodeQL (`java-kotlin` security-extended suite).
3. **`release.yml` (Tag Push `v*` — Protected `release` Environment):**
   - **Job 1 (`build-unsigned`):** Runs in a clean runner with zero secrets. Builds deterministic unsigned release APKs (`arm64-v8a`, `x86_64`, `universal`) and uploads them with their SHA-256 hashes.
   - **Job 2 (`sign-and-attest`):** Runs inside the protected GitHub Environment `release` (restricted to `refs/tags/v*` tags).
     - Decodes the release keystore from environment secrets into `/dev/shm` (`tmpfs`), signs APKs with `apksigner sign --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --alignment-preserved true`, and immediately shreds the keystore file. `--alignment-preserved true` is required: without it `apksigner` re-aligns zip entries and the `apksigcopier compare` step below fails.
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
- Implement `InvocationRouter` for AOSP `invocation_type` (`1` Gesture, `5` Home Long-Press, `6` Power Button with delegation to Gemini/external voice assistant or ignore).
- Implement `MemoryScrubber` and `FLAG_SECURE` null-screenshot handling.
- **Verification:** Unit tests for `InvocationRouter` and memory scrubbing lifecycle (`onHide()` zeroes byte arrays and recycles bitmaps).

### Milestone 3: Compose Overlay, Lasso Gesture & Offline Extraction Before OCR
- Build `OverlayScreen`, `GestureLassoCanvas`, and `AdjustableCropBox` with corner resize handles.
- Implement `AssistStructureParser` (ViewNode spatial intersection) and `EntityAndQrDetector` (ZXing QR/barcode + URL/email/phone regex).
- Render `SelectableTextLayer` on the frozen screen and float `EntityActionChipsRow` above it. Keep lasso and crop handles in image selection.
- Draw a glowing finger trail during text drags and image lassos. Give the adjustable crop a thick white border and larger corner touch targets.
- **Next overlay goal:** Prototype text versus image gesture detection in §4.2. Verify geometry-dependent behavior with milestone 4.
- **Verification:** Unit tests for `AssistStructureParser` coordinate math and `EntityAndQrDetector` QR/URL extraction.

### Milestone 4: In-Process PP-OCRv6 Tiny
- Replace the legacy isolated OCR service with a background in-process coordinator and ordinary Kotlin result types under `extraction.ocr`. Remove its manifest declaration and OCR-only AIDL/IPC plumbing.
- Bundle quantized PP-OCRv6 Tiny `.onnx` models in `src/main/assets/ocr/`. The recognizer exports only `[1,T]` ArgMax IDs and maximum probabilities; keep Softmax internal and reject uncertain words/glyphs before publishing the complete OCR result. See the pinned upstream hashes and regeneration commands in `assets/ocr/NOTICE.txt`.
- Keep `PaddleOcrEngine` (Kotlin pixel preprocessing + `OrtSession` inference + `CtcBeamDecoder`) and move model/session ownership into `OcrCoordinator`. Preserve existing inference settings and cancellation/generation behavior in `SessionOcrRunner`; use local RAM buffers instead of SharedMemory transport.
- Use screenshot-coordinate OCR word bounds for text selection and test hierarchy-assisted corrections against displaced bounds, repeated labels, and truncated filenames. Retest highlight alignment and text drags on a real phone in Android Files and a browser, including after scrolling.
- **Verification:** Deterministic tests for no screen-content disk writes, pixel/tensor scrubbing, cancellation/hide/destroy races, stale-result rejection, idle model reuse/eviction, initialization/inference failure fallback, and output/word-bound equivalence. Exercise the real inference path on an emulator; do not assert OCR isolation or process-crash containment.

### Milestone 5: Hybrid Visual Upload, Bottom-Sheet WebView & Multimodal Search
- Implement `SearchProviderConfig` schema, `BuiltInProviders` (Google Lens, Yandex, Bing, SearXNG, Google, DuckDuckGo, Brave, Startpage, Vision LLM), and `KeystoreSecretCipher` (`AndroidKeyStore` AES-256-GCM).
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
