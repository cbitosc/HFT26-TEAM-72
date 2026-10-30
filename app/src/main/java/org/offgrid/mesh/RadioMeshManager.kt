package org.offgrid.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.*
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Local-only OFFGRID mesh runtime.
 * - Classic Bluetooth RFCOMM: secure + insecure fallback + channel-1 reflection
 * - BLE beacon + scan: only OFFGRID peers (advertises and filters the app's service UUID)
 * - Wi-Fi Direct: filtered to OFFGRID peers (OEM dependent)
 * No internet, cellular, cloud, or location tracking.
 */
class RadioMeshManager(private val context: Context, private val deviceId: String) {
    data class Peer(
        val id: String,
        val label: String,
        val transport: String,
        val connected: Boolean,
        val rssiDbm: Int? = null
    )

    interface Listener {
        fun status(text: String)
        fun peers(items: List<Peer>)
        fun packet(packet: MeshPacket, local: Boolean)
        fun delivery(id: String, state: String) { }
    }

    var listener: Listener? = null
    private val listeners = mutableSetOf<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val bluetooth = BluetoothAdapter.getDefaultAdapter()
    private val wifi = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val wifiChannel = wifi.initialize(context, context.mainLooper, null)
    private val appUuid = UUID.fromString("6e7e9c64-75c2-4e7a-9c09-96e9914d873c")
    private val appSecureUuid = UUID.fromString("6e7e9c64-75c2-4e7a-9c09-96e9914d873d")
    private val bleServiceUuid = ParcelUuid(appUuid)
    private val bleSecureUuid = ParcelUuid(appSecureUuid)

    @SuppressLint("MissingPermission")
    private fun isOffgridDevice(device: BluetoothDevice, nameOverride: String? = null): Boolean {
        val name = nameOverride ?: device.name
        if (name?.contains("OFFGRID", ignoreCase = true) == true) return true
        val uuids = device.uuids
        if (uuids != null && (uuids.contains(bleServiceUuid) || uuids.contains(bleSecureUuid))) return true
        return false
    }
    private val seen = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val store = MessageStore(context)
    private val peers = ConcurrentHashMap<String, Peer>()
    private val writers = ConcurrentHashMap<String, BufferedWriter>()
    private val openSockets = ConcurrentHashMap<String, Closeable>()
    private val serverSockets = Collections.synchronizedList(mutableListOf<Closeable>())
    private val connectingAddresses = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val failedAddresses = ConcurrentHashMap<String, Long>()
    private val rfcommChannel = 1
    private val prefs = context.getSharedPreferences("offgrid", Context.MODE_PRIVATE)
    @Volatile private var running = false
    @Volatile private var pendingBluetoothAddress: String? = null
    @Volatile private var wifiDirectFailed = false
    @Volatile private var powerMode = prefs.getString("power_mode", POWER_NORMAL) ?: POWER_NORMAL
    @Volatile private var responder = prefs.getBoolean("responder", false)

    init { MeshSecurity.load(context) }

    fun addListener(l: Listener) { listeners.add(l); reportPeers() }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun report(text: String) { Log.d("OFFGRID-RADIO", text); for (l in listeners) main.post { l.status(text) } }
    private fun reportPeers() { for (l in listeners) main.post { l.peers(peers.values.sortedBy { it.label }) } }
    private fun reportDelivery(id: String, state: String) { for (l in listeners) main.post { l.delivery(id, state) } }
    private fun offgridLabel(address: String, name: String?): String =
        if (!name.isNullOrBlank()) name else "OFFGRID-${address.replace(":", "").takeLast(4)}"

    private fun Peer.withRssi(rssi: Int?): Peer = copy(rssiDbm = rssi ?: rssiDbm)

    fun setPowerMode(mode: String) {
        powerMode = mode
        prefs.edit().putString("power_mode", mode).apply()
        report("Battery mode: $mode")
    }
    fun powerMode() = powerMode
    fun scanIntervalMs() = when (powerMode) {
        POWER_EMERGENCY -> 15_000L
        POWER_RELAY -> 30_000L
        else -> 60_000L
    }
    fun setResponder(enabled: Boolean) {
        responder = enabled
        prefs.edit().putBoolean("responder", enabled).apply()
        report(if (enabled) "Responder gateway mode enabled" else "Responder gateway mode disabled")
    }
    fun isResponder() = responder

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission") override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (device.address == bluetooth?.address) return
                    val name = device.name
                    val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        .takeUnless { it == Short.MIN_VALUE }?.toInt()
                    val isBonded = device.bondState == BluetoothDevice.BOND_BONDED
                    val isOffgrid = isOffgridDevice(device, name)

                    if (isOffgrid) {
                        val wasConnected = peers["bt:${device.address}"]?.connected ?: false
                        val transport = if (isBonded) "Bluetooth (paired)" else "Bluetooth"
                        peers["bt:${device.address}"] = Peer("bt:${device.address}", offgridLabel(device.address, name), transport, wasConnected, rssi)
                        reportPeers()
                        if (!wasConnected) {
                            val lastFail = failedAddresses[device.address] ?: 0L
                            if (System.currentTimeMillis() - lastFail > 20_000L && !connectingAddresses.contains(device.address)) {
                                autoConnect(device.address)
                            }
                        }
                    } else if (isBonded || device.type != BluetoothDevice.DEVICE_TYPE_LE) {
                        // Check SDP records for OFFGRID service UUID before ignoring
                        try { device.fetchUuidsWithSdp() } catch (_: Exception) { }
                    }
                }
                BluetoothDevice.ACTION_UUID -> {
                    val device = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (device.address == bluetooth?.address) return
                    if (isOffgridDevice(device)) {
                        val wasConnected = peers["bt:${device.address}"]?.connected ?: false
                        val isBonded = device.bondState == BluetoothDevice.BOND_BONDED
                        val transport = if (isBonded) "Bluetooth (paired)" else "Bluetooth"
                        peers["bt:${device.address}"] = Peer("bt:${device.address}", offgridLabel(device.address, device.name), transport, wasConnected, peers["bt:${device.address}"]?.rssiDbm)
                        reportPeers()
                        if (!wasConnected) {
                            val lastFail = failedAddresses[device.address] ?: 0L
                            if (System.currentTimeMillis() - lastFail > 20_000L && !connectingAddresses.contains(device.address)) {
                                autoConnect(device.address)
                            }
                        }
                    } else {
                        // Not an OFFGRID device: if not actively connected, ensure it's not listed
                        if (peers["bt:${device.address}"]?.connected != true && !writers.containsKey("bt:${device.address}")) {
                            if (peers.remove("bt:${device.address}") != null) {
                                reportPeers()
                            }
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (state == BluetoothDevice.BOND_BONDED) {
                        if (isOffgridDevice(device)) {
                            report("Paired with OFFGRID peer ${device.name ?: device.address} — connecting")
                            connectBluetooth(device.address, userInitiated = true)
                        } else {
                            try { device.fetchUuidsWithSdp() } catch (_: Exception) {}
                        }
                    }
                    refreshBondedPeers()
                    reportPeers()
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) report("Wi‑Fi Direct off — turn Wi-Fi on")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (wifiDirectFailed) return
                    wifi.requestPeers(wifiChannel, object : WifiP2pManager.PeerListListener {
                        override fun onPeersAvailable(peerList: WifiP2pDeviceList) {
                            peerList.deviceList.forEach { device ->
                                if (device.deviceAddress == bluetooth?.address) return@forEach
                                if (device.deviceName.contains("OFFGRID", ignoreCase = true)) {
                                    peers["wifi:${device.deviceAddress}"] = Peer("wifi:${device.deviceAddress}", offgridLabel(device.deviceAddress, device.deviceName), "Wi‑Fi Direct", false)
                                }
                            }
                            reportPeers()
                        }
                    })
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = i.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (info?.isConnected == true) wifi.requestConnectionInfo(wifiChannel, object : WifiP2pManager.ConnectionInfoListener {
                        override fun onConnectionInfoAvailable(connection: WifiP2pInfo) { connectWifiSocket(connection) }
                    })
                }
            }
        }
    }

    @SuppressLint("MissingPermission") private fun refreshBondedPeers() {
        bluetooth?.bondedDevices?.forEach { d ->
            if (d.address == bluetooth?.address) return@forEach
            val isConnected = peers["bt:${d.address}"]?.connected == true || writers.containsKey("bt:${d.address}")
            if (isOffgridDevice(d) || isConnected) {
                val name = d.name
                peers["bt:${d.address}"] = Peer("bt:${d.address}", offgridLabel(d.address, name), "Bluetooth (paired)", isConnected, peers["bt:${d.address}"]?.rssiDbm)
            } else {
                try { d.fetchUuidsWithSdp() } catch (_: Exception) {}
            }
        }
        reportPeers()
        autoConnectAvailablePeers()
    }

    // ---------- BLE beacon + scanner (OFFGRID-only) ----------

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (device.address == bluetooth?.address) return
            val rssi = result.rssi
            val name = result.scanRecord?.deviceName ?: device.name
            val isOffgrid = name?.contains("OFFGRID", ignoreCase = true) == true ||
                result.scanRecord?.serviceUuids?.contains(bleServiceUuid) == true ||
                result.scanRecord?.serviceUuids?.contains(bleSecureUuid) == true
            if (isOffgrid) {
                val wasConnected = peers["bt:${device.address}"]?.connected ?: false
                peers["bt:${device.address}"] = Peer("bt:${device.address}", offgridLabel(device.address, name), "Bluetooth (BLE)", wasConnected, rssi)
                reportPeers()
                val lastFail = failedAddresses[device.address] ?: 0L
                val cooldownPassed = System.currentTimeMillis() - lastFail > 20_000L
                if (!wasConnected && cooldownPassed && !connectingAddresses.contains(device.address)) {
                    autoConnect(device.address)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w("OFFGRID-RADIO", "BLE scan failed (code $errorCode)")
        }
    }

    @SuppressLint("MissingPermission") private fun startBleAdvertising() {
        val advertiser = bleAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(bleServiceUuid)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        advertiser.startAdvertising(settings, data, scanResponse, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                report("OFFGRID BLE beacon active")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w("OFFGRID-RADIO", "BLE beacon failed (code $errorCode)")
            }
        })
    }

    @SuppressLint("MissingPermission") private fun startBleScan() {
        val scanner = bleScanner ?: return
        val filter1 = ScanFilter.Builder().setServiceUuid(bleServiceUuid).build()
        val filter2 = ScanFilter.Builder().setServiceUuid(bleSecureUuid).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(listOf(filter1, filter2), settings, bleScanCallback)
            report("BLE scanning for OFFGRID peers…")
        } catch (e: Exception) {
            Log.w("OFFGRID-RADIO", "BLE startScan failed: ${e.message}")
        }
    }

    private val bleAdvertiser: BluetoothLeAdvertiser? by lazy { bluetooth?.bluetoothLeAdvertiser }
    private val bleScanner: BluetoothLeScanner? by lazy { bluetooth?.bluetoothLeScanner }

    @SuppressLint("MissingPermission") fun start() {
        if (running) return
        running = true
        bluetooth?.let {
            try { it.name = "OFFGRID-${deviceId.takeLast(4)}" } catch (_: Exception) { }
        }
        try {
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_UUID)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            })
        } catch (_: Exception) { }
        startBluetoothServer()
        refreshBondedPeers()
        reportPeers()
        try { bluetooth?.startDiscovery() } catch (_: Exception) { }
        startBleAdvertising()
        startBleScan()
        setWifiDeviceName()
        try {
            wifi.discoverPeers(wifiChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { report("Scanning OFFGRID peers (WiFi Direct)…") }
                override fun onFailure(r: Int) {
                    if (r == WifiP2pManager.BUSY) {
                        wifiDirectFailed = true
                        report("Wi‑Fi Direct busy — using Bluetooth only")
                    } else {
                        report("Wi‑Fi Direct unavailable ($r)")
                    }
                }
            })
        } catch (_: Exception) { report("Wi‑Fi Direct scan failed") }
        flushOutbox()
    }

    @SuppressLint("MissingPermission")
    private fun setWifiDeviceName() {
        try {
            val reflect = wifi.javaClass.getMethod("setDeviceName", WifiP2pManager.Channel::class.java, String::class.java, WifiP2pManager.ActionListener::class.java)
            reflect.invoke(wifi, wifiChannel, "OFFGRID-${deviceId.takeLast(4)}", object : WifiP2pManager.ActionListener {
                override fun onSuccess() { }
                override fun onFailure(r: Int) { }
            })
        } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission") fun scan() {
        if (!running) { start(); return }
        bluetooth?.let {
            if (!it.isEnabled) { report("Bluetooth off — turn it on"); return }
            if (it.isDiscovering) it.cancelDiscovery()
            if (!it.startDiscovery()) report("Bluetooth scan failed")
        }
        try { bleScanner?.stopScan(bleScanCallback) } catch (_: Exception) { }
        startBleScan()
        refreshBondedPeers()
        reportPeers()
        autoConnectAvailablePeers()
        if (!wifiDirectFailed) {
            wifi.discoverPeers(wifiChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { report("Scanning OFFGRID peers…") }
                override fun onFailure(r: Int) { report("Wi‑Fi Direct unavailable ($r)") }
            })
        }
    }

    @SuppressLint("MissingPermission")
    fun autoConnectAvailablePeers() {
        if (!running || bluetooth?.isEnabled != true) return
        val now = System.currentTimeMillis()
        peers.values.forEach { peer ->
            if (peer.id.startsWith("bt:") && !peer.connected && !writers.containsKey(peer.id)) {
                val address = peer.id.removePrefix("bt:")
                val lastFail = failedAddresses[address] ?: 0L
                if (now - lastFail > 20_000L && !connectingAddresses.contains(address)) {
                    autoConnect(address)
                }
            }
        }
    }

    @SuppressLint("MissingPermission") private fun autoConnect(address: String) {
        val peer = peers["bt:$address"] ?: return
        if (peer.connected) return
        if (writers.containsKey("bt:$address")) return
        connectBluetooth(address, userInitiated = false)
    }

    @SuppressLint("MissingPermission") fun connect(peer: Peer) {
        when {
            peer.id.startsWith("bt:") -> connectBluetooth(peer.id.removePrefix("bt:"), userInitiated = true)
            peer.id.startsWith("wifi:") -> wifi.connect(wifiChannel, WifiP2pConfig().apply {
                deviceAddress = peer.id.removePrefix("wifi:")
                wps.setup = WpsInfo.PBC
            }, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { report("Connecting to ${peer.label} (Wi‑Fi Direct)…") }
                override fun onFailure(r: Int) { report("Wi‑Fi Direct request failed ($r)") }
            })
        }
    }

    @SuppressLint("MissingPermission") private fun connectBluetooth(address: String, userInitiated: Boolean = true) = thread(name = "bt-connect-$address") {
        if (!connectingAddresses.add(address)) {
            Log.d("OFFGRID-RADIO", "Connect already in progress for $address")
            return@thread
        }
        Log.d("OFFGRID-RADIO", "BT_CONNECT_START address=$address userInitiated=$userInitiated")
        val device = try { bluetooth?.getRemoteDevice(address) } catch (_: Exception) { null }
        if (device == null) {
            if (userInitiated) report("Unknown Bluetooth device: $address")
            connectingAddresses.remove(address)
            return@thread
        }
        try {
            bluetooth?.cancelDiscovery()
            try { Thread.sleep(150) } catch (_: Exception) {}

            var socket: BluetoothSocket? = null
            val connectAttempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
                "insecure-rfcomm" to { device.createInsecureRfcommSocketToServiceRecord(appUuid) },
                "secure-rfcomm" to { device.createRfcommSocketToServiceRecord(appSecureUuid) },
                "channel-1-fallback" to {
                    val method: Method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    method.invoke(device, rfcommChannel) as BluetoothSocket
                }
            )

            for ((name, attempt) in connectAttempts) {
                try {
                    Log.d("OFFGRID-RADIO", "Attempting $name with $address")
                    val s = attempt()
                    s.connect()
                    socket = s
                    Log.d("OFFGRID-RADIO", "Connected via $name with $address")
                    break
                } catch (e: Exception) {
                    Log.w("OFFGRID-RADIO", "Attempt $name failed with $address: ${e.message}")
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                    try { Thread.sleep(150) } catch (_: Exception) {}
                }
            }

            if (socket != null) {
                failedAddresses.remove(address)
                attach("bt:$address", socket.inputStream, socket.outputStream, "Bluetooth", socket)
                val label = device.name ?: address
                Log.d("OFFGRID-RADIO", "BT_CONNECT_OK remote=$label")
                report("$label connected (Bluetooth)")
            } else {
                failedAddresses[address] = System.currentTimeMillis()
                if (device.bondState != BluetoothDevice.BOND_BONDED && userInitiated) {
                    pendingBluetoothAddress = address
                    if (device.createBond()) {
                        report("Confirm pairing prompt on both phones")
                    } else {
                        report("Connection failed to ${device.name ?: address}")
                    }
                } else if (userInitiated) {
                    report("Connection failed to ${device.name ?: address}")
                }
            }
        } catch (e: Exception) {
            Log.e("OFFGRID-RADIO", "BT_CONNECT_FAIL: $e")
            failedAddresses[address] = System.currentTimeMillis()
            if (userInitiated) report("Bluetooth connect failed: ${e.message}")
        } finally {
            connectingAddresses.remove(address)
        }
    }

    @SuppressLint("MissingPermission") private fun startBluetoothServer() {
        if (bluetooth == null) return

        for (srv in serverSockets) {
            try { srv.close() } catch (_: Exception) {}
        }
        serverSockets.clear()

        // 1. Insecure RFCOMM listener loop (P2P direct connections)
        thread(name = "bt-accept-insec") {
            var server: BluetoothServerSocket? = null
            try {
                server = bluetooth.listenUsingInsecureRfcommWithServiceRecord("OFFGRID", appUuid)
                server?.let { serverSockets.add(it) }
                Log.d("OFFGRID-RADIO", "Insecure RFCOMM server listening on $appUuid")
                while (running) {
                    val socket = try {
                        server?.accept()
                    } catch (e: Exception) {
                        if (!running) break
                        try { Thread.sleep(500) } catch (_: Exception) {}
                        continue
                    }
                    if (socket != null) {
                        val addr = socket.remoteDevice.address
                        Log.d("OFFGRID-RADIO", "Accepted RFCOMM (insecure) from $addr")
                        thread(name = "bt-svc-insec-$addr") {
                            attach("bt:$addr", socket.inputStream, socket.outputStream, "Bluetooth", socket)
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e("OFFGRID-RADIO", "Insecure RFCOMM server error: ${e.message}")
            } finally {
                try { server?.close() } catch (_: Exception) {}
            }
        }

        // 2. Secure RFCOMM listener loop (paired/bonded connections)
        thread(name = "bt-accept-sec") {
            var server: BluetoothServerSocket? = null
            try {
                server = bluetooth.listenUsingRfcommWithServiceRecord("OFFGRID-SECURE", appSecureUuid)
                server?.let { serverSockets.add(it) }
                Log.d("OFFGRID-RADIO", "Secure RFCOMM server listening on $appSecureUuid")
                while (running) {
                    val socket = try {
                        server?.accept()
                    } catch (e: Exception) {
                        if (!running) break
                        try { Thread.sleep(500) } catch (_: Exception) {}
                        continue
                    }
                    if (socket != null) {
                        val addr = socket.remoteDevice.address
                        Log.d("OFFGRID-RADIO", "Accepted RFCOMM (secure) from $addr")
                        thread(name = "bt-svc-sec-$addr") {
                            attach("bt:$addr", socket.inputStream, socket.outputStream, "Bluetooth", socket)
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e("OFFGRID-RADIO", "Secure RFCOMM server error: ${e.message}")
            } finally {
                try { server?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun connectWifiSocket(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        if (info.isGroupOwner) {
            thread(name = "wifi-server") {
                try {
                    ServerSocket(8988).use { server ->
                        while (running) {
                            val socket = server.accept()
                            attach("wifi:${socket.inetAddress.hostAddress}", socket.getInputStream(), socket.getOutputStream(), "Wi‑Fi Direct", socket)
                        }
                    }
                } catch (e: Exception) {
                    if (running) report("Wi‑Fi Direct server stopped: ${e.message}")
                }
            }
        } else thread(name = "wifi-connect") {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(info.groupOwnerAddress, 8988), 8000)
                    attach("wifi:${info.groupOwnerAddress.hostAddress}", socket.getInputStream(), socket.getOutputStream(), "Wi‑Fi Direct", socket)
                    report("Wi-Fi Direct connected")
                    while (running) Thread.sleep(1000)
                }
            } catch (e: Exception) { report("Wi‑Fi Direct socket failed: ${e.message}") }
        }
    }

    private fun attach(key: String, input: InputStream, output: OutputStream, transport: String, socket: Closeable? = null) {
        if (socket != null) {
            openSockets[key]?.let { try { it.close() } catch (_: Exception) {} }
            openSockets[key] = socket
        }
        writers[key] = output.bufferedWriter()
        peers[key] = Peer(key, peers[key]?.label ?: key, transport, true, peers[key]?.rssiDbm)
        reportPeers()
        report("$transport peer connected locally")
        flushOutbox()
        thread(name = "mesh-read-$key") {
            try {
                input.bufferedReader().forEachLine { line ->
                    receive(MeshPacket.decode(line), key)
                }
            } catch (_: Exception) {
            } finally {
                writers.remove(key)
                openSockets.remove(key)?.let { try { it.close() } catch (_: Exception) {} }
                peers[key]?.let { peers[key] = it.copy(connected = false) }
                reportPeers()
                report("$transport peer disconnected")
            }
        }
    }

    fun send(priority: String, body: String) {
        Log.d("OFFGRID-RADIO", "send called priority=$priority body=$body")
        val packet = MeshPacket(sender = deviceId, priority = priority, body = body).signed()
        store.save(packet, MessageStore.PENDING)
        seen.add(packet.id)
        for (l in listeners) main.post { l.packet(packet, true) }
        forward(packet, null, true)
    }

    private fun receive(packet: MeshPacket, incoming: String?) {
        if (!packet.isAuthentic()) {
            report("Discarded unauthenticated mesh packet")
            return
        }
        if (!seen.add(packet.id)) return
        if (packet.type == MeshPacket.TYPE_RECEIPT || packet.type == MeshPacket.TYPE_RESPONDER_ACK) {
            packet.receiptFor?.let { id ->
                val state = if (packet.type == MeshPacket.TYPE_RESPONDER_ACK) MessageStore.ACKNOWLEDGED else MessageStore.RELAYED
                store.update(id, state)
                reportDelivery(id, state)
            }
            forward(packet, incoming, false)
            return
        }
        for (l in listeners) main.post { l.packet(packet, false) }
        // Each receiving device sends a signed receipt. The origin can therefore distinguish
        // a local write from an actual peer observation, even across multiple relay hops.
        sendReceipt(packet.id)
        if (responder) sendResponderAck(packet.id)
        forward(packet, incoming, false)
    }

    private fun sendReceipt(messageId: String) {
        val receipt = MeshPacket(sender = deviceId, priority = "RECEIPT", body = "", ttl = 5,
            type = MeshPacket.TYPE_RECEIPT, receiptFor = messageId).signed()
        seen.add(receipt.id)
        forward(receipt, null, false)
    }

    private fun sendResponderAck(messageId: String) {
        val acknowledgement = MeshPacket(sender = deviceId, priority = "RESPONDER", body = "", ttl = 5,
            type = MeshPacket.TYPE_RESPONDER_ACK, receiptFor = messageId).signed()
        seen.add(acknowledgement.id)
        forward(acknowledgement, null, false)
    }

    private fun forward(packet: MeshPacket, incoming: String?, locallyOriginated: Boolean) {
        if (packet.ttl <= 0) return
        val forwarded = packet.forwardThrough(deviceId).signed().encode() + "\n"
        val targets = writers.filterKeys { it != incoming }
        var sent = false
        for ((key, writer) in targets) {
            try {
                synchronized(writer) { writer.write(forwarded); writer.flush() }
                sent = true
                Log.d("OFFGRID-RADIO", "Forwarded packet ${packet.id.take(8)} to $key (TTL: ${packet.ttl - 1})")
            } catch (e: IOException) {
                Log.e("OFFGRID-RADIO", "Write failed to $key: ${e.message}")
                writers.remove(key)
                openSockets.remove(key)?.let { try { it.close() } catch (_: Exception) {} }
                peers[key]?.let { peers[key] = it.copy(connected = false) }
                reportPeers()
            }
        }
        if (targets.isNotEmpty() && !locallyOriginated) {
            val hopCount = packet.hops.size + 1
            report("Relayed ${packet.priority} packet ($hopCount hop${if (hopCount > 1) "s" else ""}) to ${targets.size} active peer(s)")
        }
        if (locallyOriginated && sent) {
            store.update(packet.id, MessageStore.SENT_TO_PEER)
            reportDelivery(packet.id, MessageStore.SENT_TO_PEER)
        }
    }

    private fun flushOutbox() {
        if (writers.isEmpty()) return
        store.pending().forEach { entry ->
            forward(entry.packet, null, true)
        }
    }

    fun stop() {
        running = false
        try { bluetooth?.cancelDiscovery() } catch (_: Exception) { }
        try { bleAdvertiser?.stopAdvertising(null) } catch (_: Exception) { }
        try { bleScanner?.stopScan(bleScanCallback) } catch (_: Exception) { }
        try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
        for ((_, sock) in openSockets) {
            try { sock.close() } catch (_: Exception) { }
        }
        openSockets.clear()
        for (srv in serverSockets) {
            try { srv.close() } catch (_: Exception) { }
        }
        serverSockets.clear()
        writers.clear()
    }

    companion object {
        const val POWER_NORMAL = "NORMAL"
        const val POWER_EMERGENCY = "EMERGENCY"
        const val POWER_RELAY = "RELAY"
        private val instance = AtomicReference<RadioMeshManager?>(null)
        fun get(context: Context, id: String): RadioMeshManager {
            var curr = instance.get()
            if (curr == null) {
                val created = RadioMeshManager(context.applicationContext, id)
                if (instance.compareAndSet(null, created)) curr = created else curr = instance.get()!!
            }
            return curr!!
        }
    }
}
