# Phone app capture-gap audit

Emitted event kinds (106):

`airplane_mode`, `ambient_context`, `app`, `attestation`, `audio_recording_configuration`, `blob_upload_skipped`, `blob_uploaded`, `bluetooth_state`, `boot`, `call_state`, `capture_failure`, `capture_toggle`, `chunk_closed`, `chunk_orphaned`, `chunk_publish_failed`, `chunk_published`, `chunk_upload_blocked`, `chunk_upload_ready`, `chunk_upload_skipped`, `chunk_upload_undeletable`, `chunk_uploaded`, `device_idle`, `direct_boot_migrated`, `dnd_filter`, `epoch_calibrated`, `epoch_invalidated`, `epoch_opened`, `estate_action`, `estate_notification`, `events_cursor_ahead`, `events_cursor_rewound`, `events_drained`, `events_upload_blocked`, `events_upload_ready`, `events_upload_stalled`, `grant_transition`, `headset_plug`, `health_active_calories`, `health_backfill`, `health_basal_metabolic_rate`, `health_blood_glucose`, `health_blood_pressure`, `health_body_fat`, `health_body_temperature`, `health_deletion`, `health_distance`, `health_elevation`, `health_exercise`, `health_floors_climbed`, `health_heart_rate`, `health_height`, `health_hrv`, `health_hydration`, `health_lane_state`, `health_respiratory_rate`, `health_resting_heart_rate`, `health_skin_temperature`, `health_sleep`, `health_speed`, `health_spo2`, `health_steps`, `health_sweep_failed`, `health_token_expired`, `health_total_calories`, `health_vo2max`, `health_weight`, `hr_live`, `hr_live_state`, `inbox_fetch_blocked`, `inbox_fetch_ready`, `inbox_fetched`, `inbox_swept`, `intent_delivered`, `intent_rejected`, `intent_retry_pending`, `intent_unparseable`, `lane_blocked`, `lane_resumed`, `lane_toggle`, `location`, `mark`, `media_mirror_blocked`, `media_mirror_ready`, `media_mirror_refused`, `media_mirror_skipped`, `media_mirrored`, `media_session`, `media_sessions`, `network_type`, `notification_posted`, `outbox_upload_blocked`, `outbox_upload_ready`, `package`, `power`, `receipt_shown`, `ringer_mode`, `signal_unavailable`, `sleep_alarm_cancelled`, `sleep_alarm_refused`, `sleep_alarm_set`, `sleep_inertia_probe`, `steering_ritual`, `transport_health`, `transport_repair`, `user_present`, `wifi_state`


## Confirmed-emitted surfaces (46)

