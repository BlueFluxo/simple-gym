package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class MobilityTest {
    private fun move(id: String, group: String) = Exercise(
        id = id, name = id, muscle = Muscle.MOBILITY, equipment = "Bodyweight", swapGroup = group, incrementKg = null,
        backLoad = BackLoad.LOW, joints = emptySet(), isAssisted = false, isBodyweight = true,
    )

    /** The bundled library's mobility and core groups (same sizes and order), plus a non-mobility exercise. */
    private val library: List<Exercise> = listOf(
        move("bench-press", "chest-press"),
        move("leg-raise-1", "core-leg-raise"), move("leg-raise-2", "core-leg-raise"), move("leg-raise-3", "core-leg-raise"),
        move("plank", "core-stability"), move("side-plank", "core-stability"),
        move("pallof", "core-stability"), move("band-pallof", "core-stability"),
        move("chin-tucks", "mobility-neck"), move("neck-rotations", "mobility-neck"), move("neck-side", "mobility-neck"),
        move("cat-cow", "mobility-lowerback"), move("childs-pose", "mobility-lowerback"),
        move("pelvic-tilt", "mobility-lowerback"), move("knee-to-chest", "mobility-lowerback"),
        move("bird-dog", "stability-lowerback"), move("dead-bug", "stability-lowerback"),
        move("90-90", "mobility-hips"), move("hip-flexor", "mobility-hips"), move("figure-four", "mobility-hips"),
        move("wall-kicks", "hip-dynamic"), move("high-kicks", "hip-dynamic"), move("leg-swings", "hip-dynamic"),
        move("thread", "mobility-shoulders"), move("doorway", "mobility-shoulders"), move("wall-slides", "mobility-shoulders"),
    )

    private fun areaOf(move: Exercise): MobilityArea =
        MobilityArea.entries.first { move.swapGroup in it.swapGroups }

    private val today = LocalDate.of(2026, 10, 22)

    @Test
    fun neckAloneRepeatsItsThreeMovesForThreePasses() {
        val routine = buildRoutine(listOf(MobilityArea.NECK), library)

        assertThat(routine.moves.map { it.id })
            .containsExactlyElementsIn(List(3) { listOf("chin-tucks", "neck-rotations", "neck-side") }.flatten())
            .inOrder()
        assertThat(routine.workSec).isEqualTo(30)
        assertThat(routine.restSec).isEqualTo(10)
    }

    @Test
    fun passesStopAsSoonAsTheRoutineReachesEightMinutes() {
        // 6 moves x 40 s = 240 s a pass: two passes reach 480 s, a third is not needed.
        val routine = buildRoutine(listOf(MobilityArea.LOWER_BACK), library)

        assertThat(routine.moves).hasSize(12)
        assertThat(routine.moves.take(6)).isEqualTo(routine.moves.drop(6))
    }

    @Test
    fun aPassOfNineMovesIsToppedUpToEightMinutesNotRepeatedWhole() {
        // Neck (3) + lower back (6) = 9 moves, 360 s a pass: 12 moves reach exactly 480 s, not a second full pass (720 s).
        val routine = buildRoutine(listOf(MobilityArea.NECK, MobilityArea.LOWER_BACK), library)

        assertThat(routine.moves).hasSize(12)
        val pass = routine.moves.take(9)
        assertThat(pass.map { it.id }).containsExactly(
            "chin-tucks", "neck-rotations", "neck-side",
            "cat-cow", "childs-pose", "pelvic-tilt", "knee-to-chest", "bird-dog", "dead-bug",
        ).inOrder()
        assertThat(routine.moves.drop(9)).isEqualTo(pass.take(3))
        assertThat(routine.moves.size * (routine.workSec + routine.restSec)).isEqualTo(480)
    }

    @Test
    fun allFiveAreasAreCappedAtFifteenMovesWithEveryAreaPresent() {
        val routine = buildRoutine(MobilityArea.entries, library)

        assertThat(routine.moves).hasSize(15)
        assertThat(routine.moves.map(::areaOf).toSet()).isEqualTo(MobilityArea.entries.toSet())
        assertThat(routine.moves.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun theCapTakesMovesRoundRobinAcrossTheSelectedAreas() {
        val routine = buildRoutine(MobilityArea.entries, library)

        // First round takes one move per area in the order given; shoulders and neck run out first and are skipped.
        assertThat(routine.moves.take(5).map(::areaOf)).isEqualTo(MobilityArea.entries)
        assertThat(routine.moves.take(5).map { it.id })
            .containsExactly("chin-tucks", "cat-cow", "90-90", "thread", "leg-raise-1").inOrder()
        assertThat(routine.moves.map(::areaOf).count { it == MobilityArea.NECK }).isEqualTo(3)
    }

    @Test
    fun areasKeepTheOrderTheyAreGiven() {
        val routine = buildRoutine(listOf(MobilityArea.LOWER_BACK, MobilityArea.NECK), library)

        val firstNeck = routine.moves.indexOfFirst { areaOf(it) == MobilityArea.NECK }
        assertThat(routine.moves.take(firstNeck).map(::areaOf).toSet()).containsExactly(MobilityArea.LOWER_BACK)
        assertThat(routine.moves.take(6).map { it.id })
            .containsExactly("cat-cow", "childs-pose", "pelvic-tilt", "knee-to-chest", "bird-dog", "dead-bug").inOrder()
    }

    @Test
    fun movesWithinAnAreaFollowLibraryOrderNotGroupOrder() {
        val routine = buildRoutine(listOf(MobilityArea.CORE), library)

        // The library lists core-leg-raise before core-stability, although the area names core-stability first.
        assertThat(routine.moves.take(7).map { it.id })
            .containsExactly("leg-raise-1", "leg-raise-2", "leg-raise-3", "plank", "side-plank", "pallof", "band-pallof")
            .inOrder()
    }

    @Test
    fun longerWorkAndRestShortenThePassesAndTheCapFollowsTheTime() {
        // 60 + 20 = 80 s a move: 3 neck moves = 240 s a pass, two passes reach 480 s.
        assertThat(buildRoutine(listOf(MobilityArea.NECK), library, workSec = 60, restSec = 20).moves).hasSize(6)
        // 25 moves x 80 s is over 600 s, so the cap still applies.
        assertThat(buildRoutine(MobilityArea.entries, library, workSec = 60, restSec = 20).moves).hasSize(15)
    }

    @Test
    fun noAreasOrNoMatchingMovesGiveAnEmptyRoutine() {
        assertThat(buildRoutine(emptyList(), library).moves).isEmpty()
        assertThat(buildRoutine(listOf(MobilityArea.NECK), emptyList()).moves).isEmpty()
    }

    @Test
    fun neverDoneAreasComeFirstThenTheOldest() {
        val lastDone = mapOf(
            MobilityArea.NECK to today.minusDays(2),
            MobilityArea.HIPS to today.minusDays(9),
            MobilityArea.CORE to today,
        )

        assertThat(neglectedOrder(lastDone, today)).containsExactly(
            MobilityArea.LOWER_BACK, MobilityArea.SHOULDERS, // never done, in area order
            MobilityArea.HIPS, MobilityArea.NECK, MobilityArea.CORE,
        ).inOrder()
    }

    @Test
    fun everyAreaNeverDoneKeepsAreaOrder() {
        assertThat(neglectedOrder(emptyMap(), today)).isEqualTo(MobilityArea.entries)
    }
}
