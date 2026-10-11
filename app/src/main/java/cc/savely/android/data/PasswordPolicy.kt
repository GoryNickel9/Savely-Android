package cc.savely.android.data

/** New credentials match the web passwordValidation.ts policy; existing-password login remains unrestricted. */
object PasswordPolicy {
    const val requirements = "Almeno 12 caratteri, una maiuscola, una minuscola, un numero e un simbolo."
    private const val symbols = """!@#$%^&*()_+-=[]{};':"\|,.<>/?"""

    fun error(password: String): String? = when {
        password.length < 12 -> "La password deve contenere almeno 12 caratteri."
        password.none { it in 'A'..'Z' } -> "La password deve contenere almeno una lettera maiuscola."
        password.none { it in 'a'..'z' } -> "La password deve contenere almeno una lettera minuscola."
        password.none { it in '0'..'9' } -> "La password deve contenere almeno un numero."
        password.none { it in symbols } -> "La password deve contenere almeno un simbolo."
        else -> null
    }

    fun validate(password: String) {
        error(password)?.let { throw ApiException(ApiError.VALIDATION, it) }
    }
}
