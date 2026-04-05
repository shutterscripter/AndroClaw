package com.androclaw.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class AndroClawDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    companion object {
        fun create(context: Context): AndroClawDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AndroClawDatabase::class.java,
                "androclaw_db"
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
