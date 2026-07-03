# 客服账号负责手机号接口使用文档

## 1. 接口用途

客服端登录后，调用该接口获取“当前客服账号负责的手机号列表”。

每条数据包含：

- 手机号
- 运营商
- 手机号所在地区

该接口适合用于客服端展示“我的手机号”“可服务号码”“当前账号负责区域”等信息。

## 2. 调用前置条件

先调用客服登录接口获取 `agent-token`：

```http
POST /agent/v1/auth/login
Content-Type: application/json
```

请求示例：

```json
{
  "username": "agent01",
  "password": "password"
}
```

登录成功后会返回：

```json
{
  "token": "agent_xxx",
  "expiresAt": 1800000000000
}
```

后续调用手机号列表接口时，把 `token` 放到请求头：

```http
Authorization: Bearer agent_xxx
```

## 3. 接口地址

```http
GET /agent/v1/sim-cards
Authorization: Bearer <agent-token>
```

## 4. 响应示例

```json
[
  {
    "id": "sim_abc",
    "phoneNumber": "+8613800000000",
    "carrierName": "China Mobile",
    "areas": "north"
  },
  {
    "id": "sim_def",
    "phoneNumber": "+8613900000000",
    "carrierName": "China Unicom",
    "areas": "east"
  }
]
```

当前客服账号没有绑定手机号时返回：

```json
[]
```

## 5. 字段说明

| 字段 | 类型 | 是否可空 | 说明 |
| --- | --- | --- | --- |
| `id` | `string` | 否 | SIM 卡 ID，可作为列表 item 的稳定 key |
| `phoneNumber` | `string \| null` | 是 | 手机号；部分设备可能无法读取完整手机号 |
| `carrierName` | `string \| null` | 是 | 运营商名称 |
| `areas` | `string \| null` | 是 | 手机号所在地区，用于展示 |

注意：

- `phoneNumber`、`carrierName`、`areas` 都要按可空字段处理。
- 不要用 `/agent/v1/me` 返回的 `areas` 自行推断负责范围。
- 当前接口返回的列表就是当前客服账号实际绑定的手机号范围。

## 6. curl 调试示例

```bash
curl -X GET "http://127.0.0.1:8000/agent/v1/sim-cards" \
  -H "Authorization: Bearer agent_xxx"
```

## 7. 前端 fetch 示例

```ts
export type AgentSimCardItem = {
  id: string;
  phoneNumber: string | null;
  carrierName: string | null;
  areas: string | null;
};

export async function fetchAgentSimCards(
  baseUrl: string,
  token: string,
): Promise<AgentSimCardItem[]> {
  const response = await fetch(`${baseUrl}/agent/v1/sim-cards`, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });

  if (response.status === 401) {
    throw new Error("登录已失效，请重新登录");
  }
  if (!response.ok) {
    throw new Error("获取负责手机号失败");
  }

  return response.json();
}
```

展示时建议给空值兜底：

```ts
function formatSimCard(item: AgentSimCardItem): string {
  const phone = item.phoneNumber ?? "未读取到手机号";
  const carrier = item.carrierName ?? "未知运营商";
  const area = item.areas ?? "未知地区";
  return `${phone} / ${carrier} / ${area}`;
}
```

## 8. Android Kotlin Retrofit 示例

数据模型：

```kotlin
@Serializable
data class AgentSimCardItem(
    val id: String,
    val phoneNumber: String? = null,
    val carrierName: String? = null,
    val areas: String? = null,
)
```

Retrofit API：

```kotlin
interface AgentApi {
    @GET("/agent/v1/sim-cards")
    suspend fun simCards(): List<AgentSimCardItem>
}
```

如果项目已经通过 OkHttp Interceptor 自动注入 token，接口方法不需要额外传 Header。

如果没有统一注入 token，可以这样写：

```kotlin
interface AgentApi {
    @GET("/agent/v1/sim-cards")
    suspend fun simCards(
        @Header("Authorization") authorization: String,
    ): List<AgentSimCardItem>
}
```

调用示例：

```kotlin
val cards = agentApi.simCards("Bearer $token")
```

UI 展示兜底：

```kotlin
fun AgentSimCardItem.displayText(): String {
    val phone = phoneNumber ?: "未读取到手机号"
    val carrier = carrierName ?: "未知运营商"
    val area = areas ?: "未知地区"
    return "$phone / $carrier / $area"
}
```

## 9. 推荐页面使用方式

登录成功进入首页后，可以立即请求：

```text
GET /agent/v1/sim-cards
```

推荐展示位置：

- “我的”页面：展示当前客服账号负责的手机号列表。
- 会话列表顶部筛选栏：展示当前账号可服务手机号。
- 空状态页面：当返回 `[]` 时提示“当前账号未绑定手机号，请联系管理员”。

## 10. 错误处理

### 401 Unauthorized

说明 token 缺失、格式错误、已过期，或者账号已被禁用。

响应示例：

```json
{
  "code": "UNAUTHORIZED",
  "message": "Invalid agent token"
}
```

客户端处理建议：

1. 清空本地 token。
2. 跳转登录页。
3. 提示“登录已失效，请重新登录”。

### 200 且返回空数组

不是错误，表示当前客服账号没有绑定任何手机号。

客户端处理建议：

```text
当前账号未绑定手机号，请联系管理员
```

## 11. 数据权限说明

服务端按当前登录客服账号 ID 查询绑定关系：

```sql
SELECT s.id, s.phone_number, s.carrier_name, s.areas
FROM account_sim_cards acs
JOIN sim_cards s ON s.id = acs.sim_card_id
WHERE acs.account_id = 当前客服账号 ID
```

因此：

- 客服只能看到自己账号绑定的手机号。
- 即使两个客服账号地区相同，也不会互相看到未绑定的手机号。
- 地区 `areas` 只是展示字段，不再作为前端权限判断依据。
