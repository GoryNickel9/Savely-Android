package cc.savely.android.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ExactJsonTest {
    @Test fun decimalsNeverPassThroughDouble() {
        val result = ExactJson.parse("""{"quantity":999999999999.12345678,"price":0.10000001,"integer":9223372036854775808,"exponent":1.23456789e-8}""") as JSONObject
        assertEquals(BigDecimal("999999999999.12345678"), result.get("quantity"))
        assertEquals(BigDecimal("0.10000001"), result.get("price"))
        assertEquals(BigDecimal("9223372036854775808"), result.get("integer"))
        assertEquals(BigDecimal("1.23456789e-8"), result.get("exponent"))
        assertTrue(result.get("quantity") is BigDecimal)
        assertEquals(123L, ExactJson.parse("123"))
    }

    @Test fun scalarArrayObjectStringsAndEscapesArePreserved() {
        val result = ExactJson.parse("""[true,false,null,"numeric 12.34",{"quoted":"x\"y","unicode":"\u00e9","newline":"a\nb"}]""") as JSONArray
        assertTrue(result.getBoolean(0))
        assertFalse(result.getBoolean(1))
        assertTrue(result.isNull(2))
        assertEquals("numeric 12.34", result.getString(3))
        assertEquals("x\"y", result.getJSONObject(4).getString("quoted"))
        assertEquals("é", result.getJSONObject(4).getString("unicode"))
        assertEquals("a\nb", result.getJSONObject(4).getString("newline"))
    }

    @Test fun nonJsonSyntaxAndTrailingDataAreRejected() {
        for (input in listOf("{'a':1}", "{a:1}", "[1,]", "{\"a\":1,}", "01", "+1", "1.", ".5", "NaN", "Infinity",
            "true false", "\"bad\\x\"", "\"bad\nline\"", "\"\\uXXXX\"", "{\"a\":1,\"a\":2}")) {
            assertInvalid(input)
        }
    }

    @Test fun depthAndNumericExpansionAreBounded() {
        assertInvalid("[".repeat(65) + "0" + "]".repeat(65))
        assertInvalid("[".repeat(65) + "]".repeat(65))
        assertInvalid("1e1000000000")
        assertInvalid("9".repeat(257))
        assertTrue(ExactJson.parse("[".repeat(20) + "0" + "]".repeat(20)) is JSONArray)
    }

    private fun assertInvalid(input: String) {
        try { ExactJson.parse(input); fail("Accepted invalid JSON: ${input.take(30)}") }
        catch (_: JSONException) { }
    }
}
