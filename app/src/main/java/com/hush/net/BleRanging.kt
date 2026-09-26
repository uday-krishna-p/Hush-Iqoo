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

    /** Our own tag: a 16-bit service UUID that only Hush uses, with the phone's Hush name as service data. */
    private val hushUuid = android.os.ParcelUuid.fromString("0000A5A5-0000-1000-8000-00805F9B34FB")
    private var ownName: String = ""
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    /** Hush name → the address that phone is advertising under right now (rotates every ~15 min). */
    private val liveAddress = HashMap<String, String>()
    private val wanted = HashMap<String, Pair<String, Boolean>>()   // Hush name → (letter, useCs) still to be started

    /** Sensor: be connectable over BLE and carry our Hush name, so the commander can find our live address. */
    @android.annotation.SuppressLint("MissingPermission")
    fun advertiseConnectable(name: String) {
        ownName = name
        try {
            val adapter = bt?.adapter ?: return
            advertiser = adapter.bluetoothLeAdvertiser ?: run { HLog.d("BleRanging: no advertiser"); return }
            val settings = android.bluetooth.le.AdvertiseSettings.Builder()
                .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true).setTimeout(0)
                .setTxPowerLevel(android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
            val data = android.bluetooth.le.AdvertiseData.Builder().setIncludeDeviceName(false)
                .addServiceData(hushUuid, name.takeLast(4).toByteArray(Charsets.US_ASCII)).build()
            advertiser?.startAdvertising(settings, data, advCallback)
        } catch (e: Throwable) { HLog.d("BleRanging: advertise threw $e") }
    }

    private val scanCallback = object : android.bluetooth.le.ScanCallback() {
        @android.annotation.SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            val tag = result.scanRecord?.getServiceData(hushUuid) ?: return
            val suffix = String(tag, Charsets.US_ASCII)
            val addr = result.device.address
            val name = wanted.keys.firstOrNull { it.endsWith(suffix) } ?: return
            if (liveAddress[name] == addr) return
            liveAddress[name] = addr
            HLog.d("BleRanging: $name is advertising as $addr (rssi ${result.rssi})")
            val (letter, useCs) = wanted[name] ?: return
            connectAndRange(letter, addr, useCs)
        }
        override fun onScanFailed(errorCode: Int) { HLog.d("BleRanging: scan FAILED $errorCode") }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun ensureScanning() {
        if (scanner != null) return
        try {
            scanner = bt?.adapter?.bluetoothLeScanner ?: run { HLog.d("BleRanging: no scanner"); return }
            val filter = android.bluetooth.le.ScanFilter.Builder().setServiceData(hushUuid, byteArrayOf(), byteArrayOf()).build()
            val settings = android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner?.startScan(listOf(filter), settings, scanCallback)
            HLog.d("BleRanging: scanning for Hush tags")
        } catch (e: Throwable) { HLog.d("BleRanging: scan start threw $e"); scanner = null }
    }

    private val bondAsked = HashSet<String>()
    private var bondReceiver: android.content.BroadcastReceiver? = null

    /** Commander: pair with the peer once (user taps Pair on both phones), then retry Channel Sounding. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun tryBondThenRetry(letter: String, peerAddress: String) {
        try {
            val device = bt?.adapter?.getRemoteDevice(peerAddress) ?: return
            if (device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) { HLog.d("BleRanging[$letter]: already bonded, CS still refused"); return }
            if (!bondAsked.add(peerAddress)) return
            if (bondReceiver == null) {
                bondReceiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context, i: android.content.Intent) {
                        val d = i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java) ?: return
                        val state = i.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1)
                        HLog.d("BleRanging: bond state of ${d.address} = $state (12=bonded)")
                        if (state == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                            val l = wanted.entries.firstOrNull { liveAddress[it.key] == d.address }?.value?.first ?: return
                            HLog.d("BleRanging[$l]: bonded, retrying Channel Sounding")
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ open(l, d.address, initiator = true, useCs = true) }, 1500)
                        }
                    }
                }
                appContext.registerReceiver(bondReceiver, android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            }
            HLog.d("BleRanging[$letter]: requesting one-time pairing with $peerAddress")
            device.createBond()
        } catch (e: Throwable) { HLog.d("BleRanging[$letter]: bond attempt threw $e") }
    }

    /** Commander: find the sensor's live address by its tag, then connect and range. */
    fun rangeAsInitiator(letter: String, peerName: String, useCs: Boolean = csSupported) {
        wanted[peerName] = letter to useCs
        liveAddress.remove(peerName)
        ensureScanning()
    }

    /** Commander: connect over BLE to the peer first, then start ranging on top of that link. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun connectAndRange(letter: String, peerAddress: String, useCs: Boolean) {
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

    private var gattServer: android.bluetooth.BluetoothGattServer? = null
    private var responderAddress: String? = null

    /**
     * Sensor: answer the commander. The commander connects from a rotating private address, so the responder
     * must be started toward THAT address, which we only learn when its link arrives at our GATT server.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun rangeAsResponder(peerIdentityAddress: String, ownName: String, useCs: Boolean = csSupported) {
        advertiseConnectable(ownName)
        try {
            if (gattServer == null) {
                gattServer = bt?.openGattServer(appContext, object : android.bluetooth.BluetoothGattServerCallback() {
                    override fun onConnectionStateChange(device: android.bluetooth.BluetoothDevice, status: Int, newState: Int) {
                        HLog.d("BleRanging: GATT server link from ${device.address} status=$status state=$newState")
                        if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED && responderAddress != device.address) {
                            responderAddress = device.address
                            HLog.d("BleRanging: commander's live address is ${device.address}, (re)starting responder toward it")
                            open("A", device.address, initiator = false, useCs = useCs)
                        }
                    }
                })
                HLog.d("BleRanging: GATT server open=${gattServer != null}")
            }
        } catch (e: Throwable) { HLog.d("BleRanging: GATT server threw $e") }
        // Also answer the identity address straight away, in case the stack resolves it.
        open("A", peerIdentityAddress, initiator = false, useCs = useCs)
    }

    private fun open(letter: String, peerAddress: String, initiator: Boolean, useCs: Boolean) {
        val mgr = manager ?: return
        if (!csSupported && !rssiSupported) { HLog.d("BleRanging: nothing supported, skip"); return }
        // A responder may hold one session per peer address; keep them under distinct keys.
        val key = if (initiator) letter else "$letter@$peerAddress"
        sessions.remove(key)?.let { try { it.close() } catch (_: Exception) {} }
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
                    if (sessions[key] === thisSession()) sessions.remove(key)
                    // Channel Sounding refused (unsupported in this configuration). Some stacks want the phones
                    // bonded first: ask for a one-time pairing, retry CS once bonded, and range by signal
                    // strength in the meantime.
                    if (useCs && reason != RangingSession.Callback.REASON_LOCAL_REQUEST) {
                        if (initiator) tryBondThenRetry(letter, peerAddress)
                        if (rssiSupported) { HLog.d("BleRanging[$letter]: CS closed, falling back to RSSI ranging"); open(letter, peerAddress, initiator, useCs = false) }
                    }
                }
                private fun thisSession(): RangingSession? = sessions[key]
            }
            val session = mgr.createRangingSession(executor, callback) ?: run { HLog.d("BleRanging[$letter/$tech]: createRangingSession returned null"); return }
            val config = if (initiator)
                RawInitiatorRangingConfig.Builder().addRawRangingDevice(rawDevice(letter, peerAddress, useCs)).build()
            else
                RawResponderRangingConfig.Builder().setRawRangingDevice(rawDevice(letter, peerAddress, useCs)).build()
            val pref = RangingPreference.Builder(if (initiator) RangingPreference.DEVICE_ROLE_INITIATOR else RangingPreference.DEVICE_ROLE_RESPONDER, config).build()
            sessions[key] = session
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
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanner = null; wanted.clear(); liveAddress.clear()
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null; responderAddress = null
        try { bondReceiver?.let { appContext.unregisterReceiver(it) } } catch (_: Exception) {}
        bondReceiver = null
    }

    companion object {
        val available: Boolean get() = Build.VERSION.SDK_INT >= 36
    }
}
