package com.westly.ludo.connect

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject

/**
 * The only class that talks to Google Nearby Connections. It knows nothing about Ludo: it advertises,
 * discovers, connects, sends and receives bytes, and reports what happens to its [listener].
 *
 * Every connection request is accepted automatically; the session decides whether to trust the
 * other phone from the "hello" message. All callbacks arrive on the main thread.
 */
class ConnectManager(context: Context) {

    interface Listener {
        /** Discovery found a host. [endpointName] is what the host advertised. */
        fun onEndpointFound(endpointId: String, endpointName: String)

        /** Both sides accepted and the link is open. */
        fun onConnected(endpointId: String)

        /** The link could not be opened. */
        fun onConnectionFailed(endpointId: String)

        fun onDisconnected(endpointId: String)

        fun onPayload(endpointId: String, bytes: ByteArray)
    }

    var listener: Listener? = null

    private val appContext = context.applicationContext
    private var cachedClient: ConnectionsClient? = null

    /** Created on first use, so a player who never opens Connect and Play never touches Nearby. */
    private fun client(): ConnectionsClient =
        cachedClient ?: Nearby.getConnectionsClient(appContext).also { cachedClient = it }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            listener?.onPayload(endpointId, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Only small byte messages are used, so there is nothing to track.
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            try {
                client().acceptConnection(endpointId, payloadCallback)
                    .addOnFailureListener { e ->
                        Log.w(TAG, "acceptConnection failed: ${e.message}")
                        listener?.onConnectionFailed(endpointId)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "acceptConnection error: ${e.message}")
                listener?.onConnectionFailed(endpointId)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                listener?.onConnected(endpointId)
            } else {
                Log.w(TAG, "connection result: ${result.status.statusCode}")
                listener?.onConnectionFailed(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            listener?.onDisconnected(endpointId)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            listener?.onEndpointFound(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            // A host that vanishes before we connect is handled by the search timeout.
        }
    }

    /** Starts advertising. [onResult] gets null on success or a short technical reason on failure. */
    fun startAdvertising(endpointName: String, onResult: (String?) -> Unit) {
        try {
            val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
            client().startAdvertising(endpointName, ConnectProtocol.SERVICE_ID, lifecycleCallback, options)
                .addOnSuccessListener { onResult(null) }
                .addOnFailureListener { e -> onResult(e.message ?: "advertising failed") }
        } catch (e: Exception) {
            onResult(e.message ?: "advertising failed")
        }
    }

    fun stopAdvertising() {
        try {
            cachedClient?.stopAdvertising()
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising: ${e.message}")
        }
    }

    /** Starts looking for hosts. [onResult] gets null on success or a short technical reason on failure. */
    fun startDiscovery(onResult: (String?) -> Unit) {
        try {
            val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
            client().startDiscovery(ConnectProtocol.SERVICE_ID, discoveryCallback, options)
                .addOnSuccessListener { onResult(null) }
                .addOnFailureListener { e -> onResult(e.message ?: "discovery failed") }
        } catch (e: Exception) {
            onResult(e.message ?: "discovery failed")
        }
    }

    fun stopDiscovery() {
        try {
            cachedClient?.stopDiscovery()
        } catch (e: Exception) {
            Log.w(TAG, "stopDiscovery: ${e.message}")
        }
    }

    /** Asks the host at [endpointId] to connect. [onResult] gets null once the request is sent, or a reason on failure. */
    fun requestConnection(myName: String, endpointId: String, onResult: (String?) -> Unit) {
        try {
            client().requestConnection(myName, endpointId, lifecycleCallback)
                .addOnSuccessListener { onResult(null) }
                .addOnFailureListener { e -> onResult(e.message ?: "connection request failed") }
        } catch (e: Exception) {
            onResult(e.message ?: "connection request failed")
        }
    }

    fun send(endpointId: String, message: JSONObject) {
        try {
            client().sendPayload(endpointId, Payload.fromBytes(ConnectProtocol.encode(message)))
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
        }
    }

    fun send(endpointIds: List<String>, message: JSONObject) {
        if (endpointIds.isEmpty()) return
        try {
            client().sendPayload(endpointIds, Payload.fromBytes(ConnectProtocol.encode(message)))
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
        }
    }

    fun disconnect(endpointId: String) {
        try {
            cachedClient?.disconnectFromEndpoint(endpointId)
        } catch (e: Exception) {
            Log.w(TAG, "disconnect: ${e.message}")
        }
    }

    /** Stops advertising and discovery and closes every connection. Safe to call at any time. */
    fun stopAll() {
        val c = cachedClient ?: return
        try {
            c.stopAdvertising()
            c.stopDiscovery()
            c.stopAllEndpoints()
        } catch (e: Exception) {
            Log.w(TAG, "stopAll: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "LudoConnect"
    }
}
