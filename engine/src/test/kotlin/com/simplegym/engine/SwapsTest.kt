package com.simplegym.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwapsTest {
    private fun ex(
        name: String,
        backLoad: BackLoad = BackLoad.LOW,
        swapGroup: String = "g",
    ) = Exercise(
        id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
        name = name,
        muscle = Muscle.CHEST,
        equipment = "Machine",
        swapGroup = swapGroup,
        incrementKg = 2.5,
        backLoad = backLoad,
        joints = emptySet(),
        isAssisted = false,
        isBodyweight = false,
    )

    private fun ctx(
        lowerBackProtection: Boolean = false,
        unavailable: Set<String> = emptySet(),
        useCounts: Map<String, Int> = emptyMap(),
        hurtFlagged: Set<String> = emptySet(),
        stiffNeck: Boolean = false,
        stiffLowerBack: Boolean = false,
    ) = SwapContext(lowerBackProtection, unavailable, useCounts, hurtFlagged, stiffNeck, stiffLowerBack)

    private fun names(list: List<Exercise>) = list.map { it.name }

    private val row = ex("Seated row")
    private val bentOver = ex("Barbell bent-over row", BackLoad.HIGH)
    private val cableRow = ex("Cable row")

    @Test
    fun protectionOnHidesHighBackLoadSwaps() {
        val result = orderSwaps(listOf(row, bentOver, cableRow), ctx(lowerBackProtection = true))

        assertThat(names(result)).containsExactly("Seated row", "Cable row").inOrder()
    }

    @Test
    fun protectionOffKeepsHighBackLoadSwaps() {
        val result = orderSwaps(listOf(row, bentOver), ctx(lowerBackProtection = false))

        assertThat(names(result)).containsExactly("Seated row", "Barbell bent-over row").inOrder()
    }

    @Test
    fun anUnavailableSwapIsDropped() {
        val result = orderSwaps(listOf(row, cableRow), ctx(unavailable = setOf(row.id)))

        assertThat(names(result)).containsExactly("Cable row")
    }

    @Test
    fun aHurtFlaggedSwapSortsLast() {
        val result = orderSwaps(listOf(row, cableRow, ex("Chest-supported row")), ctx(hurtFlagged = setOf(row.id)))

        assertThat(names(result)).containsExactly("Cable row", "Chest-supported row", "Seated row").inOrder()
    }

    @Test
    fun aStiffNeckMovesShrugAndUprightRowSwapsAfterTheOthers() {
        val upright = ex("Upright row (cable)", swapGroup = "upright-row")
        val shrug = ex("Dumbbell shrug", swapGroup = "shrug")
        val press = ex("Machine shoulder press", swapGroup = "shoulder-press")
        val swaps = listOf(upright, shrug, press, row)

        assertThat(names(orderSwaps(swaps, ctx(stiffNeck = false))))
            .containsExactly("Upright row (cable)", "Dumbbell shrug", "Machine shoulder press", "Seated row").inOrder()
        assertThat(names(orderSwaps(swaps, ctx(stiffNeck = true))))
            .containsExactly("Machine shoulder press", "Seated row", "Upright row (cable)", "Dumbbell shrug").inOrder()
    }

    @Test
    fun hurtFlaggedOutranksTheStiffNeckRule() {
        val upright = ex("Upright row (cable)", swapGroup = "upright-row")
        val press = ex("Machine shoulder press", swapGroup = "shoulder-press")

        val result = orderSwaps(listOf(press, upright), ctx(stiffNeck = true, hurtFlagged = setOf(press.id)))

        assertThat(names(result)).containsExactly("Upright row (cable)", "Machine shoulder press").inOrder()
    }

    @Test
    fun moreUsedSwapsComeFirstAndTiesKeepTheirOriginalOrder() {
        val chestPress = ex("Chest press")
        val dbPress = ex("DB press")
        val pushUps = ex("Push-ups")
        val counts = mapOf(dbPress.id to 5, chestPress.id to 2)

        val result = orderSwaps(listOf(chestPress, pushUps, dbPress), ctx(useCounts = counts))

        assertThat(names(result)).containsExactly("DB press", "Chest press", "Push-ups").inOrder()
    }

    private val squat = ex("Barbell back squat", BackLoad.MODERATE)
    private val legPress = ex("Seated leg press", BackLoad.LOW)
    private val hackSquat = ex("Hack squat", BackLoad.LOW)
    private val lunges = ex("Walking lunges", BackLoad.MODERATE)

    @Test
    fun aStiffLowerBackSwapsAModerateDefaultForTheLowestBackLoadSwap() {
        val result = suggestedVariant(squat, listOf(lunges, legPress, hackSquat), ctx(stiffLowerBack = true))

        assertThat(result.name).isEqualTo("Seated leg press") // first of the two LOW swaps
    }

    @Test
    fun aStiffLowerBackKeepsALowBackLoadDefault() {
        val result = suggestedVariant(legPress, listOf(squat, hackSquat), ctx(stiffLowerBack = true))

        assertThat(result).isEqualTo(legPress)
    }

    @Test
    fun aStiffLowerBackKeepsTheDefaultWhenNoSwapIsLighterOnTheBack() {
        val otherHigh = ex("Other high", BackLoad.HIGH)

        val result = suggestedVariant(bentOver, listOf(otherHigh), ctx(stiffLowerBack = true))

        assertThat(result).isEqualTo(bentOver)
    }

    @Test
    fun aHiddenDefaultIsReplacedByTheFirstOrderedSwap() {
        val unavailable = suggestedVariant(row, listOf(cableRow, hackSquat), ctx(unavailable = setOf(row.id)))
        val protectedOut = suggestedVariant(bentOver, listOf(cableRow, hackSquat), ctx(lowerBackProtection = true))

        assertThat(unavailable).isEqualTo(cableRow)
        assertThat(protectedOut).isEqualTo(cableRow)
    }

    @Test
    fun theFirstOrderedSwapRespectsUseCountsAndFilters() {
        val result = suggestedVariant(
            row,
            listOf(cableRow, hackSquat, bentOver),
            ctx(unavailable = setOf(row.id), useCounts = mapOf(hackSquat.id to 3), lowerBackProtection = true),
        )

        assertThat(result).isEqualTo(hackSquat)
    }

    @Test
    fun aHiddenDefaultWithNoSwapsStaysTheDefault() {
        assertThat(suggestedVariant(row, emptyList(), ctx(unavailable = setOf(row.id)))).isEqualTo(row)
    }

    @Test
    fun otherwiseTheDefaultIsKept() {
        assertThat(suggestedVariant(row, listOf(cableRow), ctx())).isEqualTo(row)
    }
}
