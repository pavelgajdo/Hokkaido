#!/usr/bin/env python3
# SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
# SPDX-License-Identifier: GPL-3.0-or-later

"""Verify the Fusion APK and the unchanged Banana Native payload it embeds."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile


class VerificationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def sha256(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
    return digest.hexdigest()


def check_zip(archive, label):
    counts = Counter(entry.filename for entry in archive.infolist())
    duplicates = sorted(name for name, count in counts.items() if count > 1)
    require(not duplicates, f"{label}: duplicate ZIP entries: {duplicates}")
    bad = archive.testzip()
    require(bad is None, f"{label}: ZIP integrity failure: {bad}")
    return {entry.filename for entry in archive.infolist() if not entry.is_dir()}


def check_elf(archive, name, label):
    with archive.open(name) as stream:
        header = stream.read(64)
    require(len(header) == 64 and header[:4] == b"\x7fELF", f"{label}: {name} is not an ELF file")
    require(header[4] == 2, f"{label}: {name} is not ELF64")
    require(header[5] == 1, f"{label}: {name} is not little-endian ELF")
    machine = struct.unpack_from("<H", header, 18)[0]
    require(machine == 183, f"{label}: {name} is not AArch64 (e_machine={machine})")


def check_badging(path):
    text = path.read_text(encoding="utf-8")
    package = re.findall(r"^package:\s+name='([^']+)'", text, re.MULTILINE)
    sdk = re.findall(r"^sdkVersion:'(\d+)'", text, re.MULTILINE)
    native = re.findall(r"^native-code:\s*(.*)$", text, re.MULTILINE)
    require(package == ["dev.lichii.fusion"], f"Unexpected APK package: {package}")
    require(sdk == ["33"], f"Expected minimum SDK 33, got: {sdk}")
    require(len(native) == 1, "Missing or ambiguous native-code entry in aapt2 badging")
    abis = re.findall(r"'([^']+)'", native[0])
    require(abis == ["arm64-v8a"], f"Expected only arm64-v8a, got: {abis}")


def verify(apk, banana_apk, badging):
    check_badging(badging)
    with zipfile.ZipFile(apk) as final, zipfile.ZipFile(banana_apk) as banana:
        final_names = check_zip(final, "Fusion")
        banana_names = check_zip(banana, "Banana")
        require("lib/arm64-v8a/libyuzu-android.so" in final_names, "Missing Hokkaido libyuzu-android.so")

        libraries = sorted(name for name in final_names if name.endswith(".so"))
        for name in libraries:
            check_elf(final, name, "Fusion")
        for name in final_names:
            if name.startswith("lib/"):
                require(name.startswith("lib/arm64-v8a/"), f"Unexpected native ABI path: {name}")

        assets = sorted(name for name in banana_names if name.startswith("assets/"))
        native = sorted(name for name in banana_names if name.startswith("lib/arm64-v8a/"))
        require(assets and native, "Reference Banana APK has no assets or ARM64 native payload")
        for name in assets + native:
            require(name in final_names, f"Missing Banana payload: {name}")
            if name.endswith(".so"):
                check_elf(banana, name, "Banana")
            with banana.open(name) as original, final.open(name) as embedded:
                require(sha256(original) == sha256(embedded), f"Changed Banana payload: {name}")

    with apk.open("rb") as stream:
        apk_hash = sha256(stream)
    with banana_apk.open("rb") as stream:
        banana_hash = sha256(stream)
    return {
        "package": "dev.lichii.fusion",
        "min_sdk": 33,
        "abi": "arm64-v8a",
        "apk_sha256": apk_hash,
        "banana_apk_sha256": banana_hash,
        "verified_elf_libraries": len(libraries),
        "unchanged_banana_assets": len(assets),
        "unchanged_banana_native_files": len(native),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Final Fusion APK")
    parser.add_argument("banana_apk", type=Path, help="Original, hash-verified Banana release APK")
    parser.add_argument("badging", type=Path, help="Output from aapt2 dump badging on the final APK")
    args = parser.parse_args()
    try:
        result = verify(args.apk, args.banana_apk, args.badging)
    except (VerificationError, OSError, RuntimeError, ValueError, zipfile.BadZipFile) as error:
        print(f"APK verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
