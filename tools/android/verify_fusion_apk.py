#!/usr/bin/env python3
# SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
# SPDX-License-Identifier: GPL-3.0-or-later

"""Verify the Fusion APK and the unchanged Banana Native payload it embeds."""

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
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


def check_loader_names(archive, name, expected):
    missing = {value.encode() + b"\0" for value in expected}
    overlap = max(map(len, missing)) - 1
    tail = b""
    with archive.open(name) as stream:
        while missing:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            data = tail + chunk
            missing = {value for value in missing if value not in data}
            tail = data[-overlap:]
    require(not missing, f"{name}: missing isolated driver hook loader names: {sorted(expected)}")


def check_hook_libraries(archive):
    """Check both hook sets and their actual ELF dependencies after packaging."""
    hooks = {
        "libhook_impl.so": None,
        "libmain_hook.so": "libhook_impl.so",
        "libhokkaido_hook_impl.so": None,
        "libhokkaido_main_hook.so": "libhokkaido_hook_impl.so",
        "libfile_redirect_hook.so": "libhokkaido_hook_impl.so",
        "libgsl_alloc_hook.so": "libhokkaido_hook_impl.so",
    }
    prefix = "lib/arm64-v8a/"
    for name in hooks:
        require(prefix + name in archive.namelist(), f"Missing isolated driver hook: {name}")
    readelf = os.environ.get("READELF") or shutil.which("readelf") or shutil.which("llvm-readelf")
    require(readelf is not None, "readelf is required to verify driver hook dependencies")
    with tempfile.TemporaryDirectory(prefix="fusion-hooks-") as directory:
        for name, dependency in hooks.items():
            path = Path(directory) / name
            path.write_bytes(archive.read(prefix + name))
            result = subprocess.run([readelf, "--dynamic", "--wide", str(path)],
                                    capture_output=True, text=True,
                                    env={**os.environ, "LC_ALL": "C"})
            require(result.returncode == 0, f"Cannot inspect {name}: {result.stderr.strip()}")
            sonames = re.findall(r"\(SONAME\).*\[([^\]]+)\]", result.stdout)
            needed = set(re.findall(r"\(NEEDED\).*\[([^\]]+)\]", result.stdout))
            require(sonames == [name], f"{name}: unexpected ELF SONAME: {sonames}")
            if dependency:
                require(dependency in needed, f"{name}: must depend on {dependency}, got {sorted(needed)}")
                other = "libhook_impl.so" if dependency.startswith("libhokkaido_") else "libhokkaido_hook_impl.so"
                require(other not in needed, f"{name}: links the other engine's driver hook: {other}")
    check_loader_names(archive, prefix + "libyuzu-android.so",
                       ["libhokkaido_hook_impl.so", "libhokkaido_main_hook.so"])
    check_loader_names(archive, prefix + "libhokkaido_hook_impl.so", ["libhokkaido_hook_impl.so"])


def check_badging(path):
    text = path.read_text(encoding="utf-8")
    package = re.findall(r"^package:\s+name='([^']+)'", text, re.MULTILINE)
    sdk = re.findall(r"^(?:minSdkVersion|sdkVersion):[ \t]*'(\d+)'[ \t]*$", text, re.MULTILINE)
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
        check_hook_libraries(final)

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
        "isolated_driver_hooks": True,
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
