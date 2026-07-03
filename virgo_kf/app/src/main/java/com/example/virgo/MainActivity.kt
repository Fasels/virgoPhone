package com.example.virgo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.virgo.ui.theme.VirgoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class HomeTab(
    val title: String,
    @param:DrawableRes val iconRes: Int,
) {
    Conversations("消息", R.drawable.ic_tab_message),
    Mine("我的", R.drawable.ic_tab_mine),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VirgoTheme(dynamicColor = false) {
                CustomerServiceAppFull()
            }
        }
    }
}

@Composable
fun CustomerServiceApp(
    store: CustomerServiceStore = remember { CustomerServiceStore.empty() },
    authClient: AgentAuthClient = remember { AgentAuthClient() },
) {
    var session by remember { mutableStateOf(LoginSessionState()) }
    var tab by remember { mutableStateOf(HomeTab.Conversations) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppColors.Background,
    ) {
        if (!session.isLoggedIn) {
            LoginScreen(
                onLogin = { username, password -> authClient.login(username, password) },
                onLoginSuccess = { result ->
                    session = session.login(
                        token = result.token,
                        agentName = result.agentName,
                        areas = result.areas,
                    )
                },
            )
        } else {
            Scaffold(
                topBar = { AppTopBar(title = tab.title, session = session) },
                bottomBar = {
                    NavigationBar(containerColor = Color.White) {
                        HomeTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
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
                containerColor = AppColors.Background,
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    when (tab) {
                        HomeTab.Conversations -> EmptyState(
                            title = "暂无会话",
                            detail = if (store.conversations.isEmpty()) "登录成功后，这里会显示服务端返回的客服会话。" else "",
                        )

                        HomeTab.Mine -> MineScreen(
                            session = session,
                            onLogout = {
                                session = session.logout()
                                tab = HomeTab.Conversations
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(
    onLogin: (String, String) -> AgentLoginResult,
    onLoginSuccess: (AgentLoginResult) -> Unit,
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
        Text(
            text = "客服工作台",
            color = AppColors.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(34.dp))
        OutlinedTextField(
            value = form.username,
            onValueChange = { form = form.copy(username = it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("账号") },
            placeholder = { Text("请输入客服账号") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = form.password,
            onValueChange = { form = form.copy(password = it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码") },
            placeholder = { Text("请输入密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = {
                scope.launch {
                    isLoading = true
                    errorMessage = null
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            onLogin(form.username, form.password)
                        }
                    }
                    isLoading = false
                    result
                        .onSuccess(onLoginSuccess)
                        .onFailure { error ->
                            errorMessage = error.message ?: "登录失败"
                        }
                }
            },
            enabled = form.canSubmit && !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = if (isLoading) "登录中..." else "登录",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        errorMessage?.let { message ->
            Spacer(Modifier.height(14.dp))
            Text(message, color = AppColors.Badge, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun AppTopBar(title: String, session: LoginSessionState) {
    Surface(color = Color.White, shadowElevation = 1.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(session.agentName, color = AppColors.TextSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(detail, color = AppColors.TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun MineScreen(session: LoginSessionState, onLogout: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(color = Color.White, shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("当前客服", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(session.agentName, color = AppColors.TextPrimary)
                session.areas?.takeIf { it.isNotBlank() }?.let { areas ->
                    Text(areas, color = AppColors.TextSecondary)
                }
            }
        }
        OutlinedButton(onClick = onLogout) {
            Text("退出登录")
        }
    }
}

private object AppColors {
    val Background = Color(0xFFF5F5F5)
    val TextPrimary = Color(0xFF202124)
    val TextSecondary = Color(0xFF7A7D81)
    val Green = Color(0xFF21B35B)
    val Badge = Color(0xFFE5484D)
}

@Preview(showBackground = true)
@Composable
fun CustomerServiceAppPreview() {
    VirgoTheme(dynamicColor = false) {
        CustomerServiceApp(CustomerServiceStore.empty())
    }
}
