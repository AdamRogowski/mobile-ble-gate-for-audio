package com.example.blephonecentral

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.room.*
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.concurrent.ArrayBlockingQueue

const val EXTRA_BLE_DEVICE = "BLUDevice"
private const val QUEUE_CAPACITY = 1000
private const val BUFFER_SIZE = 960
private val MY_UUID = UUID.fromString("25AE1489-05D3-4C5B-8281-93D4E07420CF")
private const val REQUEST_ENABLE_BLUETOOTH = 1


class BleDeviceActivity : AppCompatActivity() {

    private val textViewDeviceName: TextView
        get() = findViewById(R.id.textViewDeviceName)

    private var device: BluetoothDevice? = null
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var streamReader: StreamReader


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


        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (!bluetoothAdapter.isEnabled) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableIntent, REQUEST_ENABLE_BLUETOOTH)
        }

        testIterator = 0

        Thread {
            while (true) {
                val data = queue.take()
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
                    logManager.appendLog("state connected")
                    streamReader = StreamReader(socket!!)
                    streamReader.start()
                }
            } catch (e: IOException) {
                logManager.appendLog("state connection failed, $e")
            }
        }
    }

    private inner class StreamReader(socket: BluetoothSocket) : Thread() {
        private val inputStream: InputStream

        init {
            var tempInput: InputStream? = null
            try {
                tempInput = socket.inputStream
            } catch (e: IOException) {
                e.printStackTrace()
            }
            inputStream = tempInput!!
        }

        override fun run() {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                try {
                    inputStream.read(buffer)

                    queue.add(buffer)
                } catch (e: IOException) {
                    logManager.appendLog("input stream read failed, $e")
                }
            }
        }
    }
}