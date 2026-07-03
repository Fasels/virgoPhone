package com.example.virgo

data class LoginFormState(
    val username: String = "",
    val password: String = "",
) {
    val canSubmit: Boolean
        get() = username.isNotBlank() && password.isNotBlank()
}
