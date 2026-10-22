package com.simplegym.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplegym.data.ExerciseEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.clearHurt
import com.simplegym.domain.HurtService
import com.simplegym.domain.SettingsService
import com.simplegym.domain.effectiveIncrement
import com.simplegym.domain.INCREMENT_MESSAGE
import com.simplegym.domain.parseIncrementKg
import com.simplegym.engine.formatKg
import com.simplegym.io.BackupService
import com.simplegym.io.RestoreResult
import com.simplegym.io.StartingDataException
import com.simplegym.io.StartingDataImporter
import com.simplegym.ui.components.hurtBadgeLabels
import com.simplegym.ui.onboarding.MAX_AGE
import com.simplegym.ui.onboarding.MIN_AGE
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STOP_TIMEOUT_MS = 5_000L
private const val MAX_AGE_DIGITS = 3
private const val MAX_SEARCH_RESULTS = 8

private const val AGE_MESSAGE = "Enter an age from $MIN_AGE to $MAX_AGE."
private const val UNREADABLE_FILE = "That file could not be read."
private const val IMPORT_FAILED = "Could not import that file."
private const val BACKUP_FAILED = "Could not save the backup."
private const val RESTORE_FAILED = "Could not restore that file."

/** One equipment type's weight step. [text] is what the field shows; [custom] is false while it is the library default. */
data class EquipmentRowUi(val equipment: String, val text: String, val error: String?, val custom: Boolean)

/** One exercise in the override list. [custom] is true when it has its own step, which "Use default" removes. */
data class OverrideRowUi(val exerciseId: String, val name: String, val text: String, val error: String?, val custom: Boolean)

data class HurtRowUi(val exerciseId: String, val name: String, val badges: List<String>)

/** [message] is the outcome of the last import, backup or restore; [error] says whether it failed. */
data class ImportUi(val busy: Boolean = false, val message: String? = null, val error: Boolean = false)

/** [loaded] is false until the first read, so the form is not flashed empty. */
data class SettingsUi(
    val loaded: Boolean,
    val age: String,
    val ageError: String?,
    val lowerBackProtection: Boolean,
    val clubCount: Int,
    val equipment: List<EquipmentRowUi>,
    val query: String,
    val overrides: List<OverrideRowUi>,
    val hurts: List<HurtRowUi>,
    val import: ImportUi,
)

private val EMPTY = SettingsUi(
    loaded = false, age = "", ageError = null, lowerBackProtection = false, clubCount = 0, equipment = emptyList(),
    query = "", overrides = emptyList(), hurts = emptyList(), import = ImportUi(),
)

/** What the lifter is typing and doing right now, on top of what is stored. */
private data class Local(val drafts: Map<String, String>, val query: String, val import: ImportUi)

private const val AGE_KEY = "age"
private fun equipmentKey(equipment: String) = "eq:$equipment"
private fun exerciseKey(exerciseId: String) = "ex:$exerciseId"

