package com.example.virgo

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.virgo.ui.theme.VirgoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

private enum class FullHomeTab(
    val title: String,
    @param:DrawableRes val iconRes: Int,
) {
    Conversations("Messages", R.drawable.ic_tab_message),
    Search("Search", R.drawable.ic_tab_search),
    Mine("Mine", R.drawable.ic_tab_mine),
}

@Composable
fun CustomerServiceAppFull(
    store: CustomerServiceStore = remember { CustomerServiceStore.empty() },
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var session by remember { mutableStateOf(loadLoginSession(appContext)) }
    val serverLinkLogStore = remember { persistentServerLinkLogStore(context.applicationContext) }
    val apiClient = remember { AgentApiClient(tokenProvider = { session.token }, logStore = serverLinkLogStore) }
    var tab by remember { mutableStateOf(FullHomeTab.Conversations) }
    var selectedConversationId by remember { mutableStateOf<String?>(null) }
    var selectedSearchResult by remember { mutableStateOf<AgentConversationSearchItem?>(null) }
    var editingContact by remember { mutableStateOf<AgentContact?>(null) }
    var isServerLogVisible by remember { mutableStateOf(false) }
    val showServerLogs = shouldShowServerLogs(BuildConfig.DEBUG)
    var isRefreshing by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var simCards by remember { mutableStateOf<List<AgentSimCardItem>>(emptyList()) }
    var hasLoadedSimCards by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<AgentConversationSearchItem>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }
    var dataVersion by remember { mutableIntStateOf(0) }
    var notificationPermissionRequested by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationPermissionRequested = true
    }
    val scope = rememberCoroutineScope()

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPermissionRequested) return
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun touchData() {
        dataVersion += 1
    }

    fun applyHomePayload(payload: FullHomePayload) {
        session = session.login(session.token, payload.me.username, payload.me.areas)
        store.replaceContacts(payload.contacts)
        store.replaceConversations(payload.conversations)
        store.replaceMenus(payload.menus)
        touchData()
    }

    suspend fun loadHomePayload(): FullHomePayload {
        return withContext(Dispatchers.IO) {
            FullHomePayload(
                me = apiClient.me(),
                contacts = apiClient.contacts(),
                conversations = apiClient.conversations(),
                menus = apiClient.menus(),
            )
        }
    }

    fun handleError(error: Throwable) {
        if (error is AgentApiException && error.statusCode == 401) {
            session = session.logout()
            clearLoginSession(appContext)
            AgentRealtimeService.stop(appContext)
            selectedConversationId = null
            selectedSearchResult = null
            simCards = emptyList()
            hasLoadedSimCards = false
            errorMessage = "Session expired. Please sign in again."
        } else {
            val handling = agentApiErrorHandling(error)
            errorMessage = handling.message
            if (handling.recovery == AgentApiRecovery.RefreshConversationsAndLeaveConversation) {
                selectedConversationId = null
                selectedSearchResult = null
                scope.launch {
                    isRefreshing = true
                    runCatching { loadHomePayload() }
                        .onSuccess(::applyHomePayload)
                    isRefreshing = false
                }
            }
        }
    }

    suspend fun refreshHomeData() {
        isRefreshing = true
        errorMessage = null
        runCatching { loadHomePayload() }
            .onSuccess(::applyHomePayload)
            .onFailure(::handleError)
        isRefreshing = false
    }

    suspend fun refreshSimCards() {
        errorMessage = null
        runCatching {
            withContext(Dispatchers.IO) { apiClient.simCards() }
        }.onSuccess { loadedSimCards ->
            simCards = loadedSimCards
            hasLoadedSimCards = true
        }.onFailure(::handleError)
    }

    fun searchConversations() {
        val phoneNumber = searchQuery.trim()
        if (phoneNumber.isBlank()) {
            errorMessage = "Enter a phone number."
            return
        }
        scope.launch {
            isSearching = true
            hasSearched = true
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) { apiClient.searchConversations(phoneNumber) }
            }.onSuccess { results ->
                searchResults = results
            }.onFailure(::handleError)
            isSearching = false
        }
    }

    fun openSearchResult(result: AgentConversationSearchItem) {
        scope.launch {
            isRefreshing = true
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) { apiClient.simCards() }
            }.onSuccess { loadedSimCards ->
                simCards = loadedSimCards
                hasLoadedSimCards = true
                selectedSearchResult = result
                selectedConversationId = result.conversationId
            }.onFailure(::handleError)
            isRefreshing = false
        }
    }

    suspend fun refreshConversation(conversationId: String, markRead: Boolean = true) {
        runCatching {
            withContext(Dispatchers.IO) {
                val messages = apiClient.messages(conversationId)
                if (markRead) {
                    apiClient.markRead(conversationId)
                }
                messages
            }
        }.onSuccess { messages ->
            store.replaceMessages(conversationId, messages)
            if (markRead) {
                store.markRead(conversationId)
            }
            touchData()
        }.onFailure(::handleError)
    }

    fun sendMessage(conversationId: String, text: String) {
        val content = text.trim()
        if (content.isBlank()) return
        scope.launch {
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    apiClient.replyAndLoadMessages(conversationId, content)
                }
            }.onSuccess { messages ->
                store.replaceMessages(conversationId, messages)
                store.setDraft("")
                touchData()
            }.onFailure(::handleError)
        }
    }

    fun applyInboundMessage(event: AgentInboundMessageEvent) {
        store.recordInboundMessage(
            message = event.toAgentMessage(),
            isConversationOpen = selectedConversationId == event.conversationId,
        )
        touchData()
    }

    LaunchedEffect(session.token) {
        if (session.isLoggedIn) {
            requestNotificationPermissionIfNeeded()
            AgentRealtimeService.start(appContext)
            refreshHomeData()
            refreshSimCards()
        }
    }

    LaunchedEffect(session.token, selectedConversationId) {
        val conversationId = selectedConversationId
        if (session.isLoggedIn && conversationId != null) refreshConversation(conversationId)
    }

    DisposableEffect(session.token, selectedConversationId, context) {
        val activity = context as? ComponentActivity
        if (activity == null) {
            onDispose {}
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && session.isLoggedIn) {
                    scope.launch {
                        refreshHomeData()
                        selectedConversationId?.let { refreshConversation(it) }
                    }
                }
            }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
    }

    DisposableEffect(session.token, selectedConversationId) {
        if (!session.isLoggedIn) {
            onDispose {}
        } else {
            val unsubscribe = AgentInboundEventBus.subscribe { event ->
                scope.launch {
                    applyInboundMessage(event)
                    refreshHomeData()
                    refreshConversation(
                        conversationId = event.conversationId,
                        markRead = selectedConversationId == event.conversationId,
                    )
                }
            }
            onDispose { unsubscribe() }
        }
    }

    @Suppress("UNUSED_EXPRESSION")
    dataVersion

    Surface(modifier = Modifier.fillMaxSize(), color = FullColors.Background) {
        if (!session.isLoggedIn) {
            FullLoginScreen(
                onLogin = { username, password -> apiClient.login(username, password) },
                onLoginSuccess = { result ->
                    val loggedIn = session.login(result.token, result.agentName, result.areas)
                    session = loggedIn
                    saveLoginSession(appContext, loggedIn)
                    requestNotificationPermissionIfNeeded()
                    AgentRealtimeService.start(appContext)
                },
                showServerLogs = showServerLogs,
                onShowServerLogs = { isServerLogVisible = true },
            )
        } else {
            val conversationId = selectedConversationId
            if (conversationId != null) {
                val searchResult = selectedSearchResult
                val canSend = searchResult?.let { canSendForSearchResult(it, simCards) } ?: true
                val editableContact = store.contactForConversation(conversationId)
                    ?: searchResult?.contactPhoneNumber?.let { store.contactForPhoneNumber(it) }
                val messages = store.messagesFor(conversationId)
                FullChatScreen(
                    title = searchResult?.searchResultTitle() ?: store.conversationTitle(conversationId),
                    editableContact = editableContact,
                    messages = messages,
                    menus = store.menus,
                    draft = store.draft,
                    errorMessage = errorMessage,
                    customerServiceInfoText = customerServiceInfoDisplayText(messages),
                    canSend = canSend,
                    sendDisabledMessage = "当前账号不负责该客服号码，不能发送信息",
                    onBack = {
                        selectedConversationId = null
                        selectedSearchResult = null
                    },
                    onDraftChange = {
                        store.setDraft(it)
                        touchData()
                    },
                    onSend = { sendMessage(conversationId, store.draft) },
                    onEditContact = { editingContact = it },
                    onRetryLoad = { scope.launch { refreshConversation(conversationId) } },
                )
            } else {
                FullHomeScaffold(
                    session = session,
                    tab = tab,
                    store = store,
                    simCards = simCards,
                    hasLoadedSimCards = hasLoadedSimCards,
                    isRefreshing = isRefreshing,
                    errorMessage = errorMessage,
                    searchQuery = searchQuery,
                    searchResults = searchResults,
                    hasSearched = hasSearched,
                    isSearching = isSearching,
                    showServerLogs = showServerLogs,
                    onTabSelected = { selectedTab ->
                        tab = selectedTab
                        if (selectedTab == FullHomeTab.Mine) {
                            scope.launch { refreshSimCards() }
                        }
                    },
                    onSearchQueryChange = { searchQuery = it },
                    onSearchSubmit = ::searchConversations,
                    onSearchResultSelected = ::openSearchResult,
                    onConversationSelected = {
                        selectedSearchResult = null
                        selectedConversationId = it.id
                    },
                    onRefresh = { scope.launch { refreshHomeData() } },
                    onShowServerLogs = { isServerLogVisible = true },
                    onLogout = {
                        session = session.logout()
                        clearLoginSession(appContext)
                        AgentRealtimeService.stop(appContext)
                        tab = FullHomeTab.Conversations
                        selectedConversationId = null
                        selectedSearchResult = null
                        simCards = emptyList()
                        hasLoadedSimCards = false
                        searchQuery = ""
                        searchResults = emptyList()
                        hasSearched = false
                        errorMessage = null
                    },
                )
            }
        }
    }

    editingContact?.let { contact ->
        FullEditRemarkDialog(
            contact = contact,
            onDismiss = { editingContact = null },
            onSave = { remark ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { apiClient.updateRemark(contact.id, remark) }
                    }.onSuccess {
                        store.updateRemark(contact.id, remark)
                        editingContact = null
                        touchData()
                        refreshHomeData()
                    }.onFailure(::handleError)
                }
            },
        )
    }

    if (isServerLogVisible && showServerLogs) {
        FullServerLinkLogDialog(
            entries = serverLinkLogStore.entries.asReversed(),
            onDismiss = { isServerLogVisible = false },
        )
    }
}

