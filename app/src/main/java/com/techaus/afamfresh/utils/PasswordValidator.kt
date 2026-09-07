package com.techaus.afamfresh.utils

object PasswordValidator {
    private val HAS_UPPERCASE = Regex("[A-Z]")
    private val HAS_LOWERCASE = Regex("[a-z]")
    private val HAS_DIGIT = Regex("[0-9]")
    private val HAS_SPECIAL = Regex("[@$!%*?&#^()_\\-+=<>{}~`|\"':;,./]")

    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String? = null
    )

    fun validate(password: String): ValidationResult {
        return when {
            password.length < 8 -> ValidationResult(
                isValid = false,
                errorMessage = "Password must be at least 8 characters long."
            )
            !HAS_UPPERCASE.containsMatchIn(password) -> ValidationResult(
                isValid = false,
                errorMessage = "Password must include at least one uppercase letter (A-Z)."
            )
            !HAS_LOWERCASE.containsMatchIn(password) -> ValidationResult(
                isValid = false,
                errorMessage = "Password must include at least one lowercase letter (a-z)."
            )
            !HAS_DIGIT.containsMatchIn(password) -> ValidationResult(
                isValid = false,
                errorMessage = "Password must include at least one number (0-9)."
            )
            !HAS_SPECIAL.containsMatchIn(password) -> ValidationResult(
                isValid = false,
                errorMessage = "Password must include at least one special character (@$!%*?&#^)."
            )
            else -> ValidationResult(isValid = true)
        }
    }
}