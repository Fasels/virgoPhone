package com.example.virgo

data class LoginSessionState(
    val isLoggedIn: Boolean = false,
    val token: String = "",
    val agentName: String = "",
    val areas: String? = null,
) {
    fun login(token: String, agentName: String, areas: String?): LoginSessionState = copy(
        isLoggedIn = true,
        token = token,
        agentName = agentName,
        areas = areas,
    )

    fun logout(): LoginSessionState = LoginSessionState()
}
