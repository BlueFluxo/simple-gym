package com.simplegym.io

import com.google.common.truth.Truth.assertThat
import com.simplegym.data.GymDatabase
import com.simplegym.domain.TargetService
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.RangeType
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.testContainer
import java.io.File
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
class StartingDataTest {
    private lateinit var db: GymDatabase
    private lateinit var importer: StartingDataImporter
    private val today = LocalDate.of(2026, 10, 22)

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        testContainer(db).seed()
        importer = StartingDataImporter(db)
    }

    @After
    fun tearDown() = db.close()

    /** The committed example file; Gradle runs unit tests with the app module as the working directory. */
    private val exampleJson: String get() = File("../docs/examples/starting-data.example.json").readText()

    private fun file(
        weights: String = "",
        hurt: String = "",
        clubs: String = "",
        custom: String = "",
        version: Int = 1,
    ) = """{"version":$version,"profile":{"age":40,"lowerBackProtection":false},
        "clubs":[$clubs],"weights":[$weights],"hurt":[$hurt],"customExercises":[$custom]}"""

    private fun customExercise(id: String, muscle: String = "Chest") =
        """{"id":"$id","name":"Custom $id","primaryMuscle":"$muscle","equipment":"Barbell","swapGroup":"x",
        "lowerBackLoad":"low","jointNotes":"","stressesJoints":[],"isAssisted":false,"isBodyweight":false}"""

    @Test
    fun importingTheExampleFileCountsItsLogsAndSetsTheProfile() = runBlocking<Unit> {
        val result = importer.import(exampleJson, today)

        // 8 weights (one for the custom exercise) + 1 hurt entry, none unknown.
        assertThat(result.imported).isEqualTo(9)
        assertThat(result.skippedUnknownIds).isEmpty()
        assertThat(db.logDao().count()).isEqualTo(9)
        val settings = checkNotNull(db.settingsDao().get())
        assertThat(settings.age).isEqualTo(35)
        assertThat(settings.lowerBackProtection).isTrue()
        assertThat(settings.onboarded).isTrue()
    }

    @Test
    fun aWeightBecomesAnImportedSolidBaselineLogDatedToday() = runBlocking<Unit> {
        importer.import(file("""{"exerciseId":"machine-shoulder-press","weightKg":30,"reps":10}"""), today)

        val log = db.logDao().getForExercise("machine-shoulder-press").single()
        assertThat(log.imported).isTrue()
        assertThat(log.type).isEqualTo(SessionType.NORMAL)
        assertThat(log.feel).isEqualTo(Feel.SOLID)
        assertThat(log.rangeType).isEqualTo(RangeType.BASE)
        assertThat(log.date).isEqualTo(today)
        assertThat(log.weightKg).isEqualTo(30.0)
        assertThat(log.bestReps).isEqualTo(10)
        assertThat(log.sets).isEqualTo(3)
        assertThat(log.sessionId).isNull()
        assertThat(log.slotId).isNull()
    }

    @Test
    fun missingRepsAndSetsComeFromTheFirstSlotUsingTheExerciseElseTheDefaults() = runBlocking<Unit> {
        importer.import(
            file(
                """{"exerciseId":"ez-bar-curl","weightKg":25},
                   {"exerciseId":"dumbbell-bench-press","weightKg":20},
                   {"exerciseId":"pec-deck","weightKg":100}""",
            ),
            today,
        )

        // EZ-bar curl is a 4 x 6-10 slot; Dumbbell bench press is a swap of the 3 x 8-12 bench slot.
        val curl = db.logDao().getForExercise("ez-bar-curl").single()
        assertThat(listOf(curl.sets, curl.bestReps)).containsExactly(4, 6).inOrder()
        val dumbbell = db.logDao().getForExercise("dumbbell-bench-press").single()
        assertThat(listOf(dumbbell.sets, dumbbell.bestReps)).containsExactly(3, 8).inOrder()
        // Pec deck is in no starter slot: 3 sets, 8 reps.
        val pecDeck = db.logDao().getForExercise("pec-deck").single()
        assertThat(listOf(pecDeck.sets, pecDeck.bestReps)).containsExactly(3, 8).inOrder()
    }

    @Test
    fun anImportedBenchPressOfSixtyWithNoRepsHoldsSixtyAndAimsForNine() = runBlocking<Unit> {
        importer.import(file("""{"exerciseId":"bench-press","weightKg":60}"""), today)

        val benchSlot = db.planDao().getSlots(db.planDao().getActiveDays().first().id).first()
        val target = TargetService(db).targetFor(benchSlot.id, "bench-press", today)

        assertThat(target.weightKg).isEqualTo(60.0)
        assertThat(target.repGoal).isEqualTo(9)
    }

    @Test
    fun anUnknownExerciseIdIsSkippedAndReported() = runBlocking<Unit> {
        val result = importer.import(
            file(
                weights = """{"exerciseId":"bench-press","weightKg":60},{"exerciseId":"nope","weightKg":10}""",
                hurt = """{"exerciseId":"nope-either","joint":"KNEE"}""",
            ),
            today,
        )

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.skippedUnknownIds).containsExactly("nope", "nope-either")
        assertThat(db.logDao().count()).isEqualTo(1)
    }

    @Test
    fun aNewerVersionFailsWithTheExactMessageAndWritesNothing() {
        val error = assertThrows(StartingDataException::class.java) {
            runBlocking { importer.import(file(version = 2), today) }
        }

        assertThat(error).hasMessageThat().isEqualTo("This file was made by a newer version of Simple Gym.")
        assertThat(runBlocking { checkNotNull(db.settingsDao().get()).onboarded }).isFalse()
    }

    @Test
    fun aNewerFileWithAShapeWeDoNotKnowStillGetsTheVersionMessage() {
        val error = assertThrows(StartingDataException::class.java) {
            runBlocking { importer.import("""{"version":3,"somethingNew":[1,2]}""", today) }
        }

        assertThat(error).hasMessageThat().isEqualTo("This file was made by a newer version of Simple Gym.")
    }

    @Test
    fun aFileThatIsNotStartingDataFailsWithAReadableMessage() {
        val error = assertThrows(StartingDataException::class.java) {
            runBlocking { importer.import("not json at all", today) }
        }

        assertThat(error).hasMessageThat().isEqualTo("This is not a Simple Gym starting-data file.")
    }

    @Test
    fun clubsAreCreatedWithTheirUnavailableExercises() = runBlocking<Unit> {
        importer.import(exampleJson, today)

        val clubs = db.clubDao().observeClubs().first()
        assertThat(clubs.map { it.name }).containsExactly("Example Club One", "Example Club Two")
        val one = clubs.first { it.name == "Example Club One" }
        val two = clubs.first { it.name == "Example Club Two" }
        assertThat(db.clubDao().getUnavailable(one.id)).containsExactly("barbell-back-squat", "hip-thrust-barbell")
        assertThat(db.clubDao().getUnavailable(two.id)).containsExactly("lat-pulldown")
    }

    @Test
    fun aClubsUnknownUnavailableIdIsSkippedAndReported() = runBlocking<Unit> {
        val result = importer.import(file(clubs = """{"name":"A","unavailable":["lat-pulldown","ghost"]}"""), today)

        assertThat(result.skippedUnknownIds).containsExactly("ghost")
        val club = db.clubDao().observeClubs().first().single()
        assertThat(db.clubDao().getUnavailable(club.id)).containsExactly("lat-pulldown")
    }

    @Test
    fun hurtEntriesBecomeHeldLogsWithTheJointDatedToday() = runBlocking<Unit> {
        importer.import(file(hurt = """{"exerciseId":"upright-row-dumbbell","joint":"SHOULDER"}"""), today)

        val log = db.logDao().getForExercise("upright-row-dumbbell").single()
        assertThat(log.type).isEqualTo(SessionType.HELD)
        assertThat(log.hurt).isEqualTo(Joint.SHOULDER)
        assertThat(log.date).isEqualTo(today)
        assertThat(log.imported).isTrue()
        assertThat(log.weightKg).isNull()
    }

    @Test
    fun aWeightForACustomExerciseDefinedInTheSameFileIsImportedNotSkipped() = runBlocking<Unit> {
        val result = importer.import(exampleJson, today)

        assertThat(result.skippedUnknownIds).doesNotContain("landmine-press")
        val custom = checkNotNull(db.libraryDao().get("landmine-press"))
        assertThat(custom.isCustom).isTrue()
        assertThat(custom.name).isEqualTo("Landmine press")
        assertThat(db.logDao().getForExercise("landmine-press").single().weightKg).isEqualTo(20.0)
    }

    @Test
    fun aCustomExerciseWhoseIdAlreadyExistsIsSkippedAndTheLibraryEntryKept() = runBlocking<Unit> {
        importer.import(file(custom = customExercise("bench-press")), today)

        val bench = checkNotNull(db.libraryDao().get("bench-press"))
        assertThat(bench.name).isEqualTo("Bench press")
        assertThat(bench.isCustom).isFalse()
    }

    @Test
    fun anAssistedWeightIsStoredNegativeAndABodyweightWeightIsIgnored() = runBlocking<Unit> {
        importer.import(
            file("""{"exerciseId":"assisted-pull-up","weightKg":40},{"exerciseId":"chin-up","weightKg":10}"""),
            today,
        )

        assertThat(db.logDao().getForExercise("assisted-pull-up").single().weightKg).isEqualTo(-40.0)
        assertThat(db.logDao().getForExercise("chin-up").single().weightKg).isNull()
    }

    @Test
    fun unknownKeysAreIgnored() = runBlocking<Unit> {
        val json = """{"version":1,"exportedBy":"x","profile":{"age":40,"lowerBackProtection":true,"extra":1},
            "weights":[{"exerciseId":"bench-press","weightKg":60,"note":"hi"}]}"""

        assertThat(importer.import(json, today).imported).isEqualTo(1)
    }

    @Test
    fun theImportIsAllOrNothing() {
        // The second custom exercise has an unknown muscle and throws after the first one has already been written.
        val json = file(
            weights = """{"exerciseId":"bench-press","weightKg":60}""",
            clubs = """{"name":"A"}""",
            custom = customExercise("ok-one") + "," + customExercise("zzz", muscle = "Elbows"),
        )

        assertThrows(Exception::class.java) { runBlocking { importer.import(json, today) } }

        runBlocking {
            assertThat(db.logDao().count()).isEqualTo(0)
            assertThat(db.clubDao().observeClubs().first()).isEmpty()
            assertThat(checkNotNull(db.settingsDao().get()).onboarded).isFalse()
            assertThat(db.libraryDao().get("ok-one")).isNull()
        }
    }
}
