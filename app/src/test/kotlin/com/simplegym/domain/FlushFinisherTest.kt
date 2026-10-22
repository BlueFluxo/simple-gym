package com.simplegym.domain

import com.google.common.truth.Truth.assertThat
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SessionEntity
import com.simplegym.data.SettingsEntity
import com.simplegym.data.SlotStatus
import com.simplegym.engine.Chip
import com.simplegym.engine.Feel
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.testContainer
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The optional "+ Flush finisher" slot of a Workout (spec §6.7): created for one session and gone when it ends. */
@RunWith(RobolectricTestRunner::class)
class FlushFinisherTest {
    private val hour = 3_600_000L
    private val today = LocalDate.of(2026, 10, 22)
    private val t0 = 1_000_000_000L
    private var now = t0

    private lateinit var db: GymDatabase
    private lateinit var service: SessionService
    private var chestDayId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        testContainer(db).seed()
        chestDayId = db.planDao().getActiveDays().first().id
        service = SessionService(db) { now }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun log(
        sessionId: Long?,
        slotId: Long?,
        exerciseId: String,
        type: SessionType = SessionType.FLUSH,
    ) = db.logDao().insert(
        LogEntity(
            sessionId = sessionId, slotId = slotId, exerciseId = exerciseId, date = today, weightKg = 20.0,
            bestReps = 15, sets = 2, feel = Feel.SOLID, type = type,
        ),
    )

    private suspend fun exerciseOf(slotId: Long?) = checkNotNull(db.planDao().getSlot(checkNotNull(slotId))).defaultExerciseId

    private suspend fun library(chip: Chip) = db.libraryDao().getAll().filter { it.muscle in chip.muscles && !it.archived }

    @Test
    fun aFinisherIsASessionOnlySlotWithAFlushSessionSlotAndChangesNoCount() = runBlocking<Unit> {
        val sessionId = service.start(chestDayId, today, clubId = null)
        val planSlots = db.planDao().countSlots()

        val slotId = checkNotNull(service.addFlushFinisher(sessionId, Chip.LEGS))

        val slot = checkNotNull(db.planDao().getSlot(slotId))
        assertThat(slot.sessionOnlyFor).isEqualTo(sessionId)
        assertThat(slot.dayId).isEqualTo(chestDayId)
        assertThat(listOf(slot.sets, slot.lo, slot.hi, slot.roundSec)).containsExactly(2, 15, 20, 60).inOrder()
        val state = db.sessionDao().getSessionSlots(sessionId).single { it.slotId == slotId }
        assertThat(state.flush).isTrue()
        assertThat(state.status).isEqualTo(SlotStatus.PENDING)
        // nothing that counts the plan sees it
        assertThat(checkNotNull(db.sessionDao().getSession(sessionId)).slotsTotal).isEqualTo(5)
        assertThat(db.planDao().getSlots(chestDayId).map { it.id }).doesNotContain(slotId)
        assertThat(db.planDao().observeSlots(chestDayId).first().map { it.id }).doesNotContain(slotId)
        assertThat(db.planDao().countSlotsForDay(chestDayId)).isEqualTo(5)
        assertThat(db.planDao().countSlots()).isEqualTo(planSlots)
        assertThat(db.planDao().getSessionOnlySlots(sessionId).map { it.id }).containsExactly(slotId)
        assertThat(BlockService(db).current(today).doneThisRound).isEmpty()
    }

    @Test
    fun theFinisherIsTheChipsMostLoggedExerciseIgnoringOtherMuscles() = runBlocking<Unit> {
        repeat(3) { log(null, null, "leg-extensions", SessionType.NORMAL) }
        log(null, null, "leg-press", SessionType.NORMAL)
        repeat(5) { log(null, null, "bench-press", SessionType.NORMAL) }
        val sessionId = service.start(chestDayId, today, clubId = null)

        assertThat(exerciseOf(service.addFlushFinisher(sessionId, Chip.LEGS))).isEqualTo("leg-extensions")
    }

    @Test
    fun withNoHistoryTheFinisherIsTheFirstLibraryExerciseWithThatMuscle() = runBlocking<Unit> {
        val sessionId = service.start(chestDayId, today, clubId = null)

        assertThat(exerciseOf(service.addFlushFinisher(sessionId, Chip.LEGS))).isEqualTo(library(Chip.LEGS).first().id)
    }

