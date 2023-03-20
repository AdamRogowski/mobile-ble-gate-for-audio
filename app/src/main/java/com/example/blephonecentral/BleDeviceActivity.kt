package com.example.blephonecentral

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.room.Room
import java.text.SimpleDateFormat
import java.util.*
import com.example.room.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.log

const val EXTRA_BLE_DEVICE = "BLEDevice"
private const val SERVICE_UUID = "25AE1449-05D3-4C5B-8281-93D4E07420CF"
private const val CHAR_FOR_INDICATE_UUID = "25AE1494-05D3-4C5B-8281-93D4E07420CF"
private const val CCC_DESCRIPTOR_UUID = "00002930-0000-1000-8000-00805f9b34fb"

class BleDeviceActivity : AppCompatActivity() {
    enum class BLELifecycleState {
        Disconnected,
        Connecting,
        ConnectedDiscovering,
        ConnectedSubscribing,
        Connected
    }

    //DB
    companion object {
        lateinit var database: AppDatabase
    }

    private var lifecycleState = BLELifecycleState.Disconnected
        set(value) {
            field = value
            appendLog("status = $value")
        }

    private val textViewDeviceName: TextView
        get() = findViewById(R.id.textViewDeviceName)
    private val textViewLog: TextView
        get() = findViewById(R.id.textViewLog)
    private val scrollViewLog: ScrollView
        get() = findViewById(R.id.scrollViewLog)

