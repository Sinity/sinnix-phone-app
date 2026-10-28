package dev.sinnix.phone.ingress

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import dev.sinnix.phone.core.Events

/** System observations share the capture service lifetime; no polling or extra wakeups. */
@Suppress("DEPRECATION")
class DeviceSignals(context: Context) {
    private val ctx = context.applicationContext
    private val phone = ctx.getSystemService(TelephonyManager::class.java)
    private var listening = false
    private var registered = false
    private val packages = PackageChangeReceiver()

    private fun observe(source: String, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            Events.record(ctx, "signal_unavailable", "source", source, "error", e.javaClass.simpleName)
        }
    }

    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            // Phone numbers are unnecessary for a call-state observation.
            Events.record(ctx, "call_state", "state", state, "reason", "callback")
        }
        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            Events.record(ctx, "network_type", "data_state", state, "network_type", networkType, "reason", "data_connection")
        }
        override fun onServiceStateChanged(serviceState: ServiceState?) {
            observe("network_type") { network("service_state") }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            observe(action) {
                when (action) {
                    Intent.ACTION_USER_PRESENT -> Events.record(ctx, "user_present", "action", action)
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> ringer(action)
                    NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> dnd(action)
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> idle(action)
                    WifiManager.WIFI_STATE_CHANGED_ACTION,
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> wifi(action)
                    BluetoothAdapter.ACTION_STATE_CHANGED -> Events.record(ctx, "bluetooth_state",
                        "state", intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR),
                        "previous_state", intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.ERROR),
                        "reason", action)
                    Intent.ACTION_HEADSET_PLUG -> Events.record(ctx, "headset_plug",
                        "state", intent.getIntExtra("state", -1), "microphone", intent.getIntExtra("microphone", -1),
                        "name", intent.getStringExtra("name"), "reason", action)
                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> Events.record(ctx, "airplane_mode",
                        "enabled", intent.getBooleanExtra("state", false), "reason", action)
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        // Bluetooth broadcasts originate from a privileged app, not system UID.
        ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        registered = true
        // Package add/remove are restricted implicit broadcasts on API 26+.
        // They need a context receiver, with their own package-scheme filter.
        ctx.registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }, Context.RECEIVER_EXPORTED)
        observe("ringer_mode") { ringer("snapshot") }
        observe("dnd_filter") { dnd("snapshot") }
        observe("device_idle") { idle("snapshot") }
        observe("wifi_state") { wifi("snapshot") }
        observe("bluetooth_state") {
            Events.record(ctx, "bluetooth_state", "state",
                ctx.getSystemService(BluetoothManager::class.java)?.adapter?.state, "reason", "snapshot")
        }
        observe("airplane_mode") {
            Events.record(ctx, "airplane_mode", "enabled",
                Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0, "reason", "snapshot")
        }
        observe("headset_plug") {
            val devices = ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            Events.record(ctx, "headset_plug", "state", if (devices.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }) 1 else 0, "reason", "snapshot")
        }
        observe("network_type") { network("snapshot") }
        observe("telephony") {
            phone?.let {
                Events.record(ctx, "call_state", "state", it.callState, "reason", "snapshot")
                it.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE or
                    PhoneStateListener.LISTEN_DATA_CONNECTION_STATE or PhoneStateListener.LISTEN_SERVICE_STATE)
                listening = true
            }
        }
    }

    fun stop() {
        if (registered) {
            ctx.unregisterReceiver(receiver)
            ctx.unregisterReceiver(packages)
        }
        if (listening) phone?.listen(phoneListener, PhoneStateListener.LISTEN_NONE)
        registered = false
        listening = false
    }

    private fun network(reason: String) {
        Events.record(ctx, "network_type", "network_type", phone?.dataNetworkType,
            "voice_network_type", phone?.voiceNetworkType, "reason", reason)
    }
    private fun ringer(reason: String) {
        Events.record(ctx, "ringer_mode", "mode", ctx.getSystemService(AudioManager::class.java).ringerMode, "reason", reason)
    }
    private fun dnd(reason: String) {
        Events.record(ctx, "dnd_filter", "filter", ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter, "reason", reason)
    }
    private fun idle(reason: String) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        Events.record(ctx, "device_idle", "idle", pm.isDeviceIdleMode, "reason", reason)
    }
    private fun wifi(reason: String) {
        val wm = ctx.getSystemService(WifiManager::class.java)
        val info = wm.connectionInfo
        val ssid = info?.ssid?.takeUnless { it == WifiManager.UNKNOWN_SSID }
        Events.record(ctx, "wifi_state", "state", wm.wifiState, "ssid", ssid,
            "ssid_available", ssid != null, "network_id", info?.networkId, "reason", reason)
    }
}

/** Package broadcasts are registered by the capture service. */
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val kind = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> "package_added"
            Intent.ACTION_PACKAGE_REMOVED -> "package_removed"
            else -> return
        }
        val pkg = intent.data?.schemeSpecificPart ?: return
        Events.record(ctx, kind, "app", pkg, "replacing", intent.getBooleanExtra(Intent.EXTRA_REPLACING, false),
            "uid", intent.getIntExtra(Intent.EXTRA_UID, -1))
    }
}
