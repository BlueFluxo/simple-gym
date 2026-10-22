package com.simplegym

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.simplegym.data.GymDatabase
import com.simplegym.data.Seeder
import com.simplegym.domain.BlockService
import com.simplegym.domain.CheckInService
import com.simplegym.domain.ClubService
import com.simplegym.domain.LogService
import com.simplegym.domain.MobilityRecorder
import com.simplegym.domain.PlanEditor
import com.simplegym.domain.RotationService
import com.simplegym.domain.SessionService
import com.simplegym.domain.SettingsService
import com.simplegym.domain.TargetService
import com.simplegym.io.BackupService
import com.simplegym.io.StartingDataImporter
import com.simplegym.timer.TimerRunStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hand-rolled dependency holder; later tasks add their services here. Tests pass an in-memory [db] and a fixed
 * [clock] so nothing touches the on-disk simple-gym.db or the wall clock. Open so a test can observe the one call it
 * must not miss, [recordTimerKilled].
 */
open class AppContainer(
    context: Context,
    val db: GymDatabase = Room.databaseBuilder(context, GymDatabase::class.java, "simple-gym.db")
        .setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build(),
    val clock: () -> Long = System::currentTimeMillis,
) {
    val appContext: Context = context.applicationContext
    val timerStore: TimerRunStore = TimerRunStore.from(appContext)
    val seeder = Seeder(db, context.assets)
    val sessionService = SessionService(db, clock)
    val blockService = BlockService(db)
    val checkInService = CheckInService(db)
    val targetService = TargetService(db)
    val rotationService = RotationService(db)
    val logService = LogService(db, sessionService)
    val mobilityRecorder = MobilityRecorder(db, ::today)
    val startingDataImporter = StartingDataImporter(db)
    val backupService = BackupService(db, ::today)
    val planEditor = PlanEditor(db)
    val clubService = ClubService(db)
    val settingsService = SettingsService(db)

    /**
     * Exercise ids a starting-data import skipped because the library does not know them. Set by onboarding, shown as a
     * banner on Home until dismissed (the onboarding screen is gone by then, so it cannot hold the report).
     */
    val skippedImportIds = MutableStateFlow<List<String>>(emptyList())

    private val seededSignal = CompletableDeferred<Unit>()

    /**
     * Completes once [seed] has finished. One-shot reads of seeded data (settings, plan) must await it;
     * screens should prefer observing Flows, which fill in when seeding lands.
     */
    val seeded: Deferred<Unit> get() = seededSignal

    suspend fun seed() {
        try {
            seeder.seedIfEmpty()
            seededSignal.complete(Unit)
        } catch (e: Throwable) {
            seededSignal.completeExceptionally(e)
            throw e
        }
    }

    /** Brings the battery-settings card back: a timer was killed early. */
    open suspend fun recordTimerKilled() {
        seeded.await()
        db.settingsDao().markTimerKilled()
    }

    fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate()
}
