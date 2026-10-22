package com.simplegym.domain

import com.google.common.truth.Truth.assertThat
import com.simplegym.data.DayEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SessionEntity
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

@RunWith(RobolectricTestRunner::class)
class CheckInServiceTest {
    private val today = LocalDate.of(2026, 10, 22)

    private lateinit var db: GymDatabase
    private lateinit var service: CheckInService
    private var legsDayId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        testContainer(db).seed()
        service = CheckInService(db)
        legsDayId = db.planDao().getActiveDays().first { it.chips.contains(Chip.LEGS) }.id
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun setStiffPersistsTodaysCheckInAndTodayEmitsIt() = runBlocking<Unit> {
        assertThat(service.today(today).first()).isEmpty()

        service.setStiff(today, setOf(Chip.LEGS, Chip.NECK))

        assertThat(checkNotNull(db.sessionDao().getCheckIn(today)).stiff).containsExactly(Chip.LEGS, Chip.NECK)
        assertThat(service.today(today).first()).containsExactly(Chip.LEGS, Chip.NECK)
        assertThat(service.today(today.plusDays(1)).first()).isEmpty()
    }

    @Test
    fun anEmptyCheckInIsKeptAsACheckIn() = runBlocking<Unit> {
        service.setStiff(today, setOf(Chip.LEGS))

        service.setStiff(today, emptySet())

        assertThat(checkNotNull(db.sessionDao().getCheckIn(today)).stiff).isEmpty()
    }

    @Test
    fun toggleTapsAChipOnAndOff() = runBlocking<Unit> {
        service.toggle(today, Chip.LEGS)
        service.toggle(today, Chip.CHEST)
        assertThat(service.today(today).first()).containsExactly(Chip.LEGS, Chip.CHEST)

        service.toggle(today, Chip.LEGS)

        assertThat(service.today(today).first()).containsExactly(Chip.CHEST)
    }

    private suspend fun trainedLegs(daysAgo: Long, type: SessionType = SessionType.NORMAL) {
        val date = today.minusDays(daysAgo)
        val sessionId = db.sessionDao().insertSession(
            SessionEntity(dayId = legsDayId, date = date, startedAtMillis = 0, finishedAtMillis = 1, slotsTotal = 5),
        )
        db.logDao().insert(
            LogEntity(
                sessionId = sessionId, slotId = null, exerciseId = "leg-press", date = date, weightKg = 80.0,
                bestReps = 10, sets = 3, feel = Feel.SOLID, type = type,
            ),
        )
    }

    private suspend fun checkIn(daysAgo: Long, vararg chips: Chip) = service.setStiff(today.minusDays(daysAgo), chips.toSet())

    @Test
    fun typicalStiffDaysComesFromFinishedSessionsAndTheCheckInsAfterThem() = runBlocking<Unit> {
        trainedLegs(30)
        trainedLegs(20)
        checkIn(27, Chip.LEGS)
        checkIn(26)
        checkIn(17, Chip.LEGS)
        checkIn(16)

        // the day's chips are LEGS and CALVES; only LEGS was tapped
        assertThat(service.typicalStiffDays()).containsExactly(Chip.LEGS, 3)
    }

    @Test
    fun aFlushOnlySessionTrainedNothingAndGivesNoSample() = runBlocking<Unit> {
        trainedLegs(30)
        trainedLegs(20, SessionType.FLUSH)
        checkIn(27, Chip.LEGS)
        checkIn(26)
        checkIn(17, Chip.LEGS)
        checkIn(16)

        assertThat(service.typicalStiffDays()).isEmpty() // one sample is not an estimate
    }

    @Test
    fun anOpenSessionOrOneWithoutLogsIsNotTraining() = runBlocking<Unit> {
        trainedLegs(30)
        val open = db.sessionDao().insertSession(
            SessionEntity(dayId = legsDayId, date = today.minusDays(20), startedAtMillis = 0, slotsTotal = 5),
        )
        db.logDao().insert(
            LogEntity(
                sessionId = open, slotId = null, exerciseId = "leg-press", date = today.minusDays(20), weightKg = 80.0,
                bestReps = 10, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
            ),
        )
        db.sessionDao().insertSession(
            SessionEntity(dayId = legsDayId, date = today.minusDays(10), startedAtMillis = 0, finishedAtMillis = 1, slotsTotal = 5),
        )
        checkIn(27, Chip.LEGS)
        checkIn(26)
        checkIn(17, Chip.LEGS)
        checkIn(16)
        checkIn(7, Chip.LEGS)
        checkIn(6)

        assertThat(service.typicalStiffDays()).isEmpty()
    }

    @Test
    fun aSessionOfADeletedDayIsSkipped() = runBlocking<Unit> {
        val gone = db.planDao().insertDay(DayEntity(name = "Gone", position = 9, chips = setOf(Chip.LEGS)))
        listOf(30L, 20L).forEach { daysAgo ->
            val date = today.minusDays(daysAgo)
            val sessionId = db.sessionDao().insertSession(
                SessionEntity(dayId = gone, date = date, startedAtMillis = 0, finishedAtMillis = 1, slotsTotal = 1),
            )
            db.logDao().insert(
                LogEntity(
                    sessionId = sessionId, slotId = null, exerciseId = "leg-press", date = date, weightKg = 80.0,
                    bestReps = 10, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
                ),
            )
        }
        db.planDao().deleteDay(gone)
        checkIn(27, Chip.LEGS)
        checkIn(26)
        checkIn(17, Chip.LEGS)
        checkIn(16)

        assertThat(service.typicalStiffDays()).isEmpty()
    }
}
