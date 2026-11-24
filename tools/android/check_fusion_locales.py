#!/usr/bin/env python3
# SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check the resource text shipped by the combined Android frontend.

The two Banana resource roots have equal source-set priority; app resources may
override the library. Reference-only arrays/aliases use their referenced string
translations, so they do not need copied locale-specific definitions. Coverage
means resource coverage and format safety, not a judgment of translation quality.
Only the explicitly required locales gate CI; other locales are reported in JSON.
"""

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


QUANTITIES = {"zero", "one", "two", "few", "many", "other"}
TEXT_KINDS = {"string", "plurals", "array"}
REFERENCE = re.compile(r"^@(?:(android):)?(string|plurals|array)/([\w.]+)$")
# java.util.Formatter: index, flags, width, precision, optional date/time prefix.
FORMAT = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(<]*)(\d+)?(?:\.(\d+))?([tT])?([a-zA-Z%])")


@dataclass
class Resource:
    kind: str
    name: str
    qualifier: str
    element: ET.Element
    path: Path

    @property
    def key(self):
        return self.kind, self.name

    @property
    def label(self):
        return f"{self.kind}/{self.name}"

    @property
    def translatable(self):
        return self.element.get("translatable", "true") != "false"


def text_of(element):
    # itertext includes styled spans and xliff:g placeholders, unlike .text.
    return "".join(element.itertext()).strip()


def format_arguments(text, formatted=True):
    """Return argument index -> conversion set, allowing positional reordering."""
    if not formatted:
        return {}
    result = {}
    automatic = 0
    previous = None
    for match in FORMAT.finditer(text):
        index, flags, _, _, date, conversion = match.groups()
        # Plain percentage prose ("100% is normal", "100 % the picture") is
        # not a Formatter call. Do not interpret the start of its next word as
        # a space-flag conversion. Explicitly indexed/width formats still count.
        if not index and flags == " " and match.end() < len(text) and text[match.end()].isalpha():
            continue
        if conversion in ("%", "n") and not date:
            continue
        if date:
            if conversion not in "HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc":
                raise ValueError(f"invalid date format conversion %{date}{conversion}")
            # Date fields can differ by locale but require the same argument type.
            conversion = "date"
        else:
            conversion = conversion.lower()
            if conversion not in "bhscoxdefga":
                raise ValueError(f"invalid format conversion %{conversion}")
            conversion = {"o": "d", "x": "d", "e": "f", "g": "f", "a": "f"}.get(conversion, conversion)
        if "<" in flags:
            if previous is None or index:
                raise ValueError("format reuses an argument before one is selected")
            number = previous
        elif index:
            number = int(index)
            if number < 1:
                raise ValueError("format argument indices start at 1")
        else:
            automatic += 1
            number = automatic
        previous = number
        result.setdefault(number, set()).add(conversion)
    return {str(key): sorted(value) for key, value in sorted(result.items())}


def resource_kind(element):
    if element.tag in ("string-array", "integer-array", "array"):
        return "array"
    if element.tag == "item":
        return element.get("type")
    return element.tag


def issue(code, resource=None, **detail):
    result = {"code": code, **detail}
    if resource is not None:
        result.update(resource=resource.label, path=str(resource.path))
    return result


def load_source_set(name, roots):
    resources = {}
    errors = []
    for root in roots:
        root = Path(root)
        if not root.is_dir():
            errors.append(issue("missing_resource_root", source_set=name, path=str(root)))
            continue
        for file in sorted(root.glob("values*/*.xml")):
            qualifier = file.parent.name.removeprefix("values").removeprefix("-")
            try:
                tree = ET.parse(file)
            except ET.ParseError as error:
                errors.append(issue("invalid_xml", source_set=name, path=str(file), detail=str(error)))
                continue
            for element in tree.getroot():
                kind = resource_kind(element)
                key = element.get("name")
                # public declarations and styleable declarations do not define resource text.
                if not key or kind in ("public", "declare-styleable", "eat-comment", "skip"):
                    continue
                resource = Resource(kind, key, qualifier, element, file)
                slot = qualifier, kind, key
                if slot in resources:
                    errors.append(issue("duplicate_resource", resource, source_set=name,
                                        qualifier=qualifier, first_path=str(resources[slot].path)))
                else:
                    resources[slot] = resource
    return resources, errors


def locale_qualifier(qualifier):
    # Do not count night/land/width variants as separate languages.
    match = re.match(r"^([a-z]{2,3}(?:-r[A-Z]{2})?|b\+[A-Za-z0-9+]+)(?:-|$)", qualifier)
    if not match or match.group(1) in {"car"}:
        return None
    return match.group(1)


def cells(resource):
    if resource.kind == "string" or resource.element.tag == "item":
        return [("text", resource.element)]
    if resource.kind == "plurals":
        return [(child.get("quantity", ""), child) for child in resource.element]
    return [(str(index), child) for index, child in enumerate(resource.element)]


def structure_errors(resource):
    errors = []
    if resource.kind not in ("array", "plurals") or resource.element.tag == "item":
        return errors
    children = list(resource.element)
    if any(child.tag != "item" for child in children):
        errors.append(issue("invalid_container_child", resource))
    if resource.kind == "plurals":
        quantities = [child.get("quantity") for child in children]
        if "other" not in quantities:
            errors.append(issue("missing_plural_other", resource))
        if any(quantity not in QUANTITIES for quantity in quantities):
            errors.append(issue("invalid_plural_quantity", resource, quantities=quantities))
        if len(set(quantities)) != len(quantities):
            errors.append(issue("duplicate_plural_quantity", resource, quantities=quantities))
    return errors


def inspect(source_sets, required=("zh-rCN", "zh-rTW")):
    """source_sets are (name, resource roots), library first, app last."""
    merged = {}
    errors = []
    for name, roots in source_sets:
        resources, problems = load_source_set(name, roots)
        # Android lint checks ExtraTranslation within each module, even when the
        # final APK could resolve a default from a dependency. Keep a translation
        # with the module that owns its default instead of relying on APK merging.
        module_defaults = {value.key for (qualifier, kind, _), value in resources.items()
                           if not locale_qualifier(qualifier) and kind in TEXT_KINDS}
        for (qualifier, kind, _), resource in resources.items():
            if (locale_qualifier(qualifier) in required and kind in TEXT_KINDS
                    and resource.key not in module_defaults):
                errors.append(issue("translation_without_module_default", resource,
                                    source_set=name, qualifier=qualifier))
        merged.update(resources)
        errors.extend(problems)
    base = {(kind, name): value for (qualifier, kind, name), value in merged.items()
            if not qualifier and kind in TEXT_KINDS and value.element.tag != "integer-array"}
    locales = sorted({locale_qualifier(q) for q, _, _ in merged if locale_qualifier(q)} | set(required))
    # Version/orientation-specific text needs a matching localized variant, too.
    variants = sorted({q for q, k, _ in merged if q and not locale_qualifier(q) and k in TEXT_KINDS})
    reports = {}

    for resource in base.values():
        errors.extend(structure_errors(resource))
        for cell, element in cells(resource):
            try:
                format_arguments(text_of(element), resource.element.get("formatted") != "false")
            except ValueError as error:
                errors.append(issue("invalid_base_format", resource, cell=cell, detail=str(error)))

    for locale in locales:
        problems = []
        missing = []
        expected = 0
        covered = 0
        seen_missing = set()
        reported = set()

        def resolve(key, variant=""):
            # A locale resource wins before an unqualified configuration fallback.
            for qualifier in (f"{locale}-{variant}" if variant else locale, locale, variant, ""):
                value = merged.get((qualifier, *key))
                if value is not None:
                    return value, qualifier.startswith(locale)
            return None, False

        def covered_text(text, localized, owner, location, variant, stack):
            reference = REFERENCE.fullmatch(text)
            if reference:
                if reference.group(1):
                    return True  # Android owns and localizes framework resources.
                key = reference.group(2), reference.group(3)
                if key in stack:
                    problems.append(issue("resource_reference_cycle", owner, cell=location,
                                          reference="/".join(key)))
                    return False
                value, translated = resolve(key, variant)
                if value is None:
                    problems.append(issue("missing_resource_reference", owner, cell=location,
                                          reference="/".join(key)))
                    return False
                return covered_resource(value, translated, variant, stack | {key})
            # Blank/quoted-empty array items are intentional empty slots, not English.
            return localized or not text or text == '""' or not owner.translatable

        def covered_resource(resource, localized, variant, stack):
            if not resource.translatable:
                return True
            return all(covered_text(text_of(element), localized, resource, cell, variant, stack)
                       for cell, element in cells(resource))

        def signature(element, owner, variant, stack=None):
            stack = set() if stack is None else stack
            value = text_of(element)
            reference = REFERENCE.fullmatch(value)
            if reference and not reference.group(1):
                key = reference.group(2), reference.group(3)
                target, _ = resolve(key, variant)
                if target is not None and target.kind == "string" and key not in stack:
                    return signature(target.element, target, variant, stack | {key})
            return format_arguments(value, owner.element.get("formatted") != "false")

        for variant in ["", *variants]:
            defaults = dict(base)
            if variant:
                defaults = {(kind, name): value for (q, kind, name), value in merged.items()
                            if q == variant and kind in TEXT_KINDS and value.element.tag != "integer-array"}
            for key, original in sorted(defaults.items()):
                if not original.translatable:
                    continue
                expected += 1
                translated, localized = resolve(key, variant)
                if translated and covered_resource(translated, localized, variant, {key}):
                    covered += 1
                else:
                    identity = variant, original.label
                    if identity not in seen_missing:
                        missing.append(issue("missing_translation", original, qualifier=variant))
                        seen_missing.add(identity)
                if not translated or not localized or (variant, key) in reported:
                    continue
                reported.add((variant, key))
                problems.extend(structure_errors(translated))
                if original.element.tag != translated.element.tag:
                    problems.append(issue("resource_shape_mismatch", translated,
                                          expected=original.element.tag, actual=translated.element.tag))
                    continue
                original_cells = dict(cells(original))
                translated_cells = dict(cells(translated))
                if original.kind == "array" and len(original_cells) != len(translated_cells):
                    problems.append(issue("array_length_mismatch", translated,
                                          expected=len(original_cells), actual=len(translated_cells)))
                for cell, element in translated_cells.items():
                    source = original_cells.get(cell)
                    if source is None and original.kind == "plurals":
                        source = original_cells.get("other")
                    if source is None:
                        continue
                    try:
                        want = signature(source, original, variant)
                        actual = signature(element, translated, variant)
                    except ValueError as error:
                        problems.append(issue("invalid_format", translated, cell=cell, detail=str(error)))
                        continue
                    if want != actual:
                        problems.append(issue("format_arguments_mismatch", translated, cell=cell,
                                              expected=want, actual=actual))
        reports[locale] = {"total": expected, "covered": covered,
                           "coverage_percent": round(100 * covered / expected, 2) if expected else 100.0,
                           "missing": missing, "issues": problems}

    required = list(required)
    ok = not errors and all(not reports[locale]["missing"] and not reports[locale]["issues"]
                            for locale in required)
    return {"ok": ok, "required_locales": required, "errors": errors, "locales": reports,
            "scope": "Per-module default ownership and merged app/Banana resource coverage, structure and format arguments; not translation quality or all runtime-generated text."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--required", nargs="+", default=["zh-rCN", "zh-rTW"])
    parser.add_argument("--json", type=Path, help="Write full coverage report, including other languages")
    args = parser.parse_args()
    report = inspect([
        ("banana:main", [args.repo / "src/android/banana/upstream/main/res",
                         args.repo / "src/android/banana/src/main/res"]),
        ("app:main", [args.repo / "src/android/app/src/main/res"]),
    ], args.required)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for error in report["errors"]:
        print(json.dumps(error, ensure_ascii=False), file=sys.stderr)
    for locale in report["required_locales"]:
        item = report["locales"][locale]
        print(f"{locale}: {item['covered']}/{item['total']} resources covered; "
              f"{len(item['missing'])} missing, {len(item['issues'])} format/structure issues")
        for error in item["missing"] + item["issues"]:
            print(json.dumps(error, ensure_ascii=False), file=sys.stderr)
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
