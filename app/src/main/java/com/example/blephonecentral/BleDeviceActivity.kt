package com.example.blephonecentral

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.room.*
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import androidx.activity.addCallback


const val EXTRA_BLE_DEVICE = "BLEDevice"
private const val SERVICE_UUID = "25AE1449-05D3-4C5B-8281-93D4E07420CF"
private const val CHAR_FOR_NOTIFY_UUID = "25AE1494-05D3-4C5B-8281-93D4E07420CF"
private const val CCC_DESCRIPTOR_UUID = "00002930-0000-1000-8000-00805f9b34fb"

//ffmpeg supported freq: 64000 48000 44100 32000 24000 22050 16000 12000 11025 8000 7350
private const val SAMPLING_RATE_IN_HZ = 12000

private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_8BIT

private const val BUFFER_SIZE_FACTOR = 1

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
            logManager.appendLog("status = $value")
        }

    private val textViewDeviceName: TextView
        get() = findViewById(R.id.textViewDeviceName)

    private var device: BluetoothDevice? = null
    private var connectedGatt: BluetoothGatt? = null
    private var characteristicForNotify: BluetoothGattCharacteristic? = null

    private lateinit var logManager: LogManager

    //-------------MIC RECEIVING------------------
    /*


    private var buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)

    private var track = AudioTrack(AudioManager.STREAM_MUSIC, SAMPLING_RATE_IN_HZ,
        CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE, AudioTrack.MODE_STREAM)


    */
    //--------------------------------------------
    private val minBufferSize = AudioRecord.getMinBufferSize(SAMPLING_RATE_IN_HZ,
        CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR

    /*
        ENCODING_PCM_16BIT -> 2
        ENCODING_PCM_8BIT -> 3
        ENCODING_PCM_FLOAT -> 4
         */

    private fun logAudioBufferSize(samplingRate: Int, audioFormat: Int, channelConfig: Int = AudioFormat.CHANNEL_IN_MONO){
        val bufferSize = AudioRecord.getMinBufferSize(samplingRate, channelConfig, audioFormat)
        val audioFormatName: String = when (audioFormat){
            2 -> "PCM_16BIT"
            3 -> "PCM_8BIT"
            4 -> "PCM_FLOAT"
            else -> "undefined"
        }
        logManager.appendLog("audioBuffer size: $bufferSize, samplingRate: $samplingRate, audio format: $audioFormatName")
    }

    private var track = AudioTrack(AudioManager.STREAM_MUSIC, SAMPLING_RATE_IN_HZ,
        AudioFormat.CHANNEL_CONFIGURATION_MONO, AUDIO_FORMAT,
        minBufferSize, AudioTrack.MODE_STREAM)

    private var playingThread: Thread? = null

    private var receivingThread: Thread? = null

    private val queue: ArrayBlockingQueue<ByteArray> = ArrayBlockingQueue(QUEUE_CAPACITY)

    private var writeTrackOn: Boolean = true

    private var testIterator = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ble_device_activity)

        logManager = LogManager(this)

        device = intent.getParcelableExtra(EXTRA_BLE_DEVICE)
        val deviceName: String = device?.let {
            "${it.name ?: "<no name"} (${it.address})"
        } ?: run {
            "<null>"
        }
        textViewDeviceName.text = deviceName

        // The following lines connects the Android app to the server.
        SocketHandler.setSocket()
        SocketHandler.establishConnection()

        val mSocket = SocketHandler.getSocket()

        mSocket.on("response") { args ->
            val responseCode = args[0] as Int
            logManager.appendLog("Response code: $responseCode")
        }

        /*
        val samples: IntArray  = intArrayOf(64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        val format: IntArray = intArrayOf(2, 3, 4)

        samples.forEach {  i ->
            format.forEach { j ->
                logAudioBufferSize(i, j)
            }
        }

         */


        //startPlaying()



        receivingThread = Thread({ connect() }, "ReceiveAudio Thread")
        receivingThread!!.start()

        testIterator = 0

        Thread {
            while (true) {
                val data = queue.take()
                //println("took from queue")
                mSocket.emit("audioData", data)
                logManager.appendLog(logManager.getCurrentTime() + " $testIterator: data sent")
                testIterator++
            }
        }.start()




    }

    override fun onDestroy() {
        connectedGatt?.close()
        connectedGatt = null
        super.onDestroy()
        SocketHandler.closeConnection()
    }

    fun onTapStopSocket(view: View){
        SocketHandler.closeConnection()
    }



    // Playback received audio
    private fun startPlaying() {
        writeTrackOn = true
        track.play()
        //logManager.appendLog(track.state.toString())
        // Receive and play audio
        receivingThread = Thread({ connect() }, "ReceiveAudio Thread")
        playingThread = Thread({ writeTrack() }, "WriteTrack Thread")
        receivingThread!!.start()
        playingThread!!.start()
    }

    /*
    // Receive audio and write into audio track object for playback
    fun receiveRecording() {
        val i = 0
        while (!isRecording) {
            try {
                if (inStream.available() === 0) {
                    //Do nothing
                } else {
                    inStream.read(buffer)
                    track.write(buffer, 0, BUFFER_SIZE)
                }
            } catch (e: IOException) {
                Log.d("AUDIO", "Error when receiving recording")
            }
        }
    }

     */

    // Stop playing and free up resources
    fun stopPlaying() {
        track.stop()
        receivingThread = null
        playingThread = null
        logManager.appendLog("receiving stopped")
        writeTrackOn = false
    }

    private fun requestMTU(gatt: BluetoothGatt){
        gatt.requestMtu(GATT_MAX_MTU_SIZE)
    }



    @SuppressLint("SetTextI18n")
    fun onTapClearLog(view: View) {
        logManager.clearLog()
    }

    private fun connect() {
        device?.let {
            logManager.appendLog("Connecting to ${it.name}")
            lifecycleState = BLELifecycleState.Connecting
            it.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } ?: run {
            logManager.appendLog("ERROR: BluetoothDevice is null, cannot connect")
        }
    }

    private fun bleRestartLifecycle() {
        val timeoutSec = 2L
        logManager.appendLog("Will try reconnect in $timeoutSec seconds")
        Handler(Looper.getMainLooper()).postDelayed({
            connect()
        }, timeoutSec * 1000)
    }

    private fun subscribeToNotifications(characteristic: BluetoothGattCharacteristic, gatt: BluetoothGatt) {
        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                logManager.appendLog("ERROR: setNotification(true) failed for ${characteristic.uuid}")
                return
            }
            cccDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccDescriptor)
        }

    }

    private fun unsubscribeFromCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val gatt = connectedGatt ?: return

        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (!gatt.setCharacteristicNotification(characteristic, false)) {
                logManager.appendLog("ERROR: setNotification(false) failed for ${characteristic.uuid}")
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

    //BLE events, when connected----------------------------------------------------------------------------------------
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // The MTU negotiation was successful
                logManager.appendLog("MTU size changed to $mtu bytes")

                // recommended on UI thread https://punchthrough.com/android-ble-guide/
                Handler(Looper.getMainLooper()).post {
                    lifecycleState = BLELifecycleState.ConnectedDiscovering

                    gatt.discoverServices()
                }
            } else {
                // The MTU negotiation failed
                logManager.appendLog("MTU size negotiation failed with status $status")
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
                    logManager.appendLog("Connected to $deviceAddress, requesting priority and MTU")

                    if(gatt.requestConnectionPriority(GATT_CONNECTION_PRIORITY)) logManager.appendLog("connection priority changed successfully")
                    else logManager.appendLog("connection priority not changed")
                    requestMTU(gatt)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    logManager.appendLog("Disconnected from $deviceAddress")
                    connectedGatt = null
                    gatt.close()
                    lifecycleState = BLELifecycleState.Disconnected
                    bleRestartLifecycle()
                }
            } else {
                // random error 133 - close() and try reconnect

                logManager.appendLog("ERROR: onConnectionStateChange status=$status deviceAddress=$deviceAddress, disconnecting")

                connectedGatt = null
                gatt.close()
                lifecycleState = BLELifecycleState.Disconnected
                bleRestartLifecycle()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            logManager.appendLog("onServicesDiscovered services.count=${gatt.services.size} status=$status")

            if (status == 129 /*GATT_INTERNAL_ERROR*/) {
                // it should be a rare case, this article recommends to disconnect:
                // https://medium.com/@martijn.van.welie/making-android-ble-work-part-2-47a3cdaade07
                logManager.appendLog("ERROR: status=129 (GATT_INTERNAL_ERROR), disconnecting")
                gatt.disconnect()
                return
            }

            val service = gatt.getService(UUID.fromString(SERVICE_UUID)) ?: run {
                logManager.appendLog("ERROR: Service not found $SERVICE_UUID, disconnecting")
                gatt.disconnect()
                return
            }

            connectedGatt = gatt
            characteristicForNotify = service.getCharacteristic(UUID.fromString(CHAR_FOR_NOTIFY_UUID))

            characteristicForNotify?.let {
                lifecycleState = BLELifecycleState.ConnectedSubscribing
                subscribeToNotifications(it, gatt)
            } ?: run {
                logManager.appendLog("WARN: characteristic not found $CHAR_FOR_NOTIFY_UUID")
                lifecycleState = BLELifecycleState.Connected
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == UUID.fromString(CHAR_FOR_NOTIFY_UUID)) {
                //buffer = characteristic.value
                //logManager.appendLog("onCharacteristicChanged value=\"$strValue\"")
                val strValue = characteristic.value.toString(Charsets.UTF_8)
                //logManager.appendLog("onCharacteristicChanged value=\"$strValue\"")

                //logManager.appendLog(logManager.getCurrentTime() + " " + track.write(characteristic.value, 0, characteristic.value.size, AudioTrack.WRITE_NON_BLOCKING).toString())

                //val buffer: ByteBuffer = ByteBuffer.wrap(characteristic.value)

                //logManager.appendLog(characteristic.value.size.toString())

                //logManager.appendLog(logManager.getCurrentTime() + " received " + + characteristic.value.size + " " + characteristic.value.toString(Charsets.UTF_8))


                queue.add(characteristic.value)
                //logManager.appendLog("received " + characteristic.value.size.toString() + " bytes")

            } else {
                logManager.appendLog("onCharacteristicChanged unknown uuid $characteristic.uuid")
            }
        }
    }

    private fun writeTrack(){
        while(writeTrackOn){
            val arr = queue.take()
            //AudioTrack.WRITE_NON_BLOCKING looses some data
            logManager.appendLog(logManager.getCurrentTime() + " " + track.write(arr, 0, arr.size, AudioTrack.WRITE_BLOCKING).toString())
        }
    }

    //------------------------------------------------------------------------------------------------------------------
}