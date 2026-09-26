package com.hush.net

import android.content.Context
import android.os.Build
import android.ranging.RangingCapabilities
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.ble.cs.BleCsRangingParams
import android.ranging.ble.rssi.BleRssiRangingParams
import android.ranging.raw.RawInitiatorRangingConfig
import android.ranging.raw.RawRangingDevice
import android.ranging.raw.RawResponderRangingConfig
import androidx.annotation.RequiresApi
import com.hush.HLog
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Silent radio ranging with the Android 16 Ranging API: Bluetooth Channel Sounding first, signal-strength
 * ranging as the fallback. The commander is the initiator toward every sensor; each sensor is a responder
 * toward the commander. Distances arrive in [Listener.onDistance] as a median of the last few readings.
 */
@RequiresApi(36)
class BleRanging(context: Context, private val listener: Listener) {

    interface Listener {
        /** [peerLetter] is the sensor letter on the commander, or "A" on a sensor. */
        fun onDistance(peerLetter: String, metres: Double, technology: String, confidence: Int)
        fun onOwnAddress(address: String)
        fun onStatus(text: String)
    }

    private val manager: RangingManager? = context.getSystemService(RangingManager::class.java)
    private val executor = Executor { it.run() }
    private var ownAddress: String? = null
    private var csSupported = false
    private var rssiSupported = false
    private val sessions = HashMap<String, RangingSession>()          // peer letter → session
    private val history = HashMap<String, ArrayDeque<Double>>()       // peer letter → recent distances
    private val deviceToLetter = HashMap<RangingDevice, String>()

    val address: String? get() = ownAddress

    fun start() {
        if (manager == null) { HLog.d("BleRanging: no RangingManager"); return }
        manager.registerCapabilitiesCallback(executor, object : RangingManager.RangingCapabilitiesCallback {
            override fun onRangingCapabilities(caps: RangingCapabilities) {
                val avail = caps.technologyAvailability
                csSupported = avail[RangingManager.BLE_CS] == RangingCapabilities.ENABLED
                rssiSupported = avail[RangingManager.BLE_RSSI] == RangingCapabilities.ENABLED
                HLog.d("BleRanging: availability=$avail (technology → ${RangingCapabilities.ENABLED}=enabled)")
                // The public API does not expose our own Bluetooth address; the capabilities object prints it.
                val m = Regex("mBluetoothAddress=([0-9A-Fa-f:]{17})").find(caps.toString())
                ownAddress = m?.groupValues?.get(1)
                HLog.d("BleRanging: CS=$csSupported RSSI=$rssiSupported ownAddress=$ownAddress")
                ownAddress?.let { listener.onOwnAddress(it) }
                listener.onStatus("Radio ranging: ${if (csSupported) "Channel Sounding" else if (rssiSupported) "signal strength only" else "unavailable"}")
            }
        })
    }

    private fun rawDevice(letter: String, peerAddress: String, useCs: Boolean): RawRangingDevice {
        val dev = RangingDevice.Builder().setUuid(UUID.nameUUIDFromBytes(peerAddress.toByteArray())).build()
        deviceToLetter[dev] = letter
        val b = RawRangingDevice.Builder().setRangingDevice(dev)
        if (useCs) {
            b.setCsRangingParams(
                BleCsRangingParams.Builder(peerAddress)
                    .setSecurityLevel(1)
                    .setRangingUpdateRate(RawRangingDevice.UPDATE_RATE_NORMAL)
                    .setLocationType(BleCsRangingParams.LOCATION_TYPE_INDOOR)
                    .setSightType(BleCsRangingParams.SIGHT_TYPE_UNKNOWN)
                    .build()
            )
        } else {
            b.setBleRssiRangingParams(BleRssiRangingParams.Builder(peerAddress).setRangingUpdateRate(RawRangingDevice.UPDATE_RATE_NORMAL).build())
        }
        return b.build()
    }

