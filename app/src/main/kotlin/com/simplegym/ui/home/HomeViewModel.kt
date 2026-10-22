package com.simplegym.ui.home

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplegym.data.ClubEntity
import com.simplegym.data.DayEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.SessionEntity
import com.simplegym.data.SettingsEntity
import com.simplegym.domain.BlockService
import com.simplegym.domain.CheckInService
import com.simplegym.domain.ClubService
import com.simplegym.domain.SessionService
import com.simplegym.engine.Chip
import com.simplegym.engine.DayCard
import com.simplegym.engine.headerText
import com.simplegym.engine.suggestDay
import com.simplegym.ui.components.PendingLogUi
import com.simplegym.ui.components.displayName
import com.simplegym.ui.components.toUi
import com.simplegym.timer.TimerController
import com.simplegym.timer.TimerRun
import com.simplegym.timer.TimerRunStore
import com.simplegym.timer.detectKilledRun
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DayCardUi(
    val dayId: Long,
    val name: String,
    /** "Last done 4 days ago" or "Not done yet". */
    val lastDoneText: String,
    /** "Stiff · flush option" while a chip of this day is stiff today. */
    val statusText: String?,
    /** What the check-in history says about recovery, e.g. "Legs: usually loose after 3 days". */
    val recoveryTexts: List<String> = emptyList(),
)

/**
 * [suggestedDayId] is the day to feature: the day of the open session when there is one (its button reads
 * "Resume"), otherwise [suggestDay]: the unblocked day done longest ago. [stiff] are today's check-in chips and
 * [flushPrompt] is set when every day is blocked by one.
 */
data class HomeState(
    val days: List<DayCardUi>,
    val suggestedDayId: Long?,
    val activeSessionId: Long?,
    val header: String,
    /** The battery-settings card is shown until dismissed, and again whenever a timer was killed early. */
    val showBatteryCard: Boolean = false,
    /** A run whose service died: Home offers to resume it. */
    val interruptedTimer: TimerRun? = null,
    val stiff: Set<Chip> = emptySet(),
    val flushPrompt: Boolean = false,
    /** Every club; the switcher is shown only when there is at least one. */
    val clubs: List<ClubEntity> = emptyList(),
    /** The club the lifter is at, or null for "No club" (nothing is filtered out as unavailable). */
    val currentClubId: Long? = null,
)

/** Everything Home observes besides today's check-in. */
private data class HomeInputs(
    val days: List<DayEntity>,
    val finished: List<SessionEntity>,
    val open: SessionEntity?,
    val settings: SettingsEntity?,
    val killed: TimerRun?,
)

