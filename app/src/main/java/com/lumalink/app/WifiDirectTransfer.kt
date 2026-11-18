package com.lumalink.app

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.net.wifi.p2p.nsd.*
import android.os.*
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.*

/** Foreground P2P group with reusable authenticated file sessions and bounded sender retries. */
@SuppressLint("MissingPermission") // Activity gates creation on runtime grants.
internal class WifiDirectTransfer(private val context: Context,
    private val status: (String) -> Unit, private val failed: (Exception) -> Unit,
    private val retryDecision: (Exception, () -> Unit) -> Unit,
    private val approve: (DirectIdentity, DirectOffer, (Boolean) -> Unit) -> Unit,
    private val saved: (DirectOffer, String, String) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val profiles = DirectPeerStore.get(context)
    private val manager = requireNotNull(context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager) { "Wi-Fi Direct service unavailable" }
    private val channel = manager.initialize(context, Looper.getMainLooper()) { error("Wi-Fi Direct channel disconnected") }
    @Volatile private var closed = false
    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var current: DirectSource? = null
    private val outgoing = ArrayBlockingQueue<DirectSource>(1)
    private var ownsGroup = false
    private var connected = false
    private var invitation: DirectInvitation? = null
    private var expectedPeer: SavedDirectPeer? = null
    private var peersChanged: ((List<WifiP2pDevice>) -> Unit)? = null
    private var localService: WifiP2pDnsSdServiceInfo? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private val receipts = linkedMapOf<String, DirectOffer>()
    private val accepted = linkedSetOf<DirectOffer>()
    private var onSent: ((DirectOffer) -> Unit)? = null
    @Volatile private var dataActive = false
    @Volatile private var lastData = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (closed) return
            if (dataActive && System.nanoTime() - lastData > 45_000_000_000L) {
                dataActive = false
                runCatching { socket?.close() } // Socket writes have no Java SO_TIMEOUT.
            }
            main.postDelayed(this, 5000)
        }
    }
    private val timeout = Runnable { if (!connected) error("Wi-Fi Direct setup timed out. Keep both apps open and retry.") }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (closed) return
            try {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> manager.requestPeers(channel) { peers ->
                        if (!closed) peersChanged?.invoke(peers.deviceList.toList())
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        if (connected) manager.requestConnectionInfo(channel) { info ->
                            if (!closed && !info.groupFormed) runCatching { socket?.close() }
                        } else checkConnection()
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_DISABLED)
                        ui { status("Wi-Fi is off. Re-enable it; a lost P2P group may require reconnecting.") }
                }
            } catch (e: Exception) { failed(e) }
        }
    }
    init {
        require(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) { "Wi-Fi Direct unsupported" }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }, ContextCompat.RECEIVER_EXPORTED)
        main.postDelayed(timeout, 180_000)
        main.postDelayed(watchdog, 5000)
    }
    private fun action(next: () -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { if (!closed) try { next() } catch (e: Exception) { failed(e) } }
        override fun onFailure(reason: Int) { if (!closed) error("Wi-Fi Direct failed ($reason). Enable Wi-Fi/Location and retry; an existing group may be busy.") }
    }
    private fun fresh(next: () -> Unit) {
        manager.requestGroupInfo(channel) { group ->
            if (!closed) {
                if (group != null) error("An existing Wi-Fi Direct group is active. Disconnect it in Wi-Fi settings and retry.")
                else try { next() } catch (e: Exception) { failed(e) }
            }
        }
    }
    fun send(source: DirectSource, profile: SavedDirectPeer?, ready: (String, String) -> Unit, done: (DirectOffer) -> Unit) {
        check(outgoing.offer(source)); expectedPeer = profile; onSent = done
        status("Creating a Wi-Fi Direct group…")
        fresh {
            manager.createGroup(channel, action {
                ownsGroup = true
                manager.requestGroupInfo(channel) { group ->
                    if (!closed) try {
                        requireNotNull(group) { "Cannot read the Wi-Fi Direct group" }
                        val listener = ServerSocket(0).apply { soTimeout = 1000 }; server = listener
                        val invite = DirectInvitation(UUID.randomUUID().toString(), profile?.secret ?: DirectProtocol.random(32), listener.localPort,
                            group.owner.deviceName.ifBlank { profiles.identity.name }.take(100), source.offer.name, source.offer.size, source.offer.hash)
                        invitation = invite
                        val service = WifiP2pDnsSdServiceInfo.newInstance("LumaLink", "_lumalink._tcp", mapOf(
                            "tag" to Codec.hash(invite.key).take(24), "id" to invite.id, "port" to invite.port.toString(), "v" to "2"))
                        localService = service
                        manager.addLocalService(channel, service, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() = Unit
                            override fun onFailure(reason: Int) { ui { status("Saved-device discovery unavailable ($reason). Use the authorization QR instead.") } }
                        })
                        ready(invite.encode(), invite.owner)
                        executor.submit { serve(listener, invite) }
                    } catch (e: Exception) { failed(e) }
                }
            })
        }
    }
    fun sendMore(source: DirectSource) {
        check(!closed && connected) { "Connection closed; reconnect to the saved device" }
        check(outgoing.offer(source)) { "A file is already queued" }
        status("Queued ${source.offer.name}; waiting for the receiver…")
    }
    private fun serve(listener: ServerSocket, invite: DirectInvitation) {
        val retry = DirectRetryBudget()
        var authenticatedPeer: String? = expectedPeer?.identity?.id
        var disconnectedAt = 0L
        fun interrupted(error: Exception) {
            if (retry.failed()) {
                ui { status("Connection interrupted. Retrying automatically; the current file restarts from the beginning…") }
            } else {
                val resume = CountDownLatch(1)
                ui { retryDecision(error) { retry.reset(); resume.countDown() } }
                while (!closed && !resume.await(1, TimeUnit.SECONDS)) { }
            }
            disconnectedAt = System.nanoTime()
        }
        while (!closed) {
            var authenticated = false
            try {
                val client = try { listener.accept() } catch (_: SocketTimeoutException) {
                    if (disconnectedAt != 0L && (current != null || outgoing.isNotEmpty()) && System.nanoTime() - disconnectedAt > 15_000_000_000L)
                        interrupted(IOException("The receiver did not reconnect. Check Wi-Fi on both phones."))
                    continue
                }
                socket = client
                client.use {
                    val stream = DirectFileTransfer.open(client, invite, profiles.identity, true, authenticatedPeer)
                    authenticated = true; authenticatedPeer = stream.peer.id
                    connected = true; main.removeCallbacks(timeout)
                    ui { status("Connected to ${stream.peer.name}") }
                    while (!closed) {
                        if (current == null) current = outgoing.poll(5, TimeUnit.SECONDS)
                        val source = current
                        if (source == null) { stream.control(JSONObject().put("type", "idle")); continue }
                        val start = System.nanoTime()
                        try { stream.send(source) { count -> progress(count, source.offer.size, start) } }
                        catch (declined: DirectDeclined) {
                            current = null; source.close(); ui { status(declined.message ?: "File declined") }; continue
                        }
                        runCatching { profiles.remember(stream.peer, invite.key) }.onFailure { ui { status("File delivered, but the device profile could not be saved: ${it.message}") } }
                        current = null; source.close(); retry.reset()
                        ui { onSent?.invoke(source.offer) }
                    }
                }
            } catch (e: Exception) {
                if (closed || e is InterruptedException) break
                // Invalid proofs/identities never consume the current file or its retry budget.
                if (!authenticated) { ui { status("Waiting for the authorized receiver…") }; continue }
                try { interrupted(e) } catch (_: InterruptedException) { break }
            } finally { dataActive = false; runCatching { socket?.close() }; socket = null }
        }
    }
    fun discover(invite: DirectInvitation, peers: (List<WifiP2pDevice>) -> Unit) {
        invitation = invite; peersChanged = peers
        fresh { manager.discoverPeers(channel, action {
            status("Select the sender: ${invite.owner}")
            manager.requestPeers(channel) { list -> if (!closed) peersChanged?.invoke(list.deviceList.toList()) }
        }) }
    }
    fun discoverSaved(profile: SavedDirectPeer) {
        expectedPeer = profile
        status("Waiting for ${profile.identity.name}. On that phone choose your saved device and Send file.")
        fresh {
            manager.setDnsSdResponseListeners(channel, { _, _, _ -> }, { _, record, device ->
                if (!closed && invitation == null && record["v"] == "2" && record["tag"] == profile.tag) {
                    val id = record["id"].orEmpty(); val port = record["port"]?.toIntOrNull()
                    if (id.matches(Regex("[a-zA-Z0-9-]{8,64}")) && port != null && port in 1..65535) {
                        invitation = DirectInvitation(id, profile.secret, port, profile.identity.name, "Incoming file", 1, "0".repeat(64))
                        connect(device)
                    }
                }
            })
            val request = WifiP2pDnsSdServiceRequest.newInstance("_lumalink._tcp")
            serviceRequest = request
            manager.addServiceRequest(channel, request, action { manager.discoverServices(channel, action { }) })
        }
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
        if (closed || invitation == null || connected || !ownsGroup || server != null) return
        manager.requestConnectionInfo(channel) { info ->
            if (closed || connected || !info.groupFormed) return@requestConnectionInfo
            if (info.isGroupOwner) { error("Unexpected group owner. Reconnect with the sender first."); return@requestConnectionInfo }
            val address = info.groupOwnerAddress ?: return@requestConnectionInfo
            connected = true; main.removeCallbacks(timeout)
            executor.submit { receiveLoop(address, invitation!!) }
        }
    }
    private fun receiveLoop(address: InetAddress, invite: DirectInvitation) {
        var failures = 0
        var authenticatedPeer: String? = expectedPeer?.identity?.id
        while (!closed) {
            try {
                val client = Socket(); socket = client
                client.use {
                    client.connect(InetSocketAddress(address, invite.port), 15_000)
                    val stream = DirectFileTransfer.open(client, invite, profiles.identity, false, authenticatedPeer)
                    authenticatedPeer = stream.peer.id
                    while (!closed) {
                        val control = stream.control()
                        if (control.optString("type") == "idle") continue
                        val offer = DirectOffer.parse(control)
                        val start = System.nanoTime()
                        stream.receive(offer, context.cacheDir, receipts, { requested ->
                            if (requested in accepted) true else {
                                val reply = ArrayBlockingQueue<Boolean>(1)
                                ui { approve(stream.peer, requested) { reply.offer(it) } }
                                var answer: Boolean? = null
                                while (!closed && answer == null) answer = reply.poll(1, TimeUnit.SECONDS)
                                if (answer == true) { accepted.add(requested); while (accepted.size > 128) accepted.remove(accepted.first()) }
                                answer == true
                            }
                        }, { file, name ->
                            var heartbeat = 0L
                            val path = ReceivedFileWriter(context).save(file, name) {
                                val now = System.nanoTime()
                                if (now - heartbeat > 5_000_000_000L) {
                                    stream.control(JSONObject().put("type", "saving").put("id", offer.id)); heartbeat = now
                                }
                            }
                            runCatching { profiles.remember(stream.peer, invite.key) }
                            val seconds = ((System.nanoTime() - start) / 1e9).coerceAtLeast(.001)
                            val rate = "Verified and saved: %.1f KB/s · %.2f s".format(java.util.Locale.US, offer.size / seconds / 1000, seconds)
                            ui { saved(offer, path, rate) }
                            path
                        }) { count -> progress(count, offer.size, start) }
                        failures = 0
                    }
                }
            } catch (e: Exception) {
                if (closed || e is InterruptedException) break
                if (e !is IOException) { ui { failed(e) }; break }
                failures++
                ui { status(if (failures <= 2) "Interrupted; reconnecting to the sender (retry $failures/2)…" else
                    "Waiting for the sender's retry decision. Keep this screen open; Cancel ends the session.") }
                try { Thread.sleep(if (failures <= 2) 1500 else 5000) } catch (_: InterruptedException) { break }
            } finally { dataActive = false; runCatching { socket?.close() }; socket = null }
        }
    }
    private var lastProgress = 0L
    private fun progress(bytes: Long, total: Long, start: Long) {
        val now = System.nanoTime()
        dataActive = bytes < total; lastData = now
        if (now - lastProgress < 200_000_000 && bytes != total) return
        lastProgress = now
        val seconds = ((now - start) / 1e9).coerceAtLeast(.001)
        val text = "%.1f%% · %.1f KB/s over Wi-Fi Direct".format(java.util.Locale.US, bytes.toDouble() / total * 100, bytes / seconds / 1000)
        ui { status(text) }
    }
    private fun error(message: String) { if (!closed) failed(IOException(message)) }
    private fun ui(block: () -> Unit) { main.post { if (!closed) block() } }
    override fun close() {
        if (closed) return
        closed = true; main.removeCallbacks(timeout); main.removeCallbacks(watchdog)
        runCatching { socket?.close() }; runCatching { server?.close() }; executor.shutdownNow()
        current?.close(); while (true) { (outgoing.poll() ?: break).close() }
        runCatching { context.unregisterReceiver(receiver) }
        localService?.let { runCatching { manager.removeLocalService(channel, it, null) } }
        serviceRequest?.let { runCatching { manager.removeServiceRequest(channel, it, null) } }
        runCatching { manager.stopPeerDiscovery(channel, null) }
        if (ownsGroup) { runCatching { manager.cancelConnect(channel, null) }; runCatching { manager.removeGroup(channel, null) } }
        if (Build.VERSION.SDK_INT >= 27) runCatching { channel.close() }
    }
}