    @Test
    fun anArchivedExerciseIsNeverTheFinisherHoweverOftenItWasLogged() = runBlocking<Unit> {
        val first = library(Chip.LEGS).first()
        repeat(3) { log(null, null, first.id, SessionType.NORMAL) }
        db.libraryDao().setArchived(first.id, true)
        val sessionId = service.start(chestDayId, today, clubId = null)

        val picked = exerciseOf(service.addFlushFinisher(sessionId, Chip.LEGS))

        assertThat(picked).isEqualTo(library(Chip.LEGS).first().id)
        assertThat(picked).isNotEqualTo(first.id)
    }

    @Test
    fun aHighBackLoadExerciseIsOnlyTheFinisherWhileLowerBackProtectionIsOff() = runBlocking<Unit> {
        repeat(3) { log(null, null, "barbell-bent-over-row", SessionType.NORMAL) }
        val unprotected = service.start(chestDayId, today, clubId = null)
        assertThat(exerciseOf(service.addFlushFinisher(unprotected, Chip.BACK))).isEqualTo("barbell-bent-over-row")

        db.settingsDao().upsert((db.settingsDao().get() ?: SettingsEntity()).copy(lowerBackProtection = true))
        val guarded = service.start(chestDayId, today, clubId = null)

        val picked = exerciseOf(service.addFlushFinisher(guarded, Chip.BACK))
        assertThat(picked).isNotEqualTo("barbell-bent-over-row")
        assertThat(library(Chip.BACK).first { it.id == picked }.backLoad.name).isNotEqualTo("HIGH")
    }

    @Test
    fun addingTheSameChipTwiceReturnsTheFirstFinisher() = runBlocking<Unit> {
        val sessionId = service.start(chestDayId, today, clubId = null)

        val first = service.addFlushFinisher(sessionId, Chip.LEGS)
        val again = service.addFlushFinisher(sessionId, Chip.LEGS)

        assertThat(again).isEqualTo(first)
        assertThat(db.planDao().getSessionOnlySlots(sessionId)).hasSize(1)
        assertThat(db.sessionDao().getSessionSlots(sessionId)).hasSize(6)
    }

    @Test
    fun aChipWithNoMuscleHasNoFinisher() = runBlocking<Unit> {
        val sessionId = service.start(chestDayId, today, clubId = null)

        assertThat(service.addFlushFinisher(sessionId, Chip.NECK)).isNull()
        assertThat(service.addFlushFinisher(sessionId, Chip.LOWER_BACK)).isNull()
        assertThat(db.planDao().getSessionOnlySlots(sessionId)).isEmpty()
    }

    @Test
    fun aFinishedSessionGetsNoFinisher() = runBlocking<Unit> {
        val sessionId = service.start(chestDayId, today, clubId = null)
        service.finish(sessionId)

        assertThat(service.addFlushFinisher(sessionId, Chip.LEGS)).isNull()
        assertThat(db.planDao().getSessionOnlySlots(sessionId)).isEmpty()
    }

    @Test
    fun finishingRemovesTheFinisherSlotAndItsSessionSlotButKeepsItsLogs() = runBlocking<Unit> {
        val sessionId = service.start(chestDayId, today, clubId = null)
        val slotId = checkNotNull(service.addFlushFinisher(sessionId, Chip.LEGS))
        service.setStatus(sessionId, slotId, SlotStatus.DONE)
        val logId = log(sessionId, slotId, "leg-extensions")

        service.finish(sessionId)

        assertThat(db.planDao().getSlot(slotId)).isNull()
        assertThat(db.planDao().getSessionOnlySlots(sessionId)).isEmpty()
        assertThat(db.sessionDao().getSessionSlots(sessionId).map { it.slotId }).doesNotContain(slotId)
        assertThat(db.sessionDao().getSessionSlots(sessionId)).hasSize(5)
        val kept = checkNotNull(db.logDao().get(logId))
        assertThat(kept.slotId).isNull()
        assertThat(kept.exerciseId).isEqualTo("leg-extensions")
    }

    @Test
    fun aFinisherLogDoesNotMakeAHalfDoneDayCountOnceTheSessionIsFinished() = runBlocking<Unit> {
        val sessionId = service.start(chestDayId, today, clubId = null)
        db.planDao().getSlots(chestDayId).take(2).forEach { log(sessionId, it.id, it.defaultExerciseId, SessionType.NORMAL) }
        val slotId = checkNotNull(service.addFlushFinisher(sessionId, Chip.LEGS))
        service.setStatus(sessionId, slotId, SlotStatus.DONE)
        log(sessionId, slotId, "leg-extensions")

        service.finish(sessionId)

        // 2 of the 5 plan slots. With the finisher's orphaned log counted it would read 3 of 5 and the round would jump.
        assertThat(BlockService(db).current(today).doneThisRound).isEmpty()
    }

