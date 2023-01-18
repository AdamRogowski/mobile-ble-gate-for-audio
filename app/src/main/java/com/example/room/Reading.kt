package com.example.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading")
data class Reading(
    @ColumnInfo(name = "time") var time: String,
    @ColumnInfo(name = "value") var value: Int,
    @PrimaryKey(autoGenerate = true) var id: Int = 0
)