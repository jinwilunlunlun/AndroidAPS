@file:Suppress("WildcardImport")

package info.nightscout.androidaps.plugins.pump.omnipod.dash.driver.comm.io

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.pump.omnipod.dash.driver.comm.callbacks.BleCommCallbacks
import info.nightscout.androidaps.plugins.pump.omnipod.dash.driver.comm.callbacks.WriteConfirmationError
import info.nightscout.androidaps.plugins.pump.omnipod.dash.driver.comm.callbacks.WriteConfirmationSuccess
import info.nightscout.androidaps.plugins.pump.omnipod.dash.driver.comm.exceptions.*
import info.nightscout.androidaps.utils.extensions.toHex
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

sealed class BleReceiveResult
data class BleReceivePayload(val payload: ByteArray) : BleReceiveResult()
data class BleReceiveError(val msg: String, val cause: Throwable? = null) : BleReceiveResult()

sealed class BleSendResult

object BleSendSuccess : BleSendResult()
data class BleSendErrorSending(val msg: String, val cause: Throwable? = null) : BleSendResult()
data class BleSendErrorConfirming(val msg: String, val cause: Throwable? = null) : BleSendResult()

abstract class BleIO(
    private val aapsLogger: AAPSLogger,
    private val characteristic: BluetoothGattCharacteristic,
    private val incomingPackets: BlockingQueue<ByteArray>,
    private val gatt: BluetoothGatt,
    private val bleCommCallbacks: BleCommCallbacks,
    private val type: CharacteristicType
) {

    /***
     *
     * @param characteristic where to read from(CMD or DATA)
     * @return a byte array with the received data or error
     */
    fun receivePacket(timeoutMs: Long = DEFAULT_IO_TIMEOUT_MS): BleReceiveResult {
        try {
            val ret = incomingPackets.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: return BleReceiveError("Timeout")
            return BleReceivePayload(ret)
        } catch (e: InterruptedException) {
            return BleReceiveError("Interrupted", cause = e)
        }
    }

    /***
     *
     * @param characteristic where to write to(CMD or DATA)
     * @param payload the data to send
     */
    fun sendAndConfirmPacket(payload: ByteArray): BleSendResult {
        aapsLogger.debug(LTag.PUMPBTCOMM, "BleIO: Sending data on ${payload.toHex()}")
        val set = characteristic.setValue(payload)
        if (!set) {
            return BleSendErrorSending("Could set setValue on ${type.name}")
        }
        bleCommCallbacks.flushConfirmationQueue()
        val sent = gatt.writeCharacteristic(characteristic)
        if (!sent) {
            return BleSendErrorSending("Could not writeCharacteristic on {$type.name}")
        }

        return when (val confirmation = bleCommCallbacks.confirmWrite(
            payload, type.value,
            DEFAULT_IO_TIMEOUT_MS
        )) {
            is WriteConfirmationError ->
                BleSendErrorConfirming(confirmation.msg)
            is WriteConfirmationSuccess ->
                BleSendSuccess
        }
    }

    /**
     * Called before sending a new message.
     * The incoming queues should be empty, so we log when they are not.
     */
    fun flushIncomingQueue() {
        do {
            val found = incomingPackets.poll()?.also {
                aapsLogger.warn(LTag.PUMPBTCOMM, "BleIO: queue not empty, flushing: {${it.toHex()}")
            }
        } while (found != null)
    }

    /**
     * Enable intentions on the characteristic
     * This will signal the pod it can start sending back data
     * @return
     */
    fun readyToRead(): BleSendResult {
        val notificationSet = gatt.setCharacteristicNotification(characteristic, true)
        if (!notificationSet) {
            throw ConnectException("Could not enable notifications")
        }
        val descriptors = characteristic.descriptors
        if (descriptors.size != 1) {
            throw ConnectException("Expecting one descriptor, found: ${descriptors.size}")
        }
        val descriptor = descriptors[0]
        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val wrote = gatt.writeDescriptor(descriptor)
        if (!wrote) {
            throw ConnectException("Could not enable indications on descriptor")
        }
        val confirmation = bleCommCallbacks.confirmWrite(
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
            descriptor.uuid.toString(),
            DEFAULT_IO_TIMEOUT_MS
        )
        return when (confirmation) {
            is WriteConfirmationError ->
                throw ConnectException(confirmation.msg)
            is WriteConfirmationSuccess ->
                BleSendSuccess
        }
    }

    companion object {

        const val DEFAULT_IO_TIMEOUT_MS = 1000.toLong()
    }
}


