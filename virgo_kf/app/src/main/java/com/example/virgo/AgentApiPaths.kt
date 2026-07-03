package com.example.virgo

object AgentApiPaths {
    const val BasePath = "/agent/v1"

    const val Login = "$BasePath/auth/login"
    const val Me = "$BasePath/me"
    const val Contacts = "$BasePath/contacts"
    const val Conversations = "$BasePath/conversations"
    const val ConversationSearch = "$BasePath/conversation-search"
    const val Menus = "$BasePath/menus"
    const val SimCards = "$BasePath/sim-cards"
    const val Events = "$BasePath/events"

    fun contactRemark(contactId: String): String = "$Contacts/$contactId/remark"

    fun conversationMessages(conversationId: String): String = "$Conversations/$conversationId/messages"

    fun conversationRead(conversationId: String): String = "$Conversations/$conversationId/read"
}
