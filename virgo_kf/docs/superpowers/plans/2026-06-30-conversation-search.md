# Conversation Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a phone-number search page that opens matching conversations and prevents sending when the searched conversation's service phone is not bound to the current agent.

**Architecture:** Add the `/agent/v1/conversation-search` path and an `AgentConversationSearchItem` DTO in the API layer. Keep search UI state in `CustomerServiceAppFull`, reuse `FullChatScreen`, and pass a `canSend` flag derived from the selected search result plus the current `simCards` list.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, JUnit 4, existing `HttpURLConnection` client.

---

### Task 1: API Search Endpoint

**Files:**
- Modify: `app/src/main/java/com/example/virgo/AgentApiPaths.kt`
- Modify: `app/src/main/java/com/example/virgo/AgentApiClient.kt`
- Test: `app/src/test/java/com/example/virgo/AgentApiClientTest.kt`
- Test: `app/src/test/java/com/example/virgo/CustomerServiceStoreTest.kt`

- [ ] **Step 1: Write failing tests**

Add tests that expect `AgentApiPaths.ConversationSearch == "/agent/v1/conversation-search"` and `AgentApiClient.searchConversations("+8613800000000")` to send a bearer-authenticated GET request with the encoded phone number and map `contactPhoneNumber`, `remark`, `servicePhoneNumber`, and `conversationId`.

- [ ] **Step 2: Run tests to verify failure**

Run: `.\gradlew.bat testDebugUnitTest --tests com.example.virgo.AgentApiClientTest --tests com.example.virgo.CustomerServiceStoreTest`
Expected: fails because `ConversationSearch` and `searchConversations` do not exist.

- [ ] **Step 3: Implement minimal API support**

Add `ConversationSearch` to `AgentApiPaths`, add `AgentConversationSearchItem`, add `searchConversations(phoneNumber: String)`, and add simple query encoding with `URLEncoder`.

- [ ] **Step 4: Run tests to verify pass**

Run the same unit test command.
Expected: tests pass.

### Task 2: Search Result Send Eligibility

**Files:**
- Modify: `app/src/main/java/com/example/virgo/CustomerServiceStore.kt`
- Test: `app/src/test/java/com/example/virgo/CustomerServiceStoreTest.kt`

- [ ] **Step 1: Write failing tests**

Add tests for a helper that returns true when `servicePhoneNumber` appears in bound SIM card phone numbers, false when it does not, and false when the service phone is null or blank.

- [ ] **Step 2: Run tests to verify failure**

Run: `.\gradlew.bat testDebugUnitTest --tests com.example.virgo.CustomerServiceStoreTest`
Expected: fails because the helper does not exist.

- [ ] **Step 3: Implement minimal helper**

Add `canSendForSearchResult(result, simCards)` as a top-level function near the related data models.

- [ ] **Step 4: Run tests to verify pass**

Run the same unit test command.
Expected: tests pass.

### Task 3: Compose Search Page And Read-Only Chat

**Files:**
- Modify: `app/src/main/java/com/example/virgo/CustomerServiceAppFull.kt`

- [ ] **Step 1: Add navigation state**

Add a `Search` tab, search query/results/loading state, and selected search result state.

- [ ] **Step 2: Add search action**

Call `apiClient.searchConversations(query)` on search button click, show loading and errors via existing status line, and open the chat when a result row is selected.

- [ ] **Step 3: Reuse chat screen with send permission**

Extend `FullChatScreen` and `FullChatInputBar` with `canSend` and `sendDisabledMessage`. When `canSend` is false, show the disabled message in the bottom area and do not render the input or send button.

- [ ] **Step 4: Keep existing conversation behavior**

For normal conversation list selections, pass `canSend = true`. For search result selections, pass `canSend = canSendForSearchResult(result, simCards)`.

### Task 4: Verification

**Files:**
- All modified files.

- [ ] **Step 1: Run focused unit tests**

Run: `.\gradlew.bat testDebugUnitTest --tests com.example.virgo.AgentApiClientTest --tests com.example.virgo.CustomerServiceStoreTest`
Expected: pass.

- [ ] **Step 2: Run debug build**

Run: `.\gradlew.bat assembleDebug`
Expected: pass and generate `app/build/outputs/apk/debug/kf-debug.apk`.

