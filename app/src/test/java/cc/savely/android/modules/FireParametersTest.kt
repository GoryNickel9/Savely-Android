package cc.savely.android.modules

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FireParametersTest {
    @Test fun allTenParametersRoundTripWithoutChangingRatesOrMoney() {
        val original = FireParameters(FireCalculations.Inputs(currentAge = 41, retirementAge = 67,
            currentSavings = 98765.43, annualContribution = 12345.67, annualIncome = 65432.10,
            expectedReturn = 0.05125, inflationRate = 0.0275, withdrawalRate = 0.035,
            annualExpenses = 32100.55), partTimeIncome = 15800.75)
        val json = JSONObject(original.encode())
        assertEquals(1, json.getInt("version"))
        assertEquals(10, json.getJSONObject("params").length())
        assertEquals(original, FireParameters.decode(original.encode()))
        assertEquals(FireCalculations.standard(original.inputs, 2026),
            FireCalculations.standard(FireParameters.decode(original.encode()).inputs, 2026))
    }

    @Test fun validZerosAndNegativeReturnArePreserved() {
        val original = FireParameters(FireCalculations.Inputs(currentSavings = 0.0, annualContribution = 0.0,
            annualIncome = 0.0, annualExpenses = 0.0, expectedReturn = -0.1, inflationRate = 0.0,
            withdrawalRate = 0.0), partTimeIncome = 0.0)
        assertEquals(original, FireParameters.decode(original.encode()))
        assertEquals(-0.1, JSONObject(original.encode()).getJSONObject("params").getDouble("expectedReturn"), 0.0)
    }

    @Test fun defaultsAndResetSnapshotMatchWebDefaults() {
        val restored = FireParameters.decode(FireParameters().encode())
        assertEquals(30, restored.inputs.currentAge)
        assertEquals(55, restored.inputs.retirementAge)
        assertEquals(100000.0, restored.inputs.currentSavings, 0.0)
        assertEquals(24000.0, restored.inputs.annualContribution, 0.0)
        assertEquals(72000.0, restored.inputs.annualIncome, 0.0)
        assertEquals(48000.0, restored.inputs.annualExpenses, 0.0)
        assertEquals(0.07, restored.inputs.expectedReturn, 0.0)
        assertEquals(0.03, restored.inputs.inflationRate, 0.0)
        assertEquals(0.04, restored.inputs.withdrawalRate, 0.0)
        assertEquals(20000.0, restored.partTimeIncome, 0.0)
    }

    @Test fun accountPreferenceFilesAreDistinctDeterministicAndDoNotContainIdentity() {
        val a = FireParameters.preferencesName("alice@example.com")
        val b = FireParameters.preferencesName("bob@example.com")
        assertEquals(a, FireParameters.preferencesName("alice@example.com"))
        assertNotEquals(a, b)
        assertTrue(a.matches(Regex("fire_parameters_[0-9a-f]{64}")))
        assertFalse(a.contains("alice"))
        assertThrows(IllegalArgumentException::class.java) { FireParameters.preferencesName("") }
    }

    @Test fun unsupportedOrUnversionedDataIsRejectedRatherThanSilentlyMigrated() {
        val json = JSONObject(FireParameters().encode()).put("version", 2)
        assertThrows(IllegalArgumentException::class.java) { FireParameters.decode(json.toString()) }
        json.put("version", "1")
        assertThrows(IllegalArgumentException::class.java) { FireParameters.decode(json.toString()) }
        json.remove("version")
        assertThrows(IllegalArgumentException::class.java) { FireParameters.decode(json.toString()) }
    }

    @Test fun corruptedOrPartialDataIsRejected() {
        assertThrows(Exception::class.java) { FireParameters.decode("{broken") }
        val missing = JSONObject(FireParameters().encode())
        missing.getJSONObject("params").remove("annualIncome")
        assertThrows(Exception::class.java) { FireParameters.decode(missing.toString()) }
        val numericString = JSONObject(FireParameters().encode())
        numericString.getJSONObject("params").put("currentSavings", "1000")
        assertThrows(IllegalArgumentException::class.java) { FireParameters.decode(numericString.toString()) }
        assertThrows(IllegalArgumentException::class.java) { FireParameters.decode(" ".repeat(8193)) }
    }

    @Test fun invalidNumericsCannotReplaceValidSnapshot() {
        assertThrows(IllegalArgumentException::class.java) { FireParameters(partTimeIncome = Double.NaN).encode() }
        assertThrows(IllegalArgumentException::class.java) { FireParameters(partTimeIncome = -1.0).encode() }
        assertThrows(IllegalArgumentException::class.java) { FireParameters(FireCalculations.Inputs(annualExpenses = -1.0)).encode() }
        assertThrows(IllegalArgumentException::class.java) { FireParameters(FireCalculations.Inputs(expectedReturn = -1.0)).encode() }
        val fractionalAge = JSONObject(FireParameters().encode())
        fractionalAge.getJSONObject("params").put("currentAge", 30.5)
        assertThrows(IllegalArgumentException::class.java) { FireParameters.decode(fractionalAge.toString()) }
    }
}
