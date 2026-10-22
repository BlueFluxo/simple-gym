#!/usr/bin/env python3
"""Check that every pinned version is the newest stable release.

Run from anywhere:  python -I scripts/check-latest-versions.py

Reads [versions] in gradle/libs.versions.toml and the Gradle wrapper, looks each one up on
Google Maven / Maven Central (or services.gradle.org for Gradle) and prints one line per entry:

    name current latestStable status        (status is ok or UPDATE)

It also flags a new major line published under a different artifact group (SUCCESSORS), which a
plain version check can never see: Room 2 (androidx.room) became Room 3 (androidx.room3).

Exit status: 0 everything is current, 1 something needs an update or a lookup failed.
Standard library only; needs network access.
"""
import json
import re
import sys
import tomllib
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "gradle" / "libs.versions.toml"
WRAPPER = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"

GOOGLE = "https://dl.google.com/dl/android/maven2/"
CENTRAL = "https://repo1.maven.org/maven2/"
GRADLE_CURRENT = "https://services.gradle.org/versions/current"

# [versions] key -> (repo, "group:artifact" to ask for versions). Every key in the catalog must be here:
# a new, unlisted key is reported as a failed lookup so it cannot go unchecked.
PROBES = {
    "agp": (GOOGLE, "com.android.tools.build:gradle"),
    "kotlin": (CENTRAL, "org.jetbrains.kotlin:kotlin-stdlib"),
    "ksp": (CENTRAL, "com.google.devtools.ksp:symbol-processing-api"),
    "composeBom": (GOOGLE, "androidx.compose:compose-bom"),
    "room3": (GOOGLE, "androidx.room3:room3-runtime"),
    "sqlite": (GOOGLE, "androidx.sqlite:sqlite-framework"),
    "navigationCompose": (GOOGLE, "androidx.navigation:navigation-compose"),
    "activityCompose": (GOOGLE, "androidx.activity:activity-compose"),
    "lifecycle": (GOOGLE, "androidx.lifecycle:lifecycle-runtime-compose"),
    "coreKtx": (GOOGLE, "androidx.core:core-ktx"),
    "serializationJson": (CENTRAL, "org.jetbrains.kotlinx:kotlinx-serialization-json"),
    "coroutines": (CENTRAL, "org.jetbrains.kotlinx:kotlinx-coroutines-android"),
    "junit": (CENTRAL, "junit:junit"),
    "truth": (CENTRAL, "com.google.truth:truth"),
    "robolectric": (CENTRAL, "org.robolectric:robolectric"),
    "androidxTestExtJunit": (GOOGLE, "androidx.test.ext:junit"),
}

# Known new major lines published under a different group: (name, old group prefix, successor probe).
# Flagged when the catalog still uses the old group and not the successor.
SUCCESSORS = [
    ("room (androidx.room -> androidx.room3)", "androidx.room:", GOOGLE, "androidx.room3:room3-runtime"),
]

UNSTABLE = re.compile(r"(?i)alpha|beta|rc|dev|eap|snapshot|-M\d|preview")


def sort_key(version):
    """Segment-wise key: numbers compare numerically, text after numbers, and the two never raise."""
    key = []
    for part in re.split(r"[.\-_+]", version):
        if part.isdigit():
            key.append((0, int(part), ""))
        else:
            key.append((1, 0, part.lower()))
    return tuple(key)


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "simple-gym-version-check"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def latest_stable(repo, coordinate):
    group, artifact = coordinate.split(":")
    url = f"{repo}{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
    versions = [v.text for v in ET.fromstring(fetch(url)).iter("version") if v.text]
    stable = [v for v in versions if not UNSTABLE.search(v)]
    if not stable:
        raise ValueError(f"no stable release of {coordinate}")
    return max(stable, key=sort_key)


def gradle_wrapper_version():
    match = re.search(r"gradle-([^/\\-]+(?:-[^/\\-]+)*?)-(?:bin|all)\.zip", WRAPPER.read_text())
    if not match:
        raise ValueError("no distributionUrl in gradle-wrapper.properties")
    return match.group(1)


def main():
    catalog = tomllib.loads(CATALOG.read_text())
    failures = 0
    updates = 0

    def report(name, current, probe):
        nonlocal failures, updates
        try:
            latest = probe()
        except Exception as error:  # a failed lookup is a failure, never a silent "ok"
            print(f"{name} {current} ? FAILED ({error})")
            failures += 1
            return
        current_ok = sort_key(current) >= sort_key(latest)
        updates += 0 if current_ok else 1
        print(f"{name} {current} {latest} {'ok' if current_ok else 'UPDATE'}")

    for key, current in catalog["versions"].items():
        if key not in PROBES:
            print(f"{key} {current} ? FAILED (no probe for this key in PROBES)")
            failures += 1
            continue
        repo, coordinate = PROBES[key]
        report(key, current, lambda repo=repo, coordinate=coordinate: latest_stable(repo, coordinate))

    def gradle_latest():
        return json.loads(fetch(GRADLE_CURRENT))["version"]

    try:
        report("gradle", gradle_wrapper_version(), gradle_latest)
    except Exception as error:
        print(f"gradle ? ? FAILED ({error})")
        failures += 1

    modules = [lib["module"] for lib in catalog.get("libraries", {}).values() if "module" in lib]
    for name, old_prefix, repo, successor in SUCCESSORS:
        if any(m.startswith(old_prefix) for m in modules) and successor not in modules:
            try:
                latest = latest_stable(repo, successor)
                print(f"NOTE {name}: the catalog uses {old_prefix}* but {successor} {latest} exists")
                updates += 1
            except Exception as error:
                print(f"NOTE {name} ? FAILED ({error})")
                failures += 1

    return 1 if (updates or failures) else 0


if __name__ == "__main__":
    sys.exit(main())
