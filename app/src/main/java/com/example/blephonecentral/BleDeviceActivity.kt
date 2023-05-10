package com.example.blephonecentral

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.room.*
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import io.socket.client.Socket


const val EXTRA_BLE_DEVICE = "BLEDevice"
private const val SERVICE_UUID = "25AE1449-05D3-4C5B-8281-93D4E07420CF"
private const val CHAR_FOR_NOTIFY_UUID = "25AE1494-05D3-4C5B-8281-93D4E07420CF"
private const val CCC_DESCRIPTOR_UUID = "00002930-0000-1000-8000-00805f9b34fb"

private const val GATT_MAX_MTU_SIZE = 517

private const val GATT_CONNECTION_PRIORITY = BluetoothGatt.CONNECTION_PRIORITY_HIGH

private const val QUEUE_CAPACITY = 1000


class BleDeviceActivity : AppCompatActivity() {
    enum class BLELifecycleState {
        Disconnected,
        Connecting,
        ConnectedDiscovering,
        ConnectedSubscribing,
        Connected
    }

    private var lifecycleState = BLELifecycleState.Disconnected
        set(value) {
            field = value
            LogManager.appendLog("status = $value")
        }

    private val textViewDeviceName: TextView
        get() = findViewById(R.id.textViewDeviceName)

    private var device: BluetoothDevice? = null
    private var connectedGatt: BluetoothGatt? = null
    private var characteristicForNotify: BluetoothGattCharacteristic? = null

    private var receivingThread: Thread? = null
    private var sendingThread: Thread? = null

    private lateinit var mSocket: Socket

    private val queue: ArrayBlockingQueue<ByteArray> = ArrayBlockingQueue(QUEUE_CAPACITY)

    private var testIterator = 0

