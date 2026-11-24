# SPDX-License-Identifier: GPL-3.0-or-later

import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch
import zipfile

import extract_banana_buildconfig as extractor


def dump(fields, class_name=extractor.CLASS):
    lines = [f"  Class descriptor  : '{class_name}'", "  Static fields     -"]
    for name, descriptor, literal in fields:
        lines += [f"      name          : '{name}'", f"      type          : '{descriptor}'",
                  f"      value         : {literal}"]
    lines += ["  Instance fields   -", "  Direct methods    -"]
    return "\n".join(lines)


class BuildConfigTests(unittest.TestCase):
    def test_literal_escaping_and_class_boundary(self):
        value = 'quote " slash \\ newline\n tab\t Omega \u03a9 emoji \U0001f34c'
        text = dump([("IGNORE", "I", "1")], "Lother/Before;")
        text += "\n" + dump([("NX_TEXT", "Ljava/lang/String;", json.dumps(value)),
                               ("DEBUG", "Z", "false"), ("VERSION_CODE", "I", "39")])
        text += "\n" + dump([("IGNORE_TOO", "I", "2")], "Lother/After;")
        count, fields = extractor.parse_dump(text)
        self.assertEqual(count, 1)
        self.assertEqual(set(fields), {"NX_TEXT", "VERSION_CODE"})
        self.assertEqual(json.loads(fields["NX_TEXT"][1]), value)

    def test_duplicate_field_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            extractor.parse_dump(dump([("VERSION_CODE", "I", "39"), ("VERSION_CODE", "I", "40")]))

    def test_bad_literal_is_rejected(self):
        for descriptor, literal in [("I", "3.5"), ("Z", "1"), ("Ljava/lang/String;", '"broken'),
                                    ("Ljava/lang/String;", "null")]:
            with self.subTest(descriptor=descriptor, literal=literal), self.assertRaises(ValueError):
                extractor.java_literal(descriptor, literal)

    def test_missing_class_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"test fixture")
            with patch.object(extractor.subprocess, "run", return_value=types.SimpleNamespace(stdout=dump([], "Lother;"))):
                with self.assertRaisesRegex(ValueError, "not found"):
                    extractor.extract(apk, Path("dexdump"))

    def test_multiple_dex_class_definitions_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"first fixture")
                archive.writestr("classes2.dex", b"second fixture")
            with patch.object(extractor.subprocess, "run", return_value=types.SimpleNamespace(stdout=dump([]))):
                with self.assertRaisesRegex(ValueError, "across DEX"):
                    extractor.extract(apk, Path("dexdump"))


if __name__ == "__main__":
    unittest.main()
