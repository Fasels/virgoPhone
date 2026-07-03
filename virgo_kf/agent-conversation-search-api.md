# 对话条件查询接口

本文档说明客服端按联系人手机号查询对话的接口。该接口是独立接口，不修改现有会话列表接口。

## 接口

```http
GET /agent/v1/conversation-search?phoneNumber=<联系人手机号>
Authorization: Bearer <agent-token>
```

## 用途

通过联系人手机号查找当前客服可访问的对话，方便后续使用 `conversationId` 调用消息历史接口：

```http
GET /agent/v1/conversations/{conversationId}/messages
Authorization: Bearer <agent-token>
```

## 鉴权和数据范围

- 必须携带客服登录 token。
- 只返回当前客服账号绑定 SIM 下的对话。
- 如果同一个联系人给多个客服号码发过消息，并且这些客服号码对应的 SIM 都绑定给当前客服，则会返回多条记录。
- 不会返回当前客服无权访问的 SIM 对话。

## 查询参数

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `phoneNumber` | 是 | 联系人手机号。服务端会按现有手机号规则做标准化，支持去除空格、括号和短横线。 |

示例：

```http
GET /agent/v1/conversation-search?phoneNumber=%2B8613800000000
Authorization: Bearer agent_xxx
```

## 响应

成功时返回数组：

```json
[
  {
    "contactPhoneNumber": "+8613800000000",
    "remark": "VIP customer",
    "servicePhoneNumber": "+8613800000001",
    "conversationId": "conv_xxx"
  }
]
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `contactPhoneNumber` | string | 联系人手机号。 |
| `remark` | string 或 null | 联系人备注。 |
| `servicePhoneNumber` | string 或 null | 发送或接收该对话的客服 SIM 手机号。 |
| `conversationId` | string | 对话 ID，用于查询消息历史。 |

如果没有匹配结果，返回空数组：

```json
[]
```

## 错误

未登录或 token 无效：

```http
401 Unauthorized
```

手机号格式不合法：

```http
400 Bad Request
```

```json
{
  "code": "VALIDATION_ERROR",
  "message": "phoneNumber is invalid"
}
```

## curl 示例

```bash
curl -G "http://127.0.0.1:8000/agent/v1/conversation-search" \
  -H "Authorization: Bearer agent_xxx" \
  --data-urlencode "phoneNumber=+8613800000000"
```

## Android Retrofit 示例

```kotlin
@Serializable
data class AgentConversationSearchItem(
    val contactPhoneNumber: String,
    val remark: String? = null,
    val servicePhoneNumber: String? = null,
    val conversationId: String,
)

interface AgentApi {
    @GET("/agent/v1/conversation-search")
    suspend fun searchConversations(
        @Query("phoneNumber") phoneNumber: String,
    ): List<AgentConversationSearchItem>
}
```

使用建议：

1. 用户输入联系人手机号。
2. 调用 `GET /agent/v1/conversation-search`。
3. 如果返回一条记录，直接进入该 `conversationId` 的聊天页。
4. 如果返回多条记录，让用户按 `servicePhoneNumber` 选择对应客服号码。
5. 选定后调用 `GET /agent/v1/conversations/{conversationId}/messages` 拉取消息历史。
