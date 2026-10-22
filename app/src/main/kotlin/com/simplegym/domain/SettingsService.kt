package com.simplegym.domain

import androidx.room3.withWriteTransaction
import com.simplegym.data.GymDatabase

/** Edits to the settings row that need a read first. Single-column edits are SettingsDao statements. */
class SettingsService(private val db: GymDatabase) {

    /** Sets the lifter's weight step for [equipment], or (null) goes back to the library default. */
    suspend fun setEquipmentIncrement(equipment: String, kg: Double?) = db.withWriteTransaction {
        val current = db.settingsDao().get()?.equipmentIncrements.orEmpty()
        db.settingsDao().setEquipmentIncrements(if (kg == null) current - equipment else current + (equipment to kg))
    }
}
