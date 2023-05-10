package com.example.blephonecentral

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.*

object LogManager{


    private var activityRef: WeakReference<AppCompatActivity>? = null
    private val textViewLog: TextView?
        get() = activityRef?.get()?.findViewById(R.id.textViewLog)
    private val scrollViewLog: ScrollView?
        get() = activityRef?.get()?.findViewById(R.id.scrollViewLog)

    fun setActivity(activity: AppCompatActivity) {
        activityRef = WeakReference(activity)
    }



    @SuppressLint("SetTextI18n")
    fun appendLog(message: String) {
        Log.d("appendLog", message)

        activityRef?.get()?.runOnUiThread {
            textViewLog?.text = textViewLog?.text.toString() + "\n$message"

            // wait for the textView to update
            Handler(Looper.getMainLooper()).postDelayed({
                scrollViewLog?.fullScroll(View.FOCUS_DOWN)
            }, 20)
        }


    }


    @SuppressLint("SetTextI18n")
    fun clearLog() {
        textViewLog?.text = "Logs:"
        appendLog("log cleared")
    }

    fun getCurrentTime(): String{
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }
}