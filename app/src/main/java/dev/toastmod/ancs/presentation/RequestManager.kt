package dev.toastmod.ancs.presentation

import android.bluetooth.BluetoothGattDescriptor
import dev.toastmod.ancs.presentation.ANCSGattServiceClient.WriteQueueItem

class RequestManager {
    var queue: MutableList<BluetoothGattDescriptor> = mutableListOf()
    var queueCharWrite: MutableList<WriteQueueItem> = mutableListOf()
    var queueLen = 0
}