| surface | type | matched vocabulary |
|---|---|---|
| `android.permission.RECORD_AUDIO` | permission | actively_recorded, ambient_context, audio_recording_configuration, automatically_recorded, chunk, chunk_closed, chunk_orphaned, chunk_publish_failed |
| `android.permission.ACCESS_COARSE_LOCATION` | permission | location |
| `android.permission.ACCESS_FINE_LOCATION` | permission | location |
| `android.permission.ACCESS_BACKGROUND_LOCATION` | permission | location |
| `android.permission.ACCESS_WIFI_STATE` | permission | bluetooth_state, call_state, data_state, estate_action, estate_notification, health_lane_state, hr_live_state, previous_state |
| `android.permission.READ_PHONE_STATE` | permission | bluetooth_state, call_state, data_state, estate_action, estate_notification, health_lane_state, hr_live_state, microphone |
| `android.permission.QUERY_ALL_PACKAGES` | permission | all, automatically_recorded, call_state, callback, events_upload_stalled |
| `android.permission.health.READ_STEPS` | permission | health_steps |
| `android.permission.health.READ_HEART_RATE` | permission | direct_boot_migrated, epoch_calibrated, health_basal_metabolic_rate, health_heart_rate, health_respiratory_rate, health_resting_heart_rate, rate_limited, sample_rate |
| `android.permission.health.READ_SLEEP` | permission | health_sleep, sleep, sleep_alarm_cancelled, sleep_alarm_refused, sleep_alarm_set, sleep_inertia_probe, sleeping |
| `android.permission.health.READ_OXYGEN_SATURATION` | permission | health_spo2 |
| `android.permission.health.READ_HEART_RATE_VARIABILITY` | permission | direct_boot_migrated, epoch_calibrated, health_basal_metabolic_rate, health_heart_rate, health_respiratory_rate, health_resting_heart_rate, rate_limited, sample_rate |
| `android.permission.health.READ_RESPIRATORY_RATE` | permission | direct_boot_migrated, epoch_calibrated, health_basal_metabolic_rate, health_heart_rate, health_respiratory_rate, health_resting_heart_rate, rate_limited, sample_rate |
| `android.permission.health.READ_RESTING_HEART_RATE` | permission | direct_boot_migrated, epoch_calibrated, health_basal_metabolic_rate, health_heart_rate, health_respiratory_rate, health_resting_heart_rate, rate_limited, sample_rate |
| `android.permission.health.READ_ACTIVE_CALORIES_BURNED` | permission | actively_recorded, health_active_calories, health_total_calories |
| `android.permission.health.READ_TOTAL_CALORIES_BURNED` | permission | health_active_calories, health_total_calories |
| `android.permission.health.READ_DISTANCE` | permission | health_distance |
| `android.permission.health.READ_SPEED` | permission | health_speed, speed, speed_mps |
| `android.permission.health.READ_ELEVATION_GAINED` | permission | health_elevation |
| `android.permission.health.READ_EXERCISE` | permission | exercise, health_exercise |
| `android.permission.health.READ_VO2_MAX` | permission | health_vo2max, lux_max, max_bpm, motion_max, voice_network_type, voltage_mv |
| `android.permission.health.READ_BODY_TEMPERATURE` | permission | body, health_body_fat, health_body_temperature, health_skin_temperature, temperature_c |
| `android.permission.health.READ_BLOOD_PRESSURE` | permission | health_blood_glucose, health_blood_pressure |
| `android.permission.health.READ_WEIGHT` | permission | health_weight |
| `android.permission.health.READ_EXERCISE_ROUTES` | permission | exercise, health_exercise |
| `android.permission.health.READ_FLOORS_CLIMBED` | permission | floors, health_floors_climbed |
| `android.permission.health.READ_SKIN_TEMPERATURE` | permission | health_body_temperature, health_skin_temperature, temperature_c |
| `android.permission.health.READ_BLOOD_GLUCOSE` | permission | health_blood_glucose, health_blood_pressure |
| `android.permission.health.READ_BODY_FAT` | permission | body, health_body_fat, health_body_temperature |
| `android.permission.health.READ_BASAL_METABOLIC_RATE` | permission | direct_boot_migrated, epoch_calibrated, health_basal_metabolic_rate, health_heart_rate, health_respiratory_rate, health_resting_heart_rate, rate_limited, sample_rate |
| `android.permission.health.READ_HEIGHT` | permission | health_height |
| `android.permission.health.READ_HYDRATION` | permission | health_hydration |
| `android.permission.BLUETOOTH_CONNECT` | permission | bluetooth_state, connected, connecting, data_connection, disconnected, health_connect, health_heart_rate, health_hrv |
| `android.permission.PACKAGE_USAGE_STATS` | permission | activity_paused, activity_resumed, activity_stopped, capture_screen, keyguard_hidden, keyguard_shown, package, package_added |
| `AlarmManager` | api | sleep_alarm_cancelled, sleep_alarm_refused, sleep_alarm_set |
| `BluetoothManager` | api | bluetooth_state, health_heart_rate, health_hrv, health_resting_heart_rate, hr_live, hr_live_state |
| `ConnectivityManager` | api | blob_upload_skipped, blob_uploaded, chunk_upload_blocked, chunk_upload_ready, chunk_upload_skipped, chunk_upload_undeletable, chunk_uploaded, connected |
| `LocationManager` | api | location |
| `MediaSessionManager` | api | media_mirror_blocked, media_mirror_ready, media_mirror_refused, media_mirror_skipped, media_mirrored, media_session, media_sessions |
| `NotificationManager` | api | enabled_notification_listeners, estate_notification, notification_listener, notification_posted, notification_removed |
| `PowerManager` | api | capture_screen, health_sleep, power, screen_off, screen_on, sleep, sleep_alarm_cancelled, sleep_alarm_refused _(known-resolved: screen-interactive state: fixed via UsageLane (usage/screen_interactive, screen_non_interactive) and used directly in PassiveLanes.sleep())_ |
| `SensorManager` | api | ambient_context, lux_max, lux_mean, lux_min, lux_samples, motion_max, motion_rms, motion_samples _(known-resolved: lux/motion summary: AmbientSensors emits ambient_context with lux_mean/lux_min/lux_max/motion_rms/motion_max per window)_ |
| `TelephonyManager` | api | telephony |
| `UsageStatsManager` | api | activity_paused, activity_resumed, activity_stopped, capture_screen, keyguard_hidden, keyguard_shown, screen_off, screen_on |
| `UserManager` | api | user_present, user_unlocked |
| `WifiManager` | api | wifi_state |

