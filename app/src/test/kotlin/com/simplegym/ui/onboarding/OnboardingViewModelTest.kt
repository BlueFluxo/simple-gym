package com.simplegym.ui.onboarding

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.simplegym.data.GymDatabase
import com.simplegym.inMemoryDb
import com.simplegym.io.StartingDataImporter
import com.simplegym.testContainer
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelTest {
    private lateinit var db: GymDatabase
    private lateinit var viewModel: OnboardingViewModel
    private val files = mutableMapOf<Uri, String?>()
    private val skippedReports = mutableListOf<List<String>>()

    @Before
    fun setUp() = runBlocking<Unit> {
        // viewModelScope runs on Dispatchers.Main.immediate; Dispatchers.Unconfined keeps these tests deterministic.
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDb()
        val container = testContainer(db)
        container.seed()
        viewModel = OnboardingViewModel(
            db = db,
            importer = StartingDataImporter(db),
            readFile = { uri -> files[uri] },
            seeded = container.seeded,
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

    private fun awaitSettled() = runBlocking {
        while (viewModel.state.value.busy) kotlinx.coroutines.delay(10)
    }

    private val onboarded get() = runBlocking { checkNotNull(db.settingsDao().get()).onboarded }

    @Test
    fun theManualFormOffersTheTwentyStarterDefaultsInPlanOrder() = runBlocking<Unit> {
        val rows = viewModel.state.first { it.rows.isNotEmpty() }.rows

        assertThat(rows).hasSize(20)
        assertThat(rows.first().exerciseId).isEqualTo("bench-press")
        assertThat(rows.map { it.exerciseId }).containsNoDuplicates()
        // The bodyweight chin-up is offered too, flagged so the form can say its weight is not used.
        assertThat(rows.single { it.exerciseId == "chin-up" }.bodyweight).isTrue()
        assertThat(rows.filter { it.bodyweight }.map { it.exerciseId }).containsExactly("chin-up")
    }

    @Test
    fun aFileWithAnUnknownIdFinishesOnboardingAndReportsTheSkippedId() {
        viewModel.importFrom(
            pick(
                "typo.json",
                """{"profile":{"age":30,"lowerBackProtection":false},
                    "weights":[{"exerciseId":"bench-press","weightKg":60},{"exerciseId":"benchpress","weightKg":50}]}""",
            ),
        )
        awaitSettled()

        assertThat(onboarded).isTrue()
        assertThat(skippedReports).containsExactly(listOf("benchpress"))
    }

    @Test
    fun aCleanFileReportsNothingSkipped() {
        viewModel.importFrom(pick("clean.json", File("../docs/examples/starting-data.example.json").readText()))
        awaitSettled()

        assertThat(onboarded).isTrue()
        assertThat(skippedReports).isEmpty()
    }

    @Test
    fun pickingTheExampleFileImportsItAndFinishesOnboarding() {
        val example = File("../docs/examples/starting-data.example.json").readText()

        viewModel.importFrom(pick("example.json", example))
        awaitSettled()

        assertThat(onboarded).isTrue()
        assertThat(runBlocking { db.logDao().count() }).isEqualTo(9)
        assertThat(viewModel.state.value.importError).isNull()
    }

    @Test
    fun aFileFromANewerVersionShowsTheExactMessageAndStaysOnWelcome() {
        viewModel.importFrom(pick("newer.json", """{"version":2,"profile":{"age":30,"lowerBackProtection":false}}"""))
        awaitSettled()

        assertThat(viewModel.state.value.importError).isEqualTo("This file was made by a newer version of Simple Gym.")
        assertThat(viewModel.state.value.manual).isFalse()
        assertThat(onboarded).isFalse()
    }

    @Test
    fun aFileThatIsNotStartingDataShowsAReadableMessage() {
        viewModel.importFrom(pick("junk.json", """{"hello":"world"}"""))
        awaitSettled()

        assertThat(viewModel.state.value.importError).isEqualTo("This is not a Simple Gym starting-data file.")
        assertThat(onboarded).isFalse()
    }

    @Test
    fun aFileThatCannotBeOpenedShowsAReadableMessage() {
        viewModel.importFrom(pick("gone.json", null))
        awaitSettled()

        assertThat(viewModel.state.value.importError).isEqualTo("That file could not be read.")
        assertThat(onboarded).isFalse()
    }
}
