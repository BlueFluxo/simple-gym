package com.simplegym.ui.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplegym.data.GymDatabase
import com.simplegym.io.ImportResult
import com.simplegym.io.ProfileDto
import com.simplegym.io.StartingDataException
import com.simplegym.io.StartingDataImporter
import com.simplegym.io.StartingDataV1
import com.simplegym.io.WeightDto
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MIN_AGE = 16
const val MAX_AGE = 99

private const val AGE_MESSAGE = "Enter an age from $MIN_AGE to $MAX_AGE."
private const val WEIGHT_MESSAGE = "Enter a weight in kg, or leave it blank."
private const val UNREADABLE_FILE = "That file could not be read."
private const val IMPORT_FAILED = "Could not import that file."

/** A digit string with an optional decimal point; nothing Java's parser would also take (NaN, 1e3, 5d). */
private val KG = Regex("""\d+\.?\d*|\.\d+""")

/** One of the starter plan's default exercises, offered for an optional starting weight. */
data class StarterWeightRow(val exerciseId: String, val name: String, val bodyweight: Boolean = false)

/**
 * [manual] is false on the welcome screen and true on the manual form. [errors] maps "age" or an exercise id to its
 * inline message; [importError] is the message for a file that could not be imported.
 */
data class OnboardingUiState(
    val manual: Boolean = false,
    val age: String = "",
    val lowerBackProtection: Boolean = false,
    val rows: List<StarterWeightRow> = emptyList(),
    val weights: Map<String, String> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val importError: String? = null,
    val busy: Boolean = false,
)

/**
 * Both paths end in [StartingDataImporter], which sets onboarded; the nav host reacts to that by showing Home. The
 * manual form simply builds the same data the file would have carried.
 */
class OnboardingViewModel(
    private val db: GymDatabase,
    private val importer: StartingDataImporter,
    /** Reads the picked document's text, or null if it cannot be opened. Called off the main thread. */
    private val readFile: (Uri) -> String?,
    private val seeded: Deferred<Unit>,
    private val today: () -> LocalDate,
    /** Told the ids an import skipped as unknown (only when there are some), so Home can show them after this screen is gone. */
    private val onSkippedIds: (List<String>) -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            seeded.await()
            _state.update { it.copy(rows = starterRows()) }
        }
    }

    fun showManual() = _state.update { it.copy(manual = true, importError = null) }

    fun showWelcome() = _state.update { it.copy(manual = false, errors = emptyMap()) }

    fun setAge(text: String) = _state.update { it.copy(age = text.take(MAX_AGE_DIGITS), errors = it.errors - "age") }

    fun setLowerBackProtection(on: Boolean) = _state.update { it.copy(lowerBackProtection = on) }

    fun setWeight(exerciseId: String, text: String) =
        _state.update { it.copy(weights = it.weights + (exerciseId to text), errors = it.errors - exerciseId) }

    /** Imports the picked document. A failure leaves the lifter on the welcome screen with the reason. */
    fun importFrom(uri: Uri) {
        if (!beginWork()) return
        viewModelScope.launch {
            try {
                seeded.await()
                val text = withContext(Dispatchers.IO) { runCatching { readFile(uri) }.getOrNull() }
                if (text == null) fail(UNREADABLE_FILE) else reportSkipped(importer.import(text, today()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: StartingDataException) {
                fail(e.message ?: IMPORT_FAILED)
            } catch (e: IOException) {
                fail(UNREADABLE_FILE)
            } catch (e: Exception) {
                fail(IMPORT_FAILED)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Checks the age and any weights typed, then imports them as the starting data. */
    fun submitManual() {
        val current = _state.value
        val age = current.age.trim().toIntOrNull()?.takeIf { it in MIN_AGE..MAX_AGE }
        val errors = buildMap {
            if (age == null) put("age", AGE_MESSAGE)
            current.weights.forEach { (id, text) -> if (text.isNotBlank() && parseKg(text) == null) put(id, WEIGHT_MESSAGE) }
        }
        if (errors.isNotEmpty() || age == null) {
            _state.update { it.copy(errors = errors) }
            return
        }
        if (!beginWork()) return
        val weights = current.rows.mapNotNull { row ->
            current.weights[row.exerciseId]?.let(::parseKg)?.let { WeightDto(row.exerciseId, it) }
        }
        viewModelScope.launch {
            try {
                seeded.await()
                reportSkipped(importer.import(StartingDataV1(profile = ProfileDto(age, current.lowerBackProtection), weights = weights), today()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(importError = IMPORT_FAILED) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun beginWork(): Boolean {
        var started = false
        _state.update {
            started = !it.busy
            if (started) it.copy(busy = true, importError = null) else it
        }
        return started
    }

    private fun reportSkipped(result: ImportResult) {
        if (result.skippedUnknownIds.isNotEmpty()) onSkippedIds(result.skippedUnknownIds)
    }

    private fun fail(message: String) = _state.update { it.copy(importError = message) }

    private fun parseKg(text: String): Double? {
        val normalised = text.trim().replace(',', '.')
        return if (KG.matches(normalised)) normalised.toDoubleOrNull()?.takeIf { it > 0 } else null
    }

    /**
     * The starter plan's default exercises in plan order, each once (the brief's 20). A bodyweight one is still offered;
     * the importer ignores its weight and the form says so.
     */
    private suspend fun starterRows(): List<StarterWeightRow> {
        val library = db.libraryDao().getAll().associateBy { it.id }
        val defaults = db.planDao().getActiveDays().flatMap { day -> db.planDao().getSlots(day.id) }
            .map { it.defaultExerciseId }.distinct()
        return defaults.mapNotNull { library[it] }
            .map { StarterWeightRow(it.id, it.name, it.isBodyweight) }
    }

    private companion object {
        const val MAX_AGE_DIGITS = 3
    }
}
