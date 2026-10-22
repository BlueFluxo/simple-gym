package com.simplegym.io

import androidx.room3.withWriteTransaction
import com.google.common.truth.Truth.assertThat
import com.simplegym.data.CheckInEntity
import com.simplegym.data.ClubEntity
import com.simplegym.data.ClubUnavailableEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.MobilityDoneEntity
import com.simplegym.data.SessionSlotEntity
import com.simplegym.data.SlotStatus
import com.simplegym.engine.Chip
import com.simplegym.engine.Feel
import com.simplegym.engine.Joint
import com.simplegym.engine.RangeType
import com.simplegym.engine.SessionType
import com.simplegym.inMemoryDb
import com.simplegym.testContainer
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupTest {
    private lateinit var db: GymDatabase
    private lateinit var service: BackupService
    private val today = LocalDate.of(2026, 10, 22)

    @Before
    fun setUp() = runBlocking<Unit> {
        db = inMemoryDb()
        testContainer(db).seed()
        service = BackupService(db) { today }
    }

    @After
    fun tearDown() = db.close()

    /** Every table, row for row. */
    private data class Snapshot(val tables: Map<String, List<Any>>) {
        val rows: Int get() = tables.values.sumOf { it.size }
    }

    private suspend fun snapshot(): Snapshot = with(db.backupDao()) {
        Snapshot(
            linkedMapOf(
                "exercise" to exercises(), "day" to days(), "slot" to slots(), "slot_swap" to slotSwaps(),
                "session" to sessions(), "session_slot" to sessionSlots(), "log" to logs(), "check_in" to checkIns(),
                "club" to clubs(), "club_unavailable" to clubUnavailable(), "mobility_done" to mobilityDone(),
                "settings" to settings(),
            ),
        )
    }

    private suspend fun wipe() = db.withWriteTransaction {
        with(db.backupDao()) {
            deleteLogs()
            deleteSessionSlots()
            deleteSessions()
            deleteSlotSwaps()
            deleteSlots()
            deleteDays()
            deleteClubUnavailable()
            deleteClubs()
            deleteCheckIns()
            deleteMobilityDone()
            deleteExercises()
            deleteSettings()
        }
    }

    /** Fills every table, including the fields added after schema 1: a plan, a finished session, an open one and a club. */
    private suspend fun fillEveryTable() {
        val plan = db.planDao()
        val day = plan.getActiveDays().first()
        val slots = plan.getSlots(day.id)
        val first = slots[0]

        // A custom, archived exercise with every optional field set.
        db.libraryDao().upsert(
            db.libraryDao().getAll().first().copy(
                id = "custom-test-lift", name = "Test Lift", isCustom = true, archived = true,
                incrementOverrideKg = 1.25, hurtClearedOn = LocalDate.of(2026, 9, 1),
            ),
        )
        plan.updateSlot(
            first.copy(timerRounds = 6, timerRoundSec = 90, rotationExerciseId = "custom-test-lift", rotationBlockIndex = 2),
        )

        val club = db.clubDao().insert(ClubEntity(name = "Test Club"))
        db.clubDao().markUnavailable(ClubUnavailableEntity(club, first.defaultExerciseId))

        val sessions = testContainer(db).sessionService
        val finished = sessions.start(day.id, LocalDate.of(2026, 10, 20), club)
        sessions.setStatus(finished, first.id, SlotStatus.DONE)
        sessions.setStatus(finished, slots[1].id, SlotStatus.SKIPPED)
        db.logDao().insert(
            LogEntity(
                sessionId = finished, slotId = first.id, exerciseId = first.defaultExerciseId,
                date = LocalDate.of(2026, 10, 20), weightKg = -42.5, bestReps = 9, sets = 3, feel = Feel.GRINDER,
                type = SessionType.DELOAD, rangeType = RangeType.HIGH_REP, hurt = Joint.KNEE, note = "felt a twinge",
                imported = true, loggedSlotId = first.id,
            ),
        )
        db.logDao().insert(
            LogEntity(
                sessionId = null, slotId = null, exerciseId = first.defaultExerciseId, date = LocalDate.of(2026, 10, 1),
                weightKg = null, bestReps = 12, sets = 2, feel = Feel.EASY, type = SessionType.HELD,
            ),
        )
        sessions.finish(finished)

        // An open session with a session-only (flush finisher) slot.
        val open = sessions.start(day.id, today, club)
        val finisher = plan.insertSlot(first.copy(id = 0, position = 99, sessionOnlyFor = open))
        db.sessionDao().upsertSessionSlot(SessionSlotEntity(open, finisher, flush = true))

        db.sessionDao().upsertCheckIn(CheckInEntity(today, setOf(Chip.BACK, Chip.CHEST)))
        db.sessionDao().insertMobilityDone(MobilityDoneEntity(area = "hips", date = today))

        db.settingsDao().upsert(
            db.settingsDao().get()!!.copy(
                age = 41, lowerBackProtection = true, currentClubId = club, equipmentIncrements = mapOf("barbell" to 2.5),
                onboarded = true, batteryCardDismissed = true, timerWasKilled = true,
            ),
        )
    }

    private fun edit(text: String, change: (MutableMap<String, JsonElement>) -> Unit): String {
        val map = Json.parseToJsonElement(text).jsonObject.toMutableMap()
        change(map)
        return Json.encodeToString(JsonObject.serializer(), JsonObject(map))
    }

    @Test
    fun everyTableRoundTripsExactly() = runBlocking {
        fillEveryTable()
        val before = snapshot()
        before.tables.forEach { (table, rows) -> assertThat(rows).isNotEmpty() }

        val json = service.export()
        wipe()
        assertThat(snapshot().rows).isEqualTo(0)

        val result = service.restore(json)

        val after = snapshot()
        assertThat(result).isEqualTo(RestoreResult.Restored(before.rows))
        before.tables.forEach { (table, rows) ->
            assertThat(after.tables.getValue(table)).hasSize(rows.size)
        }
        // A sample row of the richest table, field for field, then the whole of every table.
        val sample = before.tables.getValue("log").map { it as LogEntity }.first { it.hurt != null }
        assertThat(db.logDao().get(sample.id)).isEqualTo(sample)
        assertThat(after).isEqualTo(before)
        // Ids are kept, so references still resolve.
        assertThat(db.planDao().getSlot(sample.slotId!!)).isNotNull()
        assertThat(db.sessionDao().getSession(sample.sessionId!!)).isNotNull()
    }

    @Test
    fun exportWritesTheVersionAndDate() = runBlocking {
        val root = Json.parseToJsonElement(service.export()).jsonObject
        assertThat(root["version"]).isEqualTo(JsonPrimitive(1))
        assertThat(root["exportedOn"]).isEqualTo(JsonPrimitive("2026-10-22"))
    }

    @Test
    fun aBackupFromANewerVersionIsRefusedAndNothingChanges() = runBlocking {
        fillEveryTable()
        val before = snapshot()
        // Its other fields may have any shape: only the version is looked at.
        val future = """{"version":2,"exportedOn":"2027-01-01","logs":"a new shape"}"""

        var applied = false
        val result = service.restore(future) { applied = true }

        assertThat(result).isEqualTo(RestoreResult.Failed("This backup was made by a newer version of Simple Gym."))
        assertThat(applied).isFalse()
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun malformedFilesAreRefusedWithAClearMessageAndNothingChanges() = runBlocking {
        fillEveryTable()
        val before = snapshot()
        val good = service.export()
        val notBackup = RestoreResult.Failed("This is not a Simple Gym backup file.")

        val bad = listOf(
            "",
            "not json at all",
            "[1, 2]",
            good.take(good.length / 2),
            """{"profile":{"age":30}}""",
            """{"version":"one","exportedOn":"x"}""",
            """{"version":0,"exportedOn":"x"}""",
            edit(good) { it.remove("logs") },
            edit(good) { it["days"] = JsonArray(listOf(JsonPrimitive("day"))) },
            good.replace("\"2026-10-20\"", "\"20th October\""),
            good.replace("\"GRINDER\"", "\"EXHAUSTED\""),
        )
        for (text in bad) {
            var applied = false
            assertThat(service.restore(text) { applied = true }).isEqualTo(notBackup)
            assertThat(applied).isFalse()
            assertThat(snapshot()).isEqualTo(before)
        }
    }

    @Test
    fun aWriteThatFailsHalfWayRollsEverythingBack() = runBlocking {
        fillEveryTable()
        val before = snapshot()
        val good = service.export()
        // Readable, but a log names a slot that is not in the file: the foreign key fails after the old data is deleted.
        val dangling = edit(good) { map ->
            map["logs"] = JsonArray(
                map.getValue("logs").jsonArray.map { log -> JsonObject(log.jsonObject + ("slotId" to JsonPrimitive(987_654L))) },
            )
        }
        // Readable, but two days share an id.
        val duplicate = edit(good) { map ->
            val days = map.getValue("days").jsonArray
            map["days"] = JsonArray(days + days.first())
        }

        for (text in listOf(dangling, duplicate)) {
            assertThat(service.restore(text)).isEqualTo(
                RestoreResult.Failed("Could not restore this backup. Your data was not changed."),
            )
            assertThat(snapshot()).isEqualTo(before)
        }
    }

    @Test
    fun unknownFutureFieldsAreIgnored() = runBlocking {
        fillEveryTable()
        val before = snapshot()
        val extended = edit(service.export()) { map ->
            map["somethingNew"] = buildJsonObject { put("a", 1) }
            map["logs"] = JsonArray(
                map.getValue("logs").jsonArray.map { JsonObject(it.jsonObject + ("shiny" to JsonPrimitive(true))) },
            )
        }
        wipe()

        assertThat(service.restore(extended)).isInstanceOf(RestoreResult.Restored::class.java)
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun beforeApplyRunsOnceTheFileIsKnownGoodAndBeforeTheDataIsReplaced() = runBlocking {
        fillEveryTable()
        val json = service.export()
        wipe()

        var logsWhenCalled = -1
        service.restore(json) { logsWhenCalled = db.logDao().count() }

        assertThat(logsWhenCalled).isEqualTo(0)
        assertThat(db.logDao().count()).isGreaterThan(0)
    }

    @Test
    fun aBackupWithNoSettingsRowStillLeavesTheOne() = runBlocking {
        val noSettings = edit(service.export()) { it["settings"] = JsonArray(emptyList()) }

        assertThat(service.restore(noSettings)).isInstanceOf(RestoreResult.Restored::class.java)
        assertThat(db.settingsDao().get()).isNotNull()
    }
}
