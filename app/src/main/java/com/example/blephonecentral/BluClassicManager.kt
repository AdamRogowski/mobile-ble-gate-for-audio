package com.example.blephonecentral

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.*

private const val SOCKET_UUID = "25AE1489-05D3-4C5B-8281-93D4E07420CF"

class BluClassicManager(private var device: BluetoothDevice?, private var logManager: LogManager) {

    private var bSocket: BluetoothSocket? = null

    private var connectThread = ConnectThread(logManager)

    private val socketUUID = UUID.fromString(SOCKET_UUID)



    private var inputStream: InputStream? = null

    var streamSetSuccess: Boolean = false


    fun tryConnect(): Boolean{

        var connectSuccess = connectThread.connect(device, socketUUID)

        if(connectSuccess){
            bSocket = connectThread.getSocket()

            logManager.appendLog("Connection successful")

            try {
                inputStream = bSocket!!.inputStream
                streamSetSuccess = true
                logManager.appendLog("input stream set successful")
                return true
            } catch (e: IOException) {
                logManager.appendLog("Error when creating output stream $e")
            }
        }
        else{
            logManager.appendLog("Connection unsuccessful")
            return false
        }
        return false
    }

    fun readInputStream(): ByteArray {
        //logManager.appendLog("try to read from instream")
        val arr: ByteArray = byteArrayOf()
        inputStream?.read(arr)
        return arr

    }
}