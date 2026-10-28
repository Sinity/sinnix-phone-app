# Sinnix (phone)

`dev.sinnix.phone` — the phone-side member of the sinnix estate: ambient
capture, instruments, ingress, and a remote for the workstation. Kotlin +
Compose, built reproducibly through the nixpkgs Gradle setup hook with a
committed `deps.json` (regenerate via the derivation's
`mitmCache.updateScript` from this repository after any dependency change).
Google Maven group/master indexes and the Play SDK index are retained under `gradle-indexes/`; the
updater refreshes them, and the cache build checks their hashes against
`deps.json`. New upstream index contents do not change existing builds.

Consumed by [sinnix](https://github.com/Sinity/sinnix) as a flake input;
`pkg.nix` here is the derivation sinnix calls. Operating documentation
(install flow, grants, transport contracts, capture design) lives in
sinnix's `docs/phone.md`.

## Device signal events

The ambient service records `wifi_state` (including SSID when location access
and device location are enabled), `bluetooth_state`, `call_state`,
`network_type`, `headset_plug`, `airplane_mode`, `ringer_mode`, `dnd_filter`,
and `device_idle`. `reason: snapshot` marks initial state; callbacks carry
the broadcast action or callback reason. `user_present` is emitted only for
an actual unlock broadcast. While capture is running, package broadcasts emit `package_added` and
`package_removed`, including the `replacing` flag for upgrades.

The notification listener also observes active MediaSessions. `media_session`
records app, metadata, playback state, position, speed and available transport
actions on attachment and changes; `media_sessions` records the active count.
Disconnected sessions emit `reason: disconnected`. These collectors use the
existing `Events.record` JSONL path (`kind`, UTC `ts`, flat fields), and require
no additional scheduler. Permission/API failures emit `signal_unavailable`.

After installing, grant `READ_PHONE_STATE`, `BLUETOOTH_CONNECT`, coarse and
fine location, then background location using `adb -s SERIAL shell pm grant
dev.sinnix.phone android.permission.PERMISSION`. Enable the existing
notification listener with `cmd notification allow_listener
dev.sinnix.phone/.ingress.PhoneNotificationListener`. Wi-Fi state and package
visibility are manifest permissions. SSID can remain unavailable when device
location is off; `ssid_available` distinguishes that case. Telephony values
are Android integer constants and concern the default subscription; phone
numbers are not recorded.

EMA check-ins are retired. Upgrades withdraw notification 4713 and delete
channel `sinnix-ema`; the scheduler and settings no longer offer check-ins.
Historical event files, including `ema_prompted` and `ema_answer`, remain
readable through the existing generic readers.