## Candidate gaps (0)

These are surfaces (permission granted, or system API referenced) with no keyword match in the local capture vocabulary. NOT confirmed uncaptured -- per the established method lesson, another app on the device may already cover it (see `sinnix-phone` scripts / aw-android precedent). Each needs a device-side check (`adb pm list packages -3` + manual review) before it is treated as a real hole.

| surface | type | keywords tried | site(s) | device-checked (no existing collector) |
|---|---|---|---|---|

## Zero-footprint candidate gaps (3)

Standard Android capture surfaces this app declares no permission for and references no API for at all -- invisible to the manifest/getSystemService diff above by construction. All confirmed on 2026-08-19 via `adb pm list packages -3` to have no existing dedicated collector app on the device (`com.llamalab.automate` is installed but not configured as a pipeline for any of these). Still candidates, not confirmed gaps -- the device check predates this list and should be re-run before treating any entry as settled.

- **Raw magnetic field sensor (TYPE_MAGNETIC_FIELD)**
- **Proximity sensor (TYPE_PROXIMITY)**
- **Step counter / significant motion (TYPE_STEP_COUNTER, TYPE_SIGNIFICANT_MOTION)**

_10 catalog entries already have source references and are excluded from the list above:_
  - MediaSessionManager (now-playing / media transport state) (matched: MediaSessionManager, getActiveSessions)
  - WifiManager (SSID / wifi state) (matched: WifiManager)
  - TelephonyManager (call state / network type) (matched: TelephonyManager)
  - BluetoothAdapter general state changes (ACTION_STATE_CHANGED) (matched: BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothAdapter)
  - ACTION_USER_PRESENT (unlock broadcast) (matched: ACTION_USER_PRESENT)
  - ACTION_HEADSET_PLUG (headset plug/unplug) (matched: ACTION_HEADSET_PLUG)
  - ACTION_PACKAGE_ADDED / ACTION_PACKAGE_REMOVED (install/uninstall) (matched: ACTION_PACKAGE_ADDED, ACTION_PACKAGE_REMOVED)
  - ACTION_AIRPLANE_MODE_CHANGED (matched: ACTION_AIRPLANE_MODE_CHANGED)
  - Ringer mode / Do Not Disturb (AudioManager.getRingerMode, NotificationManager.getCurrentInterruptionFilter) (matched: RINGER_MODE_CHANGED)
  - Doze transitions (ACTION_DEVICE_IDLE_MODE_CHANGED) (matched: ACTION_DEVICE_IDLE_MODE_CHANGED, isDeviceIdleMode)
