package com.simplegym.ui.settings

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.simplegym.data.ClubEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.LogEntity
import com.simplegym.engine.Feel
import com.simplegym.engine.SessionType
import com.simplegym.domain.SettingsService
import com.simplegym.inMemoryDb
import com.simplegym.io.BackupService
import com.simplegym.io.StartingDataImporter
import com.simplegym.testContainer
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings → Data → "Import starting data": the picker is the screen's, so the view model is driven with a fake reader. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsImportTest {
    private lateinit var db: GymDatabase
    private lateinit var viewModel: SettingsViewModel
    private val files = mutableMapOf<Uri, String?>()
    private val skippedReports = mutableListOf<List<String>>()

    @Before
    fun setUp() = runBlocking<Unit> {
        // viewModelScope runs on Dispatchers.Main.immediate; Dispatchers.Unconfined keeps these tests deterministic.
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDb()
        testContainer(db).seed()
        viewModel = SettingsViewModel(
            db = db,
            settings = SettingsService(db),
            importer = StartingDataImporter(db),
            backup = BackupService(db) { LocalDate.of(2026, 10, 22) },
            readFile = { uri -> files[uri] },
            writeFile = { _, _ -> },
            stopTimer = {},
            today = { LocalDate.of(2026, 10, 22) },
            onSkippedIds = { skippedReports += it },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun pick(name: String, content: String?): Uri = Uri.parse("content://test/$name").also { files[it] = content }

    private fun importResult(): ImportUi = runBlocking {
        viewModel.ui.first { it.import.message != null && !it.import.busy }.import
    }

    @Test
    fun importingAFileReportsTheCountAndUpdatesTheProfile() {
        viewModel.importFrom(
            pick(
                "ok.json",
                """{"profile":{"age":38,"lowerBackProtection":true},"weights":[{"exerciseId":"bench-press","weightKg":60}]}""",
            ),
        )

        assertThat(importResult()).isEqualTo(ImportUi(busy = false, message = "Imported 1 entry.", error = false))
        val settings = runBlocking { checkNotNull(db.settingsDao().get()) }
        assertThat(settings.age).isEqualTo(38)
        assertThat(settings.lowerBackProtection).isTrue()
        assertThat(skippedReports).isEmpty()
    }

    @Test
    fun unknownIdsAreReportedSoHomeCanShowThem() {
        viewModel.importFrom(
            pick(
                "typo.json",
                """{"profile":{"age":30,"lowerBackProtection":false},"weights":[{"exerciseId":"benchpress","weightKg":50}]}""",
            ),
        )
        importResult()

        assertThat(skippedReports).containsExactly(listOf("benchpress"))
    }

    @Test
    fun aFileThatIsNotStartingDataShowsTheReasonAndChangesNothing() {
        viewModel.importFrom(pick("bad.json", "[1, 2]"))

        val result = importResult()
        assertThat(result.error).isTrue()
        assertThat(result.message).isEqualTo("This is not a Simple Gym starting-data file.")
        assertThat(runBlocking { checkNotNull(db.settingsDao().get()).age }).isNull()
    }

    @Test
    fun anUnreadableFileShowsThatMessage() {
        viewModel.importFrom(pick("gone.json", null))

        assertThat(importResult().message).isEqualTo("That file could not be read.")
    }

    @Test
    fun aFreshInstallHasNoExistingDataSoImportDoesNotAsk() {
        assertThat(runBlocking { viewModel.hasExistingData() }).isFalse()
    }

    @Test
    fun existingClubsAloneMakeImportAsk() {
        runBlocking {
            db.clubDao().insert(ClubEntity(name = "Club A"))
            assertThat(viewModel.hasExistingData()).isTrue()
        }
    }

    @Test
    fun existingLogsAloneMakeImportAsk() {
        runBlocking {
            db.logDao().insert(
                LogEntity(
                    sessionId = null, slotId = null, exerciseId = "bench-press", date = LocalDate.of(2026, 10, 1),
                    weightKg = 60.0, bestReps = 8, sets = 3, feel = Feel.SOLID, type = SessionType.NORMAL,
                ),
            )
            assertThat(viewModel.hasExistingData()).isTrue()
        }
    }
}
