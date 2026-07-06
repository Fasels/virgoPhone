package com.example.virgo

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress

class AgentApiClientTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val seenRequests = mutableListOf<SeenRequest>()
    private var serverFailure: Throwable? = null

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun loginPostsCredentialsAndExtractsToken() {
        server.json(AgentApiPaths.Login) { exchange, body ->
            seenRequests += exchange.seen(body)
            assertEquals("POST", exchange.requestMethod)
            assertEquals("""{"username":"agent","password":"secret"}""", body)
            """{"token":"agent_token","expiresAt":1800000000000}"""
        }

        val result = runCatching {
            AgentApiClient(baseUrl = baseUrl).login(" agent ", "secret")
        }.getOrElse { error ->
            throw serverFailure ?: error
        }

        assertEquals("agent_token", result.token)
        assertEquals("agent", result.agentName)
    }

    @Test
    fun authenticatedEndpointsUseBearerTokenAndMapLists() {
        server.json(AgentApiPaths.Contacts) { exchange, body ->
            seenRequests += exchange.seen(body)
            """[{"id":"contact_1","displayName":"Client","phoneNumber":"+100","normalizedPhoneNumber":"+100","remark":"VIP","status":"NORMAL","source":"INBOUND_AUTO","lastContactAt":1800000000000,"areas":"north","updatedAt":1800000000000}]"""
        }
        server.json(AgentApiPaths.Conversations) { exchange, body ->
            seenRequests += exchange.seen(body)
            """[{"id":"conv_1","externalPhoneNumber":"+100","servicePhoneNumber":"+8613800000101","contactId":"contact_1","areas":"north","status":"OPEN","unreadCount":2,"lastMessagePreview":"Hello","lastMessageDirection":"INBOUND","lastMessageAt":1800000000000}]"""
        }
        server.json(AgentApiPaths.conversationMessages("conv_1")) { exchange, body ->
            seenRequests += exchange.seen(body)
            """[{"id":"msg_1","conversationId":"conv_1","direction":"INBOUND","messageType":"SMS","textContent":"Hello","state":"Received","fromPhoneNumber":"+100","toPhoneNumber":null,"createdAt":1800000000000,"receivedAt":1800000000000,"sentAt":null,"deliveredAt":null,"customerSimCard":"+8613800000099","customerRemark":"south support line"}]"""
        }
        server.json(AgentApiPaths.Menus) { exchange, body ->
            seenRequests += exchange.seen(body)
            """[{"id":"menu_1","menu":"Please wait.","updateTime":1800000000000,"updateBy":"acct_1","areas":"north"}]"""
        }

        val client = AgentApiClient(baseUrl = baseUrl, tokenProvider = { "agent_token" })

        assertEquals("VIP", client.contacts().single().remark)
        val conversation = client.conversations().single()
        assertEquals(2, conversation.unreadCount)
        assertEquals("+8613800000101", conversation.servicePhoneNumber)
        val message = client.messages("conv_1").single()
        assertEquals(MessageDirection.Inbound, message.direction)
        assertEquals("+8613800000099", message.customerSimCard)
        assertEquals("south support line", message.customerRemark)
        assertEquals("Please wait.", client.menus().single().menu)
        assertTrue(seenRequests.all { it.authorization == "Bearer agent_token" })
    }

    @Test
    fun messagesMapMmsAttachmentsSortedByPartIdAndDefaultMissingAttachments() {
        server.json(AgentApiPaths.conversationMessages("conv_1")) { exchange, body ->
            seenRequests += exchange.seen(body)
            """
            [
              {
                "id":"msg_mms",
                "conversationId":"conv_1",
                "direction":"INBOUND",
                "messageType":"MMS",
                "textContent":null,
                "state":"Received",
                "createdAt":1800000000000,
                "attachments":[
                  {"id":"att_2","partId":2,"contentType":"image/png","name":null,"size":200,"url":"https://cdn.example.com/two.png"},
                  {"id":"att_1","partId":1,"contentType":"image/jpeg","name":"one.jpg","size":100,"url":"https://cdn.example.com/one.jpg"}
                ]
              },
              {
                "id":"msg_sms",
                "conversationId":"conv_1",
                "direction":"OUTBOUND",
                "messageType":"SMS",
                "textContent":"Plain text",
                "state":"Sent",
                "createdAt":1800000000001
              }
            ]
            """.trimIndent()
        }
        val client = AgentApiClient(baseUrl = baseUrl, tokenProvider = { "agent_token" })

        val messages = client.messages("conv_1")

        assertEquals(2, messages.size)
        assertEquals(AgentMessageType.Mms, messages[0].messageType)
        assertEquals("", messages[0].text)
        assertEquals(listOf("att_1", "att_2"), messages[0].attachments.map { it.id })
        assertEquals("image/jpeg", messages[0].attachments[0].contentType)
        assertEquals("one.jpg", messages[0].attachments[0].name)
        assertEquals(100L, messages[0].attachments[0].size)
        assertEquals("https://cdn.example.com/one.jpg", messages[0].attachments[0].url)
        assertEquals(AgentMessageType.Sms, messages[1].messageType)
        assertEquals(emptyList<AgentMessageAttachment>(), messages[1].attachments)
    }

    @Test
    fun simCardsUsesBearerTokenAndAcceptsNullableFields() {
        server.json(AgentApiPaths.SimCards) { exchange, body ->
            seenRequests += exchange.seen(body)
            """[{"id":"sim_1","phoneNumber":"+8613800000000","carrierName":"China Mobile","areas":"north","customerRemark":"客服 A 专用"},{"id":"sim_2","phoneNumber":null,"carrierName":null,"areas":null,"customerRemark":null}]"""
        }
        val client = AgentApiClient(baseUrl = baseUrl, tokenProvider = { "agent_token" })

        val simCards = client.simCards()

        assertEquals(
            AgentSimCardItem(
                id = "sim_1",
                phoneNumber = "+8613800000000",
                carrierName = "China Mobile",
                areas = "north",
                customerRemark = "客服 A 专用",
            ),
            simCards[0],
        )
        assertEquals(
            AgentSimCardItem(
                id = "sim_2",
                phoneNumber = null,
                carrierName = null,
                areas = null,
            ),
            simCards[1],
        )
        assertTrue(seenRequests.all { it.authorization == "Bearer agent_token" })
    }

    @Test
    fun searchConversationsUsesBearerTokenAndPhoneQuery() {
        server.json(AgentApiPaths.ConversationSearch) { exchange, body ->
            seenRequests += exchange.seen(body)
            assertEquals("GET", exchange.requestMethod)
            assertEquals("phoneNumber=%2B8613800000000", exchange.requestURI.rawQuery)
            """[{"contactPhoneNumber":"+8613800000000","remark":"VIP customer","servicePhoneNumber":"+8613800000001","conversationId":"conv_search"}]"""
        }
        val client = AgentApiClient(baseUrl = baseUrl, tokenProvider = { "agent_token" })

        val results = client.searchConversations("+8613800000000")

        assertEquals(
            AgentConversationSearchItem(
                contactPhoneNumber = "+8613800000000",
                remark = "VIP customer",
                servicePhoneNumber = "+8613800000001",
                conversationId = "conv_search",
            ),
            results.single(),
        )
        assertEquals("Bearer agent_token", seenRequests.single().authorization)
    }

    @Test
    fun mutationsSendExpectedBodiesAndIdempotencyKey() {
        server.json(AgentApiPaths.contactRemark("contact_1")) { exchange, body ->
            seenRequests += exchange.seen(body)
            assertPatch(exchange)
            assertEquals("""{"remark":"Important"}""", body)
            """{"ok":true}"""
        }
        server.json(AgentApiPaths.conversationRead("conv_1")) { exchange, body ->
            seenRequests += exchange.seen(body)
            assertPatch(exchange)
            assertEquals("", body)
            """{"ok":true}"""
        }
        server.json(AgentApiPaths.conversationMessages("conv_1")) { exchange, body ->
            seenRequests += exchange.seen(body)
            assertEquals("POST", exchange.requestMethod)
            assertEquals("""{"text":"Hello"}""", body)
            assertEquals("send-key-1", exchange.requestHeaders.getFirst("Idempotency-Key"))
            """{"id":"msg_out","conversationId":"conv_1","direction":"OUTBOUND","textContent":"Hello","state":"Sent","createdAt":1800000000000}"""
        }

        val client = AgentApiClient(baseUrl = baseUrl, tokenProvider = { "agent_token" })

        assertTrue(client.updateRemark("contact_1", "Important"))
        assertTrue(client.markRead("conv_1"))
        val sent = client.reply("conv_1", "Hello", "send-key-1")

        assertEquals("msg_out", sent.id)
        assertEquals(MessageDirection.Outbound, sent.direction)
        assertTrue(seenRequests.all { it.authorization == "Bearer agent_token" })
    }

    @Test
    fun clientRecordsServerLinkLogsForSuccessfulAndFailedRequests() {
        server.json(AgentApiPaths.Me) { exchange, body ->
            seenRequests += exchange.seen(body)
        """{"id":"agent_1","username":"agent","areas":"north"}"""
        }
        server.json(AgentApiPaths.Contacts, statusCode = 500) { exchange, body ->
            seenRequests += exchange.seen(body)
            """{"message":"server down"}"""
        }
        val logs = ServerLinkLogStore(nowProvider = { 2222L })
        val client = AgentApiClient(
            baseUrl = baseUrl,
            tokenProvider = { "agent_token" },
            logStore = logs,
        )

        client.me()
        runCatching { client.contacts() }

        assertEquals(2, logs.entries.size)
        assertEquals("GET", logs.entries[0].method)
        assertEquals(AgentApiPaths.Me, logs.entries[0].path)
        assertEquals(200, logs.entries[0].statusCode)
        assertEquals("OK", logs.entries[0].message)
        assertEquals(AgentApiPaths.Contacts, logs.entries[1].path)
        assertEquals(500, logs.entries[1].statusCode)
        assertEquals("server down", logs.entries[1].message)
    }

    @Test
    fun meAcceptsNullableAreas() {
        server.json(AgentApiPaths.Me) { exchange, body ->
            seenRequests += exchange.seen(body)
            """{"id":"agent_1","username":"agent","areas":null}"""
        }
        val client = AgentApiClient(baseUrl = baseUrl, tokenProvider = { "agent_token" })

        val me = client.me()

        assertEquals("agent_1", me.id)
        assertEquals("agent", me.username)
        assertEquals(null, me.areas)
    }

    @Test
    fun eventsEndpointUsesInfiniteReadTimeoutWithoutChangingRegularRequests() {
        assertEquals(0, readTimeoutMillisForPath(AgentApiPaths.Events))
        assertEquals(30_000, readTimeoutMillisForPath(AgentApiPaths.Conversations))
    }

    @Test
    fun listenEventsWithReconnectReconnectsAfterStreamCloses() {
        var eventConnections = 0
        server.sse(AgentApiPaths.Events) { exchange ->
            seenRequests += exchange.seen("")
            eventConnections += 1
            """
            event: inbound_message
            data: {"conversationId":"conv_$eventConnections","messageId":"msg_$eventConnections","accountId":"acct_1","simCardId":"sim_$eventConnections"}

            """.trimIndent()
        }
        val received = mutableListOf<String>()
        val sleeps = mutableListOf<Long>()
        val client = AgentApiClient(baseUrl = baseUrl, tokenProvider = { "agent_token" })

        client.listenEventsWithReconnect(
            onInboundMessage = { event ->
                received += "${event.conversationId}/${event.messageId}/${event.accountId}/${event.simCardId}"
            },
            shouldContinue = { eventConnections < 2 },
            retryDelaysMillis = listOf(10L),
            sleep = { delay -> sleeps += delay },
        )

        assertEquals(listOf("conv_1/msg_1/acct_1/sim_1", "conv_2/msg_2/acct_1/sim_2"), received)
        assertEquals(2, eventConnections)
        assertEquals(listOf(10L), sleeps)
        assertTrue(seenRequests.all { it.authorization == "Bearer agent_token" })
    }

    @Test
    fun listenEventsMapsInboundMessageContentWhenPresent() {
        server.sse(AgentApiPaths.Events) { exchange ->
            seenRequests += exchange.seen("")
            """
            event: inbound_message
            data: {"conversationId":"conv_1","messageId":"msg_4","accountId":"acct_1","simCardId":"sim_1","textContent":"Hello from customer","state":"Received","createdAt":1800000000000}

            """.trimIndent()
        }
        val received = mutableListOf<AgentInboundMessageEvent>()
        val client = AgentApiClient(baseUrl = baseUrl, tokenProvider = { "agent_token" })

        client.listenEvents(onInboundMessage = { event -> received += event })

        assertEquals("Hello from customer", received.single().text)
        assertEquals("Received", received.single().state)
        assertEquals("1800000000000", received.single().time)
    }

    @Test
    fun listenEventsWithReconnectDoesNotRetryUnauthorizedEvents() {
        var eventConnections = 0
        server.json(AgentApiPaths.Events, statusCode = 401) { exchange, body ->
            seenRequests += exchange.seen(body)
            eventConnections += 1
            """{"message":"Invalid agent token"}"""
        }
        val client = AgentApiClient(baseUrl = baseUrl, tokenProvider = { "expired_token" })

        val error = runCatching {
            client.listenEventsWithReconnect(
                onInboundMessage = {},
                shouldContinue = { true },
                retryDelaysMillis = listOf(10L),
                sleep = { throw IOException("Should not sleep before throwing 401") },
            )
        }.exceptionOrNull()

        assertTrue(error is AgentApiException)
        assertEquals(401, (error as AgentApiException).statusCode)
        assertEquals(1, eventConnections)
    }

    private fun HttpServer.json(
        path: String,
        statusCode: Int = 200,
        bodyProvider: (HttpExchange, String) -> String,
    ) {
        createContext(path) { exchange ->
            val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            try {
                val response = bodyProvider(exchange, body)
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (error: Throwable) {
                serverFailure = error
                val bytes = """{"message":"server assertion failed"}""".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(500, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
    }

    private fun HttpServer.sse(
        path: String,
        bodyProvider: (HttpExchange) -> String,
    ) {
        createContext(path) { exchange ->
            try {
                val response = bodyProvider(exchange)
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (error: Throwable) {
                serverFailure = error
                val bytes = """{"message":"server assertion failed"}""".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(500, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
    }

    private fun HttpExchange.seen(body: String): SeenRequest = SeenRequest(
        method = requestMethod,
        path = requestURI.path,
        authorization = requestHeaders.getFirst("Authorization"),
        body = body,
    )

    private fun assertPatch(exchange: HttpExchange) {
        val isPatch = exchange.requestMethod == "PATCH"
        val isOverride = exchange.requestMethod == "POST" &&
            exchange.requestHeaders.getFirst("X-HTTP-Method-Override") == "PATCH"
        assertTrue("Expected PATCH or POST with X-HTTP-Method-Override", isPatch || isOverride)
    }

    private data class SeenRequest(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: String,
    )
}