private data class FullHomePayload(
    val me: AgentMe,
    val contacts: List<AgentContact>,
    val conversations: List<AgentConversation>,
    val menus: List<AgentMenu>,
)

@Composable
private fun FullLoginScreen(
    onLogin: (String, String) -> AgentLoginResult,
    onLoginSuccess: (AgentLoginResult) -> Unit,
    showServerLogs: Boolean,
    onShowServerLogs: () -> Unit,
) {
    var form by remember { mutableStateOf(LoginFormState()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Agent Workspace", color = FullColors.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(34.dp))
        OutlinedTextField(
            value = form.username,
            onValueChange = { form = form.copy(username = it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Account") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = form.password,
            onValueChange = { form = form.copy(password = it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = {
                scope.launch {
                    isLoading = true
                    errorMessage = null
                    runCatching {
                        withContext(Dispatchers.IO) { onLogin(form.username, form.password) }
                    }.onSuccess(onLoginSuccess).onFailure { error ->
                        errorMessage = error.message ?: "Login failed"
                    }
                    isLoading = false
                }
            },
            enabled = form.canSubmit && !isLoading,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = FullColors.Green),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(if (isLoading) "Signing in..." else "Sign in")
        }
        if (showServerLogs) {
            Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onShowServerLogs,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("服务器日志")
        }
        }
        errorMessage?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = FullColors.Badge, fontSize = 13.sp)
        }
    }
}

@Composable
private fun FullHomeScaffold(
    session: LoginSessionState,
    tab: FullHomeTab,
    store: CustomerServiceStore,
    simCards: List<AgentSimCardItem>,
    hasLoadedSimCards: Boolean,
    isRefreshing: Boolean,
    errorMessage: String?,
    searchQuery: String,
    searchResults: List<AgentConversationSearchItem>,
    hasSearched: Boolean,
    isSearching: Boolean,
    showServerLogs: Boolean,
    onTabSelected: (FullHomeTab) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSearchResultSelected: (AgentConversationSearchItem) -> Unit,
    onConversationSelected: (AgentConversation) -> Unit,
    onRefresh: () -> Unit,
    onShowServerLogs: () -> Unit,
    onLogout: () -> Unit,
) {
    Scaffold(
        topBar = { FullTopBar(tab.title, session, onRefresh) },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                FullHomeTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { onTabSelected(item) },
                        icon = {
                            Icon(
                                painter = painterResource(item.iconRes),
                                contentDescription = item.title,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                        label = { Text(item.title) },
                    )
                }
            }
        },
        containerColor = FullColors.Background,
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                FullStatusLine(isRefreshing, errorMessage)
                when (tab) {
                    FullHomeTab.Conversations -> FullConversationList(store, onConversationSelected)
                    FullHomeTab.Search -> FullSearchScreen(
                        query = searchQuery,
                        results = searchResults,
                        hasSearched = hasSearched,
                        isSearching = isSearching,
                        onQueryChange = onSearchQueryChange,
                        onSearch = onSearchSubmit,
                        onResultSelected = onSearchResultSelected,
                    )
                    FullHomeTab.Mine -> FullMineScreen(
                        session = session,
                        simCards = simCards,
                        hasLoadedSimCards = hasLoadedSimCards,
                        showServerLogs = showServerLogs,
                        onShowServerLogs = onShowServerLogs,
                        onLogout = onLogout,
                    )
                }
            }
        }
    }
}