    @Test
    fun aFinisherLoggedWithFlushSwitchedOffStillDoesNotCountOnceTheSessionIsFinished() = runBlocking<Unit> {
        val sessionId = service.start(chestDayId, today, clubId = null)
        db.planDao().getSlots(chestDayId).take(2).forEach { log(sessionId, it.id, it.defaultExerciseId, SessionType.NORMAL) }
        val slotId = checkNotNull(service.addFlushFinisher(sessionId, Chip.LEGS))
        service.setFlush(sessionId, slotId, false)
        service.setStatus(sessionId, slotId, SlotStatus.DONE)
        // the log carries the target's own type, as LogService writes it with Flush off
        val logId = log(sessionId, slotId, "leg-extensions", SessionType.NORMAL)

        service.finish(sessionId)

        assertThat(db.planDao().getSlot(slotId)).isNull()
        val kept = checkNotNull(db.logDao().get(logId))
        assertThat(kept.slotId).isNull()
        assertThat(kept.type).isEqualTo(SessionType.FLUSH)
        // 2 of the 5 plan slots: not half, so the day is not done (counting the finisher would make 3 and jump the round)
        assertThat(BlockService(db).current(today).doneThisRound).isEmpty()
    }

    @Test
    fun aNormalTypedFinisherLogDoesNotCountWhenAStaleSessionIsClosedEither() = runBlocking<Unit> {
        val sessionId = service.start(chestDayId, today, clubId = null)
        db.planDao().getSlots(chestDayId).take(2).forEach { log(sessionId, it.id, it.defaultExerciseId, SessionType.NORMAL) }
        val slotId = checkNotNull(service.addFlushFinisher(sessionId, Chip.LEGS))
        log(sessionId, slotId, "leg-extensions", SessionType.NORMAL)
        now = t0 + 13 * hour

        service.closeStale(now)

        assertThat(BlockService(db).current(today).doneThisRound).isEmpty()
    }

    @Test
    fun aNormalTypedFinisherLogNeverMakesASessionTrained() = runBlocking<Unit> {
        val sessionId = service.start(chestDayId, today, clubId = null)
        val slotId = checkNotNull(service.addFlushFinisher(sessionId, Chip.LEGS))
        log(sessionId, slotId, "leg-extensions", SessionType.NORMAL)
        service.finish(sessionId)

        assertThat(BlockService(db).current(today).doneThisRound).isEmpty()
        assertThat(db.logDao().getTrainedSessionIds()).doesNotContain(sessionId)
    }

    @Test
    fun aFinisherDoesNotLetAFlushOnlySessionCountEither() = runBlocking<Unit> {
        val sessionId = service.start(chestDayId, today, clubId = null)
        val slotId = checkNotNull(service.addFlushFinisher(sessionId, Chip.LEGS))
        service.setStatus(sessionId, slotId, SlotStatus.DONE)
        log(sessionId, slotId, "leg-extensions")
        service.finish(sessionId)

        assertThat(BlockService(db).current(today).doneThisRound).isEmpty()
    }

    @Test
    fun closingAStaleSessionRemovesItsFinisherToo() = runBlocking<Unit> {
        val sessionId = service.start(chestDayId, today, clubId = null)
        val slotId = checkNotNull(service.addFlushFinisher(sessionId, Chip.LEGS))
        val logId = log(sessionId, slotId, "leg-extensions")
        now = t0 + 13 * hour

        service.closeStale(now)

        assertThat(checkNotNull(db.sessionDao().getSession(sessionId)).finishedAtMillis).isNotNull()
        assertThat(db.planDao().getSlot(slotId)).isNull()
        assertThat(db.sessionDao().getSessionSlots(sessionId).map { it.slotId }).doesNotContain(slotId)
        assertThat(checkNotNull(db.logDao().get(logId)).slotId).isNull()
    }

    @Test
    fun startingANewSessionFinishesTheOpenOnesFinisherToo() = runBlocking<Unit> {
        val earlier = service.start(chestDayId, today, clubId = null)
        val slotId = checkNotNull(service.addFlushFinisher(earlier, Chip.LEGS))

        service.start(chestDayId, today, clubId = null)

        assertThat(db.planDao().getSlot(slotId)).isNull()
    }

    @Test
    fun finishingOneSessionLeavesAnotherSessionsFinisherAlone() = runBlocking<Unit> {
        val open = service.start(chestDayId, today, clubId = null)
        val slotId = checkNotNull(service.addFlushFinisher(open, Chip.LEGS))
        val other = db.sessionDao().insertSession(
            SessionEntity(dayId = chestDayId, date = today, startedAtMillis = t0, slotsTotal = 5),
        )

        service.finish(other)

        assertThat(db.planDao().getSlot(slotId)).isNotNull()
    }
}
