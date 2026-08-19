#!/usr/bin/env python3
"""Capture-gap audit for the Sinnix phone app (sinnix-66ki).

Cross-references three things pulled straight from source, not from a
hand-maintained list that would drift the moment a lane is added:

  1. Every ``Events.record(ctx, "<kind>", ...)`` call site -> the set of
     event kinds this app actually emits.
  2. Every ``<uses-permission>`` in AndroidManifest.xml -> the set of
     system surfaces this app is entitled to read.
  3. Every ``getSystemService(XManager::class.java)`` call site -> the set
     of system surfaces this app actually reads.

A permission or a getSystemService call is a *candidate* gap when nothing
in the emitted-kind vocabulary plausibly corresponds to it. "Plausibly"
is decided by keyword tokens derived from the surface's own name (e.g.
``ACCESS_COARSE_LOCATION`` -> ``location``), checked against a vocabulary
built ONLY from string literals inside files that already call
Events.record -- this is what catches the exact bug class the operator
found by accident: an API is called, the result is used for a local
decision, and it never becomes a captured event, because the file doing
the reading is a UI/status file, not a capture lane.

This tool cannot see what other apps on the device already collect (see
README note on aw-android). A surface with no local match is reported as
a CANDIDATE gap, never asserted as a confirmed capture hole -- that
distinction is load-bearing, not decoration.

Usage:
    python3 tools/capture_audit.py [--repo-root PATH] [--json] [--no-vocab]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from xml.etree import ElementTree as ET

KOTLIN_ROOT = "app/src/main/kotlin"
MANIFEST_PATH = "app/src/main/AndroidManifest.xml"

EVENTS_RECORD_RE = re.compile(r'Events\.record\(')
FIRST_STRING_RE = re.compile(r'"([^"]+)"')
GET_SYSTEM_SERVICE_RE = re.compile(
    r'getSystemService\(\s*([A-Za-z][A-Za-z0-9_]*)::class\.java\s*\)'
)
# snake_case literal, at least 3 chars, used to build the local vocabulary.
SNAKE_LITERAL_RE = re.compile(r'"([a-z][a-z0-9]*(?:_[a-z0-9]+)+|[a-z][a-z0-9]{2,})"')

# Surfaces that are not part of passive/background capture at all (instrument
# UI, sharing, network transport, storage plumbing) -- flagging these would
# just be noise on every run, so they are excluded from the audit rather than
# left to show up as permanent, ignorable "gaps".
PERMISSION_EXCLUDE = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.WAKE_LOCK",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.HIGH_SAMPLING_RATE_SENSORS",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.USE_EXACT_ALARM",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.VIBRATE",
    "com.termux.permission.RUN_COMMAND",
    # Instrument-only sensing (finger-on-camera PPG, torch CFF): operator-run
    # foreground probes, not passive background capture, per the bead scope.
    "android.permission.CAMERA",
    "android.permission.FLASHLIGHT",
    # Modifiers on the Health Connect read grant, not record types of their
    # own -- the manifest's own comment calls these two out explicitly
    # ("the two that are not record types"). Nothing should ever emit a
    # health_data_history or health_data_in_background kind.
    "android.permission.health.READ_HEALTH_DATA_HISTORY",
    "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
}

# getSystemService classes that are pure UI/plumbing reads with no plausible
# passive-capture event of their own (e.g. AudioManager for playback routing
# during an instrument run). Excluded from the audit for the same reason as
# PERMISSION_EXCLUDE above.
API_EXCLUDE = {
    "AudioManager",       # instrument playback/output routing, not a capture lane
    "CameraManager",      # instrument-only (PPG/CFF), see PERMISSION_EXCLUDE
}

# Manual keyword overrides where the surface's own name tokenizes to
# something too broad or too narrow to match the vocabulary well. Add to
# this only when the auto-tokenizer demonstrably gets a *known-good* surface
# wrong -- it is a correction table, not a place to hand-solve new surfaces.
KEYWORD_ALIASES: dict[str, list[str]] = {
    "android.permission.RECORD_AUDIO": ["chunk", "speech", "utterance", "ambient"],
    "android.permission.ACCESS_COARSE_LOCATION": ["location"],
    "android.permission.BLUETOOTH_CONNECT": ["hr", "heart"],
    "android.permission.PACKAGE_USAGE_STATS": ["usage", "screen", "keyguard", "activity"],
    "android.permission.health.READ_OXYGEN_SATURATION": ["spo2"],
    "PowerManager": ["power", "interactive", "screen", "thermal", "sleep"],
    "SensorManager": ["ambient", "lux", "motion"],
    "BluetoothManager": ["hr", "heart"],
    "UsageStatsManager": ["usage", "screen", "keyguard", "activity"],
    "LocationManager": ["location"],
    "AlarmManager": ["sleep_alarm", "alarm"],
    "NotificationManager": ["notification", "estate_notification"],
    "ConnectivityManager": ["network", "connect", "transport", "upload"],
}

STOPWORDS = {
    "access", "read", "write", "permission", "android", "health", "manager",
    "service", "system", "the", "app", "data", "get", "use",
}


def tokenize(name: str) -> list[str]:
    """Turn a permission constant or a Manager class name into lowercase word tokens."""
    base = name.rsplit(".", 1)[-1]  # drop android.permission. / android.permission.health.
    # split PascalCase and SNAKE_CASE alike
    words = re.findall(r"[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+(?![a-z])|[0-9]+", base)
    return [w.lower() for w in words if w.lower() not in STOPWORDS and len(w) > 1]


@dataclass
class EventSite:
    kind: str
    file: str
    line: int


@dataclass
class ApiSite:
    cls: str
    file: str
    line: int
    in_capture_file: bool


@dataclass
class Surface:
    kind: str  # "permission" | "api"
    name: str
    sites: list = field(default_factory=list)
    keywords: list = field(default_factory=list)
    matched_vocab: list = field(default_factory=list)
    status: str = "unknown"  # confirmed_emitted | confirmed_ui_only | candidate_gap | resolved_known


def find_events_record_kinds(kotlin_root: Path) -> tuple[list[EventSite], set[Path]]:
    sites: list[EventSite] = []
    capture_files: set[Path] = set()
    for f in sorted(kotlin_root.rglob("*.kt")):
        text = f.read_text(encoding="utf-8")
        lines = text.splitlines()
        for m in EVENTS_RECORD_RE.finditer(text):
            snippet = text[m.end():m.end() + 200]
            sm = FIRST_STRING_RE.search(snippet)
            if not sm:
                continue
            line_no = text.count("\n", 0, m.start()) + 1
            sites.append(EventSite(kind=sm.group(1), file=str(f), line=line_no))
            capture_files.add(f)
    return sites, capture_files


def find_system_service_sites(kotlin_root: Path, capture_files: set[Path]) -> list[ApiSite]:
    sites: list[ApiSite] = []
    for f in sorted(kotlin_root.rglob("*.kt")):
        text = f.read_text(encoding="utf-8")
        for m in GET_SYSTEM_SERVICE_RE.finditer(text):
            line_no = text.count("\n", 0, m.start()) + 1
            sites.append(
                ApiSite(cls=m.group(1), file=str(f), line=line_no, in_capture_file=f in capture_files)
            )
    return sites


def build_vocabulary(capture_files: set[Path]) -> set[str]:
    vocab: set[str] = set()
    for f in capture_files:
        text = f.read_text(encoding="utf-8")
        for m in SNAKE_LITERAL_RE.finditer(text):
            vocab.add(m.group(1))
    return vocab


def parse_permissions(manifest_path: Path) -> list[str]:
    ns = {"android": "http://schemas.android.com/apk/res/android"}
    tree = ET.parse(manifest_path)
    root = tree.getroot()
    perms = []
    for el in root.findall("uses-permission"):
        name = el.get(f"{{{ns['android']}}}name")
        if name:
            perms.append(name)
    return perms


def build_surfaces(
    permissions: list[str], api_sites: list[ApiSite], vocab: set[str]
) -> list[Surface]:
    surfaces: list[Surface] = []

    for perm in permissions:
        if perm in PERMISSION_EXCLUDE:
            continue
        kws = list(dict.fromkeys(tokenize(perm) + KEYWORD_ALIASES.get(perm, [])))
        s = Surface(kind="permission", name=perm, keywords=kws)
        surfaces.append(s)

    by_class: dict[str, list[ApiSite]] = {}
    for site in api_sites:
        if site.cls in API_EXCLUDE:
            continue
        by_class.setdefault(site.cls, []).append(site)

    for cls, sites in sorted(by_class.items()):
        kws = list(dict.fromkeys(tokenize(cls) + KEYWORD_ALIASES.get(cls, [])))
        s = Surface(kind="api", name=cls, sites=[(x.file, x.line) for x in sites], keywords=kws)
        s.matched_vocab = []  # filled below
        surfaces.append(s)

    for s in surfaces:
        for kw in s.keywords:
            hits = sorted(v for v in vocab if kw in v)
            s.matched_vocab.extend(hits)
        s.matched_vocab = sorted(set(s.matched_vocab))
        if s.matched_vocab:
            s.status = "confirmed_emitted"
        else:
            s.status = "candidate_gap"

    return surfaces


# Findings already settled in the bead -- the tool must reproduce these as
# resolved without re-flagging them, or it isn't trustworthy for the sweep.
KNOWN_RESOLVED = {
    ("api", "PowerManager"): "screen-interactive state: fixed via UsageLane (usage/screen_interactive, "
    "screen_non_interactive) and used directly in PassiveLanes.sleep()",
    ("api", "SensorManager"): "lux/motion summary: AmbientSensors emits ambient_context with "
    "lux_mean/lux_min/lux_max/motion_rms/motion_max per window",
}

# Surfaces already known, from a 2026-08-19 device check (`adb pm list
# packages -3`), to have NO existing dedicated collector app on the device --
# so a candidate gap here is not softened by "something else covers it".
DEVICE_CHECKED_NO_COLLECTOR = {
    "android.permission.BLUETOOTH_CONNECT",
    "ConnectivityManager",
    "NotificationManager",
    "AlarmManager",
}

# Standard Android capture-relevant surfaces this app does NOT currently
# declare a permission for or reference via getSystemService at all. The
# manifest/API diff above is structurally blind to these -- a permission
# never requested and a class never imported leave no residue to grep for.
# This catalog exists so the sweep still surfaces them: for each, checked
# whether ANY token from `search_terms` occurs anywhere in the Kotlin tree
# (not just capture files), so a partially-started or planned integration
# is reported as "referenced" rather than silently matched as absent.
#
# All entries below were checked against the device on 2026-08-19 via
# `adb pm list packages -3` and confirmed to have NO existing dedicated
# collector app installed (com.llamalab.automate is present but is not
# configured as a capture pipeline for any of these -- a caveat, not a
# solved case). Add an entry here when a new standard Android surface is
# identified as plausibly capture-worthy; remove it once either an event
# kind exists for it or the operator explicitly declines it.
ZERO_FOOTPRINT_CATALOG = [
    {
        "name": "MediaSessionManager (now-playing / media transport state)",
        "search_terms": ["MediaSessionManager", "getActiveSessions"],
    },
    {
        "name": "WifiManager (SSID / wifi state)",
        "search_terms": ["WifiManager"],
    },
    {
        "name": "TelephonyManager (call state / network type)",
        "search_terms": ["TelephonyManager"],
    },
    {
        "name": "BluetoothAdapter general state changes (ACTION_STATE_CHANGED)",
        "search_terms": ["BluetoothAdapter.ACTION_STATE_CHANGED", "BluetoothAdapter"],
    },
    {
        "name": "ACTION_USER_PRESENT (unlock broadcast)",
        "search_terms": ["ACTION_USER_PRESENT"],
    },
    {
        "name": "ACTION_HEADSET_PLUG (headset plug/unplug)",
        "search_terms": ["ACTION_HEADSET_PLUG"],
    },
    {
        "name": "ACTION_PACKAGE_ADDED / ACTION_PACKAGE_REMOVED (install/uninstall)",
        "search_terms": ["ACTION_PACKAGE_ADDED", "ACTION_PACKAGE_REMOVED"],
    },
    {
        "name": "ACTION_AIRPLANE_MODE_CHANGED",
        "search_terms": ["ACTION_AIRPLANE_MODE_CHANGED"],
    },
    {
        "name": "Ringer mode / Do Not Disturb (AudioManager.getRingerMode, "
        "NotificationManager.getCurrentInterruptionFilter)",
        "search_terms": ["getRingerMode", "getCurrentInterruptionFilter", "RINGER_MODE_CHANGED"],
    },
    {
        "name": "Doze transitions (ACTION_DEVICE_IDLE_MODE_CHANGED)",
        "search_terms": ["ACTION_DEVICE_IDLE_MODE_CHANGED", "isDeviceIdleMode"],
    },
    {
        "name": "Raw magnetic field sensor (TYPE_MAGNETIC_FIELD)",
        "search_terms": ["TYPE_MAGNETIC_FIELD"],
    },
    {
        "name": "Proximity sensor (TYPE_PROXIMITY)",
        "search_terms": ["TYPE_PROXIMITY"],
    },
    {
        "name": "Step counter / significant motion (TYPE_STEP_COUNTER, TYPE_SIGNIFICANT_MOTION)",
        "search_terms": ["TYPE_STEP_COUNTER", "TYPE_SIGNIFICANT_MOTION"],
    },
]


def audit_zero_footprint_catalog(kotlin_root: Path) -> list[dict]:
    """For each catalog entry, check whether it has ANY footprint in the source tree."""
    all_text = "\n".join(f.read_text(encoding="utf-8") for f in kotlin_root.rglob("*.kt"))
    results = []
    for entry in ZERO_FOOTPRINT_CATALOG:
        hits = [t for t in entry["search_terms"] if t in all_text]
        results.append({**entry, "referenced": bool(hits), "matched_terms": hits})
    return results


def render_report(
    surfaces: list[Surface], event_sites: list[EventSite], zero_footprint: list[dict]
) -> str:
    kinds = sorted({e.kind for e in event_sites})
    lines = []
    lines.append("# Phone app capture-gap audit\n")
    lines.append(f"Emitted event kinds ({len(kinds)}):\n")
    lines.append(", ".join(f"`{k}`" for k in kinds))
    lines.append("")

    confirmed = [s for s in surfaces if s.status == "confirmed_emitted"]
    gaps = [s for s in surfaces if s.status == "candidate_gap"]

    lines.append(f"\n## Confirmed-emitted surfaces ({len(confirmed)})\n")
    lines.append("| surface | type | matched vocabulary |")
    lines.append("|---|---|---|")
    for s in confirmed:
        note = KNOWN_RESOLVED.get((s.kind, s.name))
        tag = f" _(known-resolved: {note})_" if note else ""
        lines.append(f"| `{s.name}` | {s.kind} | {', '.join(s.matched_vocab[:8])}{tag} |")

    lines.append(f"\n## Candidate gaps ({len(gaps)})\n")
    lines.append(
        "These are surfaces (permission granted, or system API referenced) with no "
        "keyword match in the local capture vocabulary. NOT confirmed uncaptured -- "
        "per the established method lesson, another app on the device may already "
        "cover it (see `sinnix-phone` scripts / aw-android precedent). Each needs a "
        "device-side check (`adb pm list packages -3` + manual review) before it is "
        "treated as a real hole.\n"
    )
    lines.append("| surface | type | keywords tried | site(s) | device-checked (no existing collector) |")
    lines.append("|---|---|---|---|---|")
    for s in gaps:
        site_str = ""
        if s.sites:
            f, ln = s.sites[0]
            extra = f" +{len(s.sites) - 1} more" if len(s.sites) > 1 else ""
            site_str = f"{Path(f).name}:{ln}{extra}"
        checked = "yes" if s.name in DEVICE_CHECKED_NO_COLLECTOR else "unverified"
        lines.append(f"| `{s.name}` | {s.kind} | {', '.join(s.keywords)} | {site_str} | {checked} |")

    zf_unreferenced = [z for z in zero_footprint if not z["referenced"]]
    zf_referenced = [z for z in zero_footprint if z["referenced"]]
    lines.append(
        f"\n## Zero-footprint candidate gaps ({len(zf_unreferenced)})\n"
    )
    lines.append(
        "Standard Android capture surfaces this app declares no permission for and "
        "references no API for at all -- invisible to the manifest/getSystemService "
        "diff above by construction. All confirmed on 2026-08-19 via `adb pm list "
        "packages -3` to have no existing dedicated collector app on the device "
        "(`com.llamalab.automate` is installed but not configured as a pipeline for "
        "any of these). Still candidates, not confirmed gaps -- the device check "
        "predates this list and should be re-run before treating any entry as settled.\n"
    )
    for z in zf_unreferenced:
        lines.append(f"- **{z['name']}**")
    if zf_referenced:
        lines.append(f"\n_{len(zf_referenced)} catalog entries already have source "
                      f"references and are excluded from the list above:_")
        for z in zf_referenced:
            lines.append(f"  - {z['name']} (matched: {', '.join(z['matched_terms'])})")

    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parent.parent)
    ap.add_argument("--json", action="store_true", help="emit machine-readable JSON instead of markdown")
    ap.add_argument("--out", type=Path, default=None, help="write report to this path instead of stdout")
    args = ap.parse_args()

    kotlin_root = args.repo_root / KOTLIN_ROOT
    manifest_path = args.repo_root / MANIFEST_PATH
    if not kotlin_root.is_dir():
        print(f"error: {kotlin_root} not found", file=sys.stderr)
        return 1

    event_sites, capture_files = find_events_record_kinds(kotlin_root)
    api_sites = find_system_service_sites(kotlin_root, capture_files)
    vocab = build_vocabulary(capture_files)
    permissions = parse_permissions(manifest_path)
    surfaces = build_surfaces(permissions, api_sites, vocab)
    zero_footprint = audit_zero_footprint_catalog(kotlin_root)

    if args.json:
        payload = {
            "event_kinds": sorted({e.kind for e in event_sites}),
            "event_sites": [e.__dict__ for e in event_sites],
            "zero_footprint_catalog": zero_footprint,
            "surfaces": [
                {
                    "name": s.name,
                    "type": s.kind,
                    "status": s.status,
                    "keywords": s.keywords,
                    "matched_vocab": s.matched_vocab,
                    "sites": s.sites,
                    "known_resolved": KNOWN_RESOLVED.get((s.kind, s.name)),
                    "device_checked_no_collector": s.name in DEVICE_CHECKED_NO_COLLECTOR,
                }
                for s in surfaces
            ],
        }
        out_text = json.dumps(payload, indent=2, sort_keys=True) + "\n"
    else:
        out_text = render_report(surfaces, event_sites, zero_footprint)

    if args.out:
        args.out.write_text(out_text, encoding="utf-8")
    else:
        print(out_text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
