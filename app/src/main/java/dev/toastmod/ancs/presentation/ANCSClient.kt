package dev.toastmod.ancs.presentation

import android.Manifest
import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlin.io.encoding.Base64

class ANCSClient(
    val context: Context,
    val channel: NotificationChannel,
    val vibrator: Vibrator
) {

    var androidNotifCount = 0

    val androidToApple = hashMapOf<Int, ByteArray>()
    val notifications = hashMapOf<String, ANCSNotification>()

    val applications = hashMapOf<String, Object>()

    var testNot: ANCSNotification? = null


    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun newNotif(categoryId: Byte, notifUuid: ByteArray): Int {
        val androidNotifId = androidNotifCount
        val n = ANCSNotification(
            channel,
            androidNotifId,
            CategoryID.fromByte(categoryId),
            notifUuid

        )

//        n.spawnAndroidNotif(context, channel)

        notifications.put(Base64.encode(notifUuid), n)
        androidToApple.put(androidNotifId, notifUuid)
        androidNotifCount += 1
        return androidNotifId
    }

    fun removeNotif(notifUuid: ByteArray) {
        notifications[Base64.encode(notifUuid)].let {
            it?.despawnAndroidNotif(context) ?: return
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS])
    fun onNotificationSource(
        eventID: Byte,
        eventFlags: Byte,
        categoryId: Byte,
        categoryCount: Byte,
        notifUuid: ByteArray
    ): Boolean {
        when(EventID.fromByte(eventID)) {
            EventID.NotificationAdded -> {
                Log.i("ANCS", "Notif added")
                newNotif(categoryId, notifUuid)
//                vibrator.vibrate(10)
                return true
            }
            EventID.NotificationRemoved -> {
                Log.i("ANCS", "Notif removed")
//                vibrator.vibrate(40)
                removeNotif(notifUuid)
            }
            EventID.NotificationModified -> {
                Log.i("ANCS", "Notif modified")
//                vibrator.vibrate(10)
            }
            else -> {
                Log.i("ANCSIgnored", "Reserved event")
            }
        }
        return false
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun onDataSource(
        commandID: CommandID,
        notificationUuid: ByteArray,
        attributes: ByteArray
    ) {
        val notification = notifications[Base64.encode(notificationUuid)]
        when(commandID) {
            CommandID.GetNotificationAttributes -> {
                Log.i("ANCS", "GetNotificationAttributes")
                notification?.loadNotificationAttributes(context, attributes) ?: Log.i("ANCS", "Notification $notificationUuid does not exist!")
            }

            CommandID.GetAppAttributes -> {
                Log.i("ANCS", "GetAppAttributes")
            }

            else -> {}
        }
    }

    fun getNotificationAttributes(notificationUuid: ByteArray, attributes: ByteArray): ByteArray {
        return byteArrayOf(CommandID.GetNotificationAttributes.b) + notificationUuid + attributes
    }


}