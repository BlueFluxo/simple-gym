package com.simplegym.domain

import com.google.common.truth.Truth.assertThat
import com.simplegym.data.GymDatabase
import com.simplegym.data.LibraryExercise
import com.simplegym.data.LogEntity
import com.simplegym.data.SlotStatus
import com.simplegym.engine.Chip
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.Muscle
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.testContainer
import com.simplegym.ui.progress.chartPoints
import com.simplegym.data.toRecord
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlanEditorTest {
    private val today = LocalDate.of(2026, 10, 22)

    private lateinit var db: GymDatabase
    private lateinit var editor: PlanEditor
    private lateinit var sessions: SessionService
    private lateinit var targets: TargetService
    private lateinit var blocks: BlockService
    private var chestDayId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        testContainer(db).seed()
        editor = PlanEditor(db)
        sessions = SessionService(db) { 1_000_000_000L }
        targets = TargetService(db)
        blocks = BlockService(db)
        chestDayId = db.planDao().getActiveDays().first().id
    }

    @After
    fun tearDown() = db.close()

    private fun custom(name: String) = LibraryExercise(
        id = "ignored", name = name, primaryMuscle = "Chest", equipment = "Dumbbell", swapGroup = "horizontal-press",
        lowerBackLoad = "low", jointNotes = "", stressesJoints = listOf(Joint.SHOULDER), loadIncrementKg = 2.0,
        isAssisted = false, isBodyweight = false,
    )

    private suspend fun log(exerciseId: String, sessionId: Long?, slotId: Long?, weightKg: Double? = 40.0) =
        db.logDao().insert(
            LogEntity(
                sessionId = sessionId, slotId = slotId, exerciseId = exerciseId, date = today, weightKg = weightKg,
                bestReps = 10, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
            ),
        )

    private fun message(block: suspend () -> Unit): String? =
        assertThrows(IllegalArgumentException::class.java) { runBlocking { block() } }.message

    // ---- days ----

    @Test
    fun anAddedDayWithASlotShowsUpInHomesDayList() = runBlocking<Unit> {
        val dayId = editor.addDay("Arms", setOf(Chip.BICEPS, Chip.TRICEPS))
        editor.addSlot(dayId, "bench-press", sets = 3, lo = 8, hi = 12, roundSec = 90)

        val homeDays = db.planDao().observeActiveDays().first() // what Home lists
        assertThat(homeDays.map { it.name }.last()).isEqualTo("Arms")
        assertThat(homeDays.last().chips).containsExactly(Chip.BICEPS, Chip.TRICEPS)
        assertThat(homeDays.last().position).isEqualTo(homeDays.size - 1)
        val slot = db.planDao().getSlots(dayId).single()
        assertThat(slot.roundSec).isEqualTo(90)
        assertThat(slot.position).isEqualTo(0)
    }

    @Test
    fun renameAndChipsAndReorderDaysApply() = runBlocking<Unit> {
        val ids = db.planDao().getActiveDays().map { it.id }
        editor.renameDay(ids[0], "  Push  ")
        editor.setDayChips(ids[0], setOf(Chip.CHEST))
        editor.reorderDays(ids.reversed())

        val days = db.planDao().getActiveDays()
        assertThat(days.map { it.id }).containsExactlyElementsIn(ids.reversed()).inOrder()
        assertThat(days.last().name).isEqualTo("Push")
        assertThat(days.last().chips).containsExactly(Chip.CHEST)
        assertThat(message { editor.reorderDays(ids.drop(1)) }).isEqualTo("ids")
        assertThat(message { editor.addDay("   ", emptySet()) }).isEqualTo("name")
    }

    @Test
    fun deleteDayWithoutSessionsRemovesItAndItsSlots() = runBlocking<Unit> {
        val dayId = editor.addDay("Scratch", emptySet())
        val slotId = editor.addSlot(dayId, "bench-press", 3, 8, 12, 120)
        editor.setSwaps(slotId, listOf("push-ups"))

        editor.deleteDay(dayId)

        assertThat(db.planDao().getDay(dayId)).isNull()
        assertThat(db.planDao().getSlot(slotId)).isNull()
        assertThat(db.planDao().getSwaps(slotId)).isEmpty()
    }

    @Test
    fun deleteDayWithSessionsArchivesItAndDropsItFromTheActiveDays() = runBlocking<Unit> {
        val sessionId = sessions.start(chestDayId, today, clubId = null)
        db.planDao().getSlots(chestDayId).take(3).forEach { log("bench-press", sessionId, it.id) }
        sessions.finish(sessionId)
        assertThat(blocks.current(today).doneThisRound).contains(chestDayId)

        editor.deleteDay(chestDayId)

        assertThat(checkNotNull(db.planDao().getDay(chestDayId)).archived).isTrue()
        assertThat(db.planDao().getSlots(chestDayId)).isNotEmpty()
        assertThat(db.planDao().getActiveDays().map { it.id }).doesNotContain(chestDayId)
        assertThat(db.planDao().observeActiveDays().first().map { it.id }).doesNotContain(chestDayId)
        assertThat(db.sessionDao().getSession(sessionId)).isNotNull()
        assertThat(blocks.current(today).doneThisRound).doesNotContain(chestDayId) // judged against the remaining days
    }

    @Test
    fun archiveDayHidesItWithoutDeletingAnything() = runBlocking<Unit> {
        editor.archiveDay(chestDayId)

        assertThat(db.planDao().getActiveDays().map { it.id }).doesNotContain(chestDayId)
        assertThat(db.planDao().getDay(chestDayId)).isNotNull()
    }

    // ---- chips ----

    @Test
    fun proposeChipsOffersEveryChipThatTrainsTheSlotsMuscles() {
        assertThat(editor.proposeChips(listOf(Muscle.CHEST, Muscle.TRICEPS, Muscle.SHOULDERS)))
            .containsExactly(Chip.CHEST, Chip.TRICEPS, Chip.SHOULDERS)
        assertThat(editor.proposeChips(listOf(Muscle.QUADS, Muscle.GLUTES))).containsExactly(Chip.LEGS)
        assertThat(editor.proposeChips(emptyList())).isEmpty()
    }

    // ---- slots ----

    @Test
    fun updateSlotWithLoAboveHiThrowsNamingHi() = runBlocking<Unit> {
        val slot = db.planDao().getSlots(chestDayId).first()

        val message = message { editor.updateSlot(slot.id, slot.defaultExerciseId, 3, lo = 10, hi = 8, roundSec = 120) }

        assertThat(message).isEqualTo("hi")
        assertThat(db.planDao().getSlot(slot.id)).isEqualTo(slot)
    }

    @Test
    fun slotValidationNamesTheFieldThatIsOutOfRange() = runBlocking<Unit> {
        suspend fun add(sets: Int = 3, lo: Int = 8, hi: Int = 12, roundSec: Int = 120) =
            editor.addSlot(chestDayId, "bench-press", sets, lo, hi, roundSec)
        assertThat(message { add(lo = 0) }).isEqualTo("lo")
        assertThat(message { add(hi = 7) }).isEqualTo("hi")
        assertThat(message { add(sets = 0) }).isEqualTo("sets")
        assertThat(message { add(sets = 11) }).isEqualTo("sets")
        assertThat(message { add(roundSec = 10) }).isEqualTo("roundSec")
        assertThat(message { add(roundSec = 100) }).isEqualTo("roundSec")
        assertThat(message { add(roundSec = 615) }).isEqualTo("roundSec")
        assertThat(message { editor.addSlot(chestDayId, "no-such-exercise", 3, 8, 12, 120) }).isEqualTo("exerciseId")
        add(sets = 10, lo = 1, hi = 1, roundSec = 15)
        add(sets = 1, lo = 5, hi = 5, roundSec = 600)
    }

    @Test
    fun updateSlotKeepsTheTimerSettingsAndDropsTheRotationWhenTheDefaultChanges() = runBlocking<Unit> {
        val slot = db.planDao().getSlots(chestDayId).first()
        db.planDao().updateSlot(slot.copy(timerRounds = 4, timerRoundSec = 45))
        db.planDao().setRotation(slot.id, "dumbbell-bench-press", 0)

        editor.updateSlot(slot.id, slot.defaultExerciseId, 4, 6, 10, 90)
        val same = checkNotNull(db.planDao().getSlot(slot.id))
        assertThat(same.timerRounds).isEqualTo(4)
        assertThat(same.rotationExerciseId).isEqualTo("dumbbell-bench-press")
        assertThat(same.sets).isEqualTo(4)

        editor.updateSlot(slot.id, "chest-press-machine", 4, 6, 10, 90)
        val changed = checkNotNull(db.planDao().getSlot(slot.id))
        assertThat(changed.defaultExerciseId).isEqualTo("chest-press-machine")
        assertThat(changed.rotationExerciseId).isNull()
        assertThat(changed.rotationBlockIndex).isNull()
        assertThat(changed.timerRoundSec).isEqualTo(45)
    }

    @Test
    fun reorderSlotsAndSetSwapsKeepTheGivenOrder() = runBlocking<Unit> {
        val slots = db.planDao().getSlots(chestDayId)
        editor.reorderSlots(chestDayId, slots.map { it.id }.reversed())
        assertThat(db.planDao().getSlots(chestDayId).map { it.id }).containsExactlyElementsIn(slots.map { it.id }.reversed()).inOrder()
        assertThat(message { editor.reorderSlots(chestDayId, slots.map { it.id }.drop(1)) }).isEqualTo("ids")

        val slot = slots.first()
        editor.setSwaps(slot.id, listOf("push-ups", "chest-press-machine", "push-ups", slot.defaultExerciseId))
        assertThat(db.planDao().getSwaps(slot.id).map { it.exerciseId }).containsExactly("push-ups", "chest-press-machine").inOrder()
        assertThat(message { editor.setSwaps(slot.id, listOf("nope")) }).isEqualTo("exerciseId")
    }

    @Test
    fun deleteSlotMidSessionKeepsItsLogsWithANullSlotIdAndItsSessionRow() = runBlocking<Unit> {
        val sessionId = sessions.start(chestDayId, today, clubId = null)
        val slot = db.planDao().getSlots(chestDayId).first()
        val logId = log("bench-press", sessionId, slot.id)
        sessions.setStatus(sessionId, slot.id, SlotStatus.DONE)

        editor.deleteSlot(slot.id)

        assertThat(db.planDao().getSlot(slot.id)).isNull()
        val kept = checkNotNull(db.logDao().get(logId))
        assertThat(kept.slotId).isNull()
        assertThat(kept.exerciseId).isEqualTo("bench-press")
        assertThat(db.sessionDao().getSessionSlots(sessionId).map { it.slotId }).contains(slot.id)
        assertThat(checkNotNull(db.sessionDao().getSession(sessionId)).slotsTotal).isEqualTo(5)
    }

    // ---- exercises ----

    @Test
    fun deleteExerciseWithLogsArchivesItAndKeepsItsHistoryButHidesItEverywhereElse() = runBlocking<Unit> {
        val id = editor.addCustomExercise(custom("Cable crossover press")).id
        val slot = db.planDao().getSlots(chestDayId).first()
        editor.setSwaps(slot.id, listOf(id, "push-ups"))
        val sessionId = sessions.start(chestDayId, today, clubId = null)
        val logId = log(id, sessionId, slot.id, weightKg = 42.5)
        assertThat(targets.swapPlan(sessionId, slot.id, today).swaps.map { it.id }).contains(id)

        editor.deleteExercise(id)

        assertThat(checkNotNull(db.libraryDao().get(id)).archived).isTrue()
        assertThat(db.logDao().get(logId)).isNotNull()
        assertThat(targets.swapPlan(sessionId, slot.id, today).swaps.map { it.id }).doesNotContain(id)
        assertThat(db.libraryDao().observeActive().first().map { it.id }).doesNotContain(id)
        assertThat(db.libraryDao().observeWithHistory().first().map { it.id }).contains(id)
        val points = chartPoints(db.logDao().getForExercise(id).map { it.toRecord() })
        assertThat(points.map { it.weightKg }).containsExactly(42.5)
    }

    @Test
    fun deleteExerciseThatIsOnlyAPlanReferenceArchivesIt() = runBlocking<Unit> {
        val id = editor.addCustomExercise(custom("Pec fly special")).id
        val slot = db.planDao().getSlots(chestDayId).first()
        editor.setSwaps(slot.id, listOf(id))

        editor.deleteExercise(id)

        assertThat(checkNotNull(db.libraryDao().get(id)).archived).isTrue()
    }

    @Test
    fun deleteExerciseUnusedCustomRemovesTheRow() = runBlocking<Unit> {
        val id = editor.addCustomExercise(custom("Throwaway press")).id

        editor.deleteExercise(id)

        assertThat(db.libraryDao().get(id)).isNull()
    }

    @Test
    fun deleteExerciseFromTheBuiltInLibraryArchivesItEvenWhenUnused() = runBlocking<Unit> {
        editor.deleteExercise("push-ups")

        assertThat(checkNotNull(db.libraryDao().get("push-ups")).archived).isTrue()
    }

    @Test
    fun addCustomExerciseUsesTheSlugOfTheNameAndRejectsADuplicate() = runBlocking<Unit> {
        val id = editor.addCustomExercise(custom("  Zottman curl! ")).id

        assertThat(id).isEqualTo("zottman-curl")
        val row = checkNotNull(db.libraryDao().get(id))
        assertThat(row.isCustom).isTrue()
        assertThat(row.name).isEqualTo("Zottman curl!")
        assertThat(row.muscle).isEqualTo(Muscle.CHEST)
        assertThat(message { editor.addCustomExercise(custom("zottman CURL")) }).isEqualTo("name")
        assertThat(message { editor.addCustomExercise(custom("Bench press")) }).isEqualTo("name")
        assertThat(message { editor.addCustomExercise(custom("   ")) }).isEqualTo("name")
        assertThat(message { editor.addCustomExercise(custom("!!!")) }).isEqualTo("name")
    }

    @Test
    fun anArchivedExerciseCannotBecomeADefaultButKeepsWorkingWhereItAlreadyIs() = runBlocking<Unit> {
        val slot = db.planDao().getSlots(chestDayId).first()
        val id = editor.addCustomExercise(custom("Retired press")).id
        editor.updateSlot(slot.id, id, 3, 8, 12, 120)
        editor.deleteExercise(id)

        editor.updateSlot(slot.id, id, 4, 8, 12, 120) // still its default: editing the sets is fine
        assertThat(message { editor.addSlot(chestDayId, id, 3, 8, 12, 120) }).isEqualTo("exerciseId")
    }

    // ---- restoring archived items ----

    @Test
    fun aNameThatSlugsToAnArchivedExerciseRestoresItWithTheNewFieldsAndSaysSo() = runBlocking<Unit> {
        val kept = editor.addCustomExercise(custom("Kept press")).id
        val slot = db.planDao().getSlots(chestDayId).first()
        editor.setSwaps(slot.id, listOf(kept))
        editor.deleteExercise(kept) // in a swap list: archived
        assertThat(checkNotNull(db.libraryDao().get(kept)).archived).isTrue()

        val again = editor.addCustomExercise(custom("kept PRESS").copy(equipment = "Cable", loadIncrementKg = 1.0))

        assertThat(again.id).isEqualTo(kept)
        assertThat(again.note).isEqualTo("Restored kept PRESS")
        val row = checkNotNull(db.libraryDao().get(kept))
        assertThat(row.archived).isFalse()
        assertThat(row.name).isEqualTo("kept PRESS")
        assertThat(row.equipment).isEqualTo("Cable")
        assertThat(row.incrementKg).isEqualTo(1.0)
        assertThat(row.isCustom).isTrue()
        assertThat(db.libraryDao().observeActive().first().map { it.id }).contains(kept)
        // an active one is still refused
        assertThat(message { editor.addCustomExercise(custom("Kept press")) }).isEqualTo("name")
    }

    @Test
    fun anArchivedBuiltInIsRestoredWithoutBecomingCustom() = runBlocking<Unit> {
        editor.deleteExercise("push-ups")

        val again = editor.addCustomExercise(custom("Push-ups"))

        assertThat(again.id).isEqualTo("push-ups")
        assertThat(again.note).isEqualTo("Restored Push-ups")
        val row = checkNotNull(db.libraryDao().get("push-ups"))
        assertThat(row.archived).isFalse()
        assertThat(row.isCustom).isFalse()
    }

    @Test
    fun restoreExerciseAndRestoreDayBringArchivedItemsBack() = runBlocking<Unit> {
        editor.deleteExercise("push-ups")
        val sessionId = sessions.start(chestDayId, today, clubId = null)
        sessions.finish(sessionId)
        editor.deleteDay(chestDayId) // has a session: archived
        val others = db.planDao().getActiveDays().map { it.id }
        assertThat(db.planDao().observeArchivedDays().first().map { it.id }).containsExactly(chestDayId)
        assertThat(db.libraryDao().observeArchived().first().map { it.id }).containsExactly("push-ups")

        editor.restoreExercise("push-ups")
        editor.restoreDay(chestDayId)

        assertThat(db.libraryDao().observeArchived().first()).isEmpty()
        assertThat(db.planDao().observeArchivedDays().first()).isEmpty()
        assertThat(db.planDao().getActiveDays().map { it.id }).containsExactlyElementsIn(others + chestDayId).inOrder()
    }
}
