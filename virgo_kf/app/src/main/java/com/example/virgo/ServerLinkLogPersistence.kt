package com.example.virgo

import android.content.Context

private const val SERVER_LINK_LOG_PREFS = "server_link_logs"
private const val SERVER_LINK_LOG_ENTRIES = "entries"

fun persistentServerLinkLogStore(context: Context): ServerLinkLogStore {
    val prefs = context.getSharedPreferences(SERVER_LINK_LOG_PREFS, Context.MODE_PRIVATE)
    val savedEntries = ServerLinkLogCodec.decode(prefs.getString(SERVER_LINK_LOG_ENTRIES, "").orEmpty())
    return ServerLinkLogStore(initialEntries = savedEntries) { entries ->
        prefs.edit()
            .putString(SERVER_LINK_LOG_ENTRIES, ServerLinkLogCodec.encode(entries))
            .apply()
    }
}
