package com.example.room

import androidx.room.*
import com.example.room.Reading

@Dao
interface ReadingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: Reading)

    @Query("SELECT * FROM reading ORDER BY id ASC")
    suspend fun getAll(): List<Reading>

    @Query("SELECT * FROM reading WHERE time = :name")
    suspend fun findByName(name: String): Reading

    @Update
    suspend fun update(reading: Reading)

    @Delete
    suspend fun delete(reading: Reading)
}