    private val bt = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
    private val appContext = context.applicationContext
    private val gatts = HashMap<String, android.bluetooth.BluetoothGatt>()
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private val advCallback = object : android.bluetooth.le.AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: android.bluetooth.le.AdvertiseSettings?) { HLog.d("BleRanging: connectable advertising started") }
        override fun onStartFailure(errorCode: Int) { HLog.d("BleRanging: connectable advertising FAILED $errorCode") }
    }

    /** Sensor: be connectable over BLE so the commander can hold a link for Channel Sounding. */
    @android.annotation.SuppressLint("MissingPermission")
    fun advertiseConnectable() {
        try {
            val adapter = bt?.adapter ?: return
            advertiser = adapter.bluetoothLeAdvertiser ?: run { HLog.d("BleRanging: no advertiser"); return }
            val settings = android.bluetooth.le.AdvertiseSettings.Builder()
                .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true).setTimeout(0)
                .setTxPowerLevel(android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
            val data = android.bluetooth.le.AdvertiseData.Builder().setIncludeDeviceName(false).build()
            advertiser?.startAdvertising(settings, data, advCallback)
        } catch (e: Throwable) { HLog.d("BleRanging: advertise threw $e") }
    }

    /** Commander: connect over BLE to the peer first, then start ranging on top of that link. */
    @android.annotation.SuppressLint("MissingPermission")
    fun rangeAsInitiator(letter: String, peerAddress: String, useCs: Boolean = csSupported) {
        try {
            val device = bt?.adapter?.getRemoteDevice(peerAddress)
            if (device != null && useCs) {
                HLog.d("BleRanging[$letter]: connecting GATT to $peerAddress before Channel Sounding")
                gatts.remove(letter)?.close()
                val gatt = device.connectGatt(appContext, false, object : android.bluetooth.BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
                        HLog.d("BleRanging[$letter]: GATT status=$status state=$newState (2=connected)")
                        if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ open(letter, peerAddress, initiator = true, useCs = true) }, 1500)
                        } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                            if (sessions[letter] == null) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ open(letter, peerAddress, initiator = true, useCs = false) }, 500)
                        }
                    }
                }, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
                gatts[letter] = gatt
                // If GATT never connects, fall back to RSSI after 8 s.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (sessions[letter] == null) { HLog.d("BleRanging[$letter]: no GATT link after 8 s, RSSI fallback"); open(letter, peerAddress, initiator = true, useCs = false) }
                }, 8000)
                return
            }
        } catch (e: Throwable) { HLog.d("BleRanging[$letter]: GATT connect threw $e") }
        open(letter, peerAddress, initiator = true, useCs = useCs)
    }

    /** Sensor: answer the commander. Started as soon as the commander's address is known, before it initiates. */
    fun rangeAsResponder(peerAddress: String, useCs: Boolean = csSupported) {
        advertiseConnectable()
        open("A", peerAddress, initiator = false, useCs = useCs)
    }

    private fun open(letter: String, peerAddress: String, initiator: Boolean, useCs: Boolean) {
        val mgr = manager ?: return
        if (!csSupported && !rssiSupported) { HLog.d("BleRanging: nothing supported, skip"); return }
        sessions.remove(letter)?.let { try { it.close() } catch (_: Exception) {} }
        val tech = if (useCs) "CS" else "RSSI"
        try {
            val callback = object : RangingSession.Callback {
                override fun onOpened() { HLog.d("BleRanging[$letter/$tech]: opened") }
                override fun onOpenFailed(reason: Int) {
                    HLog.d("BleRanging[$letter/$tech]: open FAILED reason=$reason")
                    if (useCs && rssiSupported) { HLog.d("BleRanging[$letter]: falling back to RSSI"); open(letter, peerAddress, initiator, useCs = false) }
                }
                override fun onStarted(device: RangingDevice, technology: Int) { HLog.d("BleRanging[$letter/$tech]: started with $device technology=$technology") }
                override fun onResults(device: RangingDevice, data: RangingData) {
                    val d = data.distance?.measurement ?: return
                    val conf = data.distance?.confidence ?: -1
                    val h = history.getOrPut(letter) { ArrayDeque() }
                    h.addLast(d); while (h.size > 5) h.removeFirst()
                    val median = h.sorted()[h.size / 2]
                    HLog.d("BleRanging[$letter/$tech]: %.2f m (median %.2f, conf %d, rssi %s)".format(d, median, conf, if (data.hasRssi()) data.rssi.toString() else "-"))
                    listener.onDistance(letter, median, tech, conf)
                }
                override fun onStopped(device: RangingDevice, reason: Int) { HLog.d("BleRanging[$letter/$tech]: stopped reason=$reason (1=local 2=remote 3=unsupported 4=policy 5=no peers)") }
                override fun onClosed(reason: Int) {
                    HLog.d("BleRanging[$letter/$tech]: closed reason=$reason")
                    if (sessions[letter] === thisSession()) sessions.remove(letter)
                    // Channel Sounding refused (unsupported in this configuration): use signal strength instead.
                    if (useCs && rssiSupported && reason != RangingSession.Callback.REASON_LOCAL_REQUEST) {
                        HLog.d("BleRanging[$letter]: CS closed, falling back to RSSI ranging")
                        open(letter, peerAddress, initiator, useCs = false)
                    }
                }
                private fun thisSession(): RangingSession? = sessions[letter]
            }
            val session = mgr.createRangingSession(executor, callback) ?: run { HLog.d("BleRanging[$letter/$tech]: createRangingSession returned null"); return }
            val config = if (initiator)
                RawInitiatorRangingConfig.Builder().addRawRangingDevice(rawDevice(letter, peerAddress, useCs)).build()
            else
                RawResponderRangingConfig.Builder().setRawRangingDevice(rawDevice(letter, peerAddress, useCs)).build()
            val pref = RangingPreference.Builder(if (initiator) RangingPreference.DEVICE_ROLE_INITIATOR else RangingPreference.DEVICE_ROLE_RESPONDER, config).build()
            sessions[letter] = session
            session.start(pref)
            HLog.d("BleRanging[$letter/$tech]: start requested as ${if (initiator) "initiator" else "responder"} toward $peerAddress")
        } catch (e: Throwable) {
            HLog.d("BleRanging[$letter/$tech]: start threw $e")
            if (useCs && rssiSupported) open(letter, peerAddress, initiator, useCs = false)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun stop() {
        sessions.values.forEach { try { it.stop(); it.close() } catch (_: Exception) {} }
        sessions.clear()
        gatts.values.forEach { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatts.clear()
        try { advertiser?.stopAdvertising(advCallback) } catch (_: Exception) {}
    }

    companion object {
        val available: Boolean get() = Build.VERSION.SDK_INT >= 36
    }
}
