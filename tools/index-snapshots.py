"""Retain mutable Google Maven indexes and verify their dependency-cache hashes."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("deps", type=Path)
parser.add_argument("snapshots", type=Path)
parser.add_argument("--refresh", action="store_true")
args = parser.parse_args()
data = json.loads(args.deps.read_text())
host = "https://maven.google.com"
indexes = {key: row for key, row in data[host].items() if key.endswith("-index")}
for key, row in indexes.items():
    path = args.snapshots / (key + ".xml")
    if args.refresh:
        with urllib.request.urlopen(host + "/" + key + ".xml", timeout=15) as response:
            payload = response.read(2_000_001)
        if len(payload) > 2_000_000:
            raise SystemExit("index exceeds metadata bound: " + key)
    else:
        payload = path.read_bytes()
    root = ET.fromstring(payload)
    expected_root = "metadata" if key == "master-index" else key.removesuffix("/group-index").replace("/", ".")
    if root.tag != expected_root:
        raise SystemExit("unexpected index root: " + key)
    digest = "sha256-" + base64.b64encode(hashlib.sha256(payload).digest()).decode()
    if args.refresh:
        path.parent.mkdir(parents=True, exist_ok=True)
        staging = path.with_suffix(".xml.new")
        staging.write_bytes(payload)
        staging.replace(path)
        row["xml"] = digest
    elif row["xml"] != digest:
        raise SystemExit("index snapshot differs from deps.json: " + key)
if args.refresh:
    staging = args.deps.with_suffix(".json.new")
    staging.write_text(json.dumps(data, indent=1) + "\n")
    staging.replace(args.deps)
print(f"{len(indexes)} Maven index snapshots {'refreshed' if args.refresh else 'verified'}")
