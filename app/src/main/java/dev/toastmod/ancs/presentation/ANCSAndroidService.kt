package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.ForegroundServiceTypeException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class ANCSAndroidService : Service() {
    private val amsClient = AMSClient()
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ANCSAndroidService = this@ANCSAndroidService
    }
    private var gattClient: ANCSGattServiceClient? = null

    @RequiresPermission(allOf = [Manifest.permission.VIBRATE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS])
    fun startBle() {

        val vibmgr = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        val vibrator = vibmgr.defaultVibrator

        val nameText = "Apple"
        val descText = "Apple Notification Center Service"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val notifMgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(NOTIF_CHANNEL_ID, nameText, importance).apply {
            name = nameText
            setShowBadge(false)
            description = descText
        }
        notifMgr.createNotificationChannel(channel)

        val btManager = baseContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter
        val targetDevice = selectTargetDevice(btAdapter.bondedDevices.toList())
        if (targetDevice == null) {
            Log.e("ANCSDiscover", "No bonded Bluetooth device found for ANCS/AMS")
            return
        }
        Log.i("ANCSDiscover", "Connecting to target device: ${targetDevice.alias ?: targetDevice.name ?: targetDevice.address}")
        val callback = ANCSGattServiceClient(baseContext, channel, vibrator, amsClient)
        gattClient = callback
        targetDevice.connectGatt(baseContext, false, callback)
    }

    private fun selectTargetDevice(devices: List<BluetoothDevice>): BluetoothDevice? {
        if (devices.isEmpty()) return null
        val iphone = devices.firstOrNull { device ->
            val label = "${device.alias ?: ""} ${device.name ?: ""}"
            label.contains("iphone", ignoreCase = true)
        }
        return iphone ?: devices.first()
    }

    override fun onBind(p0: Intent?): IBinder? {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return super.onStartCommand(intent, flags, startId)
        }
        startForeground()
        return super.onStartCommand(intent, flags, startId)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS])
    private fun startForeground() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            Log.i("ANCSError", "ANCS Service does not hold required permissions")
            return
        }
        val notifChannel = NotificationChannel("ANCSAndroidService", "ANCS", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(notifChannel)
        val notification = NotificationCompat.Builder(this, "ANCSAndroidService")
            .setContentTitle("ANCS")
            .setContentText("ANCS is running.")
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Intent(baseContext, MainActivity::class.java, ).let { notificationIntent ->
                PendingIntent.getActivity(baseContext, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            })
            .build()
        ServiceCompat.startForeground(this, 9002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        startBle()
        return
    }

}