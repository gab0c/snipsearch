#!/usr/bin/env python3
"""Temporary, synthetic-only paired benchmark. Restores all APK source overlays on exit."""
import argparse
import csv
import os
from pathlib import Path
import re
import subprocess

BASELINE = '468650414c5261e8c3810e845dcd9411e544f75f'
ROOT = Path(__file__).resolve().parents[1]
TEMPLATES = ROOT / 'scripts/ocr-benchmark'
FIELDS = ['run', 'baseline', 'fixture', 'phase', 'trial', 'legacyMs', 'localMs',
          'legacyLines', 'localLines', 'legacyWords', 'localWords', 'textEqual',
          'geometryEqual', 'legacyDigest', 'localDigest']


def command(*args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--repetitions', type=int, default=3)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.repetitions <= 10:
        parser.error('repetitions must be between 1 and 10')
    adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
    originals = {ROOT / p: (ROOT / p).read_bytes() for p in
                 ['app/build.gradle.kts', 'app/src/debug/AndroidManifest.xml']}
    added = []
    created_dirs = set()

    def add(relative, data):
        path = ROOT / relative
        if path.exists():
            raise RuntimeError(f'Overlay destination already exists: {relative}')
        parent = path.parent
        while not parent.exists():
            created_dirs.add(parent)
            parent = parent.parent
        path.parent.mkdir(parents=True, exist_ok=True)
        added.append(path)
        path.write_bytes(data)

    try:
        # Refuse to compare different model assets or to overwrite an existing overlay.
        models = command('git', 'ls-tree', '-r', '--name-only', BASELINE,
                         'app/src/main/assets/ocr', capture_output=True).stdout.splitlines()
        for model in models:
            old = command('git', 'rev-parse', f'{BASELINE}:{model}', capture_output=True).stdout.strip()
            now = command('git', 'hash-object', model, capture_output=True).stdout.strip()
            if old != now:
                raise RuntimeError(f'Baseline asset differs: {model}')
        baseline_paths = command('git', 'ls-tree', '-r', '--name-only', BASELINE,
                                 'app/src/main/aidl',
                                 'app/src/main/kotlin/com/snipsearch/extraction/isolated',
                                 capture_output=True).stdout.splitlines()
        for source in baseline_paths:
            if source.endswith('SessionOcrRunner.kt'):
                continue
            data = subprocess.check_output(['git', 'show', f'{BASELINE}:{source}'], cwd=ROOT)
            add(source.replace('src/main/', 'src/debug/'), data)
        for name in ['OcrMigrationBenchmarkRunner.kt', 'OcrMigrationBenchmarkInstrumentation.kt']:
            add(f'app/src/debug/kotlin/com/snipsearch/debug/{name}', (TEMPLATES / name).read_bytes())
        build = ROOT / 'app/build.gradle.kts'
        text = build.read_text()
        if 'aidl = true' in text:
            raise RuntimeError('AIDL already enabled; inspect existing benchmark state')
        build.write_text(text.replace('buildFeatures {', 'buildFeatures {\n        aidl = true', 1))
        manifest = ROOT / 'app/src/debug/AndroidManifest.xml'
        text = manifest.read_text().replace('<application>', '''<instrumentation
        android:name="com.snipsearch.debug.OcrMigrationBenchmarkInstrumentation"
        android:targetPackage="com.snipsearch" />
    <application>
        <service android:name=".extraction.isolated.OcrIsolatedService"
            android:exported="false" android:isolatedProcess="true" android:useAppZygote="false" />''', 1)
        manifest.write_text(text)
        command('./gradlew', '--no-daemon', '--max-workers=1', '--no-parallel', 'assembleDebug')
        command(adb, '-s', args.serial, 'install', '-r',
                'app/build/outputs/apk/debug/app-x86_64-debug.apk')
        rows = []
        for run in range(1, args.repetitions + 1):
            result = command(adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w',
                             'com.snipsearch/com.snipsearch.debug.OcrMigrationBenchmarkInstrumentation',
                             capture_output=True, timeout=600)
            print(result.stdout)
            if 'passed=true' not in result.stdout:
                raise RuntimeError('Instrumentation did not report success')
            logs = command(adb, '-s', args.serial, 'logcat', '-d', '-v', 'brief', '-s',
                           'SnipOcrMigBench:I', '*:S', capture_output=True).stdout
            latest = logs.rsplit('START baseline=', 1)[-1]
            pairs = [dict(re.findall(r'(\w+)=([^ ]+)', line.strip()))
                     for line in latest.splitlines() if '): pair ' in line]
            if len(pairs) != 39 or not all(p['textEqual'] == p['geometryEqual'] == 'true' for p in pairs):
                raise RuntimeError('Missing pairs or output mismatch')
            for pair in pairs:
                rows.append({'run': run, **pair})
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open('w', newline='') as output:
            writer = csv.DictWriter(output, fieldnames=FIELDS)
            writer.writeheader()
            writer.writerows(rows)
        print(f'PASS: {len(rows)} pairs, exact text and geometry equality; metadata CSV: {args.output}')
    finally:
        for path, data in originals.items():
            path.write_bytes(data)
        for path in reversed(added):
            path.unlink(missing_ok=True)
        for path in sorted(created_dirs, key=lambda p: len(p.parts), reverse=True):
            try:
                path.rmdir()
            except OSError:
                pass
        # Installed APK was instrumented. Rebuild/install the ordinary debug APK before UI use.


if __name__ == '__main__':
    main()
