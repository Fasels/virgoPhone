package com.example.virgo

import androidx.compose.runtime.snapshots.SnapshotStateList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerServiceStoreTest {
    @Test
    fun loginFormRequiresUsernameAndPassword() {
        assertFalse(LoginFormState(username = "", password = "secret").canSubmit)
        assertFalse(LoginFormState(username = "agent_north", password = "").canSubmit)
        assertTrue(LoginFormState(username = " agent_north ", password = " secret ").canSubmit)
    }

    @Test
    fun loginSessionCanLogoutToUnauthenticatedState() {
        val loggedIn = LoginSessionState().login(
            token = "agent_token",
            agentName = "agent_north",
            areas = null,
        )

        assertTrue(loggedIn.isLoggedIn)
        assertEquals("agent_token", loggedIn.token)
        assertEquals("agent_north", loggedIn.agentName)
        assertEquals(null, loggedIn.areas)
        assertFalse(loggedIn.logout().isLoggedIn)
    }

    @Test
    fun forbiddenConversationErrorPromptsAndLeavesConversation() {
        val handling = agentApiErrorHandling(AgentApiException(403, "Forbidden"))

        assertEquals("当前账号无权限访问该会话", handling.message)
        assertEquals(AgentApiRecovery.RefreshConversationsAndLeaveConversation, handling.recovery)
    }

    @Test
    fun noAvailableDeviceErrorPromptsWithoutLeavingConversation() {
        val handling = agentApiErrorHandling(AgentApiException(422, "NO_AVAILABLE_DEVICE"))

        assertEquals("发送设备或 SIM 不可用，请稍后重试", handling.message)
        assertEquals(AgentApiRecovery.None, handling.recovery)
    }

    @Test
    fun productionStoreStartsWithoutSampleData() {
        val store = CustomerServiceStore.empty()

        assertTrue(store.contacts.isEmpty())
        assertTrue(store.conversations.isEmpty())
        assertTrue(store.menus.isEmpty())
    }

    @Test
    fun productionStoreUsesSnapshotStateListsForComposeUpdates() {
        val store = populatedStore()

        assertTrue(store.contacts is SnapshotStateList<*>)
        assertTrue(store.conversations is SnapshotStateList<*>)
        assertTrue(store.messagesFor("conv_1") is SnapshotStateList<*>)
        assertTrue(store.menus is SnapshotStateList<*>)
    }

    @Test
    fun agentApiPathsUseAgentPrefixAndEventsEndpoint() {
        assertEquals("/agent/v1", AgentApiPaths.BasePath)
        assertEquals("/agent/v1/auth/login", AgentApiPaths.Login)
        assertEquals("/agent/v1/me", AgentApiPaths.Me)
        assertEquals("/agent/v1/contacts", AgentApiPaths.Contacts)
        assertEquals("/agent/v1/conversations", AgentApiPaths.Conversations)
        assertEquals("/agent/v1/conversation-search", AgentApiPaths.ConversationSearch)
        assertEquals("/agent/v1/menus", AgentApiPaths.Menus)
        assertEquals("/agent/v1/sim-cards", AgentApiPaths.SimCards)
        assertEquals("/agent/v1/events", AgentApiPaths.Events)
        assertEquals("/agent/v1/contacts/contact_1/remark", AgentApiPaths.contactRemark("contact_1"))
        assertEquals(
            "/agent/v1/conversations/conv_1/messages",
            AgentApiPaths.conversationMessages("conv_1"),
        )
        assertEquals("/agent/v1/conversations/conv_1/read", AgentApiPaths.conversationRead("conv_1"))
    }

    @Test
    fun searchResultCanSendOnlyWhenServicePhoneIsBoundToAccount() {
        val boundCards = listOf(
            AgentSimCardItem("sim_1", "+8613800000001", "China Mobile", "north"),
            AgentSimCardItem("sim_2", null, null, null),
        )

        assertTrue(
            canSendForSearchResult(
                AgentConversationSearchItem(
                    contactPhoneNumber = "+8613800000000",
                    remark = "VIP",
                    servicePhoneNumber = "+8613800000001",
                    conversationId = "conv_allowed",
                ),
                boundCards,
            ),
        )
        assertFalse(
            canSendForSearchResult(
                AgentConversationSearchItem(
                    contactPhoneNumber = "+8613800000000",
                    remark = "VIP",
                    servicePhoneNumber = "+8613900000001",
                    conversationId = "conv_read_only",
                ),
                boundCards,
            ),
        )
        assertFalse(
            canSendForSearchResult(
                AgentConversationSearchItem(
                    contactPhoneNumber = "+8613800000000",
                    remark = null,
                    servicePhoneNumber = null,
                    conversationId = "conv_unknown_service",
                ),
                boundCards,
            ),
        )
    }

    @Test
    fun updateRemarkChangesContactTitleAndConversationTitle() {
        val store = populatedStore()

        store.updateRemark("contact_1", "Important client")

        assertEquals("Important client", store.contactTitle("contact_1"))
        assertEquals("Important client", store.conversationTitle("conv_1"))
    }

    @Test
    fun contactForConversationReturnsTheConversationContact() {
        val store = populatedStore()

        assertEquals("contact_1", store.contactForConversation("conv_1")?.id)
        assertNull(store.contactForConversation("missing_conv"))
    }

    @Test
    fun contactForPhoneNumberReturnsMatchingContact() {
        val store = populatedStore()

        assertEquals("contact_1", store.contactForPhoneNumber("+1 555 0101")?.id)
        assertNull(store.contactForPhoneNumber("+1 555 0199"))
    }

    @Test
    fun conversationPhoneNumberUsesContactPhoneAndFallsBackToConversationNumber() {
        val store = CustomerServiceStore(
            contacts = listOf(
                AgentContact("contact_1", "Client One", "+1 555 0101", "", "north"),
            ),
            conversations = listOf(
                AgentConversation("conv_1", "contact_1", "+1 555 9999", "north", 0, "Hi", "1800000000000"),
                AgentConversation("conv_2", "missing_contact", "+1 555 0102", "north", 0, "Hello", "1800000000001"),
            ),
            messagesByConversation = emptyMap(),
            menus = emptyList(),
        )

        assertEquals("+1 555 0101", store.conversationPhoneNumber("conv_1"))
        assertEquals("+1 555 0102", store.conversationPhoneNumber("conv_2"))
    }

    @Test
    fun copyMenuPutsScriptIntoDraftWithoutSending() {
        val store = populatedStore()

        store.copyMenuToDraft("menu_1")

        assertEquals("Hello, how can I help?", store.draft)
        assertEquals(3, store.messagesFor("conv_1").size)
    }

    @Test
    fun sendMenuAppendsOutboundMessageAndClearsDraft() {
        val store = populatedStore()

        store.sendMenu("conv_1", "menu_2")

        val sent = store.messagesFor("conv_1").last()
        assertEquals(MessageDirection.Outbound, sent.direction)
        assertEquals("Please wait while I check.", sent.text)
        assertEquals("", store.draft)
        assertTrue(sent.id.startsWith("local_"))
    }

    @Test
    fun storeCanReplaceServerBackedData() {
        val store = CustomerServiceStore.empty()

        store.replaceContacts(
            listOf(
                AgentContact("contact_2", "Client Two", "+1 555 0102", "VIP", "south"),
            ),
        )
        store.replaceConversations(
            listOf(
                AgentConversation("conv_2", "contact_2", "+1 555 0102", "south", 1, "Hi", "1800000000000"),
            ),
        )
        store.replaceMessages(
            "conv_2",
            listOf(
                AgentMessage("msg_9", "conv_2", MessageDirection.Inbound, "Hi", "Received", "1800000000000"),
            ),
        )
        store.replaceMenus(
            listOf(
                AgentMenu("menu_9", "Welcome", "south"),
            ),
        )

        assertEquals("VIP", store.contactTitle("contact_2"))
        assertEquals("VIP", store.conversationTitle("conv_2"))
        assertEquals("Hi", store.messagesFor("conv_2").single().text)
        assertEquals("Welcome", store.menus.single().menu)
    }

    @Test
    fun storeCanAppendServerReplyAndUpdateConversationPreview() {
        val store = populatedStore()
        val reply = AgentMessage(
            id = "msg_out",
            conversationId = "conv_1",
            direction = MessageDirection.Outbound,
            text = "Server confirmed",
            state = "Sent",
            time = "1800000000000",
        )

        store.appendMessage(reply)

        assertEquals(reply, store.messagesFor("conv_1").last())
        assertEquals("Server confirmed", store.conversations.single().lastMessagePreview)
    }

    @Test
    fun inboundNotificationAppearsImmediatelyInConversationAndMessageList() {
        val store = populatedStore()

        store.recordInboundMessage(
            AgentMessage(
                id = "msg_4",
                conversationId = "conv_1",
                direction = MessageDirection.Inbound,
                text = "Syncing incoming message...",
                state = "Received",
                time = "Now",
            ),
            isConversationOpen = false,
        )

        assertEquals("msg_4", store.messagesFor("conv_1").last().id)
        assertEquals("Syncing incoming message...", store.conversations.single().lastMessagePreview)
        assertEquals(3, store.conversations.single().unreadCount)
    }

    @Test
    fun refreshedServerMessageReplacesInboundNotificationWithoutDuplicating() {
        val store = populatedStore()
        store.recordInboundMessage(
            AgentMessage(
                id = "msg_4",
                conversationId = "conv_1",
                direction = MessageDirection.Inbound,
                text = "Syncing incoming message...",
                state = "Received",
                time = "Now",
            ),
            isConversationOpen = true,
        )

        store.replaceMessages(
            "conv_1",
            listOf(
                AgentMessage("msg_1", "conv_1", MessageDirection.Inbound, "Need help", "Received", "09:35"),
                AgentMessage("msg_2", "conv_1", MessageDirection.Outbound, "Checking", "Delivered", "09:36"),
                AgentMessage("msg_3", "conv_1", MessageDirection.Inbound, "Thanks", "Received", "09:42"),
                AgentMessage("msg_4", "conv_1", MessageDirection.Inbound, "Real incoming text", "Received", "09:43"),
            ),
        )

        assertEquals(4, store.messagesFor("conv_1").size)
        assertEquals("Real incoming text", store.messagesFor("conv_1").last().text)
        assertEquals(0, store.conversations.single().unreadCount)
    }

    @Test
    fun refreshedServerMessageReplacesInboundPlaceholderPreviewWithoutMarkingRead() {
        val store = populatedStore()
        store.recordInboundMessage(
            AgentMessage(
                id = "msg_4",
                conversationId = "conv_1",
                direction = MessageDirection.Inbound,
                text = CustomerServiceStore.IncomingMessagePlaceholder,
                state = "Received",
                time = "Now",
            ),
            isConversationOpen = false,
        )

        store.replaceMessages(
            "conv_1",
            listOf(
                AgentMessage("msg_1", "conv_1", MessageDirection.Inbound, "Need help", "Received", "09:35"),
                AgentMessage("msg_2", "conv_1", MessageDirection.Outbound, "Checking", "Delivered", "09:36"),
                AgentMessage("msg_3", "conv_1", MessageDirection.Inbound, "Thanks", "Received", "09:42"),
                AgentMessage("msg_4", "conv_1", MessageDirection.Inbound, "Real incoming text", "Received", "09:43"),
            ),
        )

        assertEquals("Real incoming text", store.conversations.single().lastMessagePreview)
        assertEquals(3, store.conversations.single().unreadCount)
    }

    @Test
    fun duplicateInboundNotificationDoesNotDuplicateMessageOrUnreadCount() {
        val store = populatedStore()
        val message = AgentMessage(
            id = "msg_4",
            conversationId = "conv_1",
            direction = MessageDirection.Inbound,
            text = "Syncing incoming message...",
            state = "Received",
            time = "Now",
        )

        store.recordInboundMessage(message, isConversationOpen = false)
        store.recordInboundMessage(message, isConversationOpen = false)

        assertEquals(4, store.messagesFor("conv_1").size)
        assertEquals(3, store.conversations.single().unreadCount)
    }

    @Test
    fun inboundNotificationCreatesPlaceholderConversationWhenMissing() {
        val store = CustomerServiceStore.empty()

        store.recordInboundMessage(
            AgentMessage(
                id = "msg_1",
                conversationId = "conv_new",
                direction = MessageDirection.Inbound,
                text = "First message",
                state = "Received",
                time = "Now",
            ),
            isConversationOpen = false,
        )

        assertEquals("conv_new", store.conversations.single().id)
        assertEquals("First message", store.conversations.single().lastMessagePreview)
        assertEquals(1, store.conversations.single().unreadCount)
        assertEquals("conv_new", store.conversationTitle("conv_new"))
    }

    @Test
    fun staleConversationRefreshKeepsLocalInboundPlaceholderConversation() {
        val store = CustomerServiceStore.empty()
        store.recordInboundMessage(
            AgentMessage(
                id = "msg_1",
                conversationId = "conv_new",
                direction = MessageDirection.Inbound,
                text = "First message",
                state = "Received",
                time = "Now",
            ),
            isConversationOpen = false,
        )

        store.replaceConversations(emptyList())

        assertEquals("conv_new", store.conversations.single().id)
        assertEquals("First message", store.conversations.single().lastMessagePreview)
    }

    @Test
    fun staleConversationRefreshKeepsLatestLocalInboundPreviewAndUnreadCount() {
        val store = populatedStore()
        store.recordInboundMessage(
            AgentMessage(
                id = "msg_4",
                conversationId = "conv_1",
                direction = MessageDirection.Inbound,
                text = "Newest customer message",
                state = "Received",
                time = "Now",
            ),
            isConversationOpen = false,
        )

        store.replaceConversations(
            listOf(
                AgentConversation(
                    id = "conv_1",
                    contactId = "contact_1",
                    externalPhoneNumber = "+1 555 0101",
                    areas = "north",
                    unreadCount = 2,
                    lastMessagePreview = "Need help",
                    lastMessageAt = "09:42",
                ),
            ),
        )

        assertEquals("Newest customer message", store.conversations.single().lastMessagePreview)
        assertEquals(3, store.conversations.single().unreadCount)
    }

    @Test
    fun staleConversationRefreshKeepsLocalReadState() {
        val store = populatedStore()

        store.markRead("conv_1")
        store.replaceConversations(
            listOf(
                AgentConversation(
                    id = "conv_1",
                    contactId = "contact_1",
                    externalPhoneNumber = "+1 555 0101",
                    areas = "north",
                    unreadCount = 2,
                    lastMessagePreview = "Need help",
                    lastMessageAt = "09:42",
                ),
            ),
        )

        assertEquals(0, store.conversations.single().unreadCount)
    }

    private fun populatedStore(): CustomerServiceStore {
        val contacts = mutableListOf(
            AgentContact(
                id = "contact_1",
                displayName = "Client One",
                phoneNumber = "+1 555 0101",
                remark = "",
                areas = "north",
            ),
        )
        val conversations = mutableListOf(
            AgentConversation(
                id = "conv_1",
                contactId = "contact_1",
                externalPhoneNumber = "+1 555 0101",
                areas = "north",
                unreadCount = 2,
                lastMessagePreview = "Need help",
                lastMessageAt = "09:42",
            ),
        )
        val messages = mutableMapOf(
            "conv_1" to mutableListOf(
                AgentMessage("msg_1", "conv_1", MessageDirection.Inbound, "Need help", "Received", "09:35"),
                AgentMessage("msg_2", "conv_1", MessageDirection.Outbound, "Checking", "Delivered", "09:36"),
                AgentMessage("msg_3", "conv_1", MessageDirection.Inbound, "Thanks", "Received", "09:42"),
            ),
        )
        val menus = listOf(
            AgentMenu("menu_1", "Hello, how can I help?", "north"),
            AgentMenu("menu_2", "Please wait while I check.", "north"),
        )
        return CustomerServiceStore(contacts, conversations, messages, menus)
    }
}
