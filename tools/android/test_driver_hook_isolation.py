# SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
# SPDX-License-Identifier: GPL-3.0-or-later

import io
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

from verify_fusion_apk import check_hook_libraries, VerificationError


@unittest.skipUnless(shutil.which("cc") and shutil.which("readelf"), "requires host ELF build tools")
class DriverHookTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="hook-test-")
        cls.root = Path(cls.directory.name)
        cls.payload = {}
        for name in ("libhook_impl.so", "libhokkaido_hook_impl.so"):
            cls.payload[name] = cls.build(name, "void hook_entry(void) {}")
        for name, dependency in (
            ("libmain_hook.so", "libhook_impl.so"),
            ("libhokkaido_main_hook.so", "libhokkaido_hook_impl.so"),
            ("libfile_redirect_hook.so", "libhokkaido_hook_impl.so"),
            ("libgsl_alloc_hook.so", "libhokkaido_hook_impl.so"),
        ):
            cls.payload[name] = cls.build(name, "void hook_entry(void); void run(void) { hook_entry(); }", dependency)
        # Architecture is checked separately; this exercises the loader-name contract.
        cls.payload["libyuzu-android.so"] = b"libhokkaido_hook_impl.so\0libhokkaido_main_hook.so\0"
        cls.cross_linked = cls.build("libhokkaido_main_hook.so",
                                    "void hook_entry(void); void run(void) { hook_entry(); }",
                                    "libhook_impl.so")
        cls.wrong_soname = cls.build("libwrong.so", "void hook_entry(void) {}")

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    @classmethod
    def build(cls, name, source, dependency=None):
        path = cls.root / name
        command = ["cc", "-x", "c", "-", "-shared", "-fPIC", f"-Wl,-soname,{name}",
                   "-L", str(cls.root), "-o", str(path)]
        if dependency:
            command.append(f"-l:{dependency}")
        subprocess.run(command, input=source, text=True, check=True, capture_output=True)
        return path.read_bytes()

    def check(self, payload):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            for name, value in payload.items():
                archive.writestr("lib/arm64-v8a/" + name, value)
        data.seek(0)
        with zipfile.ZipFile(data) as archive:
            check_hook_libraries(archive)

    def test_matching_engine_pairs(self):
        self.check(self.payload)

    def test_missing_hokkaido_hooks(self):
        payload = dict(self.payload)
        del payload["libhokkaido_hook_impl.so"]
        with self.assertRaisesRegex(VerificationError, "Missing isolated driver hook"):
            self.check(payload)

    def test_hokkaido_hook_linking_banana_is_rejected(self):
        with self.assertRaisesRegex(VerificationError, "must depend on libhokkaido_hook_impl.so"):
            self.check({**self.payload, "libhokkaido_main_hook.so": self.cross_linked})

    def test_renamed_file_with_old_soname_is_rejected(self):
        with self.assertRaisesRegex(VerificationError, "unexpected ELF SONAME"):
            self.check({**self.payload, "libhokkaido_hook_impl.so": self.wrong_soname})

    def test_old_loader_names_are_rejected(self):
        with self.assertRaisesRegex(VerificationError, "missing isolated driver hook loader names"):
            self.check({**self.payload, "libyuzu-android.so": b"libhook_impl.so\0libmain_hook.so\0"})


if __name__ == "__main__":
    unittest.main()
