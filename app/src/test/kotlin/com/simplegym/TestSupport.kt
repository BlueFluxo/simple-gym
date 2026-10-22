package com.simplegym

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.data.SettingsEntity
import com.simplegym.engine.Feel
import com.simplegym.engine.SessionType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

/** 2026-10-22 noon in the device zone, so [AppContainer.today] is stable whatever zone the tests run in. */
val TEST_NOW: Long = LocalDate.of(2026, 10, 22).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun inMemoryDb(context: Context = ApplicationProvider.getApplicationContext()): GymDatabase =
    Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
        .setDriver(AndroidSQLiteDriver())
        .allowMainThreadQueries()
        .build()

/** A container over an in-memory database: tests never touch the on-disk simple-gym.db. */
fun testContainer(db: GymDatabase = inMemoryDb(), clock: () -> Long = { TEST_NOW }): AppContainer =
    AppContainer(ApplicationProvider.getApplicationContext(), db, clock)

/** Robolectric application for activity tests: same wiring as [SimpleGymApp] but over an in-memory database. */
class TestGymApp : SimpleGymApp() {
    override fun createContainer(): AppContainer = testContainer()
}

/**
 * [rule] (a compose rule) with the in-memory database [db] closed after it, not before. A test that ends right after a
 * navigation leaves the new screen to compose, and its view model to start observing Room, while the rule disposes the
 * activity; a database closed in an @After (which runs first) made those queries fail with "Database is closed",
 * which showed up as a test failing about one run in three.
 */
fun composeRuleThenCloseDb(rule: TestRule, db: () -> GymDatabase): RuleChain =
    RuleChain.outerRule(object : ExternalResource() {
        override fun after() = db().close()
    }).around(rule)

/** Seeds the library and plan and marks onboarding done, so a screen test opens on Home rather than the welcome screen. */
suspend fun AppContainer.seedOnboarded() {
    seed()
    val settings = db.settingsDao()
    settings.upsert((settings.get() ?: SettingsEntity()).copy(onboarded = true))
}

/**
 * Plays one whole block's build rounds (3 at the default age) on [firstDate] and the days after it: every active day
 * once per round, each logging its first half of slots (rounded up). The deload round that follows is never played, so by any date
 * more than 14 days later the next block (index 1) has started.
 */
suspend fun AppContainer.completeBuildRounds(firstDate: LocalDate, rounds: Int = 3) {
    var date = firstDate
    repeat(rounds) {
        for (day in db.planDao().getActiveDays()) {
            val sessionId = sessionService.start(day.id, date, clubId = null)
            val slots = db.planDao().getSlots(day.id)
            for (slot in slots.take((slots.size + 1) / 2)) {
                db.logDao().insert(
                    LogEntity(
                        sessionId = sessionId, slotId = slot.id, exerciseId = slot.defaultExerciseId, date = date,
                        weightKg = 20.0, bestReps = 8, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
                    ),
                )
            }
            sessionService.finish(sessionId)
            date = date.plusDays(1)
        }
    }
}
