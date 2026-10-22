package com.simplegym.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.engine.BackLoad
import com.simplegym.engine.Chip
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.Muscle
import com.simplegym.engine.RangeType
import com.simplegym.engine.SessionType
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeederTest {
    private lateinit var context: Context
    private lateinit var db: GymDatabase
    private lateinit var seeder: Seeder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        seeder = Seeder(db, context.assets)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun seedInserts142ExercisesThreeDaysTwentySlotsAndBenchSwapsInSpecOrder() = runBlocking {
        seeder.seedIfEmpty()

        assertThat(db.libraryDao().count()).isEqualTo(142)
        val days = db.planDao().observeActiveDays().first()
        assertThat(days.map { it.name }).containsExactly("Chest / Triceps", "Back / Biceps", "Legs / Calves").inOrder()
        assertThat(days.map { it.position }).containsExactly(0, 1, 2).inOrder()
        assertThat(days[0].chips).containsExactly(Chip.CHEST, Chip.TRICEPS, Chip.SHOULDERS)
        assertThat(db.planDao().countSlots()).isEqualTo(20)

        val benchSlot = db.planDao().observeSlots(days[0].id).first().first()
        assertThat(benchSlot.defaultExerciseId).isEqualTo("bench-press")
        assertThat(listOf(benchSlot.sets, benchSlot.lo, benchSlot.hi, benchSlot.roundSec))
            .containsExactly(3, 8, 12, 120).inOrder()
        assertThat(benchSlot.sessionOnlyFor).isNull()
        assertThat(db.planDao().getSwaps(benchSlot.id).map { it.exerciseId }).containsExactly(
            "chest-press-machine",
            "dumbbell-bench-press",
            "machine-press-plate-loaded",
            "push-ups",
        ).inOrder()
    }

    @Test
    fun seededExerciseCarriesEngineEnumsAndFlags() = runBlocking {
        seeder.seedIfEmpty()

        val bench = checkNotNull(db.libraryDao().get("bench-press"))
        assertThat(bench.muscle).isEqualTo(Muscle.CHEST)
        assertThat(bench.backLoad).isEqualTo(BackLoad.LOW)
        assertThat(bench.incrementKg).isEqualTo(2.5)
        assertThat(bench.incrementOverrideKg).isNull()
        assertThat(bench.joints).containsAtLeast(Joint.SHOULDER, Joint.ELBOW)
        assertThat(bench.isCustom).isFalse()
        assertThat(bench.archived).isFalse()
        assertThat(checkNotNull(db.libraryDao().get("push-ups")).isBodyweight).isTrue()
        assertThat(checkNotNull(db.libraryDao().get("assisted-pull-up")).isAssisted).isTrue()
    }

    @Test
    fun seedingTwiceLeavesCountsUnchanged() = runBlocking {
        seeder.seedIfEmpty()
        val swapsAfterFirst = db.planDao().countSwaps()
        seeder.seedIfEmpty()

        assertThat(db.libraryDao().count()).isEqualTo(142)
        assertThat(db.planDao().observeActiveDays().first()).hasSize(3)
        assertThat(db.planDao().countSlots()).isEqualTo(20)
        assertThat(swapsAfterFirst).isGreaterThan(0)
        assertThat(db.planDao().countSwaps()).isEqualTo(swapsAfterFirst)
    }

    @Test
    fun seedingDoesNotRestoreADeletedPlanOrOverwriteSettings() = runBlocking {
        seeder.seedIfEmpty()
        db.settingsDao().upsert(checkNotNull(db.settingsDao().get()).copy(onboarded = true))
        db.planDao().observeActiveDays().first().forEach { db.planDao().deleteDay(it.id) }

        seeder.seedIfEmpty()

        assertThat(db.planDao().observeActiveDays().first()).isEmpty()
        assertThat(checkNotNull(db.settingsDao().get()).onboarded).isTrue()
    }

    @Test
    fun settingsDefaults() = runBlocking {
        seeder.seedIfEmpty()

        val settings = checkNotNull(db.settingsDao().get())
        assertThat(settings.id).isEqualTo(0)
        assertThat(settings.age).isNull()
        assertThat(settings.lowerBackProtection).isFalse()
        assertThat(settings.currentClubId).isNull()
        assertThat(settings.equipmentIncrements).isEmpty()
        assertThat(settings.onboarded).isFalse()
        assertThat(settings.batteryCardDismissed).isFalse()
        assertThat(settings.timerWasKilled).isFalse()
        assertThat(db.settingsDao().observe().first()).isEqualTo(settings)
    }

    @Test
    fun settingsRoundTripTheEquipmentIncrementMap() = runBlocking {
        seeder.seedIfEmpty()
        val updated = checkNotNull(db.settingsDao().get())
            .copy(age = 40, equipmentIncrements = mapOf("Dumbbell" to 2.0, "Cable" to 2.5))
        db.settingsDao().upsert(updated)

        assertThat(db.settingsDao().get()).isEqualTo(updated)
    }

    @Test
    fun deletingASlotKeepsItsLogsWithANullSlotId() = runBlocking {
        seeder.seedIfEmpty()
        val day = db.planDao().observeActiveDays().first().first()
        val slot = db.planDao().observeSlots(day.id).first().first()
        val logId = db.logDao().insert(
            LogEntity(
                sessionId = null,
                slotId = slot.id,
                exerciseId = "bench-press",
                date = LocalDate.of(2026, 10, 20),
                weightKg = 60.0,
                bestReps = 10,
                sets = 3,
                feel = Feel.SOLID,
                type = SessionType.NORMAL,
                rangeType = RangeType.BASE,
                hurt = null,
                note = null,
                imported = false,
            ),
        )

        db.planDao().deleteSlot(slot.id)

        val log = checkNotNull(db.logDao().get(logId))
        assertThat(log.slotId).isNull()
        assertThat(log.weightKg).isEqualTo(60.0)
        assertThat(db.planDao().getSwaps(slot.id)).isEmpty()
    }

    @Test
    fun sessionOnlySlotsAreExcludedFromPlanListingsAndCounts() = runBlocking {
        seeder.seedIfEmpty()
        val day = db.planDao().observeActiveDays().first().first()
        db.planDao().insertSlot(
            SlotEntity(
                dayId = day.id, position = 99, defaultExerciseId = "push-ups",
                sets = 1, lo = 10, hi = 20, roundSec = 60, sessionOnlyFor = 7L,
            ),
        )

        assertThat(db.planDao().countSlots()).isEqualTo(20)
        assertThat(db.planDao().countSlotsForDay(day.id)).isEqualTo(5)
        assertThat(db.planDao().observeSlots(day.id).first()).hasSize(5)
        assertThat(db.planDao().getSlots(day.id)).hasSize(5)
        assertThat(db.planDao().getSessionOnlySlots(7L)).hasSize(1)
    }

    @Test
    fun logEntityMapsToTheEngineRecord() {
        val entity = LogEntity(
            id = 3, sessionId = 1, slotId = 2, exerciseId = "bench-press",
            date = LocalDate.of(2026, 10, 20), weightKg = 62.5, bestReps = 9, sets = 3,
            feel = Feel.GRINDER, type = SessionType.DELOAD, rangeType = RangeType.HIGH_REP,
            hurt = Joint.KNEE, note = "n", imported = true,
        )

        val record = entity.toRecord()

        assertThat(record.exerciseId).isEqualTo("bench-press")
        assertThat(record.date).isEqualTo(LocalDate.of(2026, 10, 20))
        assertThat(record.weightKg).isEqualTo(62.5)
        assertThat(record.bestReps).isEqualTo(9)
        assertThat(record.sets).isEqualTo(3)
        assertThat(record.feel).isEqualTo(Feel.GRINDER)
        assertThat(record.type).isEqualTo(SessionType.DELOAD)
        assertThat(record.rangeType).isEqualTo(RangeType.HIGH_REP)
        assertThat(record.hurt).isEqualTo(Joint.KNEE)
    }

    @Test
    fun checkInStiffSetRoundTripsIncludingEmpty() = runBlocking {
        db.sessionDao().upsertCheckIn(CheckInEntity(LocalDate.of(2026, 10, 21), setOf(Chip.BACK, Chip.LEGS)))
        db.sessionDao().upsertCheckIn(CheckInEntity(LocalDate.of(2026, 10, 22), emptySet()))

        assertThat(checkNotNull(db.sessionDao().getCheckIn(LocalDate.of(2026, 10, 21))).stiff)
            .containsExactly(Chip.BACK, Chip.LEGS)
        assertThat(checkNotNull(db.sessionDao().getCheckIn(LocalDate.of(2026, 10, 22))).stiff).isEmpty()
    }
}