/**
 * Profile, increments, hurt flags and starting-data import. A typed number is a draft kept as typed (so "2." can become
 * "2.5") and is saved the moment it is valid; an invalid one shows its message and saves nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val db: GymDatabase,
    private val settings: SettingsService,
    private val importer: StartingDataImporter,
    private val backup: BackupService,
    /** Reads the picked document's text, or null if it cannot be opened. Called off the main thread. */
    private val readFile: (Uri) -> String?,
    /** Writes [text] to the created document; throws if it cannot. Called off the main thread. */
    private val writeFile: (Uri, String) -> Unit,
    /** Stops a running timer; called when a restore is about to replace the data it points at. */
    private val stopTimer: () -> Unit,
    private val today: () -> LocalDate,
    /** Told the ids an import skipped as unknown (only when there are some), so Home can show them. */
    private val onSkippedIds: (List<String>) -> Unit = {},
) : ViewModel() {

    private val hurts = HurtService(db)
    private val drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    private val query = MutableStateFlow("")
    private val import = MutableStateFlow(ImportUi())

    /** The exercises with an active hurt flag; recomputed when the library changes, which is what clearing one does. */
    private val hurtRows = db.libraryDao().observeAll().mapLatest { library ->
        val byId = library.associateBy { it.id }
        hurts.activeHurts().mapNotNull { (id, counts) -> byId[id]?.let { HurtRowUi(id, it.name, hurtBadgeLabels(counts)) } }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    val ui: StateFlow<SettingsUi> = combine(
        db.settingsDao().observe(),
        db.libraryDao().observeAll(),
        db.clubDao().observeClubs(),
        hurtRows,
        combine(drafts, query, import, ::Local),
    ) { stored, library, clubs, hurtList, local ->
        val increments = stored?.equipmentIncrements.orEmpty()
        val ageText = local.drafts[AGE_KEY] ?: stored?.age?.toString().orEmpty()
        SettingsUi(
            loaded = stored != null,
            age = ageText,
            ageError = AGE_MESSAGE.takeIf { AGE_KEY in local.drafts && parseAge(ageText) == null },
            lowerBackProtection = stored?.lowerBackProtection == true,
            clubCount = clubs.size,
            equipment = equipmentRows(library, increments, local.drafts),
            query = local.query,
            overrides = overrideRows(library, increments, local),
            hurts = hurtList,
            import = local.import,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EMPTY)

    // ---------------------------------------------------------------- Profile

    fun setAge(text: String) {
        val typed = text.take(MAX_AGE_DIGITS)
        drafts.update { it + (AGE_KEY to typed) }
        parseAge(typed)?.let { age -> viewModelScope.launch { db.settingsDao().setAge(age) } }
    }

    fun setLowerBackProtection(on: Boolean) {
        viewModelScope.launch { db.settingsDao().setLowerBackProtection(on) }
    }

    private fun parseAge(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN_AGE..MAX_AGE }

    // ---------------------------------------------------------------- Increments

    fun setEquipmentIncrement(equipment: String, text: String) {
        drafts.update { it + (equipmentKey(equipment) to text) }
        parseIncrementKg(text)?.let { kg -> viewModelScope.launch { settings.setEquipmentIncrement(equipment, kg) } }
    }

    /** Back to the library default for [equipment]. */
    fun resetEquipmentIncrement(equipment: String) {
        drafts.update { it - equipmentKey(equipment) }
        viewModelScope.launch { settings.setEquipmentIncrement(equipment, null) }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun setExerciseIncrement(exerciseId: String, text: String) {
        drafts.update { it + (exerciseKey(exerciseId) to text) }
        parseIncrementKg(text)?.let { kg -> viewModelScope.launch { db.libraryDao().setIncrementOverride(exerciseId, kg) } }
    }

    /** The exercise follows its equipment type's step (or the library's) again. */
    fun clearExerciseIncrement(exerciseId: String) {
        drafts.update { it - exerciseKey(exerciseId) }
        viewModelScope.launch { db.libraryDao().setIncrementOverride(exerciseId, null) }
    }

    private fun equipmentRows(
        library: List<ExerciseEntity>,
        increments: Map<String, Double>,
        drafts: Map<String, String>,
    ): List<EquipmentRowUi> = library
        .filter { !it.archived && !it.isBodyweight && it.incrementKg != null }
        .groupBy { it.equipment }
        .toSortedMap()
        .map { (equipment, exercises) ->
            val custom = increments[equipment]
            // the library's step for this equipment: the most common one among its exercises
            val default = exercises.groupingBy { it.incrementKg }.eachCount().maxByOrNull { it.value }?.key
            val draft = drafts[equipmentKey(equipment)]
            EquipmentRowUi(
                equipment = equipment,
                text = draft ?: (custom ?: default)?.let(::formatKg).orEmpty(),
                error = INCREMENT_MESSAGE.takeIf { draft != null && parseIncrementKg(draft) == null },
                custom = custom != null,
            )
        }

    /** The exercises that have their own step, plus the (at most [MAX_SEARCH_RESULTS]) whose name matches the search. */
    private fun overrideRows(library: List<ExerciseEntity>, increments: Map<String, Double>, local: Local): List<OverrideRowUi> {
        val needle = local.query.trim().lowercase(Locale.ROOT)
        val candidates = library.filter { !it.archived && !it.isBodyweight }
        val matches = if (needle.isEmpty()) emptyList() else {
            candidates.filter { it.name.lowercase(Locale.ROOT).contains(needle) }.take(MAX_SEARCH_RESULTS)
        }
        return (candidates.filter { it.incrementOverrideKg != null } + matches).distinctBy { it.id }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { e ->
                val draft = local.drafts[exerciseKey(e.id)]
                OverrideRowUi(
                    exerciseId = e.id,
                    name = e.name,
                    text = draft ?: effectiveIncrement(e, increments)?.let(::formatKg).orEmpty(),
                    error = INCREMENT_MESSAGE.takeIf { draft != null && parseIncrementKg(draft) == null },
                    custom = e.incrementOverrideKg != null,
                )
            }
    }

    // ---------------------------------------------------------------- Hurt flags

    /** "Clear hurt flag": only logs after today count towards the exercise's badge again. */
    fun clearHurt(exerciseId: String) {
        viewModelScope.launch { db.libraryDao().clearHurt(exerciseId, today()) }
    }

    // ---------------------------------------------------------------- Data

    /**
     * True when the lifter already has logs or clubs. The importer adds the file's clubs and logs again rather than
     * merging them, so a second import duplicates both; the screen asks first when this is true.
     */
    suspend fun hasExistingData(): Boolean = db.logDao().count() > 0 || db.clubDao().observeClubs().first().isNotEmpty()

    /** Imports the picked starting-data file (the same importer onboarding uses), reporting the outcome inline. */
    fun importFrom(uri: Uri) {
        if (!beginImport()) return
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { runCatching { readFile(uri) }.getOrNull() }
                if (text == null) {
                    fail(UNREADABLE_FILE)
                } else {
                    val result = importer.import(text, today())
                    if (result.skippedUnknownIds.isNotEmpty()) onSkippedIds(result.skippedUnknownIds)
                    val entries = if (result.imported == 1) "1 entry" else "${result.imported} entries"
                    import.update { it.copy(message = "Imported $entries.", error = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StartingDataException) {
                fail(e.message ?: IMPORT_FAILED)
            } catch (e: IOException) {
                fail(UNREADABLE_FILE)
            } catch (e: Exception) {
                fail(IMPORT_FAILED)
            } finally {
                import.update { it.copy(busy = false) }
            }
        }
    }

    // Backup and restore share the import state: one operation at a time, one message.

    /** The name offered by the save dialog: simple-gym-2026-10-22.gymbackup. */
    fun backupFileName(): String = "simple-gym-${today()}.gymbackup"

    /** Writes a backup of every table to the document the lifter created, reporting the outcome inline. */
    fun saveBackupTo(uri: Uri) {
        if (!beginImport()) return
        viewModelScope.launch {
            try {
                val text = backup.export()
                withContext(Dispatchers.IO) { writeFile(uri, text) }
                import.update { it.copy(message = "Backup saved.", error = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(BACKUP_FAILED)
            } finally {
                import.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Replaces all data with the picked backup. The file is checked first; only then is a running timer stopped
     * (it points at sessions and slots that are about to be replaced) and the data swapped in one transaction.
     */
    fun restoreFrom(uri: Uri) {
        if (!beginImport()) return
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { runCatching { readFile(uri) }.getOrNull() }
                if (text == null) {
                    fail(UNREADABLE_FILE)
                } else {
                    when (val result = backup.restore(text, beforeApply = stopTimer)) {
                        is RestoreResult.Restored -> import.update { it.copy(message = "Backup restored.", error = false) }
                        is RestoreResult.Failed -> fail(result.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(RESTORE_FAILED)
            } finally {
                import.update { it.copy(busy = false) }
            }
        }
    }

    private fun beginImport(): Boolean {
        var started = false
        import.update {
            started = !it.busy
            if (started) ImportUi(busy = true) else it
        }
        return started
    }

    private fun fail(message: String) = import.update { it.copy(message = message, error = true) }
}
