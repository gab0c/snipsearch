#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

for tool in unzip readelf; do
    if ! command -v "$tool" >/dev/null; then
        echo "Missing $tool. See docs/development.md for build prerequisites." >&2
        exit 1
    fi
done

./gradlew --dependency-verification strict help testDebugUnitTest lintRelease assembleRelease
scripts/check-elf-alignment.sh app/build/outputs/apk/release/*.apk
