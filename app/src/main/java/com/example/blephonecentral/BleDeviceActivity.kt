package com.example.blephonecentral

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.room.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.concurrent.ArrayBlockingQueue

const val EXTRA_BLE_DEVICE = "BLEDevice"

private const val QUEUE_CAPACITY = 1000

class BleDeviceActivity : AppCompatActivity() {

    private val textViewDeviceName: TextView
        get() = findViewById(R.id.textViewDeviceName)

    private var device: BluetoothDevice? = null
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var sendReceive: SendReceive

    private var REQUEST_ENABLE_BLUETOOTH = 1
    private val APP_NAME = "BluetoothChatApp"
    private val MY_UUID = UUID.fromString("25AE1489-05D3-4C5B-8281-93D4E07420CF")

    private val STATE_LISTENING = 1
    private val STATE_CONNECTING = 2
    private val STATE_CONNECTED = 3
    private val STATE_CONNECTION_FAILED = 4
    private val STATE_MESSAGE_RECEIVED = 5

    private lateinit var logManager: LogManager

    private val queue: ArrayBlockingQueue<ByteArray> = ArrayBlockingQueue(QUEUE_CAPACITY)
    private var testIterator = 0

    @SuppressLint("MissingPermission")
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

        //------ The following lines connects the Android app to the server.-----
        SocketHandler.setSocket()
        SocketHandler.establishConnection()
        val mSocket = SocketHandler.getSocket()
        mSocket.on("response") { args ->
            val responseCode = args[0] as Int
            logManager.appendLog("Response code: $responseCode")
        }
        //-----------------------------------------------------------------------



        //bluClassicManager = BluClassicManager(device, logManager)
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (!bluetoothAdapter.isEnabled) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableIntent, REQUEST_ENABLE_BLUETOOTH)
        }
        logManager.appendLog("START")



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
        super.onDestroy()
        SocketHandler.closeConnection()
    }

    fun onTapStartClient(view: View){
        val clientClass = ClientClass(device!!)
        logManager.appendLog("client started")
        clientClass.start()
        logManager.appendLog("state connecting")
    }

    fun onTapSend(view: View){
        logManager.appendLog("send")
        val testString = logManager.getCurrentTime() + " Test"
        sendReceive.write(testString.toByteArray())
    }



    private val handler = Handler(Handler.Callback { msg: Message ->
        if (ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            when (msg.what) {
                STATE_LISTENING -> logManager.appendLog("state listening")
                STATE_CONNECTING -> logManager.appendLog("state connecting")
                STATE_CONNECTED -> logManager.appendLog("state connected")
                STATE_CONNECTION_FAILED -> logManager.appendLog("state connection failed")
                STATE_MESSAGE_RECEIVED -> {
                    val readBuffer = msg.obj as ByteArray
                    val tempMessage = String(readBuffer, 0, msg.arg1)
                    queue.add(readBuffer)
                    logManager.appendLog(tempMessage)
                }
            }
        }
        true
    })


    private inner class ClientClass(device1: BluetoothDevice) : Thread() {
        private val device: BluetoothDevice = device1
        private var socket: BluetoothSocket? = null

        override fun run() {
            try {
                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    socket = device.createRfcommSocketToServiceRecord(MY_UUID)
                    socket?.connect()
                    val message = Message.obtain()
                    message.what = STATE_CONNECTED
                    handler.sendMessage(message)
                    sendReceive = SendReceive(socket!!)
                    sendReceive.start()
                }
            } catch (e: IOException) {
                e.printStackTrace()
                val message = Message.obtain()
                message.what = STATE_CONNECTION_FAILED
                handler.sendMessage(message)
            }
        }
    }

    private inner class SendReceive(socket: BluetoothSocket) : Thread() {
        private val inputStream: InputStream
        private val outputStream: OutputStream

        init {
            var tempInput: InputStream? = null
            var tempOutput: OutputStream? = null
            try {
                tempInput = socket.inputStream
                tempOutput = socket.outputStream
            } catch (e: IOException) {
                e.printStackTrace()
            }
            inputStream = tempInput!!
            outputStream = tempOutput!!
        }

        override fun run() {
            val buffer = ByteArray(1024)
            var bytes: Int
            while (true) {
                try {
                    bytes = inputStream.read(buffer)

                    handler.obtainMessage(STATE_MESSAGE_RECEIVED, bytes, -1, buffer).sendToTarget()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                outputStream.write(bytes)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}