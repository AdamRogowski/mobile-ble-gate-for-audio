package com.example.blephonecentral

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

private const val BLUETOOTH_PERMISSIONS_REQUEST_CODE = 1

class MainActivity : AppCompatActivity() {
    private val recyclerViewDeviceList: RecyclerView
        get() = findViewById(R.id.recyclerViewDeviceList)

    private val deviceListAdapter: DeviceListAdapter by lazy {
        DeviceListAdapter(ArrayList()) { device ->
            // onClickListener
            val intent = Intent(this, BleDeviceActivity::class.java).apply {
                putExtra(EXTRA_BLE_DEVICE, device)
            }
            startActivity(intent)
        }
    }

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            // Bluetooth is not enabled, show a dialog window
            AlertDialog.Builder(this)
                .setMessage("\n\nTo use this app, please enable Bluetooth on your device.")
                .setPositiveButton("Close app") { _, _ ->
                    // Close the app when the button is clicked
                    finish()
                }
                .setCancelable(false)
                .show()
        } else {

            setContentView(R.layout.activity_main)

            recyclerViewDeviceList.layoutManager = LinearLayoutManager(this)
            recyclerViewDeviceList.adapter = deviceListAdapter

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (hasPermissions(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.INTERNET
                        )
                    )
                ) {
                    reloadDevices()
                }
            } else {
                grantPermissionsAndReloadDevices()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun reloadDevices() {
        val allDevices = bluetoothAdapter.bondedDevices.toList()
        val bleDevices = allDevices.filter {
            it.type == BluetoothDevice.DEVICE_TYPE_LE || it.type == BluetoothDevice.DEVICE_TYPE_DUAL || it.type == BluetoothDevice.DEVICE_TYPE_CLASSIC
        }
        deviceListAdapter.updateList(ArrayList(bleDevices))
    }

    private fun grantPermissionsAndReloadDevices() {
        grantBluetoothBasicPermissions(AskType.AskOnce) { isGranted ->
            if (!isGranted) {
                Log.e("Permissions", "Bluetooth permissions denied")
                return@grantBluetoothBasicPermissions
            }
            reloadDevices()
        }
    }

    //Permissions management------------------------------------------------------------------------------------
    enum class AskType {
        AskOnce,
        InsistUntilSuccess
    }

    private var permissionResultHandlers = mutableMapOf<Int, (Array<out String>, IntArray) -> Unit>()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionResultHandlers[requestCode]?.let { handler ->
            handler(permissions, grantResults)
        } ?: run {
            Log.e("Permissions", "onRequestPermissionsResult requestCode=$requestCode not handled")
        }
    }

    private fun grantBluetoothBasicPermissions(askType: AskType, completion: (Boolean) -> Unit) {
        val wantedPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.INTERNET
            )
        } else {
            emptyArray()
        }

        if (wantedPermissions.isEmpty() || hasPermissions(wantedPermissions)) {
            completion(true)
        } else {
            runOnUiThread {
                val requestCode = BLUETOOTH_PERMISSIONS_REQUEST_CODE

                // set permission result handler
                permissionResultHandlers[requestCode] = { _, grantResults ->
                    val isSuccess = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                    if (isSuccess || askType != AskType.InsistUntilSuccess) {
                        permissionResultHandlers.remove(requestCode)
                        completion(isSuccess)
                    } else {
                        // request again
                        requestPermissionArray(wantedPermissions, requestCode)
                    }
                }

                requestPermissionArray(wantedPermissions, requestCode)
            }
        }
    }

    private fun Context.hasPermissions(permissions: Array<String>): Boolean = permissions.all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun Activity.requestPermissionArray(permissions: Array<String>, requestCode: Int) {
        ActivityCompat.requestPermissions(this, permissions, requestCode)
    }
    //----------------------------------------------------------------------------------------------------------
}