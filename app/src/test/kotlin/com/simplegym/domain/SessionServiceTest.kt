package com.simplegym.domain

import com.google.common.truth.Truth.assertThat
import com.simplegym.data.CheckInEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.SessionEntity
import com.simplegym.data.SlotEntity
import com.simplegym.data.SlotStatus
import com.simplegym.engine.Chip
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

@RunWith(RobolectricTestRunner::class)
class SessionServiceTest {
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

    @Test
    fun startCreatesOnePendingSessionSlotPerSlotAndRecordsTheTotal() = runBlocking {
        val sessionId = service.start(chestDayId, today, clubId = null)

        val session = checkNotNull(db.sessionDao().getSession(sessionId))
        assertThat(session.dayId).isEqualTo(chestDayId)
        assertThat(session.date).isEqualTo(today)
        assertThat(session.startedAtMillis).isEqualTo(t0)
        assertThat(session.finishedAtMillis).isNull()
        assertThat(session.clubId).isNull()
        assertThat(session.slotsTotal).isEqualTo(5)

        val slotIds = db.planDao().getSlots(chestDayId).map { it.id }
        val states = db.sessionDao().getSessionSlots(sessionId)
        assertThat(states.map { it.slotId }).containsExactlyElementsIn(slotIds)
        assertThat(states.map { it.status }.toSet()).containsExactly(SlotStatus.PENDING)
        assertThat(states.none { it.flush }).isTrue()
    }

    @Test
    fun startWritesTodaysEmptyCheckInWhenThereIsNone() = runBlocking {
        assertThat(db.sessionDao().getCheckIn(today)).isNull()

        service.start(chestDayId, today, clubId = null)

        // no chip tapped: a real check-in of nothing stiff, so a loose day is not a missing row
        assertThat(checkNotNull(db.sessionDao().getCheckIn(today)).stiff).isEmpty()
    }

    @Test
    fun startKeepsAnExistingCheckInOfToday() = runBlocking<Unit> {
        db.sessionDao().upsertCheckIn(CheckInEntity(today, setOf(Chip.LEGS)))

        service.start(chestDayId, today, clubId = null)

        assertThat(checkNotNull(db.sessionDao().getCheckIn(today)).stiff).containsExactly(Chip.LEGS)
    }

    @Test
    fun startStoresTheClubAndIgnoresSessionOnlySlots() = runBlocking {
        db.planDao().insertSlot(
            SlotEntity(
                dayId = chestDayId, position = 99, defaultExerciseId = "push-ups",
                sets = 1, lo = 10, hi = 20, sessionOnlyFor = 42L,
            ),
        )

        val sessionId = service.start(chestDayId, today, clubId = 7L)

        val session = checkNotNull(db.sessionDao().getSession(sessionId))
        assertThat(session.clubId).isEqualTo(7L)
        assertThat(session.slotsTotal).isEqualTo(5)
        assertThat(db.sessionDao().getSessionSlots(sessionId)).hasSize(5)
    }

    @Test
    fun activeSessionIsReturnedAfterOneHourAndNullAfterThirteen() = runBlocking {
        val sessionId = service.start(chestDayId, today, clubId = null)

        assertThat(service.activeSession(t0 + hour)?.id).isEqualTo(sessionId)
        assertThat(service.activeSession(t0 + 13 * hour)).isNull()
    }

    @Test
    fun activeSessionIsNullOnceFinished() = runBlocking {
        val sessionId = service.start(chestDayId, today, clubId = null)
        now = t0 + hour
        service.finish(sessionId)

        assertThat(service.activeSession(t0 + hour)).isNull()
        assertThat(checkNotNull(db.sessionDao().getSession(sessionId)).finishedAtMillis).isEqualTo(t0 + hour)
    }

