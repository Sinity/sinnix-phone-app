# tools/

## capture_audit.py

Re-runnable capture-gap sweep (sinnix-66ki): parses `Events.record` call
sites, `AndroidManifest.xml` permissions, and `getSystemService` call sites
straight from the current source tree, cross-references them, and reports
which system surfaces are confirmed captured versus candidate gaps. A
separate catalog also flags standard Android surfaces the app references
nowhere at all (no permission, no API) -- invisible to the manifest/API
diff by construction.

Run after adding a capture lane, a permission, or a new `getSystemService`
call, to catch the "API read but never emitted" bug class before it sits
unnoticed for months:

```
python3 tools/capture_audit.py                       # markdown to stdout
python3 tools/capture_audit.py --out tools/capture_audit_report.md
python3 tools/capture_audit.py --json                 # machine-readable
```

No dependencies beyond the standard library. See the module docstring in
`capture_audit.py` for the matching methodology and its limits (it cannot
see what other apps on the device already collect -- candidate gaps need a
device-side `adb pm list packages -3` check before being treated as real).
