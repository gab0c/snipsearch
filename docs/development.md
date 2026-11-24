# Developing SnipSearch

## Set up a Linux workstation

Install a full JDK 21 (CI uses Temurin), Bash, `unzip`, and GNU binutils for `readelf`. Set `JAVA_HOME` to the JDK directory. Use the Gradle wrapper included in the checkout.

Open the repository root in Android Studio and select JDK 21 as the Gradle JDK. In SDK Manager, install Android SDK Command-line Tools and Platform-Tools. Set `ANDROID_HOME` to the SDK location shown in Studio. For a command-line build, install the following packages and review the SDK licenses when prompted.

```bash
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'build-tools;37.0.0' 'platform-tools'
```

The app compiles against API 37 and supports devices running API 36 or 37. The current Android Gradle plugin uses Build-Tools 36.0.0. The APK verification command below uses 37.0.0.

For project-specific state, set `GRADLE_USER_HOME` (dependency cache), `ANDROID_USER_HOME` (including the debug keystore), and `ANDROID_AVD_HOME` (virtual devices) to directories outside the checkout. Launch Studio from the same environment as the shell so IDE and command-line builds use the same debug key. Keep desktop launchers, SDK paths, caches, APKs, and signing material local.

## Verify a change

```bash
./scripts/verify-local.sh
```

This runs strict Gradle dependency verification, unit and security regression tests, release lint, and unsigned release assembly. It then checks the native ELF segment alignment in each release APK. These checks run without an emulator. See the [CI workflow](../.github/workflows/ci.yml) and the [dependency-update instructions](../AGENTS.md) when changing version pins.

## Test on the emulator

Install Android Emulator in SDK Manager. On an x86_64 Linux desktop, use Device Manager to create a Pixel virtual device with the Android 17 (API 37) x86_64 system image. Use an Android 16 image to check the minimum supported version. Start the virtual device and select it as the run target in Studio.

On Fedora, run `emulator -accel-check` before troubleshooting a failed boot. If KVM is unavailable, check that CPU virtualization is enabled in firmware (AMD SVM or Intel VT-x), that the matching KVM kernel module is loaded, and that your account has read/write access to `/dev/kvm`. A firmware change takes effect after reboot.

For a smoke test, choose SnipSearch as the default digital assistant, open a screen with selectable text, and invoke the overlay with a bottom-corner swipe in gesture navigation or a Home long-press in three-button navigation. Try selecting text and adjusting a crop. Add the SnipSearch Quick Settings tile and open the overlay from the tile as well.

## Send a debug APK for GrapheneOS testing

Build from the revision under test and verify the universal APK before sharing it.

```bash
./gradlew --dependency-verification strict assembleDebug
apk=app/build/outputs/apk/debug/app-universal-debug.apk
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify "$apk"
git rev-parse HEAD
sha256sum "$apk"
```

Send the APK as a file attachment with the commit ID, SHA-256, and the behavior to check on the phone. In T3 Code, a chat attachment transfers the file to the mobile client. A desktop workspace path can be inaccessible from the phone.

For subsequent test builds, keep using the same debug keystore so Android can install updates over the previous build. Debug and release builds both use `com.snipsearch` and have different signing keys. Local release builds produce unsigned APKs. The protected release workflow handles release signing.