    private fun fakeSleepModeOn(){
        // Change screen brightness to minimum
        val brightness = 0
        val layoutParam = window.attributes
        layoutParam.screenBrightness = brightness.toFloat()
        window.attributes = layoutParam

        // Keep the screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun fakeSleepModeOff(){
        // Change screen brightness back to normal
        val brightness = -1 // -1 means use the system default brightness
        val layoutParam = window.attributes
        layoutParam.screenBrightness = brightness.toFloat()
        window.attributes = layoutParam

        // Allow the screen to turn off automatically
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }



    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ble_device_activity)

        device = intent.getParcelableExtra(EXTRA_BLE_DEVICE)
        val deviceName: String = device?.let {
            "${it.name ?: "<no name"} (${it.address})"
        } ?: run {
            "<null>"
        }
        textViewDeviceName.text = deviceName

        fakeSleepModeOn()

        LogManager.setActivity(this)

        // The following lines connects the Android app to the server.
        SocketHandler.setSocket()
        SocketHandler.establishConnection()

        mSocket = SocketHandler.getSocket()

        mSocket.on("response") { args ->
            val responseCode = args[0] as Int
            LogManager.appendLog("Response code: $responseCode")
        }

        receivingThread = Thread({ connect() }, "ReceiveAudio Thread")
        receivingThread!!.start()

        testIterator = 0

        sendingThread = Thread({ sendFromQueue()}, "SendFromQueue Thread")
        sendingThread!!.start()

        /*

        Thread {
            while (true) {
                val data = queue.take()
                //println("took from queue")
                mSocket.emit("audioData", data)
                if(testIterator % 100 == 1) LogManager.appendLog(LogManager.getCurrentTime() + " $testIterator: data sent")
                testIterator++
            }
        }.start()

         */
    }

    private fun sendFromQueue(){
        while (true) {
            val data = queue.take()
            //println("took from queue")
            mSocket.emit("audioData", data)
            if(testIterator % 100 == 1) LogManager.appendLog(LogManager.getCurrentTime() + " $testIterator: data sent")
            testIterator++
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        connectedGatt?.services?.forEach { service ->
            service.characteristics.forEach { characteristic ->
                unsubscribeFromCharacteristic(characteristic)
            }
        }
        connectedGatt?.close()
        connectedGatt = null
        SocketHandler.closeConnection()
        fakeSleepModeOff()
    }

    @SuppressLint("MissingPermission")
    fun onTapStopSocket(view: View){
        connectedGatt?.services?.forEach { service ->
            service.characteristics.forEach { characteristic ->
                unsubscribeFromCharacteristic(characteristic)
            }
        }
        connectedGatt?.close()
        connectedGatt = null
        SocketHandler.closeConnection()
        fakeSleepModeOff()
    }

    @SuppressLint("MissingPermission")
    private fun requestMTU(gatt: BluetoothGatt){
        gatt.requestMtu(GATT_MAX_MTU_SIZE)
    }

    @SuppressLint("SetTextI18n")
    fun onTapClearLog(view: View) {
        LogManager.clearLog()
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        device?.let {
            LogManager.appendLog("Connecting to ${it.name}")
            lifecycleState = BLELifecycleState.Connecting
            it.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } ?: run {
            LogManager.appendLog("ERROR: BluetoothDevice is null, cannot connect")
        }
    }

    private fun bleRestartLifecycle() {
        val timeoutSec = 2L
        LogManager.appendLog("Will try reconnect in $timeoutSec seconds")
        Handler(Looper.getMainLooper()).postDelayed({
            connect()
        }, timeoutSec * 1000)
    }

    @SuppressLint("MissingPermission")
    private fun subscribeToNotifications(characteristic: BluetoothGattCharacteristic, gatt: BluetoothGatt) {
        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                LogManager.appendLog("ERROR: setNotification(true) failed for ${characteristic.uuid}")
                return
            }
            cccDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccDescriptor)
        }

    }

    @SuppressLint("MissingPermission")
    private fun unsubscribeFromCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val gatt = connectedGatt ?: return

        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (!gatt.setCharacteristicNotification(characteristic, false)) {
                LogManager.appendLog("ERROR: setNotification(false) failed for ${characteristic.uuid}")
                return
            }
            cccDescriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccDescriptor)
        }
    }

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    @SuppressLint("MissingPermission")
    //BLE events, when connected----------------------------------------------------------------------------------------
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // The MTU negotiation was successful
                LogManager.appendLog("MTU size changed to $mtu bytes")

                // recommended on UI thread https://punchthrough.com/android-ble-guide/
                Handler(Looper.getMainLooper()).post {
                    lifecycleState = BLELifecycleState.ConnectedDiscovering

                    gatt.discoverServices()
                }
            } else {
                // The MTU negotiation failed
                LogManager.appendLog("MTU size negotiation failed with status $status")
                connectedGatt = null
                gatt.close()
                lifecycleState = BLELifecycleState.Disconnected
                bleRestartLifecycle()
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    LogManager.appendLog("Connected to $deviceAddress, requesting priority and MTU")

                    if(gatt.requestConnectionPriority(GATT_CONNECTION_PRIORITY)) LogManager.appendLog("connection priority changed successfully")
                    else LogManager.appendLog("connection priority not changed")
                    requestMTU(gatt)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    LogManager.appendLog("Disconnected from $deviceAddress")
                    connectedGatt = null
                    gatt.close()
                    lifecycleState = BLELifecycleState.Disconnected
                    bleRestartLifecycle()
                }
            } else {
                // random error 133 - close() and try reconnect

                LogManager.appendLog("ERROR: onConnectionStateChange status=$status deviceAddress=$deviceAddress, disconnecting")

                connectedGatt = null
                gatt.close()
                lifecycleState = BLELifecycleState.Disconnected
                bleRestartLifecycle()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            LogManager.appendLog("onServicesDiscovered services.count=${gatt.services.size} status=$status")

            if (status == 129) {
                LogManager.appendLog("ERROR: status=129 (GATT_INTERNAL_ERROR), disconnecting")
                gatt.disconnect()
                return
            }

            val service = gatt.getService(UUID.fromString(SERVICE_UUID)) ?: run {
                LogManager.appendLog("ERROR: Service not found $SERVICE_UUID, disconnecting")
                gatt.disconnect()
                return
            }

            connectedGatt = gatt
            characteristicForNotify = service.getCharacteristic(UUID.fromString(CHAR_FOR_NOTIFY_UUID))

            characteristicForNotify?.let {
                subscribeToNotifications(it, gatt)
                lifecycleState = BLELifecycleState.ConnectedSubscribing
            } ?: run {
                LogManager.appendLog("WARN: characteristic not found $CHAR_FOR_NOTIFY_UUID")
                lifecycleState = BLELifecycleState.Connected
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == UUID.fromString(CHAR_FOR_NOTIFY_UUID)) {

                queue.add(characteristic.value)

            } else {
                LogManager.appendLog("onCharacteristicChanged unknown uuid $characteristic.uuid")
            }
        }
    }
}