package com.simplegym.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.simplegym.data.GymDatabase
import com.simplegym.data.SessionSlotEntity
import com.simplegym.data.SlotStatus
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.RangeType
import com.simplegym.engine.RepRange
import com.simplegym.engine.SessionType
import com.simplegym.engine.Target
import com.simplegym.inMemoryDb
import com.simplegym.testContainer
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogServiceTest {
    private lateinit var db: GymDatabase
    private lateinit var sessions: SessionService
    private lateinit var service: LogService
    private val today = LocalDate.of(2026, 10, 22)
    private var sessionId = 0L
    private var slotId = 0L

    private val ok = LogValidation.Ok(weightKg = 62.5, reps = 12, sets = 3)

    private fun input(note: String? = null, hurt: Joint? = null) =
        LogInput("62.5", "12", "3", Feel.GRINDER, hurt, note)

    private fun target(type: SessionType = SessionType.NORMAL, rangeType: RangeType = RangeType.BASE) = Target(
        weightKg = 62.5, sets = 3, range = RepRange(8, 12), repGoal = 12, type = type, reason = "", rangeType = rangeType,
    )

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        val container = testContainer(db)
        container.seed()
        sessions = container.sessionService
        service = LogService(db, sessions)
        val day = db.planDao().getActiveDays().first()
        slotId = db.planDao().getSlots(day.id).first().id
        sessionId = sessions.start(day.id, today, clubId = null)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun saveWritesTheLogFromTheTargetAndMarksTheSlotDone() = runBlocking {
        val id = service.save(
            sessionId, slotId, "bench-press", ok, input(note = "  tough  ", hurt = Joint.ELBOW),
            target(rangeType = RangeType.HIGH_REP), today,
        )

        val log = checkNotNull(db.logDao().get(id))
        assertThat(log.sessionId).isEqualTo(sessionId)
        assertThat(log.slotId).isEqualTo(slotId)
        assertThat(log.exerciseId).isEqualTo("bench-press")
        assertThat(log.date).isEqualTo(today)
        assertThat(log.weightKg).isEqualTo(62.5)
        assertThat(log.bestReps).isEqualTo(12)
        assertThat(log.sets).isEqualTo(3)
        assertThat(log.feel).isEqualTo(Feel.GRINDER)
        assertThat(log.hurt).isEqualTo(Joint.ELBOW)
        assertThat(log.note).isEqualTo("tough")
        assertThat(log.type).isEqualTo(SessionType.NORMAL)
        assertThat(log.rangeType).isEqualTo(RangeType.HIGH_REP)
        assertThat(log.imported).isFalse()
        assertThat(statusOf(slotId)).isEqualTo(SlotStatus.DONE)
    }

    @Test
    fun aBlankNoteIsStoredAsNull() = runBlocking {
        val id = service.save(sessionId, slotId, "bench-press", ok, input(note = "   "), target(), today)

        assertThat(checkNotNull(db.logDao().get(id)).note).isNull()
    }

    @Test
    fun aFlushSlotIsLoggedAsFlushWhateverTheTargetType() = runBlocking {
        sessions.setFlush(sessionId, slotId, true)

        val id = service.save(sessionId, slotId, "bench-press", ok, input(), target(SessionType.NORMAL), today)

        assertThat(checkNotNull(db.logDao().get(id)).type).isEqualTo(SessionType.FLUSH)
    }

    @Test
    fun theTargetsTypeIsKeptWhenTheSlotIsNotFlush() = runBlocking {
        val id = service.save(sessionId, slotId, "bench-press", ok, input(), target(SessionType.DELOAD), today)

        assertThat(checkNotNull(db.logDao().get(id)).type).isEqualTo(SessionType.DELOAD)
    }

    @Test
    fun aLogThatCannotBeWrittenLeavesTheSlotAsItWas() = runBlocking {
        // A session slot whose plan slot does not exist: the log's slot foreign key rejects the insert.
        val ghostSlot = 9_999L
        db.sessionDao().upsertSessionSlot(SessionSlotEntity(sessionId, ghostSlot))

        val result = runCatching { service.save(sessionId, ghostSlot, "bench-press", ok, input(), target(), today) }

        assertThat(result.isFailure).isTrue()
        assertThat(db.logDao().count()).isEqualTo(0)
        assertThat(statusOf(ghostSlot)).isEqualTo(SlotStatus.PENDING)
    }

    @Test
    fun savingForASlotDeletedFromThePlanFailsBeforeWritingAnything() = runBlocking {
        db.planDao().deleteSlot(slotId)

        val result = runCatching { service.save(sessionId, slotId, "bench-press", ok, input(), target(), today) }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("slot")
        assertThat(db.logDao().count()).isEqualTo(0)
    }

    @Test
    fun savingForASessionThatDoesNotExistFailsBeforeWritingAnything() = runBlocking {
        val result = runCatching { service.save(9_999L, slotId, "bench-press", ok, input(), target(), today) }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("session")
        assertThat(db.logDao().count()).isEqualTo(0)
    }

    private suspend fun statusOf(slot: Long) = db.sessionDao().getSessionSlots(sessionId).single { it.slotId == slot }.status
}
