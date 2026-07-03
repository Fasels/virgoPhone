package com.example.virgo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

enum class MessageDirection {
    Inbound,
    Outbound,
}

data class AgentContact(
    val id: String,
    val displayName: String?,
    val phoneNumber: String,
    val remark: String,
    val areas: String,
    val status: String = "NORMAL",
)

data class AgentConversation(
    val id: String,
    val contactId: String,
    val externalPhoneNumber: String,
    val areas: String,
    val unreadCount: Int,
    val lastMessagePreview: String,
    val lastMessageAt: String,
)

data class AgentMessage(
    val id: String,
    val conversationId: String,
    val direction: MessageDirection,
    val text: String,
    val state: String,
    val time: String,
)

data class AgentMenu(
    val id: String,
    val menu: String,
    val areas: String,
)

fun canSendForSearchResult(
    result: AgentConversationSearchItem,
    simCards: List<AgentSimCardItem>,
): Boolean {
    val servicePhoneNumber = result.servicePhoneNumber?.trim().takeUnless { it.isNullOrBlank() } ?: return false
    return simCards.any { it.phoneNumber?.trim() == servicePhoneNumber }
}

class CustomerServiceStore(
    contacts: List<AgentContact>,
    conversations: List<AgentConversation>,
    messagesByConversation: Map<String, List<AgentMessage>>,
    menus: List<AgentMenu>,
) {
    val contacts = mutableStateListOf<AgentContact>().apply { addAll(contacts) }
    val conversations = mutableStateListOf<AgentConversation>().apply { addAll(conversations) }
    private val messagesByConversation = mutableStateMapOf<String, SnapshotStateList<AgentMessage>>().apply {
        messagesByConversation.forEach { (conversationId, messages) ->
            put(conversationId, mutableStateListOf<AgentMessage>().apply { addAll(messages) })
        }
    }
    private val mutableMenus = mutableStateListOf<AgentMenu>().apply { addAll(menus) }
    private val localConversationIds = mutableSetOf<String>()
    private val localConversationOverrides = mutableMapOf<String, AgentConversation>()
    private val locallyReadConversationIds = mutableSetOf<String>()

    val menus: List<AgentMenu>
        get() = mutableMenus

    private var mutableDraft: String by mutableStateOf("")

    val draft: String
        get() = mutableDraft

    private var messageSequence = 100

    fun contactTitle(contactId: String): String {
        val contact = contacts.first { it.id == contactId }
        return contact.remark.ifBlank {
            contact.displayName?.ifBlank { null } ?: contact.phoneNumber
        }
    }

    fun conversationTitle(conversationId: String): String {
        val conversation = conversations.first { it.id == conversationId }
        return contacts.firstOrNull { it.id == conversation.contactId }
            ?.let { contactTitle(it.id) }
            ?: conversation.externalPhoneNumber
    }

    fun conversationPhoneNumber(conversationId: String): String {
        val conversation = conversations.first { it.id == conversationId }
        return contacts.firstOrNull { it.id == conversation.contactId }
            ?.phoneNumber
            ?.takeIf { it.isNotBlank() }
            ?: conversation.externalPhoneNumber
    }

    fun updateRemark(contactId: String, remark: String) {
        val index = contacts.indexOfFirst { it.id == contactId }
        if (index == -1) return
        contacts[index] = contacts[index].copy(remark = remark.trim())
    }

    fun replaceContacts(items: List<AgentContact>) {
        contacts.clear()
        contacts.addAll(items)
    }

    fun replaceConversations(items: List<AgentConversation>) {
        val serverConversationIds = items.mapTo(mutableSetOf()) { it.id }
        val localOnlyConversations = conversations.filter { conversation ->
            conversation.id !in serverConversationIds &&
                (conversation.id in localConversationIds || conversation.id in localConversationOverrides)
        }
        localConversationIds.removeAll(serverConversationIds)
        conversations.clear()
        conversations.addAll(items.map(::mergeLocalConversationState))
        conversations.addAll(localOnlyConversations)
    }

    fun replaceMessages(conversationId: String, items: List<AgentMessage>) {
        val existing = messagesByConversation[conversationId].orEmpty()
        val serverMessageIds = items.mapTo(mutableSetOf()) { it.id }
        val localOnlyMessages = existing.filterNot { it.id in serverMessageIds }
        messagesByConversation[conversationId] = mutableStateListOf<AgentMessage>().apply {
            addAll(items + localOnlyMessages)
        }
        messagesByConversation[conversationId]?.lastOrNull()?.let { message ->
            updateConversationPreview(
                conversationId = conversationId,
                preview = message.text,
                unreadCount = conversations.firstOrNull { it.id == conversationId }?.unreadCount ?: 0,
                protectFromStaleRefresh = true,
            )
        }
    }

    fun replaceMenus(items: List<AgentMenu>) {
        mutableMenus.clear()
        mutableMenus.addAll(items)
    }

    fun setDraft(text: String) {
        mutableDraft = text
    }

    fun copyMenuToDraft(menuId: String) {
        mutableDraft = menus.first { it.id == menuId }.menu
    }

    fun sendMenu(conversationId: String, menuId: String) {
        sendMessage(conversationId, menus.first { it.id == menuId }.menu)
    }

    fun sendDraft(conversationId: String) {
        sendMessage(conversationId, draft)
    }

    fun sendMessage(conversationId: String, text: String) {
        val content = text.trim()
        if (content.isBlank()) return
        val message = AgentMessage(
            id = "local_${messageSequence++}",
            conversationId = conversationId,
            direction = MessageDirection.Outbound,
            text = content,
            state = "Pending",
            time = "Now",
        )
        appendMessage(message)
        mutableDraft = ""
    }

    fun appendMessage(message: AgentMessage) {
        upsertMessage(message)
        updateConversationPreview(
            conversationId = message.conversationId,
            preview = message.text,
            unreadCount = 0,
            protectFromStaleRefresh = true,
        )
    }

    fun recordInboundMessage(message: AgentMessage, isConversationOpen: Boolean) {
        ensureConversationExists(message.conversationId)
        val saved = upsertMessage(message)
        val currentUnread = conversations.firstOrNull { it.id == message.conversationId }?.unreadCount ?: 0
        val unreadIncrement = if (saved.wasNew) 1 else 0
        updateConversationPreview(
            conversationId = message.conversationId,
            preview = saved.message.text,
            unreadCount = if (isConversationOpen) 0 else currentUnread + unreadIncrement,
            protectFromStaleRefresh = true,
        )
    }

    fun messagesFor(conversationId: String): List<AgentMessage> {
        return messagesByConversation[conversationId].orEmpty()
    }

    fun markRead(conversationId: String) {
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index == -1) return
        conversations[index] = conversations[index].copy(unreadCount = 0)
        locallyReadConversationIds += conversationId
        localConversationOverrides[conversationId] = conversations[index]
    }

    fun conversationForContact(contactId: String): AgentConversation? {
        return conversations.firstOrNull { it.contactId == contactId }
    }

    fun contactForConversation(conversationId: String): AgentContact? {
        val conversation = conversations.firstOrNull { it.id == conversationId } ?: return null
        return contacts.firstOrNull { it.id == conversation.contactId }
    }

    fun contactForPhoneNumber(phoneNumber: String): AgentContact? {
        val normalized = phoneNumber.trim()
        return contacts.firstOrNull { it.phoneNumber.trim() == normalized }
    }

    private fun ensureConversationExists(conversationId: String) {
        if (conversations.any { it.id == conversationId }) return
        conversations.add(
            AgentConversation(
                id = conversationId,
                contactId = "",
                externalPhoneNumber = conversationId,
                areas = "",
                unreadCount = 0,
                lastMessagePreview = "",
                lastMessageAt = "Now",
            ),
        )
        localConversationIds += conversationId
    }

    private fun upsertMessage(message: AgentMessage): UpsertedMessage {
        val messages = messagesByConversation.getOrPut(message.conversationId) { mutableStateListOf() }
        val index = messages.indexOfFirst { it.id == message.id }
        if (index == -1) {
            messages.add(message)
            return UpsertedMessage(message = message, wasNew = true)
        }
        val savedMessage = if (
            message.text == IncomingMessagePlaceholder &&
            messages[index].text != IncomingMessagePlaceholder
        ) {
            messages[index]
        } else {
            message
        }
        messages[index] = savedMessage
        return UpsertedMessage(message = savedMessage, wasNew = false)
    }

    private fun mergeLocalConversationState(serverConversation: AgentConversation): AgentConversation {
        var merged = serverConversation
        val localOverride = localConversationOverrides[serverConversation.id]
        if (localOverride != null) {
            if (
                serverConversation.lastMessagePreview == localOverride.lastMessagePreview &&
                serverConversation.unreadCount >= localOverride.unreadCount
            ) {
                localConversationOverrides.remove(serverConversation.id)
            } else {
                merged = serverConversation.copy(
                    lastMessagePreview = localOverride.lastMessagePreview,
                    lastMessageAt = localOverride.lastMessageAt,
                    unreadCount = maxOf(serverConversation.unreadCount, localOverride.unreadCount),
                )
            }
        }

        if (serverConversation.id in locallyReadConversationIds) {
            merged = merged.copy(unreadCount = 0)
            if (serverConversation.unreadCount == 0) {
                locallyReadConversationIds.remove(serverConversation.id)
            }
        }
        return merged
    }

    private fun updateConversationPreview(
        conversationId: String,
        preview: String,
        unreadCount: Int,
        protectFromStaleRefresh: Boolean,
    ) {
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index == -1) return
        conversations[index] = conversations[index].copy(
            lastMessagePreview = preview,
            lastMessageAt = "Now",
            unreadCount = unreadCount,
        )
        if (protectFromStaleRefresh) {
            localConversationOverrides[conversationId] = conversations[index]
        }
    }

    companion object {
        const val IncomingMessagePlaceholder = "New message received. Syncing content..."

        fun empty(): CustomerServiceStore = CustomerServiceStore(
            contacts = mutableListOf(),
            conversations = mutableListOf(),
            messagesByConversation = mutableMapOf(),
            menus = emptyList(),
        )
    }

    private data class UpsertedMessage(
        val message: AgentMessage,
        val wasNew: Boolean,
    )
}
