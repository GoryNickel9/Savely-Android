package cc.savely.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizationTest {
    private val catalog = TranslationCatalog(mapOf(
        "Accedi" to "Sign in",
        "{{name}}: {{amount}} · {{id}}" to "{{name}}: {{amount}} · {{id}}",
        "{{count}} pezzi · costo {{amount}}" to "{{count}} copies · cost {{amount}}",
        "Riga {{row}}: {{message}}" to "Row {{row}}: {{message}}",
        "Errore {{id}}" to "Error {{other}}",
    ))

    @Test fun italianAndUnknownLanguageRemainItalian() {
        assertEquals(AppLanguage.ITALIAN, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.ITALIAN, AppLanguage.fromTag("unsupported"))
        assertEquals("Accedi", catalog.translate(AppLanguage.ITALIAN, "Accedi"))
        assertEquals("3 pezzi · costo 1.234,56 EUR", catalog.translate(AppLanguage.ITALIAN,
            "{{count}} pezzi · costo {{amount}}", "count" to 3, "amount" to "1.234,56 EUR"))
    }

    @Test fun englishOnlyTranslatesExactKeysWithoutGuessingRenderedTemplates() {
        assertEquals("Sign in", catalog.translate(AppLanguage.ENGLISH, "Accedi"))
        assertEquals("Accedi@example.test", catalog.translate(AppLanguage.ENGLISH, "Accedi@example.test"))
        assertEquals("3 pezzi · costo 1.234,56 EUR", catalog.translate(AppLanguage.ENGLISH, "3 pezzi · costo 1.234,56 EUR"))
    }

    @Test fun opaqueValuesAreInsertedOnceWithoutTranslationOrPlaceholderExpansion() {
        assertEquals("Accedi {{id}}: 999999999999.12345678 USD · uuid-123", catalog.translate(AppLanguage.ENGLISH,
            "{{name}}: {{amount}} · {{id}}", "name" to "Accedi {{id}}", "amount" to "999999999999.12345678 USD", "id" to "uuid-123"))
        assertEquals("3 copies · cost 1.234,56 EUR", catalog.translate(AppLanguage.ENGLISH,
            "{{count}} pezzi · costo {{amount}}", "count" to 3, "amount" to "1.234,56 EUR"))
    }

    @Test fun missingKeysAndIncompleteArgumentsRemainVisible() {
        assertEquals("Nuovo {{name}}", catalog.translate(AppLanguage.ENGLISH, "Nuovo {{name}}"))
        assertEquals("Row 4: {{message}}", catalog.translate(AppLanguage.ENGLISH, "Riga {{row}}: {{message}}", "row" to 4))
    }

    @Test fun incorrectTranslationPlaceholdersFallBackToTheOriginalTemplate() {
        assertEquals("Errore uuid-123", catalog.translate(AppLanguage.ENGLISH, "Errore {{id}}", "id" to "uuid-123"))
    }
}
