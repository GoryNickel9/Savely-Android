package cc.savely.android.modules

import org.junit.Assert.assertEquals
import org.junit.Test

class PokerRateFormattingTest {
    /** Literal Node toFixed(2) fixtures from readonly PokerHourlyEarnings.tsx, SHA 1cfcf629. */
    @Test fun exactBinaryRoundingMatchesWebForExactTiesAndNearbyDecimalInputs() {
        listOf(0.125 to "0,13", -0.125 to "-0,13", 0.305 to "0,30", 1.005 to "1,00", 2.675 to "2,67")
            .forEach { (input, expected) ->
                assertEquals("Web toFixed(2) rounding for $input", expected, pokerRateMoney(input).removeSuffix("€").trim())
            }
    }
}
