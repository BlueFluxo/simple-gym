package com.simplegym.domain

import com.google.common.truth.Truth.assertThat
import com.simplegym.data.DayEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SettingsEntity
import com.simplegym.data.SlotEntity
import com.simplegym.data.SlotStatus
import com.simplegym.engine.Feel
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.testContainer
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BlockServiceTest {
    private val today = LocalDate.of(2026, 10, 22)

    private lateinit var db: GymDatabase
    private lateinit var sessions: SessionService
    private lateinit var service: BlockService
    private lateinit var dayIds: List<Long>

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        testContainer(db).seed()
        sessions = SessionService(db) { 1_000_000_000L }
        service = BlockService(db)
        dayIds = db.planDao().getActiveDays().map { it.id }
    }

    @After
    fun tearDown() = db.close()

    /** Starts [dayId], logs its first [logged] plan slots with [type], and finishes the session. Returns its id. */
    private suspend fun session(
        dayId: Long,
        logged: Int,
        type: SessionType = SessionType.NORMAL,
        finish: Boolean = true,
        date: LocalDate = today,
    ): Long {
        val id = sessions.start(dayId, date, clubId = null)
        db.planDao().getSlots(dayId).take(logged).forEach { log(id, it.id, type) }
        if (finish) sessions.finish(id)
        return id
    }

    private suspend fun log(sessionId: Long, slotId: Long?, type: SessionType, exerciseId: String = "bench-press") {
        db.logDao().insert(
            LogEntity(
                sessionId = sessionId, slotId = slotId, exerciseId = exerciseId, date = today, weightKg = 60.0,
                bestReps = 8, sets = 3, feel = Feel.SOLID, type = type,
            ),
        )
    }

    @Test
    fun withNoSessionsItIsBlockOneRoundOneOfFourWithoutAnAge() = runBlocking<Unit> {
        val state = service.current(today)

        assertThat(state.blockIndex).isEqualTo(0)
        assertThat(state.roundInBlock).isEqualTo(1)
        assertThat(state.roundsPerBlock).isEqualTo(4) // age 45: 3 build rounds + deload
        assertThat(state.doneThisRound).isEmpty()
    }

    @Test
    fun theAgeInSettingsSetsTheBuildRounds() = runBlocking<Unit> {
        db.settingsDao().upsert((db.settingsDao().get() ?: SettingsEntity()).copy(age = 30))
        assertThat(service.current(today).roundsPerBlock).isEqualTo(6)

        db.settingsDao().upsert((db.settingsDao().get() ?: SettingsEntity()).copy(age = 60))
        assertThat(service.current(today).roundsPerBlock).isEqualTo(3)
    }

    @Test
    fun aSessionWithHalfItsSlotsLoggedCompletesItsDay() = runBlocking<Unit> {
        session(dayIds[0], logged = 3)

        assertThat(service.current(today).doneThisRound).containsExactly(dayIds[0])
    }

    @Test
    fun aSessionUnderHalfDoesNotComplete() = runBlocking<Unit> {
        session(dayIds[0], logged = 2)

        assertThat(service.current(today).doneThisRound).isEmpty()
    }

    @Test
    fun anOpenSessionIsNotCountedUntilItIsFinished() = runBlocking<Unit> {
        val id = session(dayIds[0], logged = 5, finish = false)
        assertThat(service.current(today).doneThisRound).isEmpty()

        sessions.finish(id)
        assertThat(service.current(today).doneThisRound).containsExactly(dayIds[0])
    }

    @Test
    fun aSessionOfOnlyFlushLogsDoesNotCount() = runBlocking<Unit> {
        session(dayIds[0], logged = 5, type = SessionType.FLUSH)

        assertThat(service.current(today).doneThisRound).isEmpty()
    }

    @Test
    fun aFlushLogOnAPlanSlotDoesNotCountTowardsTheHalfSlotsRule() = runBlocking<Unit> {
        val id = sessions.start(dayIds[0], today, clubId = null)
        val slots = db.planDao().getSlots(dayIds[0])
        log(id, slots[0].id, SessionType.FLUSH)
        log(id, slots[1].id, SessionType.NORMAL)
        log(id, slots[2].id, SessionType.NORMAL)
        sessions.finish(id)

        // 2 real slots of 5: the flush log must not make it 3 of 5
        assertThat(service.current(today).doneThisRound).isEmpty()
    }

    @Test
    fun oneNormalSlotAndThreeFlushSlotsIsNotDone() = runBlocking<Unit> {
        val id = sessions.start(dayIds[0], today, clubId = null)
        val slots = db.planDao().getSlots(dayIds[0])
        log(id, slots[0].id, SessionType.NORMAL)
        slots.subList(1, 4).forEach { log(id, it.id, SessionType.FLUSH) }
        sessions.finish(id)

        assertThat(service.current(today).doneThisRound).isEmpty()
    }

    @Test
    fun aSlotWithBothAFlushAndANormalLogCountsOnce() = runBlocking<Unit> {
        val id = sessions.start(dayIds[0], today, clubId = null)
        val slots = db.planDao().getSlots(dayIds[0])
        slots.take(3).forEach { log(id, it.id, SessionType.NORMAL) }
        log(id, slots[0].id, SessionType.FLUSH)
        sessions.finish(id)

        assertThat(service.current(today).doneThisRound).containsExactly(dayIds[0])
    }

    @Test
    fun logsOfAFinisherSlotNeverCountTowardsTheRound() = runBlocking<Unit> {
        val id = sessions.start(dayIds[0], today, clubId = null)
        val slots = db.planDao().getSlots(dayIds[0])
        val finisher = db.planDao().insertSlot(
            SlotEntity(
                dayId = dayIds[0], position = 99, defaultExerciseId = "push-ups",
                sets = 1, lo = 10, hi = 20, sessionOnlyFor = id,
            ),
        )
        log(id, slots[0].id, SessionType.NORMAL)
        log(id, slots[1].id, SessionType.NORMAL)
        log(id, finisher, SessionType.FLUSH)
        sessions.finish(id)

        // 2 of 5 plan slots: the finisher log would have made it 3 of 5.
        assertThat(service.current(today).doneThisRound).isEmpty()
    }

    @Test
    fun aSlotLoggedTwiceCountsOnce() = runBlocking<Unit> {
        val id = sessions.start(dayIds[0], today, clubId = null)
        val slot = db.planDao().getSlots(dayIds[0]).first()
        repeat(3) { log(id, slot.id, SessionType.NORMAL) }
        sessions.finish(id)

        assertThat(service.current(today).doneThisRound).isEmpty() // 1 of 5
    }

    @Test
    fun aSessionStartedBeforeASlotWasDeletedKeepsItsStoredSlotCount() = runBlocking<Unit> {
        val id = session(dayIds[0], logged = 2) // 2 of the 5 slots it started with
        val slots = db.planDao().getSlots(dayIds[0])
        slots.takeLast(2).forEach { db.planDao().deleteSlot(it.id) } // plan now has 3: 2 of 3 would be done

        assertThat(checkNotNull(db.sessionDao().getSession(id)).slotsTotal).isEqualTo(5)
        assertThat(service.current(today).doneThisRound).isEmpty()
    }

    @Test
    fun logsOfASlotDeletedAfterwardsStillCount() = runBlocking<Unit> {
        session(dayIds[0], logged = 3)
        val slots = db.planDao().getSlots(dayIds[0])
        db.planDao().deleteSlot(slots[0].id) // its log keeps living with a null slotId

        assertThat(service.current(today).doneThisRound).containsExactly(dayIds[0])
    }

    @Test
    fun deletingTheOnlyUndoneDayCompletesTheRound() = runBlocking<Unit> {
        session(dayIds[0], logged = 5)
        session(dayIds[1], logged = 5)
        assertThat(service.current(today).roundInBlock).isEqualTo(1)

        db.planDao().deleteDay(dayIds[2])

        val state = service.current(today)
        assertThat(state.roundInBlock).isEqualTo(2)
        assertThat(state.doneThisRound).isEmpty()
    }

    @Test
    fun anArchivedDayIsNotNeededForTheRound() = runBlocking<Unit> {
        session(dayIds[0], logged = 5)
        session(dayIds[1], logged = 5)
        val third = checkNotNull(db.planDao().getDay(dayIds[2]))
        db.planDao().updateDay(third.copy(archived = true))

        assertThat(service.current(today).roundInBlock).isEqualTo(2)
    }

    @Test
    fun aNewDayAddedMidRoundIsNeededBeforeTheRoundCompletes() = runBlocking<Unit> {
        session(dayIds[0], logged = 5)
        session(dayIds[1], logged = 5)

        val extra = db.planDao().insertDay(DayEntity(name = "Extra", position = 9, chips = emptySet()))
        db.planDao().insertSlot(
            SlotEntity(dayId = extra, position = 0, defaultExerciseId = "push-ups", sets = 3, lo = 8, hi = 12),
        )
        session(dayIds[2], logged = 5)
        val open = service.current(today)
        assertThat(open.roundInBlock).isEqualTo(1)
        assertThat(open.doneThisRound).containsExactly(dayIds[0], dayIds[1], dayIds[2])

        session(extra, logged = 1)
        assertThat(service.current(today).roundInBlock).isEqualTo(2)
    }

    @Test
    fun startingASessionStoresTheActiveDays() = runBlocking<Unit> {
        val third = checkNotNull(db.planDao().getDay(dayIds[2]))
        db.planDao().updateDay(third.copy(archived = true))

        val id = sessions.start(dayIds[0], today, clubId = null)

        assertThat(checkNotNull(db.sessionDao().getSession(id)).activeDayIdsAtStart)
            .containsExactly(dayIds[0], dayIds[1])
    }

    @Test
    fun aDayAddedAfterTheRoundWasPlayedDoesNotReopenIt() = runBlocking<Unit> {
        dayIds.forEach { session(it, logged = 5) }
        assertThat(service.current(today).roundInBlock).isEqualTo(2)

        val extra = db.planDao().insertDay(DayEntity(name = "Extra", position = 9, chips = emptySet()))
        db.planDao().insertSlot(
            SlotEntity(dayId = extra, position = 0, defaultExerciseId = "push-ups", sets = 3, lo = 8, hi = 12),
        )

        val state = service.current(today)
        assertThat(state.roundInBlock).isEqualTo(2)
        assertThat(state.doneThisRound).isEmpty()
    }

    @Test
    fun sessionsStartedBeforeSchemaTwoFallBackToTheCurrentDays() = runBlocking<Unit> {
        val ids = listOf(dayIds[0], dayIds[1], dayIds[0], dayIds[1]).map { session(it, logged = 5) }
        ids.forEach { db.sessionDao().updateSession(checkNotNull(db.sessionDao().getSession(it)).copy(activeDayIdsAtStart = null)) }
        db.planDao().deleteDay(dayIds[2])

        // Judged against the two days left: two rounds played. Read through three-day snapshots it would be round 2.
        assertThat(service.current(today).roundInBlock).isEqualTo(3)
    }

    @Test
    fun twoDeletedSlotsHoldingTheSameExerciseCountAsTwo() = runBlocking<Unit> {
        val id = sessions.start(dayIds[0], today, clubId = null)
        val slots = db.planDao().getSlots(dayIds[0])
        listOf("bench-press", "bench-press", "push-ups").forEachIndexed { i, exercise ->
            log(id, slots[i].id, SessionType.NORMAL, exercise)
            sessions.setStatus(id, slots[i].id, SlotStatus.DONE)
        }
        sessions.finish(id)
        slots.take(3).forEach { db.planDao().deleteSlot(it.id) }

        // 3 of the 5 slots it started with. Told apart by exercise alone, the two bench slots would read as one: 2 of 5.
        assertThat(service.current(today).doneThisRound).containsExactly(dayIds[0])
    }

    @Test
    fun aDeletedSlotLoggedSeveralTimesStillCountsOnce() = runBlocking<Unit> {
        val id = sessions.start(dayIds[0], today, clubId = null)
        val slot = db.planDao().getSlots(dayIds[0]).first()
        repeat(3) { log(id, slot.id, SessionType.NORMAL) }
        sessions.setStatus(id, slot.id, SlotStatus.DONE)
        sessions.finish(id)
        db.planDao().deleteSlot(slot.id)

        assertThat(service.current(today).doneThisRound).isEmpty() // 1 of 5
    }
}
