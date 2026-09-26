package com.hush.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.hush.HLog

/**
 * Offline phone-to-phone link over Nearby Connections (Bluetooth + Wi-Fi Direct, no internet).
 * Commander advertises and accepts everyone; sensors discover and connect to the first commander found.
 * All callbacks arrive on the main thread.
 */
class NearbyLink(context: Context, private val localName: String, private val listener: Listener) {

    interface Listener {
        fun onLinkStatus(text: String)
        fun onPeerConnected(endpointId: String, name: String)
        fun onPeerDisconnected(endpointId: String)
        fun onMessage(endpointId: String, text: String)
    }

    companion object {
        private const val SERVICE_ID = "com.hush.v1"
        private const val RETRY_MS = 3000L
    }

    private val client = Nearby.getConnectionsClient(context)
    private val main = Handler(Looper.getMainLooper())
    private val strategy = Strategy.P2P_STAR
    private val pendingNames = HashMap<String, String>()
    private var isSensor = false
    private var connecting = false
    private var stopped = false

    /** endpointId → advertised name of every connected peer. */
    val connected = LinkedHashMap<String, String>()

    fun startCommander() {
        isSensor = false
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(localName, SERVICE_ID, lifecycle, options)
            .addOnSuccessListener { HLog.d("Nearby: advertising as $localName"); status("Advertising as $localName · ${connected.size} sensors") }
            .addOnFailureListener { e -> HLog.d("Nearby: advertising FAILED: $e"); status("Advertise failed: ${e.message}"); retry { startCommander() } }
    }

    fun startSensor() {
        isSensor = true
        discover()
    }

    private fun discover() {
        if (stopped) return
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(SERVICE_ID, discovery, options)
            .addOnSuccessListener { HLog.d("Nearby: discovering"); status("Searching for commander…") }
            .addOnFailureListener { e ->
                // 8002 = STATUS_ALREADY_DISCOVERING, harmless
                HLog.d("Nearby: discovery FAILED: $e")
                if (e.message?.contains("8002") != true) { status("Discovery failed: ${e.message}"); retry { discover() } }
            }
    }

    private fun retry(block: () -> Unit) {
        if (!stopped) main.postDelayed({ if (!stopped) block() }, RETRY_MS)
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            HLog.d("Nearby: found ${info.endpointName} ($endpointId), connected=${connected.size} connecting=$connecting")
            if (connected.isNotEmpty() || connecting) return
            connecting = true
            status("Found ${info.endpointName}, connecting…")
            client.requestConnection(localName, endpointId, lifecycle)
                .addOnFailureListener { e -> HLog.d("Nearby: requestConnection FAILED: $e"); connecting = false; status("Connect failed: ${e.message}"); retry { discover() } }
        }

        override fun onEndpointLost(endpointId: String) {
            HLog.d("Nearby: endpoint lost $endpointId")
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            HLog.d("Nearby: connection initiated with ${info.endpointName} ($endpointId), accepting")
            pendingNames[endpointId] = info.endpointName
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val name = pendingNames.remove(endpointId) ?: "?"
            connecting = false
            if (result.status.isSuccess) {
                connected[endpointId] = name
                HLog.d("Nearby: CONNECTED to $name ($endpointId), total ${connected.size}")
                if (isSensor) {
                    client.stopDiscovery()
                    status("Connected to $name")
                } else {
                    status("Advertising as $localName · ${connected.size} sensors")
                }
                listener.onPeerConnected(endpointId, name)
            } else {
                HLog.d("Nearby: connection to $name FAILED, status ${result.status.statusCode}")
                status("Connection failed (${result.status.statusCode})")
                if (isSensor) retry { discover() }
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = connected.remove(endpointId)
            HLog.d("Nearby: DISCONNECTED from $name ($endpointId), total ${connected.size}")
            listener.onPeerDisconnected(endpointId)
            if (isSensor) {
                status("Lost commander, searching…")
                retry { discover() }
            } else {
                status("Advertising as $localName · ${connected.size} sensors")
            }
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            listener.onMessage(endpointId, String(bytes, Charsets.UTF_8))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    fun send(endpointId: String, text: String) {
        client.sendPayload(endpointId, Payload.fromBytes(text.toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { e -> HLog.d("Nearby: send to $endpointId FAILED: $e") }
    }

    fun broadcast(text: String) {
        if (connected.isEmpty()) return
        client.sendPayload(connected.keys.toList(), Payload.fromBytes(text.toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { e -> HLog.d("Nearby: broadcast FAILED: $e") }
    }

    private fun status(text: String) = listener.onLinkStatus(text)

    fun stop() {
        stopped = true
        HLog.d("Nearby: stopping")
        try {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        } catch (e: Exception) {
            HLog.d("Nearby: stop threw $e")
        }
        connected.clear()
    }
}
