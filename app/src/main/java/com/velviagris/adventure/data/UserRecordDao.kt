package com.velviagris.adventure.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserRecordDao {
    @Query("SELECT * FROM user_records WHERE id = 1")
    suspend fun getRecord(): UserRecord?

    @Query("SELECT * FROM user_records WHERE id = 1")
    fun getRecordFlow(): Flow<UserRecord?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: UserRecord)
}