package cc.savely.android.ui

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranslationCatalogTest {
    private fun translations(): Map<String, String> {
        val path = sequenceOf(File("src/main/res/raw/ui_translations_en.json"), File("app/src/main/res/raw/ui_translations_en.json"))
            .firstOrNull { it.isFile } ?: error("Native translation catalog not found")
        val json = JSONObject(path.readText(Charsets.UTF_8))
        return json.keys().asSequence().associateWith { json.getString(it) }
    }

    @Test fun everyTranslationPreservesExactPlaceholderNamesAndOccurrences() {
        val placeholder = Regex("\\{\\{[A-Za-z0-9_]+}}")
        translations().forEach { (italian, english) ->
            assertFalse("Empty translation: $italian", english.isBlank())
            assertEquals("Changed placeholders: $italian",
                placeholder.findAll(italian).map { it.value }.sorted().toList(),
                placeholder.findAll(english).map { it.value }.sorted().toList())
        }
    }

    @Test fun accountFinanceTransferFireAndLegalStringsHaveExplicitEnglishEntries() {
        val values = translations()
        val required = mapOf(
            "Chiudi le altre sessioni" to "Sign out other sessions",
            "Registrazioni temporaneamente disabilitate." to "Registration temporarily disabled.",
            "Codice QR per configurare l’autenticatore" to "QR code to set up your authenticator",
            "Condividi con il partner" to "Share with your partner",
            "Cambio automatico al salvataggio" to "Automatic exchange rate when saving",
            "Gestisci Mapping ISIN" to "Manage ISIN mappings",
            "Rileggi il mapping prima di modificarlo." to "Reload the mapping before changing it.",
            "Progressione al FIRE" to "Progress toward FIRE",
            "Usa importi mensili" to "Use monthly amounts",
            "Documento non disponibile. Riprova." to "Document unavailable. Try again.",
        )
        required.forEach { (key, expected) -> assertEquals("Missing or changed translation: $key", expected, values[key]) }
    }

    @Test fun newTemplatesPreserveUserNamesPrecisionAndLiteralPlaceholderText() {
        val catalog = TranslationCatalog(translations())
        val name = "Accedi {{currency}} · Nick"
        assertEquals("Remove sharing for $name", catalog.translate(AppLanguage.ENGLISH,
            "Rimuovi condivisione {{name}}", "name" to name))
        assertEquals("Partner's share (USD)", catalog.translate(AppLanguage.ENGLISH,
            "Quota partner ({{currency}})", "currency" to "USD"))
        assertEquals("ISIN IE00BK5BQT80 · quantity 1 · purchase price 999999999999.12345678 USD",
            catalog.translate(AppLanguage.ENGLISH,
                "ISIN {{isin}} · quantità 1 · prezzo di acquisto {{amount}} {{currency}}",
                "isin" to "IE00BK5BQT80", "amount" to "999999999999.12345678", "currency" to "USD"))
        assertEquals(name, catalog.translate(AppLanguage.ENGLISH, name))
    }
}