class HomeViewModel(
    private val db: GymDatabase,
    private val sessions: SessionService,
    private val blocks: BlockService,
    private val checkIns: CheckInService,
    private val clubs: ClubService,
    private val seeded: Deferred<Unit>,
    private val clock: () -> Long,
    private val today: () -> LocalDate,
    private val timerStore: TimerRunStore,
    private val skippedImportIds: MutableStateFlow<List<String>> = MutableStateFlow(emptyList()),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val isTimerRunning: () -> Boolean = TimerController::isServiceRunning,
) : ViewModel() {

    private val interrupted = MutableStateFlow<TimerRun?>(null)

    // flow { } so that "today" is read each time the screen starts collecting, not once when the view model is made
    private val stiffToday = flow { emitAll(checkIns.today(today())) }

    val state: StateFlow<HomeState> = combine(
        combine(
            db.planDao().observeActiveDays(),
            db.sessionDao().observeFinishedSessions(),
            db.sessionDao().observeOpenSession(),
            db.settingsDao().observe(),
            interrupted,
            ::HomeInputs,
        ),
        stiffToday,
        db.clubDao().observeClubs(),
    ) { inputs, stiff, clubList ->
        val block = blocks.current(today())
        buildState(inputs.days, inputs.finished, inputs.open, stiff, block.doneThisRound).copy(
            header = headerText(block),
            showBatteryCard = inputs.settings != null &&
                (!inputs.settings.batteryCardDismissed || inputs.settings.timerWasKilled),
            interruptedTimer = inputs.killed,
            clubs = clubList,
            // a club id that no longer exists reads as "No club"
            currentClubId = inputs.settings?.currentClubId?.takeIf { id -> clubList.any { it.id == id } },
        )
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            HomeState(days = emptyList(), suggestedDayId = null, activeSessionId = null, header = ""),
        )

    /** A set whose timer ended unseen and is still to be logged: Home offers "Log <exercise>" until saved or dismissed. */
    val pendingLog: StateFlow<PendingLogUi?> = timerStore.observePendingLog()
        .map { it?.toUi(db) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun dismissPendingLog() = timerStore.clearPendingLog()

    /** One tap on a check-in chip: stiff today, or not. Optional; Start never waits on it. */
    fun toggleStiff(chip: Chip) {
        viewModelScope.launch {
            seeded.await()
            checkIns.toggle(today(), chip)
        }
    }

    /** Ids a starting-data import skipped as unknown; Home shows a banner until the lifter dismisses it. */
    val importSkippedIds: StateFlow<List<String>> = skippedImportIds.asStateFlow()

    fun dismissImportNotice() {
        skippedImportIds.value = emptyList()
    }

    /** One tap on the club switcher: [clubId] (null for "No club") becomes the current club, and the open session's. */
    fun selectClub(clubId: Long?) {
        viewModelScope.launch {
            seeded.await()
            clubs.select(clubId)
        }
    }

    private var starting = false

    /** Starts [dayId] with the current club, then reports the new session id (on the main thread). */
    fun start(dayId: Long, onStarted: (sessionId: Long) -> Unit) {
        if (starting) return
        starting = true
        viewModelScope.launch {
            try {
                seeded.await()
                val clubId = db.settingsDao().get()?.currentClubId
                onStarted(sessions.start(dayId, today(), clubId))
            } finally {
                starting = false
            }
        }
    }

    /**
     * Looks for a timer whose service died (a stored run with no service behind it). The first time one is found it
     * also marks [com.simplegym.data.SettingsEntity.timerWasKilled], which brings the battery card back. Call it
     * whenever Home comes to the front.
     */
    fun refreshInterruptedTimer() {
        viewModelScope.launch {
            seeded.await()
            val killed = detectKilledRun(timerStore, isTimerRunning(), elapsedRealtime())
            if (killed != null) db.settingsDao().markTimerKilled()
            interrupted.value = killed?.run
        }
    }

    fun dismissBatteryCard() {
        viewModelScope.launch {
            seeded.await()
            db.settingsDao().dismissBatteryCard()
        }
    }

    private suspend fun buildState(
        days: List<DayEntity>,
        finished: List<SessionEntity>,
        open: SessionEntity?,
        stiff: Set<Chip>,
        doneThisRound: Set<Long>,
    ): HomeState {
        val now = today()
        // only a session with a real (non-flush) log trained anything: an abandoned or flush-only one is not "last done"
        val trainedIds = db.logDao().getTrainedSessionIds().toSet()
        val lastDone = finished.filter { it.id in trainedIds }.groupBy { it.dayId }
            .mapValues { (_, list) -> list.maxOf { it.date } }
        // An open session whose day is no longer in the plan cannot be shown on a card, so it is not offered.
        val active = open?.takeIf { s -> !SessionService.isStale(s, clock()) && days.any { it.id == s.dayId } }
        val typical = checkIns.typicalStiffDays()
        val suggestion = suggestDay(days.map { DayCard(it.id, it.chips, lastDone[it.id]) }, stiff, doneThisRound, typical, now)
        return HomeState(
            days = days.map { day ->
                DayCardUi(
                    dayId = day.id,
                    name = day.name,
                    lastDoneText = lastDoneText(lastDone[day.id], now),
                    statusText = STIFF_STATUS.takeIf { day.id in suggestion.blocked },
                    recoveryTexts = Chip.entries.filter { it in day.chips }.mapNotNull { chip ->
                        typical[chip]?.let { recoveryText(chip, it) }
                    },
                )
            },
            suggestedDayId = active?.dayId ?: suggestion.dayId,
            activeSessionId = active?.id,
            header = "",
            stiff = stiff,
            flushPrompt = active == null && suggestion.flushPrompt,
        )
    }

    private fun recoveryText(chip: Chip, days: Int) =
        "${chip.displayName()}: usually loose after $days ${if (days == 1) "day" else "days"}"

    private fun lastDoneText(lastDone: LocalDate?, today: LocalDate): String {
        if (lastDone == null) return "Not done yet"
        return when (val ago = ChronoUnit.DAYS.between(lastDone, today)) {
            0L -> "Last done today"
            1L -> "Last done yesterday"
            else -> "Last done $ago days ago"
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val STIFF_STATUS = "Stiff · flush option"
    }
}
