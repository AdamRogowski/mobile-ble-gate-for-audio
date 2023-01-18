package com.example.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Reading::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao
}