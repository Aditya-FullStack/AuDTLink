#!/usr/bin/env python3
"""Bump the release version everywhere it is written, in one command.

    python3 tools/bump_version.py 4        -> 4.0
    python3 tools/bump_version.py 4.1

Touches the three places that must stay in sync:

    android/app/build.gradle.kts            versionCode, versionName
    android/app/src/main/res/values/strings.xml   app_name (launcher label)

Then it prints what changed and reminds you to commit. The CI workflow turns
the new versionName into a GitHub release tag, which is what the in-app update
check reads.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
GRADLE = ROOT / "android" / "app" / "build.gradle.kts"
STRINGS = ROOT / "android" / "app" / "src" / "main" / "res" / "values" / "strings.xml"


def label_for(version: str) -> str:
    """3.0 -> AuDTLink v3 ; 4.1 -> AuDTLink v4.1"""
    short = version[:-2] if version.endswith(".0") else version
    return "AuDTLink v%s" % short


def main(argv: list) -> int:
    if len(argv) != 1:
        print(__doc__)
        return 1

    version = argv[0].lstrip("vV")
    if not re.fullmatch(r"\d+(\.\d+)*", version):
        print("error: version must look like 4 or 4.1, got %r" % argv[0])
        return 1
    if "." not in version:
        version += ".0"

    gradle = GRADLE.read_text(encoding="utf-8")
    strings = STRINGS.read_text(encoding="utf-8")

    m_code = re.search(r"versionCode = (\d+)", gradle)
    m_name = re.search(r'versionName = "([^"]+)"', gradle)
    if not m_code or not m_name:
        print("error: could not find versionCode/versionName in %s" % GRADLE)
        return 1

    old_code, old_name = int(m_code.group(1)), m_name.group(1)
    new_code = old_code + 1
    new_label = label_for(version)

    gradle = gradle.replace(m_code.group(0), "versionCode = %d" % new_code)
    gradle = gradle.replace(m_name.group(0), 'versionName = "%s"' % version)
    strings = re.sub(r"<string name=\"app_name\">[^<]*</string>",
                     '<string name="app_name">%s</string>' % new_label, strings)

    GRADLE.write_text(gradle, encoding="utf-8")
    STRINGS.write_text(strings, encoding="utf-8")

    print("versionCode : %d -> %d" % (old_code, new_code))
    print("versionName : %s -> %s" % (old_name, version))
    print("app label   : %s" % new_label)
    print("release tag : v%s" % version)
    print()
    print("next: git commit + push to main; CI builds, tests, and publishes v%s." % version)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
