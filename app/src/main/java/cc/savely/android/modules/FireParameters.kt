package cc.savely.android.modules

import org.json.JSONObject
import java.security.MessageDigest

/** All ten visible FIRE parameters; rates are fractions, exactly as in the web calculation API. */
data class FireParameters(
    val inputs: FireCalculations.Inputs = FireCalculations.Inputs(),
    val partTimeIncome: Double = 20000.0,
) {
    fun encode(): String {
        inputs.validate()
        require(partTimeIncome.isFinite() && partTimeIncome >= 0) { "Reddito part-time non valido" }
        val params = JSONObject()
            .put("currentAge", inputs.currentAge).put("retirementAge", inputs.retirementAge)
            .put("currentSavings", inputs.currentSavings).put("annualContribution", inputs.annualContribution)
            .put("annualIncome", inputs.annualIncome).put("annualExpenses", inputs.annualExpenses)
            .put("expectedReturn", inputs.expectedReturn).put("inflationRate", inputs.inflationRate)
            .put("withdrawalRate", inputs.withdrawalRate).put("partTimeIncome", partTimeIncome)
        return JSONObject().put("version", VERSION).put("params", params).toString()
    }

    companion object {
        const val VERSION = 1
        const val STORAGE_KEY = "parameters"

        /** Account-specific storage deliberately differs from the web's browser-wide localStorage. */
        fun preferencesName(userId: String): String {
            require(userId.isNotBlank()) { "Account mancante" }
            val hash = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') }
            return "fire_parameters_$hash"
        }

        fun decode(encoded: String): FireParameters {
            require(encoded.length <= 8192) { "Parametri FIRE locali troppo grandi" }
            val json = JSONObject(encoded)
            require(json.opt("version") == VERSION) { "Versione dei parametri FIRE locali non supportata" }
            val params = json.getJSONObject("params")
            fun number(key: String): Double {
                val value = params.get(key)
                require(value is Number && value.toDouble().isFinite()) { "Parametro FIRE locale non valido: $key" }
                return value.toDouble()
            }
            fun age(key: String): Int {
                val value = number(key)
                require(value in 0.0..120.0 && value % 1 == 0.0) { "Età FIRE locale non valida" }
                return value.toInt()
            }
            return FireParameters(FireCalculations.Inputs(
                age("currentAge"), age("retirementAge"), number("currentSavings"), number("annualContribution"),
                number("annualIncome"), number("expectedReturn"), number("inflationRate"), number("withdrawalRate"),
                number("annualExpenses")), number("partTimeIncome")).also { it.encode() }
        }
    }
}
