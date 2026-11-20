package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.CommissionLogEntity
import com.example.data.model.FarmBoundaryEntity
import com.example.data.model.FarmerProfileEntity
import com.example.data.model.OrderEntity
import com.example.data.model.PilotBankAccountEntity
import com.example.data.model.PilotProfileEntity
import com.example.data.model.PilotWithdrawalEntity
import com.example.data.model.RejectedOrderEntity
import com.example.data.model.UserEntity

@Database(
    entities = [
        UserEntity::class,
        PilotProfileEntity::class,
        FarmerProfileEntity::class,
        OrderEntity::class,
        CommissionLogEntity::class,
        RejectedOrderEntity::class,
        FarmBoundaryEntity::class,
        PilotBankAccountEntity::class,
        PilotWithdrawalEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class KrishivantriDatabase : RoomDatabase() {
    abstract fun dao(): KrishivantriDao

    companion object {
        @Volatile
        private var INSTANCE: KrishivantriDatabase? = null

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN email TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): KrishivantriDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KrishivantriDatabase::class.java,
                    "krishivantri_db"
                )
                    .addMigrations(MIGRATION_5_6)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
