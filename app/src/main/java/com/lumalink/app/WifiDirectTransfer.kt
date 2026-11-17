package com.lumalink.app

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.*
import androidx.core.content.ContextCompat
import java.io.*
import java.net.*
import java.util.concurrent.Executors

/** One foreground Wi-Fi Direct session. All Android Wi-Fi calls are made on main. */
@SuppressLint("MissingPermission") // Activity gates creation on runtime permission grants.
internal class WifiDirectTransfer(private val context: Context,
    private val status: (String) -> Unit, private val failed: (Exception) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val manager = requireNotNull(context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager) { "Wi-Fi Direct service unavailable" }
    private val channel = manager.initialize(context, Looper.getMainLooper()) { error("Wi-Fi Direct channel disconnected") }
    @Volatile private var closed = false
    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    private var ownsGroup = false
    private var connected = false
    private var invitation: DirectInvitation? = null
    private var receiveDone: ((ByteArray, String, String) -> Unit)? = null
    private var peersChanged: ((List<WifiP2pDevice>) -> Unit)? = null
    private val timeout = Runnable { error("Wi-Fi Direct timed out. Keep both apps open, enable Wi-Fi and retry.") }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (closed) return
            try {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> manager.requestPeers(channel) { peers ->
                    if (!closed) peersChanged?.invoke(peers.deviceList.toList())
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> checkConnection()
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_DISABLED) error("Enable Wi-Fi to use Wi-Fi Direct")
            }
            } catch (e: Exception) { failed(e) }
        }
    }
    init {
        require(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) { "Wi-Fi Direct is not supported on this device" }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }, ContextCompat.RECEIVER_EXPORTED)
        main.postDelayed(timeout, 180_000)
    }
    private fun action(next: () -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { if (!closed) try { next() } catch (e: Exception) { failed(e) } }
        override fun onFailure(reason: Int) { if (!closed) error(when(reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is unsupported"
            WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Disconnect any existing Direct session and retry."
            else -> "Wi-Fi Direct failed ($reason). Enable Wi-Fi and Location for peer discovery, then retry."
        }) }
    }
    private fun fresh(next: () -> Unit) {
        manager.requestGroupInfo(channel) { group ->
            if (!closed) {
                if (group != null) error("An existing Wi-Fi Direct group is active. Disconnect it in Wi-Fi settings and retry.")
                else try { next() } catch (e: Exception) { failed(e) }
            }
        }
    }
    fun send(sender: Sender, ready: (String, String) -> Unit, done: () -> Unit) {
        status("Creating a Wi-Fi Direct group…")
        fresh {
            ownsGroup = true
            manager.createGroup(channel, action {
                manager.requestGroupInfo(channel) { group ->
                    if (!closed) {
                        if (group == null) error("Cannot read the Wi-Fi Direct group")
                        else {
                            try {
                            val listener = ServerSocket(0).apply { soTimeout = 180_000 }; server = listener
                            val invite = DirectInvitation(sender.id, DirectProtocol.random(32), listener.localPort,
                                group.owner.deviceName.ifBlank { "Sending phone" }.take(100), sender.name.take(100), sender.bytes.size, sender.sha)
                            ready(invite.encode(), invite.owner)
                            executor.submit {
                                try {
                                    // A failed authentication cannot consume the offer; await another client.
                                    val deadline = System.currentTimeMillis() + 180_000
                                    while (!closed && System.currentTimeMillis() < deadline) {
                                        val client = listener.accept(); socket = client
                                        client.use {
                                            val start = System.nanoTime()
                                            if (DirectFileTransfer.send(it, invite, sender.bytes) { count -> progress(count, sender.bytes.size, start) }) {
                                                ui { done() }; return@submit
                                            }
                                        }
                                    }
                                    throw IOException("Wi-Fi Direct offer expired")
                                } catch (e: Exception) { ui { failed(e) } }
                            }
                            } catch (e: Exception) { if (!closed) failed(e) }
                        }
                    }
                }
            })
        }
    }
    fun discover(invite: DirectInvitation, peers: (List<WifiP2pDevice>) -> Unit, done: (ByteArray, String, String) -> Unit) {
        invitation = invite; peersChanged = peers; receiveDone = done
        fresh { manager.discoverPeers(channel, action {
            status("Select the sender: ${invite.owner}")
            manager.requestPeers(channel) { list -> if (!closed) peersChanged?.invoke(list.deviceList.toList()) }
        }) }
    }
    fun connect(device: WifiP2pDevice) {
        if (connected || closed) return
        fresh {
        peersChanged = null; ownsGroup = true
        status("Connecting to ${device.deviceName}. Accept Android's connection prompt if shown.")
        manager.connect(channel, WifiP2pConfig().apply { deviceAddress = device.deviceAddress; groupOwnerIntent = 0; wps.setup = WpsInfo.PBC }, action { checkConnection() })
        }
    }
    private fun checkConnection() {
        if (closed || invitation == null || connected || !ownsGroup) return
        manager.requestConnectionInfo(channel) { info ->
            if (closed || connected || !info.groupFormed) return@requestConnectionInfo
            if (info.isGroupOwner) { error("Unexpected group owner. Disconnect and retry with the sender first."); return@requestConnectionInfo }
            val address = info.groupOwnerAddress ?: return@requestConnectionInfo
            connected = true
            val invite = invitation!!
            executor.submit {
                try {
                    val client = Socket(); socket = client
                    client.use {
                        // The address comes from Android's P2P group, never from an arbitrary QR URL.
                        it.connect(InetSocketAddress(address, invite.port), 15_000); it.soTimeout = 30_000
                        val start = System.nanoTime()
                        val bytes = DirectFileTransfer.receive(it, invite) { count -> progress(count, invite.size, start) }
                        val elapsed = ((System.nanoTime() - start) / 1e9).coerceAtLeast(.001)
                        val rate = "Wi-Fi Direct verified: %.1f KB/s · %.2f s".format(java.util.Locale.US, bytes.size / elapsed / 1000, elapsed)
                        ui { receiveDone?.invoke(bytes, invite.name, rate) }
                    }
                } catch (e: Exception) { ui { failed(e) } }
            }
        }
    }
    private var lastProgress = 0L
    private fun progress(bytes: Int, total: Int, start: Long) {
        val now = System.nanoTime()
        if (now - lastProgress < 200_000_000 && bytes != total) return
        lastProgress = now
        val seconds = ((now - start) / 1e9).coerceAtLeast(.001)
        val text = "%d%% · %.1f KB/s over Wi-Fi Direct".format(java.util.Locale.US, bytes * 100 / total, bytes / seconds / 1000)
        ui { status(text) }
    }
    private fun error(message: String) { if (!closed) failed(IOException(message)) }
    private fun ui(block: () -> Unit) { main.post { if (!closed) block() } }
    override fun close() {
        if (closed) return
        closed = true; main.removeCallbacks(timeout)
        runCatching { socket?.close() }; runCatching { server?.close() }; executor.shutdownNow()
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { manager.stopPeerDiscovery(channel, null) }
        if (ownsGroup) {
            runCatching { manager.cancelConnect(channel, null) }
            runCatching { manager.removeGroup(channel, null) }
        }
        if (Build.VERSION.SDK_INT >= 27) runCatching { channel.close() }
    }
}
