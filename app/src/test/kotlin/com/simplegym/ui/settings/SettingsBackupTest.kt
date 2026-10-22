package com.simplegym.ui.settings

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.simplegym.data.ClubEntity
import com.simplegym.data.GymDatabase
import com.simplegym.domain.SettingsService
import com.simplegym.inMemoryDb
import com.simplegym.io.BackupService
import com.simplegym.io.StartingDataImporter
import com.simplegym.testContainer
import java.io.IOException
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

/** Settings → Data → "Save backup" and "Restore backup": the pickers are the screen's, so the view model gets fake files. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsBackupTest {
    private lateinit var db: GymDatabase
    private lateinit var viewModel: SettingsViewModel
    private val files = mutableMapOf<Uri, String?>()
    private val written = mutableMapOf<Uri, String>()
    private var writeFails = false
    private var timersStopped = 0

    @Before
    fun setUp() = runBlocking<Unit> {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDb()
        testContainer(db).seed()
        viewModel = SettingsViewModel(
            db = db,
            settings = SettingsService(db),
            importer = StartingDataImporter(db),
            backup = BackupService(db) { LocalDate.of(2026, 10, 22) },
            readFile = { uri -> files[uri] },
            writeFile = { uri, text ->
                if (writeFails) throw IOException("disk full")
                written[uri] = text
            },
            stopTimer = { timersStopped++ },
            today = { LocalDate.of(2026, 10, 22) },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun uri(name: String): Uri = Uri.parse("content://test/$name")

    private fun outcome(): ImportUi = runBlocking { viewModel.ui.first { it.import.message != null && !it.import.busy }.import }

    @Test
    fun theSaveDialogOffersADatedGymbackupName() {
        assertThat(viewModel.backupFileName()).isEqualTo("simple-gym-2026-10-22.gymbackup")
    }

    @Test
    fun savingWritesTheBackupToTheChosenDocument() {
        val target = uri("out")

        viewModel.saveBackupTo(target)

        assertThat(outcome()).isEqualTo(ImportUi(busy = false, message = "Backup saved.", error = false))
        assertThat(written.getValue(target)).contains("\"version\":1")
    }

    @Test
    fun aFailedWriteIsReportedInline() {
        writeFails = true

        viewModel.saveBackupTo(uri("out"))

        assertThat(outcome().message).isEqualTo("Could not save the backup.")
        assertThat(outcome().error).isTrue()
    }

    @Test
    fun restoringReplacesTheDataAndStopsTheTimerFirst() = runBlocking {
        db.clubDao().insert(ClubEntity(name = "Saved Club"))
        viewModel.saveBackupTo(uri("out"))
        outcome()
        db.clubDao().insert(ClubEntity(name = "Later Club"))
        files[uri("in")] = written.getValue(uri("out"))

        viewModel.restoreFrom(uri("in"))

        assertThat(outcome()).isEqualTo(ImportUi(busy = false, message = "Backup restored.", error = false))
        assertThat(db.clubDao().observeClubs().first().map { it.name }).containsExactly("Saved Club")
        assertThat(timersStopped).isEqualTo(1)
    }

    @Test
    fun aFileThatIsNotABackupChangesNothingAndLeavesTheTimerAlone() = runBlocking {
        db.clubDao().insert(ClubEntity(name = "Kept Club"))
        files[uri("bad")] = """{"profile":{"age":30,"lowerBackProtection":false}}"""

        viewModel.restoreFrom(uri("bad"))

        assertThat(outcome().message).isEqualTo("This is not a Simple Gym backup file.")
        assertThat(outcome().error).isTrue()
        assertThat(db.clubDao().observeClubs().first().map { it.name }).containsExactly("Kept Club")
        assertThat(timersStopped).isEqualTo(0)
    }

    @Test
    fun aNewerBackupShowsTheVersionMessage() {
        files[uri("new")] = """{"version":2,"exportedOn":"2027-01-01"}"""

        viewModel.restoreFrom(uri("new"))

        assertThat(outcome().message).isEqualTo("This backup was made by a newer version of Simple Gym.")
    }

    @Test
    fun anUnreadableFileShowsThatMessage() {
        files[uri("gone")] = null

        viewModel.restoreFrom(uri("gone"))

        assertThat(outcome().message).isEqualTo("That file could not be read.")
    }
}
