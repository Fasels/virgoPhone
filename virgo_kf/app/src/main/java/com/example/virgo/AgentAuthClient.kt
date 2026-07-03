package com.example.virgo

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AgentLoginResult(
    val token: String,
    val agentName: String,
    val areas: String,
)

class AgentAuthClient(
    private val baseUrl: String = BuildConfig.AGENT_BASE_URL,
) {
    fun login(username: String, password: String): AgentLoginResult {
        val endpoint = URL(baseUrl.trimEnd('/') + AgentApiPaths.Login)
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val body = """{"username":"${jsonEscape(username.trim())}","password":"${jsonEscape(password)}"}"""
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val response = if (status in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }

            if (status !in 200..299) {
                throw IOException("登录失败，请检查账号或密码")
            }

            val token = TOKEN_PATTERN.find(response)?.groupValues?.get(1).orEmpty()
            if (token.isBlank()) {
                throw IOException("登录响应缺少 token")
            }

            return AgentLoginResult(
                token = token,
                agentName = username.trim(),
                areas = "",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun jsonEscape(value: String): String {
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

    private companion object {
        val TOKEN_PATTERN = Regex(""""token"\s*:\s*"([^"]+)"""")
    }
}
