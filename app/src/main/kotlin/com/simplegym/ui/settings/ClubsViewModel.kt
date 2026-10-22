package com.simplegym.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplegym.data.GymDatabase
import com.simplegym.domain.ClubService
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MS = 5_000L
private const val NAME_MESSAGE = "Give the club a name"

/** An exercise a club lacks. */
data class MissingExerciseUi(val exerciseId: String, val name: String)

/** [name] is what the field shows (a rename in progress, else the stored name); a blank one is [nameError] and not saved. */
data class ClubRowUi(
    val id: Long,
    val name: String,
    val nameError: String?,
    val missing: List<MissingExerciseUi>,
)

/** [clubs] is null until the first read. */
data class ClubsUi(val clubs: List<ClubRowUi>?, val newNameError: String?)

class ClubsViewModel(db: GymDatabase, private val clubs: ClubService) : ViewModel() {
    private val renames = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val newNameError = MutableStateFlow<String?>(null)

    val ui: StateFlow<ClubsUi> = combine(
        db.clubDao().observeClubs(),
        db.clubDao().observeAllUnavailable(),
        db.libraryDao().observeAll(),
        renames,
        newNameError,
    ) { clubList, unavailable, library, renames, newNameError ->
        val names = library.associate { it.id to it.name }
        val missingByClub = unavailable.groupBy { it.clubId }
        ClubsUi(
            clubs = clubList.map { club ->
                val typed = renames[club.id]
                ClubRowUi(
                    id = club.id,
                    name = typed ?: club.name,
                    nameError = NAME_MESSAGE.takeIf { typed != null && typed.isBlank() },
                    missing = missingByClub[club.id].orEmpty()
                        .map { MissingExerciseUi(it.exerciseId, names[it.exerciseId] ?: it.exerciseId) }
                        .sortedBy { it.name.lowercase(Locale.ROOT) },
                )
            },
            newNameError = newNameError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ClubsUi(null, null))

    /** Adds a club, then runs [onAdded]; a blank name shows an inline error instead. */
    fun addClub(name: String, onAdded: () -> Unit) {
        viewModelScope.launch {
            try {
                clubs.add(name)
                newNameError.value = null
                onAdded()
            } catch (e: IllegalArgumentException) {
                newNameError.value = NAME_MESSAGE
            }
        }
    }

    fun clearNewNameError() {
        newNameError.value = null
    }

    /** Typing in a club's name field renames it as soon as the name is not blank. */
    fun rename(clubId: Long, name: String) {
        renames.update { it + (clubId to name) }
        if (name.isNotBlank()) viewModelScope.launch { clubs.rename(clubId, name) }
    }

    /** The exercise is back at the club. */
    fun markAvailable(clubId: Long, exerciseId: String) {
        viewModelScope.launch { clubs.setUnavailable(clubId, exerciseId, false) }
    }

    fun delete(clubId: Long) {
        renames.update { it - clubId }
        viewModelScope.launch { clubs.delete(clubId) }
    }
}