    @Test
    fun closeStaleFinishesOnlySessionsTwelveHoursOrOlder() = runBlocking {
        val stale = service.start(chestDayId, today, clubId = null)
        // Inserted directly: start() would finish the stale session itself, and this test is about closeStale.
        val fresh = db.sessionDao().insertSession(
            SessionEntity(dayId = chestDayId, date = today, startedAtMillis = t0 + 11 * hour, slotsTotal = 5),
        )

        service.closeStale(t0 + 12 * hour)

        assertThat(checkNotNull(db.sessionDao().getSession(stale)).finishedAtMillis).isNotNull()
        assertThat(checkNotNull(db.sessionDao().getSession(fresh)).finishedAtMillis).isNull()
    }

    @Test
    fun setStatusAndSetFlushUpdateOnlyThatSlot() = runBlocking {
        val sessionId = service.start(chestDayId, today, clubId = null)
        val (first, second) = db.planDao().getSlots(chestDayId).map { it.id }

        service.setStatus(sessionId, first, SlotStatus.DONE)
        service.setStatus(sessionId, second, SlotStatus.SKIPPED)
        service.setFlush(sessionId, second, true)

        val states = service.slotStates(sessionId).first().associateBy { it.slotId }
        assertThat(states.getValue(first).status).isEqualTo(SlotStatus.DONE)
        assertThat(states.getValue(first).flush).isFalse()
        assertThat(states.getValue(second).status).isEqualTo(SlotStatus.SKIPPED)
        assertThat(states.getValue(second).flush).isTrue()
        assertThat(states.values.count { it.status == SlotStatus.PENDING }).isEqualTo(3)
    }

    @Test
    fun finishTurnsLeftoverPendingSlotsIntoSkippedWithoutLogsAndKeepsDoneOnes() = runBlocking {
        val sessionId = service.start(chestDayId, today, clubId = null)
        val (first, second) = db.planDao().getSlots(chestDayId).map { it.id }
        service.setStatus(sessionId, first, SlotStatus.DONE)
        service.setStatus(sessionId, second, SlotStatus.SKIPPED)
        now = t0 + hour

        service.finish(sessionId)

        val states = db.sessionDao().getSessionSlots(sessionId).associateBy { it.slotId }
        assertThat(states.getValue(first).status).isEqualTo(SlotStatus.DONE)
        assertThat(states.values.count { it.status == SlotStatus.SKIPPED }).isEqualTo(4)
        assertThat(states.values.none { it.status == SlotStatus.PENDING }).isTrue()
        assertThat(checkNotNull(db.sessionDao().getSession(sessionId)).finishedAtMillis).isEqualTo(t0 + hour)
        assertThat(db.logDao().count()).isEqualTo(0)
    }

    @Test
    fun finishingAnAlreadyFinishedSessionChangesNothing() = runBlocking {
        val sessionId = service.start(chestDayId, today, clubId = null)
        now = t0 + hour
        service.finish(sessionId)
        now = t0 + 2 * hour

        service.finish(sessionId)

        assertThat(checkNotNull(db.sessionDao().getSession(sessionId)).finishedAtMillis).isEqualTo(t0 + hour)
    }

    @Test
    fun startFinishesAnotherOpenSessionFirstSkippingItsPendingSlots() = runBlocking<Unit> {
        val earlier = service.start(chestDayId, today, clubId = null)
        val doneSlot = db.planDao().getSlots(chestDayId).first().id
        service.setStatus(earlier, doneSlot, SlotStatus.DONE)
        now = t0 + hour

        val later = service.start(chestDayId, today, clubId = null)

        val earlierSession = checkNotNull(db.sessionDao().getSession(earlier))
        assertThat(earlierSession.finishedAtMillis).isEqualTo(t0 + hour)
        val earlierStates = db.sessionDao().getSessionSlots(earlier)
        assertThat(earlierStates.single { it.slotId == doneSlot }.status).isEqualTo(SlotStatus.DONE)
        assertThat(earlierStates.count { it.status == SlotStatus.SKIPPED }).isEqualTo(4)
        assertThat(checkNotNull(db.sessionDao().getSession(later)).finishedAtMillis).isNull()
        assertThat(db.sessionDao().getOpenSessions().map { it.id }).containsExactly(later)
        assertThat(db.sessionDao().getSessionSlots(later).map { it.status }.toSet())
            .containsExactly(SlotStatus.PENDING)
    }
}
