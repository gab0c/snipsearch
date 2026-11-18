#!/usr/bin/env bash
# Fails if any native library inside the given APKs has an ELF LOAD segment
# that is not aligned to a multiple of 16 KB (required for 16 KB page devices).
set -euo pipefail

if [ "$#" -eq 0 ]; then
    echo "usage: $0 <apk>..." >&2
    exit 2
fi

readonly page_size=16384
workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

status=0
checked=0
index=0

for apk in "$@"; do
    if [ ! -f "$apk" ]; then
        echo "error: $apk does not exist" >&2
        exit 2
    fi

    index=$((index + 1))
    dir="$workdir/$index"
    mkdir -p "$dir"

    # unzip exits 11 when the APK contains no native libraries.
    unzip_status=0
    unzip -qq -o "$apk" 'lib/*.so' -d "$dir" 2>/dev/null || unzip_status=$?
    if [ "$unzip_status" -ne 0 ] && [ "$unzip_status" -ne 11 ]; then
        echo "error: could not read $apk" >&2
        exit 2
    fi

    while IFS= read -r -d '' so; do
        checked=$((checked + 1))
        name="$(basename "$apk"):${so#"$dir"/}"
        aligns=$(readelf -lW "$so" | awk '$1 == "LOAD" { print $NF }')
        if [ -z "$aligns" ]; then
            echo "FAIL $name (no LOAD segments found)"
            status=1
            continue
        fi
        bad=""
        for align in $aligns; do
            if [ $((align)) -lt "$page_size" ] || [ $((align % page_size)) -ne 0 ]; then
                bad="$bad $align"
            fi
        done
        if [ -n "$bad" ]; then
            echo "FAIL $name (LOAD alignment:$bad)"
            status=1
        else
            echo "ok   $name"
        fi
    done < <(find "$dir" -type f -name '*.so' -print0 | sort -z)
done

echo "Checked $checked native libraries in $# APK(s)."
exit "$status"
