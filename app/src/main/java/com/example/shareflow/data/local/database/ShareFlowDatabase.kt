package com.example.shareflow.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.shareflow.data.local.database.dao.TransferHistoryDao
import com.example.shareflow.data.local.database.dao.TrustedDeviceDao
import com.example.shareflow.data.local.database.dao.VaultItemDao
import com.example.shareflow.data.local.database.entity.TransferHistoryEntity
import com.example.shareflow.data.local.database.entity.TrustedDeviceEntity
import com.example.shareflow.data.local.database.entity.VaultItemEntity

/**
 * ShareFlow offline-first Room database.
 * Destructive migrations are intentionally disallowed to protect user history and vault metadata.
 */
@Database(
    entities = [
        TransferHistoryEntity::class,
        TrustedDeviceEntity::class,
        VaultItemEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ShareFlowDatabase : RoomDatabase() {
    abstract fun transferHistoryDao(): TransferHistoryDao
    abstract fun trustedDeviceDao(): TrustedDeviceDao
    abstract fun vaultItemDao(): VaultItemDao

    companion object {
        const val DATABASE_NAME = "shareflow_local.db"

        fun create(context: Context): ShareFlowDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ShareFlowDatabase::class.java,
                DATABASE_NAME
            ).build()
    }
}
