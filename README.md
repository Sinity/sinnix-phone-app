# Sinnix (phone)

`dev.sinnix.phone` — the phone-side member of the sinnix estate: ambient
capture, instruments, ingress, and a remote for the workstation. Kotlin +
Compose, built reproducibly through the nixpkgs Gradle setup hook with a
committed `deps.json` (regenerate via the derivation's
`mitmCache.updateScript` from this repository after any dependency change).
Google Maven group/master indexes are retained under `gradle-indexes/`; the
updater refreshes them, and the cache build checks their hashes against
`deps.json`. New upstream index contents do not change existing builds.

Consumed by [sinnix](https://github.com/Sinity/sinnix) as a flake input;
`pkg.nix` here is the derivation sinnix calls. Operating documentation
(install flow, grants, transport contracts, capture design) lives in
sinnix's `docs/phone.md`.
