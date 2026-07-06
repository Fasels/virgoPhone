package com.example.virgo

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.UUID

data class AgentMe(
    val id: String,
    val username: String,
    val areas: String?,
)

data class AgentSimCardItem(
    val id: String,
    val phoneNumber: String?,
    val carrierName: String?,
    val areas: String?,
    val customerRemark: String? = null,
)

data class AgentConversationSearchItem(
    val contactPhoneNumber: String,
    val remark: String?,
    val servicePhoneNumber: String?,
    val conversationId: String,
)

data class AgentInboundMessageEvent(
    val conversationId: String,
    val messageId: String,
    val accountId: String,
    val simCardId: String?,
    val text: String = CustomerServiceStore.IncomingMessagePlaceholder,
    val state: String = "Received",
    val time: String = "Now",
)

fun AgentInboundMessageEvent.toAgentMessage(): AgentMessage {
    return AgentMessage(
        id = messageId,
        conversationId = conversationId,
        direction = MessageDirection.Inbound,
        text = text.ifBlank { CustomerServiceStore.IncomingMessagePlaceholder },
        state = state.ifBlank { "Received" },
        time = time.ifBlank { "Now" },
    )
}

enum class AgentApiRecovery {
    None,
    RefreshConversationsAndLeaveConversation,
}

data class AgentApiErrorHandling(
    val message: String,
    val recovery: AgentApiRecovery,
)

class AgentApiException(
    val statusCode: Int,
    message: String,
) : IOException(message)

