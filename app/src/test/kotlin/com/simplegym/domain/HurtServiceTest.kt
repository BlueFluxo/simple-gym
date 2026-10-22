package com.simplegym.domain

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.simplegym.data.ClubEntity
import com.simplegym.data.ClubUnavailableEntity
import com.simplegym.data.ExerciseEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.Seeder
import com.simplegym.data.SessionEntity
import com.simplegym.data.clearHurt
import com.simplegym.engine.BackLoad
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.Muscle
import com.simplegym.engine.SessionType
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HurtServiceTest {
    private lateinit var db: GymDatabase
    private lateinit var service: HurtService
    private lateinit var targets: TargetService
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
        benchSlotId = db.planDao().getSlots(db.planDao().getActiveDays().first().id).first().id
        service = HurtService(db)
        targets = TargetService(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun log(exerciseId: String, daysAgo: Long, hurt: Joint? = null) {
        db.logDao().insert(
            LogEntity(
                sessionId = null, slotId = null, exerciseId = exerciseId, date = today.minusDays(daysAgo),
                weightKg = 60.0, bestReps = 8, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL, hurt = hurt,
            ),
        )
    }

    private suspend fun startSession(clubId: Long? = null): Long = db.sessionDao().insertSession(
        SessionEntity(
            dayId = checkNotNull(db.planDao().getSlot(benchSlotId)).dayId,
            date = today, startedAtMillis = 0, clubId = clubId, slotsTotal = 5,
        ),
    )

    @Test
    fun activeHurtsAreCountedPerExerciseFromItsOwnLogs() = runBlocking<Unit> {
        log("bench-press", 9, Joint.SHOULDER)
        log("bench-press", 5, Joint.SHOULDER)
        log("bench-press", 3)
        log("dumbbell-bench-press", 4, Joint.ELBOW)
        log("dumbbell-bench-press", 3)
        log("dumbbell-bench-press", 2)
        log("dumbbell-bench-press", 1) // the third pain-free log clears the elbow flag
        log("push-ups", 2) // never hurt

        assertThat(service.activeHurts()).containsExactly("bench-press", mapOf(Joint.SHOULDER to 2))
    }

    @Test
    fun clearHurtHidesEarlierHurtsAndKeepsLaterOnes() = runBlocking<Unit> {
        log("bench-press", 5, Joint.SHOULDER)
        assertThat(service.activeHurts()).containsKey("bench-press")

        db.libraryDao().clearHurt("bench-press", today.minusDays(2))
        assertThat(checkNotNull(db.libraryDao().get("bench-press")).hurtClearedOn).isEqualTo(today.minusDays(2))
        assertThat(service.activeHurts()).isEmpty()

        log("bench-press", 1, Joint.SHOULDER)
        assertThat(service.activeHurts()).containsExactly("bench-press", mapOf(Joint.SHOULDER to 1))
    }

    @Test
    fun swapPlanFeedsTheHurtFlaggedIdsAndTheyDropToTheEndOfTheChips() = runBlocking<Unit> {
        val session = startSession()
        assertThat(targets.swapPlan(session, benchSlotId, today).ctx.hurtFlagged).isEmpty()
        assertThat(targets.swapPlan(session, benchSlotId, today).ordered.first().id).isEqualTo("chest-press-machine")

        log("chest-press-machine", 2, Joint.ELBOW)

        val plan = targets.swapPlan(session, benchSlotId, today)
        assertThat(plan.ctx.hurtFlagged).containsExactly("chest-press-machine")
        assertThat(plan.ordered.last().id).isEqualTo("chest-press-machine")
    }

    @Test
    fun cautionNamesTheJointAnotherExerciseHurtOnlyOnExercisesThatLoadIt() = runBlocking<Unit> {
        log("bench-press", 3, Joint.SHOULDER)

        assertThat(service.cautionFor("dumbbell-bench-press", today)).isEqualTo(Joint.SHOULDER)
        assertThat(service.cautionFor("leg-extensions", today)).isNull() // loads the knee only
        assertThat(service.cautionFor("bench-press", today)).isNull() // its own hurt is a badge, not a caution
    }

    @Test
    fun cautionEndsAfterTwoPainFreeOwnLogsOrWhenTheOtherFlagIsCleared() = runBlocking<Unit> {
        log("bench-press", 5, Joint.SHOULDER)
        val dumbbell = "dumbbell-bench-press"
        assertThat(service.cautionFor(dumbbell, today)).isEqualTo(Joint.SHOULDER)

        log("dumbbell-bench-press", 3)
        assertThat(service.cautionFor(dumbbell, today)).isEqualTo(Joint.SHOULDER) // one is not enough
        log("dumbbell-bench-press", 2)
        assertThat(service.cautionFor(dumbbell, today)).isNull()

        // the same hurt seen from an exercise with no pain-free logs of its own, until the lifter clears bench press
        assertThat(service.cautionFor("chest-press-machine", today)).isEqualTo(Joint.SHOULDER)
        db.libraryDao().clearHurt("bench-press", today.minusDays(1))
        assertThat(service.cautionFor("chest-press-machine", today)).isNull()
    }

    @Test
    fun friendlySwapPrefersTheFirstOrderedSwapNotLoadingTheJoint() = runBlocking<Unit> {
        val plan = targets.swapPlan(startSession(), benchSlotId, today)

        // only bench press and push-ups load the wrist; the first ordered swap that does not is the chest press machine
        assertThat(service.friendlySwap(plan, "bench-press", Joint.WRIST)?.id).isEqualTo("chest-press-machine")
    }

    @Test
    fun friendlySwapNeverSuggestsTheVariantItself() = runBlocking<Unit> {
        val plan = targets.swapPlan(startSession(), benchSlotId, today)

        // no press variant loads the knee: without the exclusion the variant itself, the slot default, would win
        assertThat(service.friendlySwap(plan, "bench-press", Joint.KNEE)?.id).isEqualTo("chest-press-machine")
        assertThat(service.friendlySwap(plan, "chest-press-machine", Joint.KNEE)?.id).isEqualTo("bench-press")
    }

    private suspend fun groupMember(id: String, joints: Set<Joint>, archived: Boolean = false, backLoad: BackLoad = BackLoad.LOW) =
        db.libraryDao().upsert(
            ExerciseEntity(
                id = id, name = id, muscle = Muscle.CHEST, equipment = "Machine", swapGroup = "horizontal-press",
                incrementKg = 2.5, backLoad = backLoad, joints = joints, isAssisted = false, isBodyweight = false,
                isCustom = true, archived = archived,
            ),
        )

    @Test
    fun theGroupFallbackSkipsArchivedUnavailableAndProtectedCandidates() = runBlocking<Unit> {
        val club = db.clubDao().insert(ClubEntity(name = "Test club"))
        db.clubDao().markUnavailable(ClubUnavailableEntity(club, "b-club-missing"))
        // each of these has fewer joints than the expected pick, so a fallback that skipped a filter would choose it
        groupMember("a-archived", emptySet(), archived = true)
        groupMember("b-club-missing", emptySet())
        groupMember("c-back-risk", emptySet(), backLoad = BackLoad.HIGH)
        groupMember("d-expected", setOf(Joint.ELBOW))
        groupMember("e-more-joints", setOf(Joint.ELBOW, Joint.WRIST))
        db.settingsDao().upsert(checkNotNull(db.settingsDao().get()).copy(lowerBackProtection = true))
        val variant = "bench-press"

        // every swap on the slot loads the shoulder, so the answer comes from the group
        val plan = targets.swapPlan(startSession(club), benchSlotId, today)
        assertThat(service.friendlySwap(plan, variant, Joint.SHOULDER)?.id).isEqualTo("d-expected")

        // the control: with the three filters off, one of the empty-joint candidates wins
        db.libraryDao().setArchived("a-archived", false)
        db.settingsDao().upsert(checkNotNull(db.settingsDao().get()).copy(lowerBackProtection = false))
        val open = targets.swapPlan(startSession(null), benchSlotId, today)
        assertThat(service.friendlySwap(open, variant, Joint.SHOULDER)?.id)
            .isIn(listOf("a-archived", "b-club-missing", "c-back-risk"))
    }

    @Test
    fun withNoCandidateAvoidingTheJointThereIsNoSuggestion() = runBlocking<Unit> {
        val plan = targets.swapPlan(startSession(), benchSlotId, today)

        assertThat(service.friendlySwap(plan, "bench-press", Joint.SHOULDER)).isNull()
    }
}
