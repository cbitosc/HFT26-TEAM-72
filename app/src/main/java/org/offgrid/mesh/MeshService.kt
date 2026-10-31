package org.offgrid.mesh

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Keeps the local Bluetooth/Wi-Fi Direct mesh alive while OFFGRID is running.
 * It does not use internet, cellular, cloud, or location services.
 */
class MeshService : Service() {
    private lateinit var mesh: RadioMeshManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var handler: Handler
    private val reconnect = object : Runnable {
        override fun run() {
            mesh.scan()
            handler.postDelayed(this, mesh.scanIntervalMs())
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(mainLooper)
        val prefs = getSharedPreferences("offgrid", MODE_PRIVATE)
        val id = prefs.getString("node", "NODE-UNKNOWN") ?: "NODE-UNKNOWN"
        mesh = RadioMeshManager.get(this, id)
        mesh.addListener(object : RadioMeshManager.Listener {
            override fun status(text: String) { updateNotification(text) }
            override fun peers(items: List<RadioMeshManager.Peer>) { }
            override fun packet(packet: MeshPacket, local: Boolean) { if (!local) notifyIncoming(packet) }
        })
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OFFGRID::mesh")
        wakeLock.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat("OFFGRID local mesh is active")
        mesh.start()
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, mesh.scanIntervalMs())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(reconnect)
        mesh.stop()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        val channelId = "offgrid_mesh"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Local mesh", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps Bluetooth and Wi-Fi Direct mesh connections alive"
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("OFFGRID")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notification = NotificationCompat.Builder(this, "offgrid_mesh")
            .setContentTitle("OFFGRID")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        NotificationManagerCompat.from(this).notify(1, notification)
    }

    private fun notifyIncoming(packet: MeshPacket) {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val urgent = packet.priority == "SOS"
        val channelId = if (urgent) "offgrid_sos" else "offgrid_messages"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = if (urgent) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            val title = if (urgent) "OFFGRID SOS alerts" else "OFFGRID mesh messages"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(channelId, title, importance))
        }
        val title = if (urgent) "SOS received over OFFGRID" else "${packet.priority} message received"
        val text = "From ${packet.sender} · relayed through ${packet.hops.size} device(s)"
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(packet.id.hashCode(), notification)
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, MeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
