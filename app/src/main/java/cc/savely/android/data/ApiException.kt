package cc.savely.android.data

enum class ApiError { CONFIGURATION, VALIDATION, NETWORK, AUTHENTICATION, PERMISSION, RATE_LIMIT, SERVER, RESPONSE, STORAGE }

/** Only fixed, user-safe text is exposed; server bodies may contain private data. */
class ApiException(
    val kind: ApiError,
    message: String,
    val status: Int? = null,
    val code: String? = null,
) : Exception(message)
