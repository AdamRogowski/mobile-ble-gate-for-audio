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

const val EXTRA_BLE_DEVICE = "BLEDevice"

private const val QUEUE_CAPACITY = 1000

class BleDeviceActivity : AppCompatActivity() {

    private val textViewDeviceName: TextView
        get() = findViewById(R.id.textViewDeviceName)

    private var device: BluetoothDevice? = null

    private lateinit var logManager: LogManager


    private val queue: ArrayBlockingQueue<ByteArray> = ArrayBlockingQueue(QUEUE_CAPACITY)

    private var testIterator = 0

    private var bluClassicManager: BluClassicManager? = null

    private var startAction = false


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

        bluClassicManager = BluClassicManager(device, logManager)



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

    fun onTapStopSocket(view: View){
        if(bluClassicManager!!.tryConnect()){
            startAction = true
            logManager.appendLog("start action")

            Thread {

                logManager.appendLog("im here")
                while (true) {
                    val arr = bluClassicManager!!.readInputStream()
                    val size = arr.size
                    logManager.appendLog("red and added to queue: $size")

                    //queue.add(arr)
                    Thread.sleep(1000)
                }

            }.start()
        }
        else logManager.appendLog("errrrr")
        //SocketHandler.closeConnection()
    }
}