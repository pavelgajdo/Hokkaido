#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
# SPDX-License-Identifier: GPL-3.0-or-later

# Usage: sync_bananative.sh <BananaNative checkout> <tag> <matching APK> <APK URL>
# DEXDUMP may name a build-tools dexdump outside ANDROID_HOME.
set -euo pipefail

if [ "$#" -ne 4 ]; then
    echo 'Usage: sync_bananative.sh <checkout> <tag> <apk> <apk-url>' >&2
    exit 2
fi
repo=$(cd -- "$1" && pwd)
tag=$2
apk=$(realpath -- "$3")
url=$4
root=$(cd -- "$(dirname -- "$0")/../.." && pwd)
module=$root/src/android/banana

commit=$(git -C "$repo" rev-parse --verify "$tag^{commit}")
apk_sha=$(sha256sum "$apk" | cut -d ' ' -f 1)
if [ -n "${BANANA_EXPECTED_COMMIT:-}" ] && [ "$commit" != "$BANANA_EXPECTED_COMMIT" ]; then
    echo "Banana source commit mismatch: $commit" >&2
    exit 1
fi
if [ -n "${BANANA_EXPECTED_APK_SHA256:-}" ] && [ "$apk_sha" != "$BANANA_EXPECTED_APK_SHA256" ]; then
    echo "Banana APK SHA-256 mismatch: $apk_sha" >&2
    exit 1
fi

dexdump=${DEXDUMP:-}
if [ -z "$dexdump" ]; then
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    if [ -z "$sdk" ]; then
        echo 'Set ANDROID_HOME or DEXDUMP to the Android build-tools dexdump.' >&2
        exit 1
    fi
    for candidate in "$sdk"/build-tools/*/dexdump; do
        [ ! -x "$candidate" ] || dexdump=$candidate
    done
fi
if [ -z "$dexdump" ] || [ ! -x "$dexdump" ]; then
    echo "No executable dexdump found: $dexdump" >&2
    exit 1
fi

# Validate the whole replacement in a temporary folder before changing the embedded sources.
work=$(mktemp -d "$module/.banana-sync.XXXXXX")
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/upstream"
git -C "$repo" archive "$tag" android/app/src/main/java android/app/src/main/res \
    android/app/src/main/AndroidManifest.xml android/app/src/ondevice android/app/src/variant-play |
    tar -x -C "$work/upstream" --strip-components=3
for patch_file in "$module"/patches/*.patch; do
    patch --batch --fuzz=0 --no-backup-if-mismatch -d "$work/upstream" -p1 < "$patch_file"
done

python3 "$root/tools/android/extract_banana_buildconfig.py" \
    "$apk" "$dexdump" "$work/buildconfig.txt" --expected-version "${tag#v}"
cat > "$work/UPSTREAM.txt" <<EOF
# The Banana Native release this module embeds (tools/android/sync_bananative.sh writes this file).
repo = https://git.eden-emu.dev/moceure/BananaNative
tag = $tag
commit = $commit
apk_url = $url
apk_sha256 = $apk_sha
EOF

rm -rf -- "$module/upstream"
mv -- "$work/upstream" "$module/upstream"
mv -- "$work/buildconfig.txt" "$module/buildconfig.txt"
mv -- "$work/UPSTREAM.txt" "$module/UPSTREAM.txt"
echo "Banana Native $tag synced from $commit; all patches applied without fuzz."