class AgentApiClient(
    private val baseUrl: String = BuildConfig.AGENT_BASE_URL,
    private val tokenProvider: () -> String? = { null },
    private val logStore: ServerLinkLogStore? = null,
) {
    fun login(username: String, password: String): AgentLoginResult {
        val body = buildJson(
            "username" to username.trim(),
            "password" to password,
        )
        val response = request(
            method = "POST",
            path = AgentApiPaths.Login,
            body = body,
            requiresAuth = false,
        )
        val json = JsonSupport.objectMap(response)
        val token = json.getValue("token")
        return AgentLoginResult(
            token = token,
            agentName = username.trim(),
            areas = "",
        )
    }

    fun me(): AgentMe {
        val json = JsonSupport.objectMap(request("GET", AgentApiPaths.Me))
        return AgentMe(
            id = json.getValue("id"),
            username = json.getValue("username"),
            areas = json["areas"]?.takeIf { it.isNotBlank() },
        )
    }

    fun contacts(): List<AgentContact> {
        return JsonSupport.objectList(request("GET", AgentApiPaths.Contacts)).map { item ->
            AgentContact(
                id = item.getValue("id"),
                displayName = item["displayName"],
                phoneNumber = item.getValue("phoneNumber"),
                remark = item["remark"].orEmpty(),
                areas = item.getValue("areas"),
                status = item["status"] ?: "NORMAL",
            )
        }
    }

    fun conversations(): List<AgentConversation> {
        return JsonSupport.objectList(request("GET", AgentApiPaths.Conversations)).map { item ->
            AgentConversation(
                id = item.getValue("id"),
                contactId = item.getValue("contactId"),
                externalPhoneNumber = item.getValue("externalPhoneNumber"),
                areas = item.getValue("areas"),
                unreadCount = item["unreadCount"]?.toIntOrNull() ?: 0,
                lastMessagePreview = item["lastMessagePreview"].orEmpty(),
                lastMessageAt = item["lastMessageAt"]?.let(::formatTimestamp).orEmpty(),
            )
        }
    }

    fun messages(conversationId: String): List<AgentMessage> {
        return JsonSupport.objectNodeList(request("GET", AgentApiPaths.conversationMessages(conversationId))).map { item ->
            mapMessage(item, conversationId)
        }
    }

    fun menus(): List<AgentMenu> {
        return JsonSupport.objectList(request("GET", AgentApiPaths.Menus)).map { item ->
            AgentMenu(
                id = item.getValue("id"),
                menu = item.getValue("menu"),
                areas = item.getValue("areas"),
            )
        }
    }

    fun simCards(): List<AgentSimCardItem> {
        return JsonSupport.objectList(request("GET", AgentApiPaths.SimCards)).map { item ->
            AgentSimCardItem(
                id = item.getValue("id"),
                phoneNumber = item["phoneNumber"].toNullableField(),
                carrierName = item["carrierName"].toNullableField(),
                areas = item["areas"].toNullableField(),
                customerRemark = item["customerRemark"].toNullableField(),
            )
        }
    }

    fun searchConversations(phoneNumber: String): List<AgentConversationSearchItem> {
        val encodedPhoneNumber = URLEncoder.encode(phoneNumber, Charsets.UTF_8.name())
        return JsonSupport.objectList(
            request("GET", "${AgentApiPaths.ConversationSearch}?phoneNumber=$encodedPhoneNumber"),
        ).map { item ->
            AgentConversationSearchItem(
                contactPhoneNumber = item.getValue("contactPhoneNumber"),
                remark = item["remark"].toNullableField(),
                servicePhoneNumber = item["servicePhoneNumber"].toNullableField(),
                conversationId = item.getValue("conversationId"),
            )
        }
    }

    fun updateRemark(contactId: String, remark: String): Boolean {
        val response = request(
            method = "PATCH",
            path = AgentApiPaths.contactRemark(contactId),
            body = buildJson("remark" to remark),
        )
        return JsonSupport.objectMap(response)["ok"] == "true"
    }

    fun markRead(conversationId: String): Boolean {
        val response = request(
            method = "PATCH",
            path = AgentApiPaths.conversationRead(conversationId),
        )
        return JsonSupport.objectMap(response)["ok"] == "true"
    }

    fun reply(
        conversationId: String,
        text: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): AgentMessage {
        val response = request(
            method = "POST",
            path = AgentApiPaths.conversationMessages(conversationId),
            body = buildJson("text" to text),
            extraHeaders = mapOf("Idempotency-Key" to idempotencyKey),
        )
        return mapMessage(JsonSupport.objectNode(response), conversationId)
    }

    fun listenEvents(onInboundMessage: (AgentInboundMessageEvent) -> Unit) {
        val connection = openConnection("GET", AgentApiPaths.Events, requiresAuth = true)
        connection.setRequestProperty("Accept", "text/event-stream")
        logStore?.record("SSE", AgentApiPaths.Events, null, "Connecting")
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val response = readError(connection)
                val message = responseMessage(status, response)
                logStore?.record("SSE", AgentApiPaths.Events, status, message)
                throw AgentApiException(status, message)
            }
            logStore?.record("SSE", AgentApiPaths.Events, status, "Connected")
            var eventName = ""
            val dataLines = mutableListOf<String>()
            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.isBlank() -> {
                            dispatchEvent(eventName, dataLines.joinToString("\n"), onInboundMessage)
                            eventName = ""
                            dataLines.clear()
                        }
                        line.startsWith("event:") -> eventName = line.substringAfter(":").trim()
                        line.startsWith("data:") -> dataLines += line.substringAfter(":").trim()
                    }
                }
            }
            dispatchEvent(eventName, dataLines.joinToString("\n"), onInboundMessage)
        } catch (error: IOException) {
            if (error !is AgentApiException) {
                logStore?.record("SSE", AgentApiPaths.Events, null, error.message ?: "Connection failed")
            }
            throw error
        } finally {
            logStore?.record("SSE", AgentApiPaths.Events, null, "Closed")
            connection.disconnect()
        }
    }

    fun listenEventsWithReconnect(
        onInboundMessage: (AgentInboundMessageEvent) -> Unit,
        shouldContinue: () -> Boolean = { true },
        retryDelaysMillis: List<Long> = DefaultSseReconnectDelaysMillis,
        sleep: (Long) -> Unit = { delay -> Thread.sleep(delay) },
    ) {
        var retryIndex = 0
        while (shouldContinue()) {
            try {
                listenEvents(onInboundMessage)
            } catch (error: AgentApiException) {
                if (error.statusCode == 401) throw error
                if (!shouldContinue()) break
                retryIndex = delayBeforeReconnect(retryIndex, retryDelaysMillis, sleep)
                continue
            } catch (_: IOException) {
                if (!shouldContinue()) break
                retryIndex = delayBeforeReconnect(retryIndex, retryDelaysMillis, sleep)
                continue
            }

            if (shouldContinue()) {
                retryIndex = delayBeforeReconnect(retryIndex, retryDelaysMillis, sleep)
            }
        }
    }

    private fun dispatchEvent(
        eventName: String,
        data: String,
        onInboundMessage: (AgentInboundMessageEvent) -> Unit,
    ) {
        if (eventName != "inbound_message" || data.isBlank()) return
        val json = JsonSupport.objectMap(data)
        val conversationId = json["conversationId"].orEmpty()
        val messageId = json["messageId"].orEmpty()
        if (conversationId.isNotBlank() && messageId.isNotBlank()) {
            onInboundMessage(
                AgentInboundMessageEvent(
                    conversationId = conversationId,
                    messageId = messageId,
                    accountId = json["accountId"].orEmpty(),
                    simCardId = json["simCardId"]?.takeIf { it.isNotBlank() },
                    text = (json["textContent"] ?: json["text"]).orEmpty()
                        .ifBlank { CustomerServiceStore.IncomingMessagePlaceholder },
                    state = json["state"].orEmpty().ifBlank { "Received" },
                    time = json["createdAt"]?.let(::formatTimestamp).orEmpty().ifBlank { "Now" },
                ),
            )
        }
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        requiresAuth: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val connection = openConnection(method, path, requiresAuth)
        extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
        }

        try {
            val status = connection.responseCode
            val response = if (status in 200..299) readSuccess(connection) else readError(connection)
            val message = if (status in 200..299) "OK" else responseMessage(status, response)
            logStore?.record(method, path, status, message)
            if (status !in 200..299) {
                throw AgentApiException(status, message)
            }
            return response
        } catch (error: IOException) {
            if (error !is AgentApiException) {
                logStore?.record(method, path, null, error.message ?: "Request failed")
            }
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        method: String,
        path: String,
        requiresAuth: Boolean,
    ): HttpURLConnection {
        val endpoint = URL(baseUrl.trimEnd('/') + path)
        return (endpoint.openConnection() as HttpURLConnection).apply {
            setRequestMethodCompat(method)
            connectTimeout = 15_000
            readTimeout = readTimeoutMillisForPath(path)
            setRequestProperty("Accept", "application/json")
            if (requiresAuth) {
                val token = tokenProvider()
                if (!token.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
        }
    }

    private fun mapMessage(item: Map<String, Any?>, fallbackConversationId: String): AgentMessage {
        val direction = when (item.stringValue("direction").uppercase()) {
            "OUTBOUND" -> MessageDirection.Outbound
            else -> MessageDirection.Inbound
        }
        val messageType = when (item.stringValue("messageType").uppercase()) {
            "DATA_SMS" -> AgentMessageType.DataSms
            "MMS" -> AgentMessageType.Mms
            else -> AgentMessageType.Sms
        }
        return AgentMessage(
            id = item.stringValue("id"),
            conversationId = item.stringValue("conversationId").ifBlank { fallbackConversationId },
            direction = direction,
            text = item.stringValue("textContent").ifBlank { item.stringValue("text") },
            state = item.stringValue("state").ifBlank {
                if (direction == MessageDirection.Outbound) "Sent" else "Received"
            },
            time = item.stringValue("createdAt").let(::formatTimestamp),
            messageType = messageType,
            attachments = item.attachments(),
        )
    }

    private fun Map<String, Any?>.stringValue(key: String): String {
        return when (val value = this[key]) {
            null -> ""
            is String -> value
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> ""
        }
    }

    private fun Map<String, Any?>.attachments(): List<AgentMessageAttachment> {
        val rawAttachments = this["attachments"] as? List<*> ?: return emptyList()
        return rawAttachments.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            AgentMessageAttachment(
                id = item.rawStringValue("id"),
                partId = item.rawStringValue("partId").toIntOrNull() ?: 0,
                contentType = item.rawStringValue("contentType"),
                name = item.rawStringValue("name").takeIf { it.isNotBlank() },
                size = item.rawStringValue("size").toLongOrNull(),
                url = item.rawStringValue("url").takeIf { it.isNotBlank() },
            )
        }.sortedWith(compareBy<AgentMessageAttachment> { it.partId }.thenBy { it.id })
    }

    private fun Map<*, *>.rawStringValue(key: String): String {
        return when (val value = this[key]) {
            null -> ""
            is String -> value
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> ""
        }
    }

    private fun apiException(status: Int, response: String): AgentApiException {
        return AgentApiException(status, responseMessage(status, response))
    }

    private fun responseMessage(status: Int, response: String): String {
        return runCatching {
            JsonSupport.objectMap(response)["message"]
        }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "Request failed: HTTP $status"
    }

    private fun delayBeforeReconnect(
        retryIndex: Int,
        retryDelaysMillis: List<Long>,
        sleep: (Long) -> Unit,
    ): Int {
        val delay = when {
            retryDelaysMillis.isEmpty() -> 0L
            retryIndex >= retryDelaysMillis.lastIndex -> retryDelaysMillis.last()
            else -> retryDelaysMillis[retryIndex]
        }
        if (delay > 0L) sleep(delay)
        return (retryIndex + 1).coerceAtMost((retryDelaysMillis.size - 1).coerceAtLeast(0))
    }

    private fun readSuccess(connection: HttpURLConnection): String {
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun readError(connection: HttpURLConnection): String {
        return connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun buildJson(vararg pairs: Pair<String, String>): String {
        return pairs.joinToString(separator = ",", prefix = "{", postfix = "}") { (name, value) ->
            "\"" + JsonSupport.escape(name) + "\":\"" + JsonSupport.escape(value) + "\""
        }
    }

    private fun HttpURLConnection.setRequestMethodCompat(method: String) {
        runCatching {
            requestMethod = method
        }.onFailure { error ->
            if (method != "PATCH") throw error
            requestMethod = "POST"
            setRequestProperty("X-HTTP-Method-Override", "PATCH")
        }
    }

    private fun formatTimestamp(raw: String): String {
        return raw.takeIf { it.isNotBlank() }.orEmpty()
    }

    private fun String?.toNullableField(): String? {
        return this?.takeIf { it.isNotBlank() }
    }

    companion object {
        private val DefaultSseReconnectDelaysMillis = listOf(
            1_000L,
            2_000L,
            5_000L,
            10_000L,
            30_000L,
        )
    }
}

fun readTimeoutMillisForPath(path: String): Int {
    return if (path == AgentApiPaths.Events) 0 else 30_000
}

fun agentApiErrorHandling(error: Throwable): AgentApiErrorHandling {
    if (error is AgentApiException) {
        if (error.statusCode == 403) {
            return AgentApiErrorHandling(
                message = "当前账号无权限访问该会话",
                recovery = AgentApiRecovery.RefreshConversationsAndLeaveConversation,
            )
        }
        if (error.statusCode == 422 && error.message?.contains("NO_AVAILABLE_DEVICE") == true) {
            return AgentApiErrorHandling(
                message = "发送设备或 SIM 不可用，请稍后重试",
                recovery = AgentApiRecovery.None,
            )
        }
    }
    return AgentApiErrorHandling(
        message = error.message ?: "Request failed.",
        recovery = AgentApiRecovery.None,
    )
}

private object JsonSupport {
    fun objectMap(json: String): Map<String, String> {
        return stringifyMap(objectNode(json))
    }

    fun objectList(json: String): List<Map<String, String>> {
        return objectNodeList(json).map(::stringifyMap)
    }

    fun objectNode(json: String): Map<String, Any?> {
        return asObject(Parser(json).parseValue()).orEmpty()
    }

    fun objectNodeList(json: String): List<Map<String, Any?>> {
        val parsed = Parser(json).parseValue() as? List<*> ?: return emptyList()
        return parsed.mapNotNull(::asObject)
    }

    fun escape(value: String): String {
        return buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }

    private fun stringifyMap(item: Map<String, Any?>): Map<String, String> {
        return item.mapValues { (_, value) ->
            when (value) {
                null -> ""
                is String -> value
                is Number -> value.toString()
                is Boolean -> value.toString()
                else -> ""
            }
        }
    }

    private fun asObject(value: Any?): Map<String, Any?>? {
        val raw = value as? Map<*, *> ?: return null
        val result = linkedMapOf<String, Any?>()
        for ((key, item) in raw) {
            if (key !is String) return null
            result[key] = item
        }
        return result
    }

    private class Parser(private val json: String) {
        private var index = 0

        fun parseValue(): Any? {
            skipWhitespace()
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> {
                    consumeLiteral("true")
                    true
                }
                'f' -> {
                    consumeLiteral("false")
                    false
                }
                'n' -> {
                    consumeLiteral("null")
                    null
                }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val result = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index += 1
                return result
            }
            while (true) {
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index += 1
                        skipWhitespace()
                    }
                    '}' -> {
                        index += 1
                        return result
                    }
                    else -> throw IllegalArgumentException("Invalid JSON object at $index")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index += 1
                return result
            }
            while (true) {
                result += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index += 1
                        skipWhitespace()
                    }
                    ']' -> {
                        index += 1
                        return result
                    }
                    else -> throw IllegalArgumentException("Invalid JSON array at $index")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            return buildString {
                while (index < json.length) {
                    val char = json[index++]
                    when {
                        char == '"' -> return@buildString
                        char == '\\' && index < json.length -> appendEscaped()
                        else -> append(char)
                    }
                }
            }
        }

        private fun StringBuilder.appendEscaped() {
            when (val escaped = json[index++]) {
                '\\' -> append('\\')
                '"' -> append('"')
                '/' -> append('/')
                'b' -> append('\b')
                'f' -> append('\u000C')
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                'u' -> {
                    val hex = json.substring(index, (index + 4).coerceAtMost(json.length))
                    append(hex.toInt(16).toChar())
                    index += 4
                }
                else -> append(escaped)
            }
        }

        private fun parseNumber(): Number {
            val start = index
            if (peek() == '-') index += 1
            while (peek()?.isDigit() == true) index += 1
            if (peek() == '.') {
                index += 1
                while (peek()?.isDigit() == true) index += 1
            }
            if (peek() == 'e' || peek() == 'E') {
                index += 1
                if (peek() == '+' || peek() == '-') index += 1
                while (peek()?.isDigit() == true) index += 1
            }
            val raw = json.substring(start, index)
            return if (raw.contains('.') || raw.contains('e', ignoreCase = true)) {
                raw.toDouble()
            } else {
                raw.toLong()
            }
        }

        private fun consumeLiteral(literal: String) {
            if (!json.startsWith(literal, index)) {
                throw IllegalArgumentException("Expected $literal at $index")
            }
            index += literal.length
        }

        private fun expect(char: Char) {
            skipWhitespace()
            if (peek() != char) throw IllegalArgumentException("Expected $char at $index")
            index += 1
        }

        private fun skipWhitespace() {
            while (peek()?.isWhitespace() == true) index += 1
        }

        private fun peek(): Char? {
            return json.getOrNull(index)
        }
    }
}
