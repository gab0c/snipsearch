# In-process OCR migration verification

Measured on 2026-11-27 using SnipSearch_API_37, Android 17 x86_64 emulator,
1080×2400 synthetic inputs and 4096-byte pages. Baseline is main commit
`468650414c5261e8c3810e845dcd9411e544f75f`; both routes use its preprocessing
optimization, identical PP-OCRv6 Tiny assets, ONNX settings and recognition rules.
These are emulator results, not ARM, GrapheneOS, synchronous MTE or 16KB-page runtime measurements.

## Method

The temporary debug build contains the baseline isolated service and session runner
alongside the migrated production `OcrCoordinator` and `SessionOcrRunner`.
The only adaptation to the baseline runner is its unused, content-free timing logger API.
Both routes are timed from scan submission through their COMPLETE state: owned pixel
copy, dispatch, model initialization when needed, inference and result delivery.
Pixel scrubbing follows result availability for both routes. No UI rendering or assist
capture latency is included.

Three complete repetitions each collect three worker-cold pairs and ten warm pairs
per fixture, with two warmups discarded before each warm batch. Execution order
alternates within pairs. Cold trials create new model owners and a fresh isolated
worker; teardown delays are outside timing. Warm trials retain both sets of models.
Cold means fresh worker/models within an already-running app, not a fresh app or
runtime. Local runtime/JIT may remain primed, including by lifecycle smoke checks.
Host scheduling, emulator JIT and small sample sizes limit precision.

Fixtures are generated in RAM with fixed fonts, seeds and dimensions: tiny dense
three-column text, low-contrast/rotated UI text, and a dense technical table.
Full text, word character ranges and all bounding boxes are compared in RAM for
every pair. The committed CSV contains only timings, counts, equality flags and
hashes; no screen pixels or extracted strings are written or logged.

## Results

All **117 pairs** had identical text, word counts, character ranges and bounding boxes.

| State | Fixture | Pairs | Isolated median ms | In-process median ms | Lower latency |
| :--- | :--- | ---: | ---: | ---: | ---: |
| cold | columns | 9 | 687.68 | 287.20 | 58.24% |
| cold | lowcontrast_rotated | 9 | 784.35 | 280.58 | 64.23% |
| cold | technical_table | 9 | 902.15 | 382.99 | 57.55% |
| warm | columns | 30 | 236.01 | 227.23 | 3.72% |
| warm | lowcontrast_rotated | 30 | 226.93 | 220.91 | 2.65% |
| warm | technical_table | 30 | 332.97 | 321.98 | 3.30% |

Across the three fixture medians, summed latency fell **59.95% worker-cold** and
**3.24% warm**. These are descriptive ratios of summed medians, not confidence
intervals or a universal speedup estimate. The warm difference is small compared
with the cold worker setup saving. Raw metadata is in
[ocr-migration-samples.csv](ocr-migration-samples.csv).

All three repetitions passed lifecycle smoke checks: **15 cancellation/hide/reopen
cycles**, three hardware-bitmap equivalence checks, three crop-equivalence/bounds
checks and three idle-eviction checks. Baseline worker lifecycle logs confirmed
new isolated processes were started and destroyed between cold trials.

## Reproduction

With the repository's configured Android SDK and JDK 25:

```sh
export JAVA_HOME=/home/gab0c/.local/share/snipsearch/gradle/jdks/eclipse_adoptium-25-amd64-linux.2
export ANDROID_HOME=/home/gab0c/Android/Sdk
python3 scripts/benchmark-ocr-migration.py --serial emulator-5554 \
  --repetitions 3 --output /tmp/ocr-migration-samples.csv
```

Use the device tools to open the emulator first. The script uses only the Android
instrumentation test runner, not shell UI automation. It checks that assets match
the pinned baseline, temporarily generates baseline debug sources/AIDL and test
instrumentation, builds and installs that debug APK, runs the comparison, then
restores the build/manifest and removes generated sources in `finally`. It refuses
to overwrite existing overlay sources. An interrupted process that cannot execute
`finally` requires restoring those temporary source edits before normal builds.
The installed instrumented APK must be replaced with an ordinary debug build before
normal use. No baseline service or AIDL is packaged in the ordinary build.

## Regression coverage and limits

Each repetition also exercises five actual-inference cancellation/hide/reopen cycles,
immediate recycling of a copied source bitmap, hardware-bitmap OCR equivalence,
cropped-image text/geometry equivalence and bounds containment, and model eviction
using a shortened idle delay. Every check must pass for instrumentation success;
output mismatches fail the benchmark.

The unit/security suites cover initialization and inference failures, native-runtime
linkage failure and retry, concurrent scan serialization, stale results, cancellation
during copying/inference, prompt scrubbing of superseded queued inputs while native
work is blocked, destroy/init races, pixel/tensor scrubbing, correction
hints and deterministic geometry, and forbidden disk/network/service paths.

Manual assistant selection/handle dragging remains unverified: the emulator UI
snapshot helper twice returned “insufficient foreground app content” on the
pixel-only fixture, so that UI automation path was stopped. The fixture rendered
correctly in the device screenshot. Real-engine geometry checks and deterministic
selection tests do not replace a phone check of handle dragging, assist capture,
FLAG_SECURE behavior and GrapheneOS Network-toggle degradation.

Native crashes can terminate the app; Kotlin fallback handles recoverable failures.
Cancellation is cooperative and cannot forcibly stop arbitrary native inference.

## Final ordinary-build checks

After removing the temporary overlay, all checks passed:

- `./gradlew testDebugUnitTest`: 158 tests, no failures/errors/skips.
- `./gradlew lintRelease`.
- `./gradlew --dependency-verification strict help`.
- `./gradlew assembleRelease assembleDebug` (release APKs remain unsigned).
- `scripts/check-elf-alignment.sh`: all 18 libraries in the three release APKs pass 16KB alignment.
- Ordinary debug and universal release DEX contain no legacy OCR/IPC or benchmark
  classes; models remain uncompressed and graphics-path libraries remain packaged.
- The emulator was reinstalled with the ordinary debug APK after instrumentation.

No dependencies, OCR assets, recognition heuristics or model options were changed.
Current-main preprocessing optimizations were preserved while moving packages.
Verification found and fixed recoverable native-runtime linkage fallback and
superseded queued-scan buffer retention; dedicated regression tests cover both.
