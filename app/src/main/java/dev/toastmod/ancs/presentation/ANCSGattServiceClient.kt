package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.currentCompositionLocalContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class ANCSGattServiceClient(
    context: Context,
    channel: NotificationChannel,
    vibrator: Vibrator
): BluetoothGattCallback() {

    val client = ANCSClient(context, channel, vibrator)

    val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val CCCD_SUB_NOTIFICATIONS: ByteArray = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    val CCCD_SUB_INDICATIONS: ByteArray = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
    val CCCD_UNSUBSCRIBE: ByteArray = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE

    class WriteQueueItem(val char: BluetoothGattCharacteristic, val data: ByteArray) {}

    enum class QueueItem {
        CHAR_WRITE,
        DESC_WRITE
    }

    var queueLen = 0

    var targetANCSServers = mutableListOf<String>()

    var subscribedDataSource = false
    var subscribedNotifSource = false
    var fullySubscribed = AtomicBoolean(false)

    var queueDescWrite: Channel<BluetoothGattDescriptor> = Channel<BluetoothGattDescriptor>()
    var queueCharWrite: Channel<WriteQueueItem> = Channel<WriteQueueItem>()

    var queue: Channel<QueueItem> = Channel()

    var lockCh: Channel<Int> = Channel()

    val scope = CoroutineScope(Dispatchers.Default)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if(gatt != null) {
                Log.i("ANCSDiscover", "Connected to ${gatt.device.alias} | bondstate: ${gatt.device.bondState}")
                gatt.discoverServices()
            }

        } else {
            if(gatt != null) {
                Log.i("ANCSDiscover", "Disconnected from ${gatt.device.alias}")
                if(targetANCSServers.contains(gatt.device.address)) {
                    Log.i("ANCSDiscover", "Attempting to recconect to ${gatt.device.alias}")
                    gatt.connect()
                }
            }
        }
    }

    override fun onServiceChanged(gatt: BluetoothGatt) {
        Log.i("ANCSDiscover", "Service Changed!")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        scope.launch { lockCh.send(0) }
        scope.launch {
            while (gatt!=null) {
                Log.i("ANCSDiscover", "===Waiting===")
                lockCh.receive()
                Log.i("ANCSDiscover", "===Continuing===")
                when(queue.receive()) {

                    QueueItem.CHAR_WRITE -> {
                        if(fullySubscribed.get()) {
                            Log.i("ANCSDiscover", "Dequeueing char write")
                            val charWrite = queueCharWrite.receive()
                            gatt.writeCharacteristic(charWrite.char, charWrite.data, charWrite.char.writeType)
                        } else {
                            Log.i("ANCSDiscover", "Requeueing char write")
                            queue.send(QueueItem.CHAR_WRITE)
                            lockCh.send(0)
                        }
                    }

                    QueueItem.DESC_WRITE -> {
                        Log.i("ANCSDiscover", "Dequeueing desc write")
                        val cccd = queueDescWrite.receive()
                        gatt.writeDescriptor(cccd, CCCD_SUB_NOTIFICATIONS)
                    }
                }
            }
        }

        if(gatt != null) {
            val service = gatt.services.firstOrNull { it.uuid == ANCS_SERVICE }
            if (service != null) {
                gatt.device.createBond()
                targetANCSServers.add(gatt.device.address)

                service.characteristics.forEach {
                    if(arrayOf(
                            ANCS_DATA_SOURCE,
                            ANCS_NOTIFICAITON_SOURCE,
//                            ANCS_CONTROL_POINT
                    ).contains(it.uuid)) {
                        // If it has a CCCD
                        val cccd = it.getDescriptor(CCCD_UUID)
                        if (cccd != null) {
                            // Subscribe to each characteristic by writing to their CCCDs
                            Log.i("ANCSDiscover", "Attempting subscription to ANCS characteristic ${it.uuid}.")
                            if(!gatt.setCharacteristicNotification(it, true)) {
                                Log.i("ANCSDiscover", "A subscription will not notify! :(")
                            }

                            scope.launch {
                                queue.send(QueueItem.DESC_WRITE)
                                queueDescWrite.send(cccd)
                            }


                        }

                    }
                }
            } else {
                Log.i("ANCSDiscover", "ANCS could not be found on the scanned device.")
                return
            }
        }

        // Assuming we only discover services once...
    }

    override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
        Log.i("ANCSDiscover", "PHY: TX: $txPhy | RX: $rxPhy | status$status")
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        Log.i("ANCSDiscover", "VALUE READ: ${value.toString()}")
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onCharacteristicWrite(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?,
        status: Int
    ) {

        Log.i("ANCSDiscovery", "Char write!")
        if (characteristic == null) {
            Log.i("ANCSDiscovery", "Char write status was $status but characteristic not found")
            return
        }
        if(status == BluetoothGatt.GATT_SUCCESS) {
            Log.i("ANCSDiscovery", "Successfully wrote to characteristic ${characteristic.uuid}")
        } else {
            Log.i("ANCSDiscovery", "Problem writing to characteristic: ${status.toString(16)}")
        }
        scope.launch { lockCh.send(0) }

    }

    override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
        super.onReliableWriteCompleted(gatt, status)
        Log.i("ANCSQueueMgr", "Reliable write!")
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d("ANCSDiscover", "MTU changed to $mtu")
        } else {
            Log.e("ANCSDiscover", "MTU change failed: $status")
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDescriptorWrite(
        gatt: BluetoothGatt?,
        descriptor: BluetoothGattDescriptor?,
        status: Int
    ) {
        // Check that write succeeded
        if(gatt != null && descriptor != null && descriptor.uuid == CCCD_UUID) {
            // This should always work
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("ANCSDiscover", "Successfully subscribed to char: ${descriptor.characteristic.uuid}")
                if(descriptor.characteristic.uuid == ANCS_NOTIFICAITON_SOURCE) {
                    subscribedNotifSource = true
                }
                if(descriptor.characteristic.uuid == ANCS_DATA_SOURCE) {
                    subscribedDataSource = true
                }
                fullySubscribed.set(subscribedNotifSource && subscribedDataSource)
                if (fullySubscribed.get()) {
                    Log.i("ANCSDiscover", "Fully subscribed!")
                    // Now that we know we are full subbed, we can kick off the char writes
                }

            } else {
                Log.i("ANCSDiscover", "Failed to subscribe to char because of code ${status}")
            }
        } else {
            Log.i("ANCSDiscover", "Error occurred on descriptor write.")
        }
        scope.launch { lockCh.send(0) }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS])
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        cvalue: ByteArray
    ) {
        val value = cvalue
        Log.i("ANCSDiscover", "Char change!")
        // TODO: Receive ANCS notifications here
        if(characteristic.uuid == ANCS_NOTIFICAITON_SOURCE) {
            val notifUuid = byteArrayOf(value[4], value[5], value[6], value[7])

            if(client.onNotificationSource(value[0], value[1], value[2], value[3], notifUuid)) {
                val ctrl_char = characteristic.service.getCharacteristic(ANCS_CONTROL_POINT)
                if(ctrl_char == null) {
                    Log.e("ANCSCtrlPoint", "Could not get Control Point!")
                }else {
                    Log.i("ANCSCtrlPoint",ctrl_char.permissions.toString())
                }
                val charWrite = WriteQueueItem(ctrl_char, client.getNotificationAttributes(
                    notifUuid,
                    byteArrayOf(
                        NotificationAttributeID.AppIdentifier.b,
                        NotificationAttributeID.Title.b,
                        0xff.toByte(),
                        0x01.toByte(),
                        NotificationAttributeID.Subtitle.b,
                        0xff.toByte(),
                        0x01.toByte(),
                        NotificationAttributeID.Message.b,
                        0xff.toByte(),
                        0x01.toByte(),
                    )
                ))
                scope.launch {
                    queue.send(QueueItem.CHAR_WRITE)
                    queueCharWrite.send(charWrite)
                }

            }
        }
        if (characteristic.uuid == ANCS_DATA_SOURCE) {
            Log.i("ANCSDiscover", "Data source returned!")
            val cmdId = CommandID.fromByte(value[0])
            val notifUuid = byteArrayOf(value[1], value[2], value[3], value[4])
            client.onDataSource(cmdId, notifUuid, value.slice(5 until value.size).toByteArray())
        }

    }
}