#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Read the embedded Banana Native release constants with Android's dexdump."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

CLASS = "Ldev/nxnative/android/BuildConfig;"
EXCLUDED = {"APPLICATION_ID", "BUILD_TYPE", "DEBUG"}
TYPES = {"Z": "boolean", "I": "int", "Ljava/lang/String;": "String"}
REQUIRED = {
    "VERSION_NAME", "VERSION_CODE", "NX_DMSH_CHAIN", "NX_DMSH_VK_CHAIN",
    "NX_DMSH_VK_ABI", "NX_ENV_SWITCHES", "NX_PROFILES", "NX_REWRITER",
    "NX_MIN_SDK", "NX_PACK_ENGINE", "NX_PLAY", "NX_LIVE", "NX_LSFG",
}


def java_literal(descriptor, literal):
    """Validate the value and escape it once for a Java source literal."""
    if descriptor not in TYPES:
        raise ValueError(f"Unsupported BuildConfig field type: {descriptor}")
    try:
        value = json.loads(literal)
    except (ValueError, TypeError) as error:
        raise ValueError("Invalid BuildConfig constant literal") from error
    if descriptor == "Ljava/lang/String;":
        if not isinstance(value, str):
            raise ValueError("Expected a non-null String constant")
    elif descriptor == "Z":
        if type(value) is not bool:
            raise ValueError("Expected a Boolean constant")
    elif type(value) is not int or not -(1 << 31) <= value < (1 << 31):
        raise ValueError("Expected a 32-bit integer constant")
    return json.dumps(value, ensure_ascii=True)


def parse_dump(text):
    """Return the number of target classes and their checked static fields."""
    found = 0
    active = False
    static = False
    fields = {}
    seen = set()
    name = descriptor = literal = None

    def finish_field():
        nonlocal name, descriptor, literal
        if name is None:
            return
        if name in seen:
            raise ValueError(f"Duplicate BuildConfig field: {name}")
        seen.add(name)
        if name not in EXCLUDED:
            if descriptor is None or literal is None:
                raise ValueError(f"Missing type or value for BuildConfig field: {name}")
            if re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", name) is None:
                raise ValueError("Invalid BuildConfig field name")
            fields[name] = (TYPES.get(descriptor), java_literal(descriptor, literal))
        name = descriptor = literal = None

    for line in text.splitlines():
        match = re.match(r"\s*Class descriptor\s*:\s*'([^']+)'\s*$", line)
        if match:
            finish_field()
            active = match[1] == CLASS
            static = False
            if active:
                found += 1
                if found > 1:
                    raise ValueError("Multiple Banana Native BuildConfig classes")
            continue
        if not active:
            continue
        if re.match(r"\s*Static fields\s*-", line):
            static = True
            continue
        if re.match(r"\s*(?:Instance fields|Direct methods|Virtual methods)\s*-", line):
            finish_field()
            static = False
            continue
        if not static:
            continue
        match = re.match(r"\s*(name|type|value)\s*:\s*(.*)$", line)
        if match is None:
            continue
        key, value = match.groups()
        if key == "name":
            finish_field()
            if len(value) < 2 or not value.startswith("'") or not value.endswith("'"):
                raise ValueError("Malformed BuildConfig field name")
            name = value[1:-1]
        elif key == "type":
            if len(value) < 2 or not value.startswith("'") or not value.endswith("'"):
                raise ValueError("Malformed BuildConfig field type")
            descriptor = value[1:-1]
        else:
            literal = value
    finish_field()
    return found, fields


def extract(apk, dexdump, expected_version=None):
    found = 0
    fields = {}
    with tempfile.TemporaryDirectory(prefix="banana-dex-") as directory:
        with zipfile.ZipFile(apk) as archive:
            names = sorted(name for name in archive.namelist()
                           if re.fullmatch(r"classes(?:[2-9][0-9]*|1[0-9]+)?\.dex", name))
            if not names or len(names) != len(set(names)):
                raise ValueError("APK has no DEX files or duplicate DEX entries")
            for name in names:
                path = Path(directory) / name
                path.write_bytes(archive.read(name))
                result = subprocess.run([str(dexdump), str(path)], check=True,
                                        capture_output=True, text=True, encoding="utf-8")
                count, current = parse_dump(result.stdout)
                found += count
                if found > 1:
                    raise ValueError("Multiple Banana Native BuildConfig classes across DEX files")
                fields.update(current)
    if found != 1:
        raise ValueError("Banana Native BuildConfig class was not found in the APK")
    missing = REQUIRED - fields.keys()
    if missing:
        raise ValueError("Missing BuildConfig fields: " + ", ".join(sorted(missing)))
    version = json.loads(fields["VERSION_NAME"][1])
    if expected_version is not None and version != expected_version:
        raise ValueError(f"Wrong Banana Native version: expected {expected_version}, found {version}")
    for key in ("NX_DMSH_CHAIN", "NX_DMSH_VK_CHAIN", "NX_DMSH_VK_ABI"):
        value = json.loads(fields[key][1])
        if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{12}", value) is None:
            raise ValueError(f"Invalid shader-chain value: {key}")
    return fields


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("dexdump", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--expected-version")
    args = parser.parse_args()
    fields = extract(args.apk, args.dexdump, args.expected_version)
    lines = ["# BuildConfig constants from the pinned Banana Native release APK."]
    lines.extend(f"{name} {typ} {literal}" for name, (typ, literal) in sorted(fields.items()))
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Read {len(fields)} Banana Native BuildConfig constants")


if __name__ == "__main__":
    main()
