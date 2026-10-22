package com.simplegym.domain

import android.content.Context
import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.completeBuildRounds
import com.simplegym.data.CheckInEntity
import com.simplegym.data.ClubEntity
import com.simplegym.data.ClubUnavailableEntity
import com.simplegym.data.ExerciseEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.Seeder
import com.simplegym.data.SessionEntity
import com.simplegym.data.SlotSwapEntity
import com.simplegym.engine.BackLoad
import com.simplegym.engine.Chip
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.Muscle
import com.simplegym.engine.RangeType
import com.simplegym.engine.RepRange
import com.simplegym.engine.SessionType
import com.simplegym.engine.WeightHint
import com.simplegym.testContainer
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TargetServiceTest {
    private lateinit var db: GymDatabase
    private lateinit var service: TargetService
    private var benchSlotId = 0L
    private val today = LocalDate.of(2026, 10, 22)

    @Before
    fun setUp() = runBlocking<Unit> {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        Seeder(db, context.assets).seedIfEmpty()
        val firstDay = db.planDao().getActiveDays().first()
        benchSlotId = db.planDao().getSlots(firstDay.id).first().id
        service = TargetService(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun log(
        exerciseId: String,
        weightKg: Double?,
        reps: Int,
        feel: Feel = Feel.SOLID,
        type: SessionType = SessionType.NORMAL,
        date: LocalDate = LocalDate.of(2026, 10, 20),
        hurt: Joint? = null,
    ): Long = db.logDao().insert(
        LogEntity(
            sessionId = null,
            slotId = null,
            exerciseId = exerciseId,
            date = date,
            weightKg = weightKg,
            bestReps = reps,
            sets = 3,
            feel = feel,
            type = type,
            rangeType = RangeType.BASE,
            hurt = hurt,
        ),
    )

    private fun entity(override: Double? = null, library: Double? = 2.5) = ExerciseEntity(
        id = "x", name = "X", muscle = Muscle.CHEST, equipment = "Dumbbell",
        swapGroup = "g", incrementKg = library, incrementOverrideKg = override,
        backLoad = BackLoad.LOW, joints = emptySet(), isAssisted = false, isBodyweight = false,
    )

    @Test
    fun effectiveIncrementPrefersOverrideThenEquipmentMapThenLibrary() {
        val map = mapOf("Dumbbell" to 2.0)

        assertThat(effectiveIncrement(entity(override = 1.0), map)).isEqualTo(1.0)
        assertThat(effectiveIncrement(entity(), map)).isEqualTo(2.0)
        assertThat(effectiveIncrement(entity(), emptyMap())).isEqualTo(2.5)
        assertThat(effectiveIncrement(entity(library = null), emptyMap())).isNull()
    }

    @Test
    fun toEngineUsesTheEffectiveIncrement() {
        val engine = entity(override = 1.0).toEngine(mapOf("Dumbbell" to 2.0))

        assertThat(engine.incrementKg).isEqualTo(1.0)
        assertThat(engine.id).isEqualTo("x")
        assertThat(engine.equipment).isEqualTo("Dumbbell")
    }

    @Test
    fun benchBaselineOf60x12SolidTargets62Point5ForThreeSetsInTheSlotRange() = runBlocking {
        log("bench-press", 60.0, 12, Feel.SOLID)

        val target = service.targetFor(benchSlotId, "bench-press", today)

        assertThat(target.weightKg).isEqualTo(62.5)
        assertThat(target.sets).isEqualTo(3)
        assertThat(target.range).isEqualTo(RepRange(8, 12))
        assertThat(target.type).isEqualTo(SessionType.NORMAL)
        assertThat(target.rangeType).isEqualTo(RangeType.BASE)
        assertThat(target.roundSec).isEqualTo(120)
        assertThat(target.hint).isNull()
    }

    @Test
    fun equipmentIncrementFromSettingsChangesTheStep() = runBlocking {
        db.settingsDao().upsert(
            checkNotNull(db.settingsDao().get()).copy(equipmentIncrements = mapOf("Barbell" to 5.0)),
        )
        log("bench-press", 60.0, 12, Feel.SOLID)

        assertThat(service.targetFor(benchSlotId, "bench-press", today).weightKg).isEqualTo(65.0)
    }

    @Test
    fun missingSettingsAreTreatedAsNoEquipmentIncrements() = runBlocking {
        db.useWriterConnection { it.executeSQL("DELETE FROM settings") }
        log("bench-press", 60.0, 12, Feel.SOLID)

        assertThat(service.targetFor(benchSlotId, "bench-press", today).weightKg).isEqualTo(62.5)
    }

    @Test
    fun noHistoryGivesColdStartWithNullWeightAndNoHint() = runBlocking {
        val target = service.targetFor(benchSlotId, "bench-press", today)

        assertThat(target.weightKg).isNull()
        assertThat(target.repGoal).isNull()
        assertThat(target.reason).isEqualTo("New: start light, with a weight you could lift about 15 times.")
        assertThat(target.hint).isNull()
        assertThat(target.sets).isEqualTo(3)
        assertThat(target.range).isEqualTo(RepRange(8, 12))
        assertThat(target.type).isEqualTo(SessionType.NORMAL)
    }

    @Test
    fun coldStartHintComesFromAnotherExerciseWithSameGroupAndEquipment() = runBlocking {
        log("dumbbell-bench-press", 22.5, 10)

        val target = service.targetFor(benchSlotId, "dumbbell-floor-press", today)

        assertThat(target.weightKg).isNull()
        assertThat(target.hint).isEqualTo(WeightHint("Dumbbell bench press", 22.5))
    }

    @Test
    fun coldStartHintIgnoresMatchingExercisesWithoutAUsableBaseline() = runBlocking {
        log("dumbbell-bench-press", 22.5, 10, date = LocalDate.of(2026, 10, 10))
        log("dumbbell-bench-press", 15.0, 4, Feel.GRINDER, SessionType.HELD, LocalDate.of(2026, 10, 20))

        val target = service.targetFor(benchSlotId, "dumbbell-floor-press", today)

        assertThat(target.hint).isEqualTo(WeightHint("Dumbbell bench press", 22.5))
    }

    @Test
    fun barbellBaselineGivesNoHintForADumbbellExercise() = runBlocking {
        log("bench-press", 60.0, 10)

        val target = service.targetFor(benchSlotId, "dumbbell-bench-press", today)

        assertThat(target.weightKg).isNull()
        assertThat(target.hint).isNull()
    }

    @Test
    fun heldLogAfterANormalOneIsIgnored() = runBlocking {
        log("bench-press", 60.0, 12, Feel.SOLID, SessionType.NORMAL, LocalDate.of(2026, 10, 18))
        log("bench-press", 40.0, 5, Feel.GRINDER, SessionType.HELD, LocalDate.of(2026, 10, 20))

        assertThat(service.targetFor(benchSlotId, "bench-press", today).weightKg).isEqualTo(62.5)
    }

    @Test
    fun hurtLogIsIgnoredAsABaselineButStepsTheWeightDown() = runBlocking {
        log("bench-press", 60.0, 12, Feel.SOLID, date = LocalDate.of(2026, 10, 18))
        log("bench-press", 40.0, 5, Feel.GRINDER, date = LocalDate.of(2026, 10, 20), hurt = Joint.SHOULDER)

        // v1 gave 62.5 (progression off the 60 baseline). Since the coach, the active hurt holds one step below that
        // baseline instead: 57.5 from 60, so the hurt log's own 40 is still not the baseline.
        val target = service.targetFor(benchSlotId, "bench-press", today)

        assertThat(target.weightKg).isEqualTo(57.5)
        assertThat(target.type).isEqualTo(SessionType.HELD)
    }

    @Test
    fun sameDayBaselinesAreTieBrokenByInsertionOrder() = runBlocking {
        val day = LocalDate.of(2026, 10, 20)
        log("bench-press", 50.0, 12, Feel.SOLID, date = day)
        log("bench-press", 60.0, 12, Feel.SOLID, date = day)

        assertThat(service.targetFor(benchSlotId, "bench-press", today).weightKg).isEqualTo(62.5)
    }

    @Test
    fun swapChipWeightsReturnsLastBaselineWeightOrNullForNew() = runBlocking<Unit> {
        log("bench-press", 55.0, 10, date = LocalDate.of(2026, 10, 10))
        log("bench-press", 60.0, 10, date = LocalDate.of(2026, 10, 15))
        log("bench-press", 30.0, 4, Feel.GRINDER, SessionType.HELD, LocalDate.of(2026, 10, 20))
        log("dumbbell-bench-press", 22.5, 10)

        val weights = service.swapChipWeights(
            listOf("bench-press", "dumbbell-bench-press", "push-ups", "chest-press-machine"),
        )

        assertThat(weights).containsExactly(
            "bench-press", 60.0,
            "dumbbell-bench-press", 22.5,
            "push-ups", null,
            "chest-press-machine", null,
        )
    }

    @Test
    fun swapChipWeightsOfNoExercisesIsEmpty() = runBlocking {
        assertThat(service.swapChipWeights(emptyList())).isEmpty()
    }

    @Test
    fun swapChipLabelsTellANewVariantFromABodyweightOneWithABaseline() = runBlocking<Unit> {
        log("bench-press", 60.0, 10)
        log("push-ups", null, 20) // a bodyweight baseline has no weight
        log("dumbbell-bench-press", 30.0, 4, Feel.GRINDER, SessionType.HELD) // not a baseline: still "new"
        log("assisted-pull-up", -35.0, 8)

        val labels = service.swapChipLabels(
            listOf("bench-press", "push-ups", "dumbbell-bench-press", "chest-press-machine", "assisted-pull-up"),
        )

        assertThat(labels).containsExactly(
            "bench-press", "60",
            "push-ups", "BW",
            "dumbbell-bench-press", "new",
            "chest-press-machine", "new",
            "assisted-pull-up", "−35",
        )
    }

    private suspend fun startSession(clubId: Long? = null): Long = db.sessionDao().insertSession(
        SessionEntity(
            dayId = checkNotNull(db.planDao().getSlot(benchSlotId)).dayId,
            date = today,
            startedAtMillis = 0,
            clubId = clubId,
            slotsTotal = 5,
        ),
    )

    private suspend fun logForSlot(exerciseId: String, weightKg: Double?, slotId: Long) {
        val id = log(exerciseId, weightKg, 10)
        db.logDao().update(checkNotNull(db.logDao().get(id)).copy(slotId = slotId))
    }

    @Test
    fun resolveVariantIsTheSlotsDefaultWhenNothingHidesIt() = runBlocking {
        assertThat(service.resolveVariant(startSession(), benchSlotId, today)).isEqualTo("bench-press")
    }

    @Test
    fun resolveVariantFallsToTheFirstSwapWhenTheDefaultIsUnavailableAtTheSessionsClub() = runBlocking {
        val club = db.clubDao().insert(ClubEntity(name = "Test club"))
        db.clubDao().markUnavailable(ClubUnavailableEntity(club, "bench-press"))

        assertThat(service.resolveVariant(startSession(club), benchSlotId, today)).isEqualTo("chest-press-machine")
        // the same default is fine in a session at no club
        assertThat(service.resolveVariant(startSession(null), benchSlotId, today)).isEqualTo("bench-press")
    }

    @Test
    fun resolveVariantPrefersTheSwapUsedMostOftenInThisSlot() = runBlocking {
        val club = db.clubDao().insert(ClubEntity(name = "Test club"))
        db.clubDao().markUnavailable(ClubUnavailableEntity(club, "bench-press"))
        logForSlot("dumbbell-bench-press", 22.5, benchSlotId)
        logForSlot("dumbbell-bench-press", 22.5, benchSlotId)
        logForSlot("push-ups", null, benchSlotId + 1) // used in another slot: does not count here

        assertThat(service.resolveVariant(startSession(club), benchSlotId, today)).isEqualTo("dumbbell-bench-press")
    }

    @Test
    fun swapPlanHidesHighBackLoadSwapsOnlyWhileProtectionIsOn() = runBlocking {
        db.planDao().insertSwaps(listOf(SlotSwapEntity(benchSlotId, "barbell-bent-over-row", 9)))
        val session = startSession()

        assertThat(service.swapPlan(session, benchSlotId, today).ordered.map { it.id }).contains("barbell-bent-over-row")
        db.settingsDao().upsert(checkNotNull(db.settingsDao().get()).copy(lowerBackProtection = true))
        assertThat(service.swapPlan(session, benchSlotId, today).ordered.map { it.id }).doesNotContain("barbell-bent-over-row")
    }

    @Test
    fun swapPlanLeavesOutArchivedSwaps() = runBlocking {
        db.libraryDao().setArchived("push-ups", true)

        val plan = service.swapPlan(startSession(), benchSlotId, today)

        assertThat(plan.ordered.map { it.id }).containsExactly(
            "chest-press-machine", "dumbbell-bench-press", "machine-press-plate-loaded",
        ).inOrder()
    }

    @Test
    fun aRotationForTheCurrentBlockMakesItsVariantTheSlotsDefault() = runBlocking {
        db.planDao().setRotation(benchSlotId, "dumbbell-bench-press", 0)
        val session = startSession()

        assertThat(service.resolveVariant(session, benchSlotId, today)).isEqualTo("dumbbell-bench-press")
        val plan = service.swapPlan(session, benchSlotId, today)
        assertThat(plan.default.id).isEqualTo("dumbbell-bench-press")
        // the plan default takes its place among the swaps; nothing is listed twice
        assertThat(plan.ordered.map { it.id }).containsExactly(
            "bench-press", "chest-press-machine", "machine-press-plate-loaded", "push-ups",
        ).inOrder()
    }

    @Test
    fun aRotationForAnotherBlockIsIgnored() = runBlocking {
        db.planDao().setRotation(benchSlotId, "dumbbell-bench-press", 1) // the current block is 0
        assertThat(service.resolveVariant(startSession(), benchSlotId, today)).isEqualTo("bench-press")
    }

    @Test
    fun aRotatedVariantStillGivesWayWhenItIsUnavailableAtTheSessionsClub() = runBlocking {
        db.planDao().setRotation(benchSlotId, "dumbbell-bench-press", 0)
        val club = db.clubDao().insert(ClubEntity(name = "Test club"))
        db.clubDao().markUnavailable(ClubUnavailableEntity(club, "dumbbell-bench-press"))

        assertThat(service.resolveVariant(startSession(club), benchSlotId, today)).isEqualTo("bench-press")
    }

    @Test
    fun anArchivedRotatedVariantIsIgnored() = runBlocking {
        db.planDao().setRotation(benchSlotId, "dumbbell-bench-press", 0)
        db.libraryDao().setArchived("dumbbell-bench-press", true)

        assertThat(service.resolveVariant(startSession(), benchSlotId, today)).isEqualTo("bench-press")
    }

    @Test
    fun plateauCountsTheStalledBaselineSessionsOfThatVariantAndRangeTypeOnly() = runBlocking {
        repeat(4) { log("bench-press", 60.0, 10, date = LocalDate.of(2026, 10, 10 + it)) }
        log("bench-press", 60.0, 10, type = SessionType.DELOAD, date = LocalDate.of(2026, 10, 15)) // not a baseline
        log("bench-press", 60.0, 10, hurt = Joint.SHOULDER, date = LocalDate.of(2026, 10, 16)) // not a baseline
        log("dumbbell-bench-press", 20.0, 10) // another variant

        assertThat(service.plateauFor("bench-press", RangeType.BASE)).isEqualTo(3)
        assertThat(service.plateauFor("bench-press", RangeType.HIGH_REP)).isEqualTo(0)
        assertThat(service.plateauFor("dumbbell-bench-press", RangeType.BASE)).isEqualTo(0)
        assertThat(service.plateauFor("push-ups", RangeType.BASE)).isEqualTo(0) // no history
    }

    // The coach: modifiers assembled from the database

    private suspend fun stiff(vararg chips: Chip) = db.sessionDao().upsertCheckIn(CheckInEntity(today, chips.toSet()))

    @Test
    fun aStiffChipForTheExercisesMuscleHoldsTheWeight() = runBlocking {
        log("bench-press", 60.0, 12)
        stiff(Chip.CHEST)

        val target = service.targetFor(benchSlotId, "bench-press", today)

        assertThat(target.weightKg).isEqualTo(60.0)
        assertThat(target.type).isEqualTo(SessionType.HELD)
        assertThat(service.stiffMuscle("bench-press", today)).isTrue()
    }

    @Test
    fun aStiffChipForAnotherMuscleOrAnotherDayChangesNothing() = runBlocking {
        log("bench-press", 60.0, 12)
        stiff(Chip.BACK, Chip.LEGS)
        db.sessionDao().upsertCheckIn(CheckInEntity(today.minusDays(1), setOf(Chip.CHEST)))

        assertThat(service.targetFor(benchSlotId, "bench-press", today).weightKg).isEqualTo(62.5)
        assertThat(service.stiffMuscle("bench-press", today)).isFalse()
    }

    private suspend fun startRealSession() =
        SessionService(db) { 0L }.start(checkNotNull(db.planDao().getSlot(benchSlotId)).dayId, today, clubId = null)

    @Test
    fun flushIsReadFromTheOpenSessionsSlot() = runBlocking {
        log("bench-press", 60.0, 12)
        val sessions = SessionService(db) { 0L }
        val session = startRealSession()
        assertThat(service.targetFor(benchSlotId, "bench-press", today).type).isEqualTo(SessionType.NORMAL)

        sessions.setFlush(session, benchSlotId, true)
        val flush = service.targetFor(benchSlotId, "bench-press", today)

        assertThat(flush.type).isEqualTo(SessionType.FLUSH)
        assertThat(flush.weightKg).isEqualTo(25.0)
        assertThat(flush.range).isEqualTo(RepRange(15, 20))
        assertThat(flush.roundSec).isEqualTo(60)

        sessions.finish(session) // a finished session no longer flushes the slot
        assertThat(service.targetFor(benchSlotId, "bench-press", today).type).isEqualTo(SessionType.NORMAL)
    }

    @Test
    fun layoffLooksAtTheWholeSwapGroupImportedLogsIncluded() = runBlocking {
        log("bench-press", 60.0, 12, date = today.minusDays(30))
        // a different press variant in the same movement, imported 4 days ago: the lifter has been pressing
        db.logDao().insert(
            LogEntity(
                sessionId = null, slotId = null, exerciseId = "dumbbell-bench-press", date = today.minusDays(4),
                weightKg = 20.0, bestReps = 10, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL, imported = true,
            ),
        )
        assertThat(service.targetFor(benchSlotId, "bench-press", today).type).isEqualTo(SessionType.NORMAL)

        db.logDao().delete(db.logDao().getForExercise("dumbbell-bench-press").single().id)
        val back = service.targetFor(benchSlotId, "bench-press", today) // now 30 days since any press: -20%

        assertThat(back.type).isEqualTo(SessionType.RETURN)
        assertThat(back.weightKg).isEqualTo(47.5)
    }

    @Test
    fun anotherExercisesHurtOnASharedJointHoldsTheWeight() = runBlocking {
        log("bench-press", 60.0, 12, date = today.minusDays(3))
        log("chest-press-machine", 40.0, 8, date = today.minusDays(2), hurt = Joint.ELBOW)

        val target = service.targetFor(benchSlotId, "bench-press", today)

        assertThat(target.weightKg).isEqualTo(60.0)
        assertThat(target.type).isEqualTo(SessionType.HELD)
        assertThat(target.reason).isEqualTo("Elbow flagged recently: holding.")
    }

    @Test
    fun theAgeInSettingsSetsTheStepsPerSession() = runBlocking {
        log("bench-press", 60.0, 15, Feel.EASY) // 3 over the top and easy
        assertThat(service.targetFor(benchSlotId, "bench-press", today).weightKg).isEqualTo(65.0) // no age: 45, two steps

        db.settingsDao().upsert(checkNotNull(db.settingsDao().get()).copy(age = 60))

        assertThat(service.targetFor(benchSlotId, "bench-press", today).weightKg).isEqualTo(62.5) // 55+: one step
    }

    @Test
    fun aNewBlockGetsTheHighRepRangeAndConvertsTheWeight() = runBlocking {
        testContainer(db).completeBuildRounds(today.minusDays(60)) // block 1 has started
        log("bench-press", 60.0, 12)

        val target = service.targetFor(benchSlotId, "bench-press", today)

        assertThat(target.rangeType).isEqualTo(RangeType.HIGH_REP)
        assertThat(target.range).isEqualTo(RepRange(12, 16))
        assertThat(target.weightKg).isEqualTo(55.0)
        assertThat(target.repGoal).isEqualTo(13)
    }

    @Test
    fun aHighRepSessionIsStoredAsHighRepSoTheNextOneBuildsOnItWithoutConvertingTwice() = runBlocking {
        val container = testContainer(db)
        container.completeBuildRounds(today.minusDays(60)) // block 1 has started
        log("bench-press", 60.0, 12)
        val session = startRealSession()
        val first = service.targetFor(benchSlotId, "bench-press", today)
        assertThat(first.rangeType).isEqualTo(RangeType.HIGH_REP)

        LogService(db, container.sessionService).save(
            session, benchSlotId, "bench-press", LogValidation.Ok(first.weightKg, 14, 3),
            LogInput("55", "14", "3", Feel.SOLID, null, null), first, today,
        )
        val next = service.targetFor(benchSlotId, "bench-press", today.plusDays(3))

        assertThat(db.logDao().getForExercise("bench-press").last().rangeType).isEqualTo(RangeType.HIGH_REP)
        assertThat(next.weightKg).isEqualTo(55.0) // built on the HIGH_REP log as it is: in range, hold, no re-conversion
        assertThat(next.repGoal).isEqualTo(15)
    }
}
