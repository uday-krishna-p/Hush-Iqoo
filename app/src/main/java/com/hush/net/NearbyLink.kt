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
 * Offline phone-to-phone MESH over Nearby Connections (Bluetooth + Wi-Fi Direct, no internet).
 *
 * Tree topology rooted at the commander. Every phone advertises and discovers. A sensor keeps ONE
 * upstream link (to the commander if it can see it, else to a sensor that already has a route) and
 * accepts any number of downstream links. It only advertises once it has a route, so loops cannot form.
 * Messages from downstream are forwarded upstream; messages from upstream are forwarded to all downstream.
 * The advertised name carries role and hop count: "C|<name>" or "S|<name>|<hops>".
 */
class NearbyLink(context: Context, private val localName: String, private val listener: Listener) {

    interface Listener {
        fun onLinkStatus(text: String)
        /** A message arrived from [endpointId] (direct neighbour); [fromUpstream] says which direction. */
        fun onMessage(endpointId: String, text: String, fromUpstream: Boolean)
        /** Our upstream route came up (sensor only). */
        fun onRouteUp(hops: Int)
        /** Our upstream route went down (sensor only). */
        fun onRouteDown()
        /** A downstream neighbour disconnected (any role). */
        fun onDownstreamLost(endpointId: String)
    }

    companion object {
        private const val SERVICE_ID = "com.hush.v2"
        private const val RETRY_MS = 3000L
        const val ROLE_C = "C"
        const val ROLE_S = "S"
    }

    private val client = Nearby.getConnectionsClient(context)
    private val main = Handler(Looper.getMainLooper())
    private val strategy = Strategy.P2P_CLUSTER
    private val pendingNames = HashMap<String, String>()
    private var isSensor = false
    private var stopped = false
    private var advertising = false

    /** endpointId → advertised name of direct neighbours below us. */
    val downstream = LinkedHashMap<String, String>()
    /** Our single upstream neighbour (sensor only). */
    var upstreamId: String? = null
        private set
    var upstreamName: String? = null
        private set
    /** Hops to the commander: 0 for the commander, -1 when unrouted. */
    var hops: Int = -1
        private set
    private var connectingTo: String? = null
    /** Candidates seen while unrouted: endpointId → (name, hops). */
    private val seen = HashMap<String, Pair<String, Int>>()

    val isRouted: Boolean get() = hops >= 0

    fun startCommander() {
        isSensor = false
        hops = 0
        advertise()
        discover()   // the commander also discovers, so it can see stragglers in the log
    }

    fun startSensor() {
        isSensor = true
        hops = -1
        discover()
    }

    private fun advertisedName(): String = if (isSensor) "$ROLE_S|$localName|$hops" else "$ROLE_C|$localName"

    private fun advertise() {
        if (stopped || advertising) return
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(advertisedName(), SERVICE_ID, lifecycle, options)
            .addOnSuccessListener { advertising = true; HLog.d("Nearby: advertising as ${advertisedName()}"); refreshStatus() }
            .addOnFailureListener { e -> HLog.d("Nearby: advertising FAILED: $e"); retry { advertise() } }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        client.stopAdvertising()
        advertising = false
    }

    private fun discover() {
        if (stopped) return
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(SERVICE_ID, discovery, options)
            .addOnSuccessListener { HLog.d("Nearby: discovering"); refreshStatus() }
            .addOnFailureListener { e ->
                HLog.d("Nearby: discovery FAILED: $e")   // 8002 = already discovering, harmless
                if (e.message?.contains("8002") != true) retry { discover() }
            }
    }

    private fun retry(block: () -> Unit) {
        if (!stopped) main.postDelayed({ if (!stopped) block() }, RETRY_MS)
    }

