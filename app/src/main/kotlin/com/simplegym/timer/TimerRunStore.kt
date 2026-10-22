package com.simplegym.timer

import android.content.Context
import android.content.SharedPreferences
import com.simplegym.engine.EmomConfig
import com.simplegym.engine.IntervalConfig
import com.simplegym.engine.Phase
import com.simplegym.engine.TimerState
import com.simplegym.engine.emomState
import com.simplegym.engine.intervalState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Everything needed to recompute a timer from nothing but the clock: [startElapsedMs] and the pause fields are
 * `SystemClock.elapsedRealtime()` instants, so the position survives screen-off, lock and process death. Primitive
 * fields only: the engine module has no serialization plugin.
 *
 * [rounds] is used by both kinds; [roundSec] only by [EMOM], [workSec] and [restSec] only by [INTERVAL].
 */
@Serializable
data class TimerRun(
    val kind: String,
    val rounds: Int,
    val roundSec: Int,
    val workSec: Int,
    val restSec: Int,
    val startElapsedMs: Long,
    val pausedAtElapsedMs: Long?,
    val pausedTotalMs: Long,
    val sessionId: Long?,
    val slotId: Long?,
    val exerciseId: String?,
    /**
     * The round and phase whose start cue last played, or 0 / null while none has. Saved with the run so a restarted
     * service neither replays a cue nor skips one it never played; see [seedPrevState]. Old stored runs lack both.
     */
    val lastCuedRound: Int = 0,
    val lastCuedPhase: Phase? = null,
    /**
     * Set only on a mobility add-on run: the [com.simplegym.engine.MobilityArea] names to mark done when it ends, and
     * [moveNames], one per round, so the screen can name the move after a restart. Standalone runs leave both empty.
     */
    val mobilityAreas: List<String> = emptyList(),
    val moveNames: List<String> = emptyList(),
) {
    val isMobility: Boolean get() = mobilityAreas.isNotEmpty()

    companion object {
        const val EMOM = "EMOM"
        const val INTERVAL = "INTERVAL"
    }
}

/**
 * A slot-bound EMOM run that ended while no timer screen was watching: its set still has to be logged. [exerciseId] is
 * the variant that was timed.
 */
@Serializable
data class PendingLog(val sessionId: Long, val slotId: Long, val exerciseId: String)

/** The log a finished run leads to: only an EMOM run that belongs to a slot (not a standalone or interval run). */
fun TimerRun.pendingLog(): PendingLog? {
    if (kind != TimerRun.EMOM) return null
    return PendingLog(sessionId ?: return null, slotId ?: return null, exerciseId ?: return null)
}

fun TimerRun.emom(): EmomConfig = EmomConfig(rounds, roundSec)

fun TimerRun.interval(): IntervalConfig = IntervalConfig(workSec, restSec, rounds)

/** The timer's position after [elapsedMs] of running time (pauses already excluded). */
fun TimerRun.stateAt(elapsedMs: Long): TimerState =
    if (kind == TimerRun.INTERVAL) intervalState(interval(), elapsedMs) else emomState(emom(), elapsedMs)

/** Running time at [nowElapsedMs], excluding every pause (a pause in progress counts up to when it began). */
fun TimerRun.elapsedAt(nowElapsedMs: Long): Long =
    ((pausedAtElapsedMs ?: nowElapsedMs) - startElapsedMs - pausedTotalMs).coerceAtLeast(0)

/** How long the whole run takes without pauses. */
fun TimerRun.totalMs(): Long =
    if (kind == TimerRun.INTERVAL) rounds * (workSec + restSec) * 1000L else rounds * roundSec * 1000L

fun formatClock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** The one run in progress, kept in SharedPreferences so a killed process can find it again. */
class TimerRunStore(private val prefs: SharedPreferences) {

    /** Written with `commit()`: a run saved just before the process is killed must not be lost. */
    fun save(run: TimerRun) {
        prefs.edit().putString(KEY_RUN, json.encodeToString(run)).commit()
    }

    fun load(): TimerRun? {
        val text = prefs.getString(KEY_RUN, null) ?: return null
        return try {
            json.decodeFromString<TimerRun>(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_RUN).commit()
    }

    /** Written with `commit()` like the run. A newer pending log replaces an older one: there is only ever one. */
    fun savePendingLog(pending: PendingLog) {
        prefs.edit().putString(KEY_PENDING, json.encodeToString(pending)).commit()
    }

    fun pendingLog(): PendingLog? {
        val text = prefs.getString(KEY_PENDING, null) ?: return null
        return try {
            json.decodeFromString<PendingLog>(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Dismissing the banner: forget the pending log whatever it is. */
    fun clearPendingLog() {
        prefs.edit().remove(KEY_PENDING).commit()
    }

    /** Saving a log for [sessionId] / [slotId] settles the pending log only if it is for that slot. */
    fun clearPendingLog(sessionId: Long, slotId: Long) {
        val pending = pendingLog() ?: return
        if (pending.sessionId == sessionId && pending.slotId == slotId) clearPendingLog()
    }

    /** The pending log now, and again whenever it changes. Nothing is cached, so it is right whichever prefs file is behind it. */
    fun observePendingLog(): Flow<PendingLog?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_PENDING || key == null) trySend(pendingLog())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(pendingLog())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** Running time at [nowElapsedMs], excluding every pause (a pause in progress counts up to when it began). */
    fun elapsedMs(run: TimerRun, nowElapsedMs: Long): Long = run.elapsedAt(nowElapsedMs)

    /** Whether the notification permission was asked for already: it is asked once, at the first timer start. */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ASKED, value).apply()
        }

    companion object {
        private const val PREFS = "timer_run"
        private const val KEY_RUN = "run"
        private const val KEY_PENDING = "pending_log"
        private const val KEY_ASKED = "notification_permission_asked"
        private val json = Json { ignoreUnknownKeys = true }

        /** SharedPreferences hands out one instance per file, so every wrapper sees the same data. */
        fun from(context: Context): TimerRunStore =
            TimerRunStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}

/** A run that was in progress when its service died: [timer] is where it is now, recomputed from the clock. */
data class KilledRun(val run: TimerRun, val timer: TimerState)

/**
 * At app start: a stored run with no service behind it was killed (Samsung's background limits, process death).
 * A run that has since finished is cleared, leaving a pending log when it was for a slot. So is one from before a
 * reboot (elapsedRealtime restarts at boot, so its instants mean nothing now), but that leaves nothing. Otherwise the run is kept as it is, so resuming it keeps the time exact.
 */
fun detectKilledRun(store: TimerRunStore, isRunning: Boolean, nowElapsed: Long): KilledRun? {
    if (isRunning) return null
    val run = store.load() ?: return null
    if (nowElapsed < run.startElapsedMs || nowElapsed < (run.pausedAtElapsedMs ?: 0)) {
        store.clear()
        return null
    }
    val timer = run.stateAt(store.elapsedMs(run, nowElapsed))
    if (timer.finished) {
        // It ended while nothing was watching: the set it timed is still to be logged.
        run.pendingLog()?.let(store::savePendingLog)
        store.clear()
        return null
    }
    return KilledRun(run, timer)
}
