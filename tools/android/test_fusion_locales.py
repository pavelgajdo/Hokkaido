# SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
# SPDX-License-Identifier: GPL-3.0-or-later

from pathlib import Path
import tempfile
import unittest

from check_fusion_locales import format_arguments, inspect


class FusionLocaleTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="fusion-locales-")
        self.root = Path(self.directory.name)
        for source in ("upstream", "bridge", "app"):
            (self.root / source).mkdir()

    def tearDown(self):
        self.directory.cleanup()

    def write(self, source, qualifier, xml, file="strings.xml"):
        path = self.root / source / ("values" + ("-" + qualifier if qualifier else "")) / file
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("<resources>" + xml + "</resources>", encoding="utf-8")

    def report(self):
        return inspect([("banana:main", [self.root / "upstream", self.root / "bridge"]),
                        ("app:main", [self.root / "app"])], required=("zh-rCN",))

    def test_app_overlay_is_allowed_and_other_locales_do_not_gate(self):
        self.write("upstream", "", '<string name="hello">Hello %1$s</string>')
        self.write("upstream", "zh-rCN", '<string name="hello">你好 %1$s</string>')
        self.write("app", "zh-rCN", '<string name="hello">欢迎 %1$s</string>')
        self.write("app", "fr", '<string name="another">Autre</string>')
        report = self.report()
        self.assertTrue(report["ok"])
        self.assertEqual(len(report["locales"]["fr"]["missing"]), 1)

    def test_duplicate_across_banana_main_roots_is_rejected(self):
        self.write("upstream", "", '<string name="hello">Hello</string>')
        self.write("bridge", "", '<string name="hello">Oops</string>')
        self.write("app", "zh-rCN", '<string name="hello">你好</string>')
        report = self.report()
        self.assertFalse(report["ok"])
        self.assertEqual(report["errors"][0]["code"], "duplicate_resource")

    def test_duplicate_in_same_file_is_rejected(self):
        self.write("app", "", '<string name="x">X</string><string name="x">Y</string>')
        self.assertEqual(self.report()["errors"][0]["code"], "duplicate_resource")

    def test_reference_arrays_use_localized_strings_and_nontranslatable_labels(self):
        self.write("app", "", '''
            <string name="off">Off</string><string name="gpu" translatable="false">GPU</string>
            <string-array name="names"><item>@string/off</item><item>@string/gpu</item><item>""</item></string-array>
            <string name="alias">@string/off</string>''')
        self.write("app", "zh-rCN", '<string name="off">关闭</string>')
        self.assertTrue(self.report()["ok"])
        self.write("app", "zh-rCN", '<string name="unrelated">其他</string>')
        missing = {item["resource"] for item in self.report()["locales"]["zh-rCN"]["missing"]}
        self.assertEqual(missing, {"string/off", "string/alias", "array/names"})

    def test_missing_literal_array_and_wrong_element_count_are_rejected(self):
        self.write("app", "", '<string-array name="names"><item>Off</item><item>On</item></string-array>')
        self.assertFalse(self.report()["ok"])
        self.write("app", "zh-rCN", '<string-array name="names"><item>关闭</item></string-array>')
        report = self.report()
        self.assertFalse(report["ok"])
        self.assertEqual(report["locales"]["zh-rCN"]["issues"][0]["code"], "array_length_mismatch")

    def test_chinese_plural_can_have_only_other_but_must_keep_arguments(self):
        self.write("app", "", '''<plurals name="games">
            <item quantity="one">%1$d game: %2$s</item>
            <item quantity="other">%1$d games: %2$s</item></plurals>''')
        self.write("app", "zh-rCN", '''<plurals name="games">
            <item quantity="other">%2$s，共 %1$d 个游戏</item></plurals>''')
        self.assertTrue(self.report()["ok"])
        self.write("app", "zh-rCN", '''<plurals name="games">
            <item quantity="other">%1$s 个游戏</item></plurals>''')
        self.assertEqual(self.report()["locales"]["zh-rCN"]["issues"][0]["code"],
                         "format_arguments_mismatch")

    def test_plural_quantity_structure_is_checked(self):
        self.write("app", "", '<plurals name="games"><item quantity="other">Games</item></plurals>')
        self.write("app", "zh-rCN", '''<plurals name="games">
            <item quantity="one">游戏</item><item quantity="one">游戏</item></plurals>''')
        codes = {item["code"] for item in self.report()["locales"]["zh-rCN"]["issues"]}
        self.assertEqual(codes, {"duplicate_plural_quantity", "missing_plural_other"})

    def test_styled_placeholders_and_array_element_formats_are_checked(self):
        self.write("app", "", '''<string name="text">Hello <b>%1$s</b></string>
            <string-array name="a"><item>%1$d items</item></string-array>''')
        self.write("app", "zh-rCN", '''<string name="text">你好 <b>%1$d</b></string>
            <string-array name="a"><item>%1$s 项</item></string-array>''')
        issues = self.report()["locales"]["zh-rCN"]["issues"]
        self.assertEqual(len(issues), 2)
        self.assertTrue(all(item["code"] == "format_arguments_mismatch" for item in issues))

    def test_missing_reference_and_cycle_are_not_counted_as_translated(self):
        self.write("app", "", '<string name="a">@string/b</string><string name="b">@string/a</string>')
        report = self.report()
        self.assertFalse(report["ok"])
        self.assertIn("resource_reference_cycle", {x["code"] for x in report["locales"]["zh-rCN"]["issues"]})
        self.write("app", "", '<string name="a">@string/gone</string>')
        self.assertEqual(self.report()["locales"]["zh-rCN"]["issues"][0]["code"], "missing_resource_reference")

    def test_variant_text_requires_translated_variant_or_localized_fallback(self):
        self.write("app", "", '<string name="a">Hello</string>')
        self.write("app", "land", '<string name="land">Landscape</string>')
        self.write("app", "zh-rCN", '<string name="a">你好</string>')
        self.assertFalse(self.report()["ok"])
        self.write("app", "zh-rCN-land", '<string name="land">横屏</string>')
        self.assertTrue(self.report()["ok"])

    def test_plain_percent_and_formatted_false_do_not_invent_arguments(self):
        self.assertEqual(format_arguments("100% is normal; below 100 % the picture shrinks"), {})
        self.assertEqual(format_arguments("literal %1$s", False), {})
        self.assertEqual(format_arguments("%1$s: %2$,d / %<x %% %n"), {"1": ["s"], "2": ["d"]})
        self.assertEqual(format_arguments("%s %d"), {"1": ["s"], "2": ["d"]})
        self.assertEqual(format_arguments("%1$tF"), {"1": ["date"]})


if __name__ == "__main__":
    unittest.main()
