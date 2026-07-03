package com.example.virgo

import android.content.Context

private const val AgentSessionPrefs = "agent_session"
private const val KeyToken = "token"
private const val KeyAgentName = "agent_name"
private const val KeyAreas = "areas"

fun loadLoginSession(context: Context): LoginSessionState {
    val prefs = context.getSharedPreferences(AgentSessionPrefs, Context.MODE_PRIVATE)
    val token = prefs.getString(KeyToken, "").orEmpty()
    if (token.isBlank()) return LoginSessionState()
    return LoginSessionState().login(
        token = token,
        agentName = prefs.getString(KeyAgentName, "").orEmpty(),
        areas = prefs.getString(KeyAreas, null),
    )
}

fun saveLoginSession(context: Context, session: LoginSessionState) {
    val prefs = context.getSharedPreferences(AgentSessionPrefs, Context.MODE_PRIVATE)
    prefs.edit()
        .putString(KeyToken, session.token)
        .putString(KeyAgentName, session.agentName)
        .putString(KeyAreas, session.areas)
        .apply()
}

fun clearLoginSession(context: Context) {
    context.getSharedPreferences(AgentSessionPrefs, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .apply()
}
