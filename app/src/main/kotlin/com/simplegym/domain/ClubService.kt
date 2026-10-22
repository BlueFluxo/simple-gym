package com.simplegym.domain

import androidx.room3.withWriteTransaction
import com.simplegym.data.ClubEntity
import com.simplegym.data.GymDatabase
import com.simplegym.data.setUnavailable

/** The lifter's gyms (spec 6.8): which one they are at now, and which exercises each lacks. */
class ClubService(private val db: GymDatabase) {

    /** Adds a club; a blank name is rejected with [IllegalArgumentException]. */
    suspend fun add(name: String): Long = db.clubDao().insert(ClubEntity(name = validName(name)))

    suspend fun rename(clubId: Long, name: String) = db.clubDao().rename(clubId, validName(name))

    /**
     * Makes [clubId] the club the lifter is at (null for none). The open session follows, so its swap chips change at
     * once instead of when the next session starts.
     */
    suspend fun select(clubId: Long?) = db.withWriteTransaction {
        db.settingsDao().setCurrentClub(clubId)
        db.sessionDao().setClubOfOpenSessions(clubId)
    }

    /**
     * Deletes the club and what it lacked (cascade). Neither the current club nor the open session may keep pointing at
     * it: the next "Not at this club" would then name a club that is gone.
     */
    suspend fun delete(clubId: Long) = db.withWriteTransaction {
        if (db.settingsDao().get()?.currentClubId == clubId) db.settingsDao().setCurrentClub(null)
        db.sessionDao().getOpenSessions().filter { it.clubId == clubId }
            .forEach { db.sessionDao().updateSession(it.copy(clubId = null)) }
        db.clubDao().delete(clubId)
    }

    suspend fun setUnavailable(clubId: Long, exerciseId: String, unavailable: Boolean) =
        db.clubDao().setUnavailable(clubId, exerciseId, unavailable)

    private fun validName(name: String): String = name.trim().also { require(it.isNotEmpty()) { "name" } }
}
