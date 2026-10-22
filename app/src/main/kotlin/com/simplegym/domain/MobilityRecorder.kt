package com.simplegym.domain

import android.util.Log
import androidx.room3.withWriteTransaction
import com.simplegym.data.GymDatabase
import com.simplegym.data.MobilityDoneEntity
import com.simplegym.timer.TimerController
import com.simplegym.timer.TimerRun
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Marks the areas of a finished mobility run done (one [MobilityDoneEntity] per area, dated the day it is recorded).
 * [record] writes a run once, however many times it is called, so the screen that watches the run end and the
 * app-wide [start] watcher (which covers a lifter who left the screen mid-routine) can both call it safely. A failed
 * write is logged and swallowed, so it never stops the watcher from recording later runs.
 */
class MobilityRecorder(private val db: GymDatabase, private val today: () -> LocalDate) {
    private companion object {
        const val TAG = "MobilityRecorder"
    }

    private val lock = Mutex()
    private var lastRecordedStart: Long? = null

    suspend fun record(run: TimerRun) = lock.withLock {
        if (!run.isMobility || lastRecordedStart == run.startElapsedMs) return@withLock
        try {
            val date = today()
            db.withWriteTransaction {
                run.mobilityAreas.forEach { db.sessionDao().insertMobilityDone(MobilityDoneEntity(area = it, date = date)) }
            }
            lastRecordedStart = run.startElapsedMs
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One failed write must not end the app-wide watcher (or crash the screen): log it and carry on, so a
            // later run is still recorded. The run is not marked recorded, so a retry of the same run can succeed.
            Log.w(TAG, "Could not record the mobility run", e)
        }
    }

    /** Records every mobility run that ends while the app process is alive, whichever screen is showing. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        TimerController.state.collect { state ->
            val run = state.run
            if (state.finished && run != null && run.isMobility) record(run)
        }
    }
}
