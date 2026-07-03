package com.example.virgo

data class AgentMessageNotificationContent(
    val title: String,
    val body: String,
)

enum class AgentMessageNotificationDecision(
    val logMessage: String,
) {
    Notify("Posting inbound message notification"),
    SuppressedAppInForeground("Notification suppressed because app is in foreground"),
    SuppressedMissingMessageId("Notification suppressed because inbound event has no messageId"),
}

object AgentMessageNotificationPolicy {
    fun shouldNotify(appInForeground: Boolean, event: AgentInboundMessageEvent): Boolean {
        return decisionFor(appInForeground, event) == AgentMessageNotificationDecision.Notify
    }

    fun decisionFor(
        appInForeground: Boolean,
        event: AgentInboundMessageEvent,
    ): AgentMessageNotificationDecision {
        return when {
            appInForeground -> AgentMessageNotificationDecision.SuppressedAppInForeground
            event.messageId.isBlank() -> AgentMessageNotificationDecision.SuppressedMissingMessageId
            else -> AgentMessageNotificationDecision.Notify
        }
    }

    fun contentFor(event: AgentInboundMessageEvent): AgentMessageNotificationContent {
        val text = event.text.trim()
        return AgentMessageNotificationContent(
            title = "New customer message",
            body = text
                .takeIf { it.isNotBlank() && it != CustomerServiceStore.IncomingMessagePlaceholder }
                ?: "Open the app to view the latest message.",
        )
    }
}