@Composable
private fun FullSearchScreen(
    query: String,
    results: List<AgentConversationSearchItem>,
    hasSearched: Boolean,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onResultSelected: (AgentConversationSearchItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = Color.White) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Phone number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = RoundedCornerShape(8.dp),
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onSearch,
                    enabled = !isSearching,
                    colors = ButtonDefaults.buttonColors(containerColor = FullColors.Green),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(if (isSearching) "Searching" else "Search")
                }
            }
        }

        when {
            !hasSearched -> FullEmptyState("Search conversations", "Enter a contact phone number to find conversations.")
            isSearching -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Searching conversations", color = FullColors.TextSecondary, fontSize = 13.sp)
                }
            }
            results.isEmpty() -> FullEmptyState("No matches", "No conversations matched this phone number.")
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results, key = { it.conversationId }) { result ->
                    FullSearchResultRow(result, onResultSelected)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun FullSearchResultRow(
    result: AgentConversationSearchItem,
    onResultSelected: (AgentConversationSearchItem) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onResultSelected(result) }
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(result.searchResultTitle(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(result.contactPhoneNumber, color = FullColors.TextSecondary, fontSize = 13.sp)
            Text(
                result.servicePhoneNumber?.let { "Service phone: $it" } ?: "Service phone unavailable",
                color = FullColors.Green,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun AgentConversationSearchItem.searchResultTitle(): String {
    return remark?.takeIf { it.isNotBlank() } ?: contactPhoneNumber
}

@Composable
private fun FullConversationList(store: CustomerServiceStore, onConversationSelected: (AgentConversation) -> Unit) {
    if (store.conversations.isEmpty()) {
        FullEmptyState("No conversations", "Conversations are loaded from /agent/v1/conversations.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(store.conversations, key = { it.id }) { conversation ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onConversationSelected(conversation) }
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(store.conversationTitle(conversation.id), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        conversation.lastMessagePreview,
                        color = FullColors.TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        conversationServicePhoneDisplayText(conversation),
                        color = FullColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        store.conversationPhoneNumber(conversation.id),
                        color = FullColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (conversation.unreadCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Badge(containerColor = FullColors.Badge) {
                            Text(conversation.unreadCount.toString(), color = Color.White)
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun FullChatScreen(
    title: String,
    editableContact: AgentContact?,
    messages: List<AgentMessage>,
    menus: List<AgentMenu>,
    draft: String,
    errorMessage: String?,
    customerServiceInfoText: String?,
    canSend: Boolean,
    sendDisabledMessage: String,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onEditContact: (AgentContact) -> Unit,
    onRetryLoad: () -> Unit,
) {
    var selectedImagePreview by remember { mutableStateOf<AgentImagePreviewTarget?>(null) }
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            Surface(color = Color.White, shadowElevation = 1.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                    }
                    Text(
                        title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (editableContact != null) {
                        TextButton(onClick = { onEditContact(editableContact) }) { Text("Remark") }
                    }
                    TextButton(onClick = onRetryLoad) { Text("Reload") }
                }
            }
        },
        bottomBar = {
            if (canSend) {
                FullChatInputBar(menus, draft, customerServiceInfoText, onDraftChange, onSend)
            } else {
                FullReadOnlyChatBar(sendDisabledMessage, customerServiceInfoText)
            }
        },
        containerColor = FullColors.Background,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            FullStatusLine(false, errorMessage)
            if (messages.isEmpty()) {
                FullEmptyState("No messages", "Message history is loaded from the server.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(messages, key = { it.id }) { message ->
                        FullMessageBubble(
                            message = message,
                            onImagePreviewSelected = { selectedImagePreview = it },
                        )
                    }
                }
            }
        }
    }

    selectedImagePreview?.let { target ->
        FullImagePreviewDialog(
            target = target,
            onDismiss = { selectedImagePreview = null },
            onOpenOriginal = { runCatching { uriHandler.openUri(target.url) } },
        )
    }
}

@Composable
private fun FullMessageBubble(
    message: AgentMessage,
    onImagePreviewSelected: (AgentImagePreviewTarget) -> Unit,
) {
    val isOutbound = message.direction == MessageDirection.Outbound
    val hasText = message.text.isNotBlank()
    val isPendingMms = message.messageType == AgentMessageType.Mms && message.attachments.isEmpty()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = if (isOutbound) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isOutbound) FullColors.Green else Color.White,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(0.78f),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasText) {
                    Text(
                        message.text,
                        color = if (isOutbound) Color.White else FullColors.TextPrimary,
                        fontSize = 15.sp,
                    )
                }
                if (isPendingMms) {
                    FullAttachmentStatus("Image downloading", isOutbound)
                }
                message.attachments.forEach { attachment ->
                    FullAttachmentPreview(attachment, isOutbound, onImagePreviewSelected)
                }
            }
        }
    }
}