    private var device: BluetoothDevice? = null
    private var connectedGatt: BluetoothGatt? = null
    private var characteristicForIndicate: BluetoothGattCharacteristic? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ble_device_activity)

        //DB
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "sensor_readings_db"
        ).build()

        device = intent.getParcelableExtra(EXTRA_BLE_DEVICE)
        val deviceName: String = device?.let {
            "${it.name ?: "<no name"} (${it.address})"
        } ?: run {
            "<null>"
        }
        textViewDeviceName.text = deviceName
        connect()
    }

    private fun init(){

    }

    override fun onDestroy() {
        connectedGatt?.close()
        connectedGatt = null
        super.onDestroy()
    }

    //DB
    private fun insertIntoDatabase(date: String, value: Int){
        val reading = Reading(time = date, value = value)
        CoroutineScope(Dispatchers.IO).launch {
            database.readingDao().insert(reading)
        }
    }

    //DB
    private fun clearDatabase(){
        CoroutineScope(Dispatchers.IO).launch {
            database.readingDao().nukeTable()
        }
    }

    //DB
    private fun logDatabaseContent(){
        CoroutineScope(Dispatchers.IO).launch {
            val allReadings = database.readingDao().getAll()
            var allReadingsText = "All sensor readings from the database:\n"
            for (reading in allReadings){
                allReadingsText += (reading.time + " " + reading.value.toString() + "\n")
            }
            //remove last \n
            appendLog(allReadingsText.dropLast(1))
        }
    }

    //DB
    fun onTapLogDatabase(view: View) {
        logDatabaseContent()
    }

    fun onTapClearDatabase(view: View){
        appendLog("Database cleared")
        clearDatabase()
    }

    //reading from watch follows pattern "hr:ddd"
    fun matchesPattern(s: String): Boolean {
        val pattern = Regex("hr:\\d{1,3}")
        return pattern.matches(s)
    }

    //extract digits from pattenr
    fun extractNumber(s: String): Int {
        val pattern = Regex("hr:(\\d+)")
        val match = pattern.find(s)
        return match?.groupValues?.get(1)?.toInt() ?: -1
    }

    private fun getCurrentTime(): String{
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    @SuppressLint("SetTextI18n")
    private fun appendLog(message: String) {
        Log.d("appendLog", message)
        runOnUiThread {
            textViewLog.text = textViewLog.text.toString() + "\n${getCurrentTime()} $message"

            // wait for the textView to update
            Handler(Looper.getMainLooper()).postDelayed({
                scrollViewLog.fullScroll(View.FOCUS_DOWN)
            }, 20)
        }
    }

    @SuppressLint("SetTextI18n")
    fun onTapClearLog(view: View) {
        textViewLog.text = "Logs:"
        appendLog("log cleared")
    }

    private fun connect() {
        device?.let {
            appendLog("Connecting to ${it.name}")
            lifecycleState = BLELifecycleState.Connecting
            it.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } ?: run {
            appendLog("ERROR: BluetoothDevice is null, cannot connect")
        }
    }

    private fun bleRestartLifecycle() {
        val timeoutSec = 6L
        appendLog("Will try reconnect in $timeoutSec seconds")
        Handler(Looper.getMainLooper()).postDelayed({
            connect()
        }, timeoutSec * 1000)
    }

    private fun subscribeToIndications(characteristic: BluetoothGattCharacteristic, gatt: BluetoothGatt) {
        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                appendLog("ERROR: setNotification(true) failed for ${characteristic.uuid}")
                return
            }
            cccDescriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            gatt.writeDescriptor(cccDescriptor)
        }

    }

    private fun unsubscribeFromCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val gatt = connectedGatt ?: return

        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (!gatt.setCharacteristicNotification(characteristic, false)) {
                appendLog("ERROR: setNotification(false) failed for ${characteristic.uuid}")
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
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    appendLog("Connected to $deviceAddress")

                    // recommended on UI thread https://punchthrough.com/android-ble-guide/
                    Handler(Looper.getMainLooper()).post {
                        lifecycleState = BLELifecycleState.ConnectedDiscovering
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    appendLog("Disconnected from $deviceAddress")
                    connectedGatt = null
                    gatt.close()
                    lifecycleState = BLELifecycleState.Disconnected
                    bleRestartLifecycle()
                }
            } else {
                // random error 133 - close() and try reconnect

                appendLog("ERROR: onConnectionStateChange status=$status deviceAddress=$deviceAddress, disconnecting")

                connectedGatt = null
                gatt.close()
                lifecycleState = BLELifecycleState.Disconnected
                bleRestartLifecycle()
            }
        }

        //@Suppress("SpellCheckingInspection")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            appendLog("onServicesDiscovered services.count=${gatt.services.size} status=$status")

            if (status == 129 /*GATT_INTERNAL_ERROR*/) {
                // it should be a rare case, this article recommends to disconnect:
                // https://medium.com/@martijn.van.welie/making-android-ble-work-part-2-47a3cdaade07
                appendLog("ERROR: status=129 (GATT_INTERNAL_ERROR), disconnecting")
                gatt.disconnect()
                return
            }

            val service = gatt.getService(UUID.fromString(SERVICE_UUID)) ?: run {
                appendLog("ERROR: Service not found $SERVICE_UUID, disconnecting")
                gatt.disconnect()
                return
            }

            connectedGatt = gatt
            characteristicForIndicate = service.getCharacteristic(UUID.fromString(CHAR_FOR_INDICATE_UUID))

            characteristicForIndicate?.let {
                lifecycleState = BLELifecycleState.ConnectedSubscribing
                subscribeToIndications(it, gatt)
            } ?: run {
                appendLog("WARN: characteristic not found $CHAR_FOR_INDICATE_UUID")
                lifecycleState = BLELifecycleState.Connected
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == UUID.fromString(CHAR_FOR_INDICATE_UUID)) {
                val strValue = characteristic.value.toString(Charsets.UTF_8)
                appendLog("onCharacteristicChanged value=\"$strValue\"")

                //DB
                if(matchesPattern(strValue)) insertIntoDatabase(getCurrentTime(), extractNumber(strValue))
            } else {
                appendLog("onCharacteristicChanged unknown uuid $characteristic.uuid")
            }
        }

    }
    //------------------------------------------------------------------------------------------------------------------
}