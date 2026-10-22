package com.simplegym.data

import com.google.common.truth.Truth.assertThat
import com.simplegym.inMemoryDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The two single-statement flag updates: each changes only its own columns and never reads first. */
@RunWith(RobolectricTestRunner::class)
class SettingsDaoTest {
    private lateinit var db: GymDatabase

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDb()
        db.settingsDao().upsert(
            SettingsEntity(age = 41, lowerBackProtection = true, equipmentIncrements = mapOf("barbell" to 2.5)),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun settings() = checkNotNull(runBlocking { db.settingsDao().get() })

    @Test
    fun markTimerKilledSetsOnlyThatFlag() = runBlocking {
        val before = settings()

        db.settingsDao().markTimerKilled()

        assertThat(settings()).isEqualTo(before.copy(timerWasKilled = true))
    }

    @Test
    fun markTimerKilledIsIdempotent() = runBlocking {
        db.settingsDao().markTimerKilled()
        db.settingsDao().markTimerKilled()

        assertThat(settings().timerWasKilled).isTrue()
    }

    @Test
    fun markTimerKilledWithNoSettingsRowChangesNothing() = runBlocking {
        db.close()
        db = inMemoryDb()

        db.settingsDao().markTimerKilled()

        assertThat(db.settingsDao().get()).isNull()
    }

    @Test
    fun dismissBatteryCardDismissesAndClearsTheKilledFlagTogether() = runBlocking {
        db.settingsDao().markTimerKilled()
        val before = settings()

        db.settingsDao().dismissBatteryCard()

        assertThat(settings()).isEqualTo(before.copy(batteryCardDismissed = true, timerWasKilled = false))
    }
}
