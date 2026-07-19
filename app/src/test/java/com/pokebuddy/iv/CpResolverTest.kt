package com.pokebuddy.iv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CpResolverTest {

    private lateinit var moltres: BaseStats

    @Before fun setUp() {
        SpeciesTable.load("moltres,251,181,207")
        moltres = SpeciesTable["Moltres"]!!
    }

    @Test fun rejects_occlusion_partials_and_keeps_the_true_cp() {
        // A real burst on Moltres (HP 145): mostly junk, one true read.
        val candidates = listOf(131, 431, 2431, 243)
        assertEquals(2431, CpResolver.resolve(candidates, moltres, 145))
    }

    @Test fun the_wrong_partial_is_actually_infeasible() {
        assertTrue(CpResolver.isFeasible(moltres, 2431, 145))
        assertFalse(CpResolver.isFeasible(moltres, 131, 145))
        assertFalse(CpResolver.isFeasible(moltres, 243, 145))
    }

    @Test fun returns_null_when_no_candidate_is_feasible() {
        assertNull(CpResolver.resolve(listOf(131, 243, 99), moltres, 145))
    }

    @Test fun without_base_or_hp_falls_back_to_max() {
        assertEquals(2431, CpResolver.resolve(listOf(131, 2431), null, null))
    }
}
