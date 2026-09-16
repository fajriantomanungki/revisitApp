package com.fajriantomanungki.revisitapp.data.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardCoverageSnapshotTest {

    @Test
    fun targetPerPetugasIsFourteenSlsTimesNineRespondents() {
        assertEquals(14, SLS_PER_PETUGAS)
        assertEquals(9, RESPONDEN_PER_SLS)
        assertEquals(126, TARGET_PER_PETUGAS)
        assertEquals(126L, DashboardCoverageSnapshot().target)
    }

    @Test
    fun localPendingIsAddedToServerRealisasi() {
        val snapshot = DashboardCoverageSnapshot(
            serverTerdata = 100L,
            localPending = 12L
        )

        assertEquals(112L, snapshot.terdata)
        assertEquals(112.0 / 126.0 * 100.0, snapshot.persenPenyelesaian, 0.0001)
        assertEquals(112.0f / 126.0f, snapshot.progressFraction, 0.0001f)
    }

    @Test
    fun progressIndicatorIsCappedButPercentageKeepsOverflowVisible() {
        val snapshot = DashboardCoverageSnapshot(
            serverTerdata = 126L,
            localPending = 2L
        )

        assertEquals(128.0 / 126.0 * 100.0, snapshot.persenPenyelesaian, 0.0001)
        assertEquals(1.0f, snapshot.progressFraction, 0.0001f)
    }
}
