package com.simplegym.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Schema 1 (the M1 APK) to schema 2 (sessions remember the days they started with) to schema 3 (logs remember the plan
 * slot they were recorded for). The old file is built from the exported schema JSON, then opened through the real
 * [GymDatabase], which runs the AutoMigrations and validates the result against the schema it was compiled with. (room3-testing's MigrationTestHelper reads the schemas from the
 * test APK's assets, which a local unit test does not have.)
 */
@RunWith(RobolectricTestRunner::class)
class GymDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"
    private var db: GymDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(dbName)
    }

    private fun createVersionOne() = createVersion(1)

    /** Creates [dbName] as the schema [version] database an older APK would have left behind, with one finished session in it. */
    private fun createVersion(version: Int, extra: (SQLiteDatabase) -> Unit = {}) {
        val schema = Json.parseToJsonElement(File("schemas/com.simplegym.data.GymDatabase/$version.json").readText()).jsonObject
        val database = schema.getValue("database").jsonObject
        assertThat(database.getValue("version").jsonPrimitive.content).isEqualTo(version.toString())

        val sqlite = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        try {
            database.getValue("entities").jsonArray.map { it.jsonObject }.forEach { entity ->
                val table = entity.getValue("tableName").jsonPrimitive.content
                sqlite.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                (entity["indices"] as? JsonArray).orEmpty().map { it.jsonObject }.forEach { index ->
                    sqlite.execSQL(index.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            database.getValue("setupQueries").jsonArray.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
            sqlite.execSQL(
                "INSERT INTO session (id, dayId, date, startedAtMillis, finishedAtMillis, clubId, slotsTotal) " +
                    "VALUES (7, 3, 20000, 1000, 2000, NULL, 5)",
            )
            extra(sqlite)
            sqlite.version = version
        } finally {
            sqlite.close()
        }
    }

    private fun open(): GymDatabase =
        Room.databaseBuilder(context, GymDatabase::class.java, dbName)
            .setDriver(AndroidSQLiteDriver())
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    @Test
    fun aSchemaOneSessionSurvivesWithNoStoredDays() = runBlocking<Unit> {
        createVersionOne()

        val session = checkNotNull(open().sessionDao().getSession(7))

        assertThat(session.dayId).isEqualTo(3)
        assertThat(session.date).isEqualTo(LocalDate.ofEpochDay(20000))
        assertThat(session.startedAtMillis).isEqualTo(1000)
        assertThat(session.finishedAtMillis).isEqualTo(2000)
        assertThat(session.slotsTotal).isEqualTo(5)
        assertThat(session.activeDayIdsAtStart).isNull()
    }

    @Test
    fun aMigratedDatabaseKeepsAcceptingNewSessions() = runBlocking<Unit> {
        createVersionOne()
        val migrated = open()

        val id = migrated.sessionDao().insertSession(
            SessionEntity(dayId = 3, date = LocalDate.ofEpochDay(20001), startedAtMillis = 5, slotsTotal = 4, activeDayIdsAtStart = setOf(3, 1)),
        )

        assertThat(checkNotNull(migrated.sessionDao().getSession(id)).activeDayIdsAtStart).containsExactly(1L, 3L)
        assertThat(checkNotNull(migrated.sessionDao().getSession(7)).activeDayIdsAtStart).isNull()
    }

    @Test
    fun aSchemaOneDatabaseMigratesAllTheWayToTheCurrentVersion() = runBlocking<Unit> {
        createVersionOne()

        assertThat(checkNotNull(open().sessionDao().getSession(7)).slotsTotal).isEqualTo(5)
    }

    @Test
    fun aSchemaTwoLogSurvivesWithNoLoggedSlotAndNewLogsRememberTheirs() = runBlocking<Unit> {
        createVersion(2) { sqlite ->
            sqlite.execSQL(
                "INSERT INTO log (id, sessionId, slotId, exerciseId, date, weightKg, bestReps, sets, feel, type, rangeType, " +
                    "hurt, note, imported) VALUES (11, 7, NULL, 'bench-press', 20000, 60.0, 8, 3, 'SOLID', 'NORMAL', 'BASE', NULL, NULL, 0)",
            )
        }
        val migrated = open()

        val old = migrated.logDao().observeForSession(7).first().single()
        assertThat(old.exerciseId).isEqualTo("bench-press")
        assertThat(old.weightKg).isEqualTo(60.0)
        assertThat(old.loggedSlotId).isNull()

        migrated.logDao().insert(old.copy(id = 0, loggedSlotId = 42))
        assertThat(migrated.logDao().observeForSession(7).first().mapNotNull { it.loggedSlotId }).containsExactly(42L)
    }
}
