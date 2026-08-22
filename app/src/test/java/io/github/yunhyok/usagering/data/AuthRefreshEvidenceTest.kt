package io.github.yunhyok.usagering.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthRefreshEvidenceTest {
    @Test fun falseOrSparseObservationPreservesPriorMarker() {
        val prior = AuthRefreshEvidence(4L, 100L)
        assertEquals(prior, recordAuthRefreshObservation(prior, false, 200L))
    }

    @Test fun trueObservationIncrementsCountAndStoresOnlyLocalTime() {
        val next = recordAuthRefreshObservation(AuthRefreshEvidence(4L, 100L), true, 200L)
        assertEquals(AuthRefreshEvidence(5L, 200L), next)
    }

    @Test fun countSaturatesAndNegativeTimeIsSafe() {
        val next = recordAuthRefreshObservation(AuthRefreshEvidence(Long.MAX_VALUE, 100L), true, -1L)
        assertEquals(AuthRefreshEvidence(Long.MAX_VALUE, 0L), next)
    }
}