@Composable
private fun FullAttachmentPreview(
    attachment: AgentMessageAttachment,
    isOutbound: Boolean,
    onImagePreviewSelected: (AgentImagePreviewTarget) -> Unit,
) {
    val url = attachment.url
    if (url.isNullOrBlank()) {
        FullAttachmentStatus("Image unavailable", isOutbound)
        return
    }
    val previewTarget = attachment.imagePreviewTarget()
    if (previewTarget == null) {
        FullAttachmentLink(attachment.name ?: "Open attachment", url, isOutbound)
        return
    }

    when (val state = rememberRemoteImage(url)) {
        ImageLoadState.Loading -> FullAttachmentStatus("Loading image", isOutbound)
        ImageLoadState.Error -> FullAttachmentLink("Open original image", previewTarget.url, isOutbound)
        is ImageLoadState.Loaded -> {
            Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = previewTarget.description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color(0xFFF3F4F6), RoundedCornerShape(8.dp))
                    .clickable { onImagePreviewSelected(previewTarget) },
            )
        }
    }
}

@Composable
private fun FullImagePreviewDialog(
    target: AgentImagePreviewTarget,
    onDismiss: () -> Unit,
    onOpenOriginal: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val state = rememberRemoteImage(target.url)) {
                    ImageLoadState.Loading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("Loading image", color = Color.White, fontSize = 14.sp)
                        }
                    }
                    ImageLoadState.Error -> {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("Image unavailable", color = Color.White, fontSize = 16.sp)
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = onOpenOriginal) {
                                Text("Open original", color = Color.White)
                            }
                        }
                    }
                    is ImageLoadState.Loaded -> {
                        Image(
                            bitmap = state.bitmap.asImageBitmap(),
                            contentDescription = target.description,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 72.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onOpenOriginal) {
                        Text("Open original", color = Color.White)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun FullAttachmentLink(label: String, url: String, isOutbound: Boolean) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = label,
        color = if (isOutbound) Color.White else FullColors.Green,
        fontSize = 13.sp,
        modifier = Modifier
            .background(
                if (isOutbound) Color.White.copy(alpha = 0.14f) else FullColors.AttachmentBackground,
                RoundedCornerShape(8.dp),
            )
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun FullAttachmentStatus(label: String, isOutbound: Boolean) {
    Text(
        text = label,
        color = if (isOutbound) Color.White else FullColors.TextSecondary,
        fontSize = 13.sp,
        modifier = Modifier
            .background(
                if (isOutbound) Color.White.copy(alpha = 0.14f) else FullColors.AttachmentBackground,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun rememberRemoteImage(url: String): ImageLoadState {
    var state by remember(url) { mutableStateOf<ImageLoadState>(ImageLoadState.Loading) }
    LaunchedEffect(url) {
        state = ImageLoadState.Loading
        state = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection().apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                connection.getInputStream().use { input ->
                    BitmapFactory.decodeStream(input)?.let(ImageLoadState::Loaded) ?: ImageLoadState.Error
                }
            }.getOrElse { ImageLoadState.Error }
        }
    }
    return state
}

private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data object Error : ImageLoadState
    data class Loaded(val bitmap: Bitmap) : ImageLoadState
}

@Composable
private fun FullReadOnlyChatBar(message: String, customerServiceInfoText: String?) {
    Surface(color = Color.White, shadowElevation = 1.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                message,
                color = FullColors.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            FullCustomerServiceInfoLine(customerServiceInfoText)
        }
    }
}

@Composable
private fun FullChatInputBar(
    menus: List<AgentMenu>,
    draft: String,
    customerServiceInfoText: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    var remindersExpanded by remember { mutableStateOf(false) }
    var selectedReminder by remember { mutableStateOf<AgentMenu?>(null) }
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(ClipboardManager::class.java)

    fun copyReminder(menu: AgentMenu) {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("客服提醒", menu.menu))
        Toast.makeText(context, "已复制客服提醒", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .navigationBarsPadding()
            .imePadding()
            .padding(10.dp),
    ) {
        if (remindersExpanded && menus.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(menus, key = { it.id }) { menu ->
                    FullReminderCard(
                        menu = menu,
                        onOpen = { selectedReminder = it },
                        onCopy = ::copyReminder,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { remindersExpanded = !remindersExpanded },
                enabled = menus.isNotEmpty(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(reminderButtonLabel(), fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a reply") },
                minLines = 1,
                maxLines = 4,
                shape = RoundedCornerShape(8.dp),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSend,
                enabled = draft.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = FullColors.Green),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Send")
            }
        }
        FullCustomerServiceInfoLine(customerServiceInfoText)
    }

    selectedReminder?.let { menu ->
        AlertDialog(
            onDismissRequest = { selectedReminder = null },
            title = { Text(reminderCardTitle(menu.id)) },
            text = { Text(menu.menu, color = FullColors.TextPrimary, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = { selectedReminder = null }) {
                    Text("关闭")
                }
            },
        )
    }
}

@Composable
private fun FullCustomerServiceInfoLine(text: String?) {
    val displayText = text?.takeIf { it.isNotBlank() } ?: return
    Spacer(Modifier.height(6.dp))
    Text(
        displayText,
        color = FullColors.TextSecondary,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullReminderCard(
    menu: AgentMenu,
    onOpen: (AgentMenu) -> Unit,
    onCopy: (AgentMenu) -> Unit,
) {
    Surface(
        color = Color(0xFFF4F8F4),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 1.dp,
        modifier = Modifier
            .width(176.dp)
            .height(88.dp)
            .combinedClickable(
                onClick = { onOpen(menu) },
                onLongClick = { onCopy(menu) },
            ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                reminderCardTitle(menu.id),
                color = FullColors.Green,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                reminderCardPreview(menu.menu),
                color = FullColors.TextPrimary,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FullEditRemarkDialog(contact: AgentContact, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var remark by remember(contact.id) { mutableStateOf(contact.remark) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit remark") },
        text = {
            OutlinedTextField(
                value = remark,
                onValueChange = { remark = it },
                label = { Text("Remark") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(remark) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FullTopBar(title: String, session: LoginSessionState, onRefresh: () -> Unit) {
    Surface(color = Color.White, shadowElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(session.agentName, color = FullColors.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
    }
}

@Composable
private fun FullStatusLine(isRefreshing: Boolean, errorMessage: String?) {
    if (!isRefreshing && errorMessage.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Loading", color = FullColors.TextSecondary, fontSize = 13.sp)
        }
        errorMessage?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = FullColors.Badge, fontSize = 13.sp)
        }
    }
}

@Composable
private fun FullEmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = FullColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(detail, color = FullColors.TextSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun FullMineScreen(
    session: LoginSessionState,
    simCards: List<AgentSimCardItem>,
    hasLoadedSimCards: Boolean,
    showServerLogs: Boolean,
    onShowServerLogs: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = Color.White, shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Current agent", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(session.agentName, color = FullColors.TextPrimary)
                session.areas?.takeIf { it.isNotBlank() }?.let { areas ->
                    Text(areas, color = FullColors.TextSecondary)
                }
            }
        }
        FullSimCardsSection(simCards, hasLoadedSimCards)
        if (showServerLogs) {
        OutlinedButton(onClick = onShowServerLogs, modifier = Modifier.fillMaxWidth()) {
            Text("服务器日志")
        }
        }
        OutlinedButton(onClick = onLogout) {
            Text("Sign out")
        }
    }
}

@Composable
private fun FullSimCardsSection(
    simCards: List<AgentSimCardItem>,
    hasLoadedSimCards: Boolean,
) {
    Surface(color = Color.White, shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("SIM cards", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            when {
                !hasLoadedSimCards -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading SIM cards", color = FullColors.TextSecondary, fontSize = 13.sp)
                }
                simCards.isEmpty() -> Text(
                    "No phone numbers are bound to this account. Please contact an administrator.",
                    color = FullColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                else -> FullSimCardsTable(simCards)
            }
        }
    }
}

@Composable
private fun FullSimCardsTable(simCards: List<AgentSimCardItem>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().background(FullColors.Background).padding(vertical = 8.dp)) {
            FullSimCardCell("Phone number", weight = 1.35f, isHeader = true)
            FullSimCardCell("Remark", weight = 1f, isHeader = true)
            FullSimCardCell("Area", weight = 0.85f, isHeader = true)
        }
        simCards.forEach { simCard ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                FullSimCardCell(simCard.phoneNumber ?: "Unread phone number", weight = 1.35f)
                FullSimCardCell(simCardRemarkDisplayText(simCard), weight = 1f)
                FullSimCardCell(simCard.areas ?: "Unknown area", weight = 0.85f)
            }
            HorizontalDivider(color = FullColors.Background)
        }
    }
}

internal fun simCardRemarkDisplayText(simCard: AgentSimCardItem): String {
    return simCard.customerRemark ?: "No remark"
}

@Composable
private fun RowScope.FullSimCardCell(
    text: String,
    weight: Float,
    isHeader: Boolean = false,
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight).padding(horizontal = 6.dp),
        color = if (isHeader) FullColors.TextPrimary else FullColors.TextSecondary,
        fontSize = if (isHeader) 12.sp else 13.sp,
        fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun FullServerLinkLogDialog(
    entries: List<ServerLinkLogEntry>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务器日志") },
        text = {
            if (entries.isEmpty()) {
                Text("暂无服务器链接日志", color = FullColors.TextSecondary)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    items(entries) { entry ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                entry.toDisplayText(),
                                color = FullColors.TextPrimary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

private object FullColors {
    val Background = Color(0xFFF5F5F5)
    val TextPrimary = Color(0xFF202124)
    val TextSecondary = Color(0xFF7A7D81)
    val Green = Color(0xFF21B35B)
    val Badge = Color(0xFFE5484D)
    val AttachmentBackground = Color(0xFFF3F4F6)
}

@Preview(showBackground = true)
@Composable
private fun CustomerServiceAppFullPreview() {
    VirgoTheme(dynamicColor = false) {
        CustomerServiceAppFull(CustomerServiceStore.empty())
    }
}