    private fun parseName(adv: String): Triple<String, String, Int>? {
        val p = adv.split("|")
        return when {
            p.size == 2 && p[0] == ROLE_C -> Triple(ROLE_C, p[1], 0)
            p.size == 3 && p[0] == ROLE_S -> Triple(ROLE_S, p[1], p[2].toIntOrNull() ?: -1)
            else -> null
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val parsed = parseName(info.endpointName)
            HLog.d("Nearby: found ${info.endpointName} ($endpointId) parsed=$parsed routed=$isRouted")
            if (parsed == null || !isSensor) return
            val (_, name, theirHops) = parsed
            if (name == localName || theirHops < 0) return
            seen[endpointId] = name to theirHops
            tryConnectUpstream()
        }

        override fun onEndpointLost(endpointId: String) {
            seen.remove(endpointId)
        }
    }

    /** Sensor: pick the best-known candidate (fewest hops) and connect to it as our upstream. */
    private fun tryConnectUpstream() {
        if (!isSensor || isRouted || connectingTo != null || seen.isEmpty()) return
        val (id, cand) = seen.minByOrNull { it.value.second }!!
        connectingTo = id
        listener.onLinkStatus("Found ${cand.first} (${cand.second} hops), connecting…")
        client.requestConnection(advertisedName(), id, lifecycle)
            .addOnFailureListener { e ->
                HLog.d("Nearby: requestConnection to ${cand.first} FAILED: $e")
                connectingTo = null
                seen.remove(id)
                retry { tryConnectUpstream() }
            }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            HLog.d("Nearby: connection initiated with ${info.endpointName} ($endpointId), accepting")
            pendingNames[endpointId] = info.endpointName
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val adv = pendingNames.remove(endpointId) ?: "?"
            val parsed = parseName(adv)
            val name = parsed?.second ?: adv
            if (!result.status.isSuccess) {
                HLog.d("Nearby: connection with $name FAILED, status ${result.status.statusCode}")
                if (connectingTo == endpointId) { connectingTo = null; seen.remove(endpointId); retry { tryConnectUpstream() } }
                return
            }
            if (isSensor && connectingTo == endpointId) {
                // This is our upstream.
                connectingTo = null
                upstreamId = endpointId
                upstreamName = name
                hops = (parsed?.third ?: 0) + 1
                HLog.d("Nearby: ROUTE UP via $name, hops=$hops")
                client.stopDiscovery()
                advertise()                     // now others may route through us
                listener.onRouteUp(hops)
            } else {
                downstream[endpointId] = name
                HLog.d("Nearby: DOWNSTREAM $name joined ($endpointId), ${downstream.size} below us")
            }
            refreshStatus()
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId == upstreamId) {
                HLog.d("Nearby: ROUTE DOWN (lost $upstreamName)")
                upstreamId = null; upstreamName = null; hops = -1
                stopAdvertising()
                // Drop everyone below us: they will re-route on their own.
                downstream.keys.toList().forEach { client.disconnectFromEndpoint(it) }
                downstream.clear()
                listener.onRouteDown()
                seen.clear()
                retry { discover() }
            } else if (downstream.remove(endpointId) != null) {
                HLog.d("Nearby: downstream $endpointId left, ${downstream.size} below us")
                listener.onDownstreamLost(endpointId)
            }
            refreshStatus()
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            listener.onMessage(endpointId, String(bytes, Charsets.UTF_8), endpointId == upstreamId)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    /** Send toward the commander. */
    fun sendUp(text: String) {
        val id = upstreamId ?: return
        client.sendPayload(id, Payload.fromBytes(text.toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { e -> HLog.d("Nearby: sendUp FAILED: $e") }
    }

    /** Send to everyone below us (they forward further down). */
    fun sendDown(text: String, except: String? = null) {
        val ids = downstream.keys.filter { it != except }
        if (ids.isEmpty()) return
        client.sendPayload(ids, Payload.fromBytes(text.toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { e -> HLog.d("Nearby: sendDown FAILED: $e") }
    }

    private fun refreshStatus() {
        val text = if (isSensor) {
            if (isRouted) "Connected via $upstreamName · $hops hop${if (hops == 1) "" else "s"} · ${downstream.size} below" else "Searching for a route…"
        } else {
            "Commander · ${downstream.size} direct link${if (downstream.size == 1) "" else "s"}"
        }
        listener.onLinkStatus(text)
    }

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
        downstream.clear(); upstreamId = null; upstreamName = null; hops = -1; advertising = false
    }
}
