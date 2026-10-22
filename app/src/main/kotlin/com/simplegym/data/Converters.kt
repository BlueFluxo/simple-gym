package com.simplegym.data

import androidx.room3.ColumnTypeConverter
import com.simplegym.engine.Chip
import com.simplegym.engine.Joint
import java.time.LocalDate
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Sets are stored as comma-joined enum names, dates as epoch days and maps as JSON. */
class Converters {
    @ColumnTypeConverter
    fun fromEpochDay(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    @ColumnTypeConverter
    fun toEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @ColumnTypeConverter
    fun fromJoints(value: String): Set<Joint> = splitNames(value).map { Joint.valueOf(it) }.toSet()

    @ColumnTypeConverter
    fun toJoints(joints: Set<Joint>): String = joints.joinToString(",") { it.name }

    @ColumnTypeConverter
    fun fromChips(value: String): Set<Chip> = splitNames(value).map { Chip.valueOf(it) }.toSet()

    @ColumnTypeConverter
    fun toChips(chips: Set<Chip>): String = chips.joinToString(",") { it.name }

    @ColumnTypeConverter
    fun fromIncrementsJson(value: String): Map<String, Double> = assetJson.decodeFromString(INCREMENTS, value)

    @ColumnTypeConverter
    fun toIncrementsJson(map: Map<String, Double>): String = assetJson.encodeToString(INCREMENTS, map)

    @ColumnTypeConverter
    fun fromIdSet(value: String?): Set<Long>? =
        value?.let { v -> if (v.isEmpty()) emptySet() else v.split(',').map(String::toLong).toSet() }

    @ColumnTypeConverter
    fun toIdSet(ids: Set<Long>?): String? = ids?.sorted()?.joinToString(",")

    private fun splitNames(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(',')

    private companion object {
        val INCREMENTS = MapSerializer(String.serializer(), Double.serializer())
    }
}
