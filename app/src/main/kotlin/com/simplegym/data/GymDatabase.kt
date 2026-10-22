package com.simplegym.data

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.ColumnTypeConverters

@Database(
    entities = [
        ExerciseEntity::class,
        DayEntity::class,
        SlotEntity::class,
        SlotSwapEntity::class,
        SessionEntity::class,
        SessionSlotEntity::class,
        LogEntity::class,
        CheckInEntity::class,
        ClubEntity::class,
        ClubUnavailableEntity::class,
        MobilityDoneEntity::class,
        SettingsEntity::class,
    ],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
    exportSchema = true,
)
@ColumnTypeConverters(Converters::class)
abstract class GymDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun planDao(): PlanDao
    abstract fun sessionDao(): SessionDao
    abstract fun logDao(): LogDao
    abstract fun settingsDao(): SettingsDao
    abstract fun clubDao(): ClubDao
    abstract fun backupDao(): BackupDao
}
