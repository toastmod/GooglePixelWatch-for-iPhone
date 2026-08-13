package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.NotificationChannel
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ANCSGattServiceClient(
    context: Context,
    channel: NotificationChannel,
    vibrator: Vibrator,
    private val amsClient: AMSClient
) : BluetoothGattCallback() {

    private val client = ANCSClient(context, channel, vibrator)

    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val CCCD_SUB_NOTIFICATIONS: ByteArray = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

    class WriteQueueItem(
        val char: BluetoothGattCharacteristic?,
        val data: ByteArray,
        val writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        val expectsCallback: Boolean = true
    )

    sealed class QueueItem {
        data class AncsCharWrite(val item: WriteQueueItem) : QueueItem()
        data class AmsCharWrite(val item: WriteQueueItem) : QueueItem()
        data class DescWrite(val cccd: BluetoothGattDescriptor) : QueueItem()
    }

    private enum class AMSSetupPhase {
        NONE,
        TRACK_PENDING,
        PLAYER_PENDING
    }

    private var targetANCSServers = mutableListOf<String>()

    private var subscribedDataSource = false
    private var subscribedNotifSource = false
    private var fullySubscribed = AtomicBoolean(false)
    private var awaitingResubscribeEnable = false

    private var dataSourceBuffer = ByteArray(0)

    private var queue: Channel<QueueItem> = Channel(Channel.UNLIMITED)
    private var lockCh: Channel<Int> = Channel()
    private val scope = CoroutineScope(Dispatchers.Default)

    private var connectedGatt: BluetoothGatt? = null
    private var amsSetupPhase = AMSSetupPhase.NONE

    private val trackSubscriptionPayload = byteArrayOf(
        AMSEntityID.Track.b,
        AMSTrackAttributeID.Artist.b,
        AMSTrackAttributeID.Album.b,
        AMSTrackAttributeID.Title.b,
        AMSTrackAttributeID.Duration.b
    )
    private val playerSubscriptionPayload = byteArrayOf(
        AMSEntityID.Player.b,
        AMSPlayerAttributeID.PlaybackInfo.b
    )

    init {
        watchForStaleTrackInfo()
    }

    /**
     * iOS doesn't always resend the current Track entity values on a fresh AMS subscription when
     * a song was already playing before the watch connected/reconnected (a known AMS quirk) - the
     * one-time registration in [onDescriptorWrite] can go unanswered, leaving [AMSClient] stuck
     * reporting a playing state with no title/artist forever. This periodically checks for exactly
     * that stuck combination (playing, no track data) and re-writes the Track subscription to
     * nudge iOS into resending it, so the UI's "Loading track info…" state actually resolves
     * instead of waiting on a push that may never come. No-ops harmlessly whenever track data is
     * already present, playback is paused, or there's no active connection.
     */
    private fun watchForStaleTrackInfo() {
        scope.launch {
            while (true) {
                delay(3_000)
                val state = amsClient.state.value
                if (state.player.isPlaying && !state.track.hasData && !awaitingResubscribeEnable) {
                    val gatt = connectedGatt ?: continue
                    Log.i("AMS", "Player is playing but no track metadata yet - cycling Track subscription")
                    requestTrackRefresh(gatt)
                }
            }
        }
    }

    /**
     * Simply re-writing the same Entity Update registration (same EntityID+AttributeIDs) gets
     * silently ignored by iOS - from its point of view nothing changed, so there's nothing to
     * resend. What actually forces a resend of the current Track values is disabling the AMS
     * Entity Update characteristic's notifications and re-enabling them, which iOS treats as a
     * brand new companion registration. [onDescriptorWrite] completes the other half of this once
     * the disable write lands.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun requestTrackRefresh(gatt: BluetoothGatt) {
        val entityUpdate = gatt.getService(AMS_SERVICE)?.getCharacteristic(AMS_ENTITY_UPDATE)
        val cccd = entityUpdate?.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.e("AMS", "Cannot refresh Track subscription: Entity Update CCCD missing")
            return
        }
        awaitingResubscribeEnable = true
        val result = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        if (result != BluetoothGatt.GATT_SUCCESS) {
            Log.e("AMS", "Failed to start disabling AMS Entity Update subscription: code=$result")
            awaitingResubscribeEnable = false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (gatt != null) {
                connectedGatt = gatt
                Log.i("ANCSDiscover", "Connected to ${gatt.device.alias} | bondstate: ${gatt.device.bondState}")
                gatt.discoverServices()
            }
        } else {
            amsSetupPhase = AMSSetupPhase.NONE
            amsClient.reset()
            connectedGatt = null
            if (gatt != null) {
                Log.i("ANCSDiscover", "Disconnected from ${gatt.device.alias}")
                if (targetANCSServers.contains(gatt.device.address)) {
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
            while (gatt != null) {
                lockCh.receive()
                when (val op = queue.receive()) {
                    is QueueItem.AncsCharWrite -> {
                        if (fullySubscribed.get()) {
                            val charWrite = op.item
                            val char = charWrite.char
                            if (char == null) {
                                Log.e("ANCSQueue", "Dropped CHAR_WRITE: characteristic was null")
                                lockCh.send(0)
                                continue
                            }
                            val result = gatt.writeCharacteristic(char, charWrite.data, charWrite.writeType)
                            if (result != BluetoothGatt.GATT_SUCCESS) {
                                Log.e("ANCSQueue", "Characteristic write failed to start: code=$result uuid=${char.uuid}")
                                lockCh.send(0)
                            } else if (!charWrite.expectsCallback) {
                                lockCh.send(0)
                            }
                        } else {
                            queue.send(op)
                            lockCh.send(0)
                        }
                    }

                    is QueueItem.AmsCharWrite -> {
                        val charWrite = op.item
                        val char = charWrite.char
                        if (char == null) {
                            Log.e("AMSQueue", "Dropped AMS_CHAR_WRITE: characteristic was null")
                            lockCh.send(0)
                            continue
                        }
                        val result = gatt.writeCharacteristic(char, charWrite.data, charWrite.writeType)
                        if (result != BluetoothGatt.GATT_SUCCESS) {
                            Log.e("AMSQueue", "Characteristic write failed to start: code=$result uuid=${char.uuid}")
                            lockCh.send(0)
                        } else if (!charWrite.expectsCallback) {
                            lockCh.send(0)
                        }
                    }

                    is QueueItem.DescWrite -> {
                        val cccd = op.cccd
                        val result = gatt.writeDescriptor(cccd, CCCD_SUB_NOTIFICATIONS)
                        if (result != BluetoothGatt.GATT_SUCCESS) {
                            Log.e("ANCSQueue", "Descriptor write failed to start: code=$result uuid=${cccd.characteristic.uuid}")
                            lockCh.send(0)
                        }
                    }
                }
            }
        }

        if (gatt == null) return
        connectedGatt = gatt

        val ancsService = gatt.services.firstOrNull { it.uuid == ANCS_SERVICE }
        if (ancsService != null) {
            gatt.device.createBond()
            targetANCSServers.add(gatt.device.address)
            ancsService.characteristics.forEach {
                if (arrayOf(ANCS_DATA_SOURCE, ANCS_NOTIFICAITON_SOURCE).contains(it.uuid)) {
                    val cccd = it.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        Log.i("ANCSDiscover", "Attempting subscription to ANCS characteristic ${it.uuid}.")
                        if (!gatt.setCharacteristicNotification(it, true)) {
                            Log.i("ANCSDiscover", "A subscription will not notify! :(")
                        }
                        enqueueDescWrite(cccd)
                    }
                }
            }
        } else {
            Log.i("ANCSDiscover", "ANCS could not be found on the scanned device.")
            return
        }

        val amsService = gatt.services.firstOrNull { it.uuid == AMS_SERVICE }
        if (amsService == null) {
            Log.i("AMS", "AMS not found on connected device")
            amsClient.setAvailable(false)
            return
        }
        amsClient.setAvailable(true)
        val entityUpdate = amsService.getCharacteristic(AMS_ENTITY_UPDATE)
        val cccd = entityUpdate?.getDescriptor(CCCD_UUID)
        if (entityUpdate == null || cccd == null) {
            Log.e("AMS", "AMS Entity Update characteristic or CCCD missing")
            amsClient.setAvailable(false)
            return
        }
        if (!gatt.setCharacteristicNotification(entityUpdate, true)) {
            Log.e("AMS", "Failed to enable local AMS Entity Update notifications")
            amsClient.setAvailable(false)
            return
        }
        enqueueDescWrite(cccd)
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
        when (amsSetupPhase) {
            AMSSetupPhase.TRACK_PENDING -> {
                if (gatt == null || characteristic == null) {
                    Log.e("AMS", "Track subscription callback missing gatt/characteristic")
                    amsSetupPhase = AMSSetupPhase.NONE
                    scope.launch { lockCh.send(0) }
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e("AMS", "Track subscription write failed: $status")
                    amsSetupPhase = AMSSetupPhase.NONE
                    scope.launch { lockCh.send(0) }
                    return
                }
                if (!writeAmsSubscriptionDirect(gatt, playerSubscriptionPayload, AMSSetupPhase.PLAYER_PENDING)) {
                    amsClient.setAvailable(false)
                    amsSetupPhase = AMSSetupPhase.NONE
                    scope.launch { lockCh.send(0) }
                }
                return
            }

            AMSSetupPhase.PLAYER_PENDING -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i("AMS", "AMS setup complete")
                } else {
                    Log.e("AMS", "Player subscription write failed: $status")
                }
                amsSetupPhase = AMSSetupPhase.NONE
                scope.launch { lockCh.send(0) }
                return
            }

            AMSSetupPhase.NONE -> {}
        }

        if (characteristic == null) {
            Log.i("ANCSDiscovery", "Char write status was $status but characteristic not found")
            scope.launch { lockCh.send(0) }
            return
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.i("ANCSDiscovery", "Successfully wrote to characteristic ${characteristic.uuid}")
        } else {
            Log.i("ANCSDiscovery", "Problem writing to characteristic: ${status.toString(16)}")
        }
        scope.launch { lockCh.send(0) }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDescriptorWrite(
        gatt: BluetoothGatt?,
        descriptor: BluetoothGattDescriptor?,
        status: Int
    ) {
        if (gatt == null || descriptor == null || descriptor.uuid != CCCD_UUID) {
            Log.i("ANCSDiscover", "Error occurred on descriptor write.")
            scope.launch { lockCh.send(0) }
            return
        }

        val charUuid = descriptor.characteristic.uuid

        if (awaitingResubscribeEnable && charUuid == AMS_ENTITY_UPDATE) {
            awaitingResubscribeEnable = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("AMS", "Disabled AMS Entity Update subscription, re-enabling to force a fresh Track push")
                gatt.writeDescriptor(descriptor, CCCD_SUB_NOTIFICATIONS)
            } else {
                Log.e("AMS", "Failed to disable AMS Entity Update subscription for refresh: $status")
            }
            return
        }

        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.i("ANCSDiscover", "Failed to subscribe to char because of code $status")
            if (charUuid == AMS_ENTITY_UPDATE) {
                amsSetupPhase = AMSSetupPhase.NONE
            }
            scope.launch { lockCh.send(0) }
            return
        }

        Log.i("ANCSDiscover", "Successfully subscribed to char: $charUuid")
        if (charUuid == ANCS_NOTIFICAITON_SOURCE) {
            subscribedNotifSource = true
        }
        if (charUuid == ANCS_DATA_SOURCE) {
            subscribedDataSource = true
        }
        fullySubscribed.set(subscribedNotifSource && subscribedDataSource)
        if (fullySubscribed.get()) {
            Log.i("ANCSDiscover", "Fully subscribed!")
        }

        if (charUuid == AMS_ENTITY_UPDATE) {
            if (!writeAmsSubscriptionDirect(gatt, trackSubscriptionPayload, AMSSetupPhase.TRACK_PENDING)) {
                amsClient.setAvailable(false)
                amsSetupPhase = AMSSetupPhase.NONE
                scope.launch { lockCh.send(0) }
            }
            return
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
        if (characteristic.uuid == ANCS_NOTIFICAITON_SOURCE) {
            if (value.size < 8) {
                Log.e("ANCSDiscover", "Notification Source payload too short: ${value.size}")
                return
            }
            val notifUuid = byteArrayOf(value[4], value[5], value[6], value[7])

            if (client.onNotificationSource(value[0], value[1], value[2], value[3], notifUuid)) {
                val ctrlChar = characteristic.service.getCharacteristic(ANCS_CONTROL_POINT)
                if (ctrlChar == null) {
                    Log.e("ANCSCtrlPoint", "Could not get Control Point. Dropping request.")
                    return
                }
                val charWrite = WriteQueueItem(
                    ctrlChar,
                    client.getNotificationAttributes(
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
                    )
                )
                enqueueAncsCharWrite(charWrite)
            }
        }

        if (characteristic.uuid == ANCS_DATA_SOURCE) {
            dataSourceBuffer += cvalue
            if (dataSourceBuffer.size < 5) return

            val cmdId = CommandID.fromByte(dataSourceBuffer[0])
            val notifUuid = byteArrayOf(dataSourceBuffer[1], dataSourceBuffer[2], dataSourceBuffer[3], dataSourceBuffer[4])
            val attributes = dataSourceBuffer.copyOfRange(5, dataSourceBuffer.size)

            if (client.onDataSource(cmdId, notifUuid, attributes)) {
                dataSourceBuffer = ByteArray(0)
            }
        }

        if (characteristic.uuid == AMS_ENTITY_UPDATE) {
            amsClient.onEntityUpdate(value)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendAMSCommand(commandId: Byte) {
        val gatt = connectedGatt
        if (gatt == null) {
            Log.e("AMS", "Cannot send command: no active GATT connection")
            return
        }
        val amsService = gatt.getService(AMS_SERVICE)
        if (amsService == null) {
            Log.e("AMS", "Cannot send command: AMS service missing")
            return
        }
        val remoteCommand = amsService.getCharacteristic(AMS_REMOTE_COMMAND)
        if (remoteCommand == null) {
            Log.e("AMS", "Cannot send command: Remote Command characteristic missing")
            return
        }
        val hasWrite = (remoteCommand.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val hasWriteNoResponse = (remoteCommand.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val writeType = when {
            hasWrite -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            hasWriteNoResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> {
                Log.e("AMS", "Cannot send command: Remote Command characteristic not writable")
                return
            }
        }
        val expectsCallback = writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        enqueueAmsCharWrite(
            WriteQueueItem(
                char = remoteCommand,
                data = byteArrayOf(commandId),
                writeType = writeType,
                expectsCallback = expectsCallback
            )
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun writeAmsSubscriptionDirect(
        gatt: BluetoothGatt,
        payload: ByteArray,
        nextPhase: AMSSetupPhase
    ): Boolean {
        val amsService = gatt.getService(AMS_SERVICE)
        val entityUpdate = amsService?.getCharacteristic(AMS_ENTITY_UPDATE)
        if (entityUpdate == null) {
            Log.e("AMS", "AMS Entity Update characteristic missing")
            return false
        }
        val result = gatt.writeCharacteristic(entityUpdate, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (result != BluetoothGatt.GATT_SUCCESS) {
            Log.e("AMS", "AMS subscription write failed to start: code=$result")
            return false
        }
        amsSetupPhase = nextPhase
        return true
    }

    private fun enqueueDescWrite(cccd: BluetoothGattDescriptor) {
        if (queue.trySend(QueueItem.DescWrite(cccd)).isFailure) {
            Log.e("ANCSQueue", "Failed to enqueue descriptor write item")
        }
    }

    private fun enqueueAncsCharWrite(item: WriteQueueItem) {
        if (queue.trySend(QueueItem.AncsCharWrite(item)).isFailure) {
            Log.e("ANCSQueue", "Failed to enqueue ANCS char write item")
        }
    }

    private fun enqueueAmsCharWrite(item: WriteQueueItem) {
        if (queue.trySend(QueueItem.AmsCharWrite(item)).isFailure) {
            Log.e("AMSQueue", "Failed to enqueue AMS char write item")
        }
    }
}
