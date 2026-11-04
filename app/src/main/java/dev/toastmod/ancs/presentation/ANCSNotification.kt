package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.toastmod.ancs.R
import kotlinx.serialization.descriptors.PrimitiveKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.util.Date
import java.util.UUID
import kotlin.coroutines.coroutineContext

class ANCSNotification(val channel: NotificationChannel, val androidNotifHandle: Int, val category: CategoryID, val appleNotifHandle: ByteArray) {

    var appIdent = ""
    var title = "Loading..."
    var subtitle = "Loading..."
    var message = ""
    var date = LocalDate.now()
    var androidNotif: Notification? = null


    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun loadNotificationAttributes(context: Context, attributes: ByteArray): Boolean {
        var idx = 0
        while (idx < attributes.size) {
            // Need at least 3 bytes for attribute header (id + 2-byte length)
            if (idx + 3 > attributes.size) {
                Log.i("ANCS", "Fragment: header truncated at idx=$idx")
                return false
            }
            // Parse
            val attr = NotificationAttributeID.fromByte(attributes[idx])
            val attrLen = ByteBuffer.wrap(byteArrayOf(attributes[idx+1],attributes[idx+2]))
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort()
                .toUShort()
                .toInt()
            Log.i("ANCS","Attrlen: $attrLen | Datasize: ${attributes.size} | idx: $idx")
            if (idx + 3 + attrLen > attributes.size) {
                // Data for this attribute is truncated — wait for more fragments
                Log.i("ANCS", "Fragment: data truncated for attr ${attr.name}")
                return false
            }
            val attrData = attributes.slice(idx+3 until idx+3+attrLen).toByteArray().decodeToString()
            Log.i("ANCS", "${attributes[idx]}(${attr}):  ${attrData}")
            // Apply
            when(attr) {
                NotificationAttributeID.AppIdentifier -> appIdent = attrData
                NotificationAttributeID.Title -> title = attrData
                NotificationAttributeID.Message -> message = attrData
                NotificationAttributeID.Subtitle -> subtitle = attrData
//                NotificationAttributeID.Date -> date = LocalDate.parse(attrData)
                else -> {
                    Log.i("ANCS", "Unknown notification attribute ${attr.b.toInt()}")
                }
            }
            idx += 3+attrLen
        }

        Log.i("ANCS", "Loaded Notification \n$appIdent\n\t$title ; $subtitle\n\t$message")

        return true
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun spawnAndroidNotif(context: Context) {
        androidNotif = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        androidNotif?.let { NotificationManagerCompat.from(context).notify(androidNotifHandle, it) }

    }

    fun despawnAndroidNotif(context: Context) {
        androidNotif?.let { NotificationManagerCompat.from(context).cancel(androidNotifHandle) }
    }
}