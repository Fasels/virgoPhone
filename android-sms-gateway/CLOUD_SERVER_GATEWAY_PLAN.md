# 局域网多设备短信网关服务器方案

## 1. 背景与目标

本方案面向一个部署在局域网内的服务器，用作多部 Android 手机的短信网关层。手机通过同一 Wi-Fi 或内网地址连接服务器，负责真实短信收发；服务器负责设备管理、任务调度、会话管理、状态追踪、回调通知和业务侧 API。

如果后续需要跨网络访问，可以在局域网方案稳定后再增加 VPN、内网穿透、反向代理或公网 HTTPS 部署。本方案优先按“手机和服务器在同一局域网”设计。

核心目标：

- 支持多台手机通过局域网同时接入服务器。
- 支持业务系统通过统一 API 发送短信。
- 支持手机拉取短信任务并回传发送状态。
- 支持手机上报收到的短信。
- 支持基于手机号的多轮短信会话。
- 支持设备、SIM 卡、限速、健康状态和路由策略管理。
- 尽量兼容当前 Android 项目已有的 Gateway API 语义，降低手机端改动成本。
- 支持固定 IP、局域网域名、mDNS 或二维码配置服务器地址。

非目标：

- 服务器本身不直接接入运营商短信通道。
- 服务器不直接发送短信，真实发送动作始终由 Android 设备完成。
- 第一阶段不做复杂客服系统、工单系统或营销自动化，只提供可扩展的会话和消息基础能力。

## 2. 总体架构

推荐采用三类 API 和一组内部服务模块：

```text
业务系统 / 管理后台 / 内网服务
        |
        | 业务侧 API / 管理侧 API
        v
局域网短信网关服务器
        |
        | 局域网手机侧 API + SSE/WebSocket
        v
多台 Android 手机
        |
        | SMS / MMS
        v
外部联系人
```

### 2.1 API 分层

| API 类型 | 调用方 | 作用 |
| --- | --- | --- |
| 手机侧 API | Android 手机 | 通过局域网注册设备、心跳、拉取待发送短信、回传状态、上报收到短信、获取配置 |
| 业务侧 API | 业务系统、机器人、CRM | 创建短信任务、查询状态、创建会话、读取收件箱、配置 webhook |
| 管理侧 API | 管理后台、运维系统 | 管理设备、SIM、限流、路由策略、告警、审计 |

### 2.2 局域网连接模式

手机连接服务器时，建议按以下优先级配置服务器地址：

| 方式 | 示例 | 适用场景 |
| --- | --- | --- |
| 固定内网 IP | `http://192.168.1.10:8080/mobile/v1` | 最简单，适合小规模部署 |
| 局域网域名 | `http://sms-gateway.lan:8080/mobile/v1` | 路由器或内网 DNS 可控时使用 |
| mDNS | `http://sms-gateway.local:8080/mobile/v1` | 希望自动发现服务器时使用 |
| 二维码配置 | 扫码写入服务器 URL 和注册 token | 适合批量接入手机 |
| VPN 地址 | `https://10.8.0.2/mobile/v1` | 手机不在同一 Wi-Fi，但通过 VPN 进入内网 |

第一阶段推荐使用固定内网 IP 或局域网域名。服务器应绑定内网网卡地址，例如：

```text
http://192.168.1.10:8080
```

Android App 的服务器地址配置为以下值；如果现有界面仍命名为 Cloud Server URL，也填写这个局域网地址：

```text
http://192.168.1.10:8080/mobile/v1
```

如果沿用当前无前缀兼容接口，也可以配置为：

```text
http://192.168.1.10:8080
```

### 2.3 网络要求

- 手机和服务器必须处于同一局域网，或者手机能通过 VPN 访问服务器内网地址。
- 服务器防火墙需要放行 API 端口，例如 `8080` 或 `8443`。
- 路由器需要允许 Wi-Fi 设备访问局域网内其他主机，不能开启 AP 隔离。
- 如果使用 SSE/WebSocket，需要保证局域网代理或网关不会过早断开长连接。
- 如果使用 HTTP 明文连接，Android 端必须允许访问该局域网 HTTP 地址。

### 2.4 内部模块

| 模块 | 职责 |
| --- | --- |
| Auth/Tenant Service | API Key、JWT、租户隔离、权限控制 |
| Device Registry | 设备注册、设备状态、SIM 信息、在线判断 |
| Message Queue | 短信任务队列、优先级、过期、重试、幂等 |
| Router | 根据设备、SIM、分组、负载、成功率选择发送设备 |
| Conversation Service | 多轮会话、上下文绑定、收发消息归档 |
| Inbox Service | 处理手机上报的入站短信和 MMS |
| State Tracker | 管理 queued、pulled、sent、delivered、failed 等状态 |
| Webhook Service | 将收信、发送状态变化、设备异常推送给业务系统 |
| Settings Service | 给手机下发远程配置 |
| Monitoring/Audit | 日志、指标、告警、审计记录 |

## 3. 核心设计原则

1. 局域网服务器是状态中心，手机是执行节点。
2. 业务侧不直接关心哪台手机发送，除非业务显式指定设备或 SIM。
3. 多轮通信必须有 `conversationId`，不能只依赖 `messageId`。
4. 发给同一个外部号码的连续对话，优先复用同一台设备和同一张 SIM。
5. 所有创建短信任务的接口必须支持幂等，避免重复发送。
6. 手机和服务器之间要允许 Wi-Fi 断开、服务器重启、手机锁屏和重试。
7. 服务器必须完整记录任务生命周期，便于排查短信是否真正发送、是否送达、为什么失败。

## 4. 手机侧 API

手机侧 API 建议使用 `/mobile/v1` 前缀。当前 Android 项目已有 `GatewayApi`，其中已包含 `/device`、`/message`、`/webhooks`、`/settings`、`/user/code` 等调用语义，服务器实现应优先兼容这些路径。

局域网部署时，示例基础地址如下：

```text
http://192.168.1.10:8080/mobile/v1
```

如果 Android 端暂时不方便支持 HTTP，需要二选一：

- 在局域网服务器上配置 HTTPS，例如 `https://sms-gateway.lan:8443/mobile/v1`。
- 修改 Android 端网络安全配置，允许连接指定内网 HTTP 地址。

### 4.1 获取服务器连接信息

```http
GET /mobile/v1/device
Authorization: Bearer <device_token>
```

用途：

- 手机检测当前访问服务器时的来源 IP。
- 局域网部署时可用于判断手机是否真的从内网访问服务器。
- 可用于 UI 展示、调试、健康检测。

响应：

```json
{
  "externalIp": "192.168.1.23",
  "serverMode": "LAN",
  "serverTime": "2026-06-16T10:00:00Z"
}
```

### 4.2 设备注册

```http
POST /mobile/v1/device
Authorization: Bearer <private_token>
Content-Type: application/json
```

也可以支持：

- `Authorization: Basic <base64(login:password)>`
- `Authorization: Code <registration_code>`
- 匿名注册，取决于部署策略。

局域网部署建议关闭匿名注册，使用私有注册 token 或一次性注册码，避免同一 Wi-Fi 内的未知设备随意接入。

请求：

```json
{
  "name": "Samsung/SM-G9910",
  "pushToken": null,
  "simCards": [
    {
      "slotIndex": 0,
      "simNumber": 1,
      "phoneNumber": "+8613800000000",
      "carrierName": "China Mobile",
      "iccid": "****1234"
    }
  ]
}
```

响应：

```json
{
  "id": "dev_01HZX...",
  "token": "device-jwt-or-random-token",
  "login": "device-login",
  "password": "initial-password"
}
```

服务端行为：

- 创建或绑定设备。
- 保存设备名、push token、SIM 卡信息。
- 返回设备级 token，后续手机侧 API 均使用该 token。
- 记录 `registeredAt`、`lastSeenAt`、`status=online`。

### 4.3 设备更新与心跳

```http
PATCH /mobile/v1/device
Authorization: Bearer <device_token>
Content-Type: application/json
```

请求：

```json
{
  "id": "dev_01HZX...",
  "pushToken": null,
  "simCards": [
    {
      "slotIndex": 0,
      "simNumber": 1,
      "phoneNumber": "+8613800000000",
      "carrierName": "China Mobile",
      "iccid": "****1234"
    }
  ],
  "health": {
    "batteryLevel": 76,
    "isCharging": true,
    "networkType": "wifi",
    "appVersion": "1.35.0",
    "smsPermission": true,
    "defaultSmsApp": false
  }
}
```

响应：

```json
{
  "ok": true
}
```

服务端行为：

- 更新设备和 SIM 信息。
- 更新在线状态和健康状态。
- 若设备被禁用，响应可以带 `disabled=true`，手机端应停止拉取任务。

### 4.4 拉取待发送短信

```http
GET /mobile/v1/message?order=fifo&limit=10
Authorization: Bearer <device_token>
```

响应：

```json
[
  {
    "id": "msg_01HZY...",
    "phoneNumbers": ["+8613900000000"],
    "textMessage": {
      "text": "验证码是 123456"
    },
    "simNumber": 1,
    "withDeliveryReport": true,
    "isEncrypted": false,
    "validUntil": "2026-06-14T12:00:00Z",
    "scheduleAt": null,
    "priority": 10,
    "createdAt": "2026-06-14T11:50:00Z"
  }
]
```

服务端行为：

- 只返回分配给该设备的任务。
- 对返回的任务加锁或标记为 `pulled`，避免被其他设备重复拉取。
- 支持 `fifo` 和 `lifo`。
- 跳过已过期、设备不匹配、SIM 不可用的任务。

### 4.5 回传短信状态

```http
PATCH /mobile/v1/message
Authorization: Bearer <device_token>
Content-Type: application/json
```

请求：

```json
[
  {
    "id": "msg_01HZY...",
    "state": "DELIVERED",
    "recipients": [
      {
        "phoneNumber": "+8613900000000",
        "state": "DELIVERED",
        "error": null
      }
    ],
    "states": {
      "QUEUED": "2026-06-14T11:50:00Z",
      "PROCESSED": "2026-06-14T11:50:05Z",
      "SENT": "2026-06-14T11:50:08Z",
      "DELIVERED": "2026-06-14T11:50:15Z"
    }
  }
]
```

服务端行为：

- 更新消息状态。
- 更新每个收件人的状态。
- 触发 webhook，例如 `message.sent`、`message.delivered`、`message.failed`。
- 如果消息属于某个会话，同时写入会话时间线。

### 4.6 上报收到短信

```http
POST /mobile/v1/inbox
Authorization: Bearer <device_token>
Content-Type: application/json
```

请求：

```json
{
  "id": "in_01HZ...",
  "deviceId": "dev_01HZX...",
  "simNumber": 1,
  "from": "+8613900000000",
  "to": "+8613800000000",
  "text": "你好，我收到了",
  "receivedAt": "2026-06-14T11:55:00Z",
  "type": "SMS",
  "raw": {
    "threadId": "android-thread-id",
    "subscriptionId": 1
  }
}
```

响应：

```json
{
  "ok": true,
  "conversationId": "conv_01HZ..."
}
```

服务端行为：

- 根据 `tenantId + from + to/deviceId/simNumber` 匹配会话。
- 如果存在开放会话，则追加入站消息。
- 如果不存在开放会话，则创建新会话。
- 触发 webhook：`conversation.message.received` 和 `inbox.message.received`。

### 4.7 获取远程配置

```http
GET /mobile/v1/settings
Authorization: Bearer <device_token>
```

响应：

```json
{
  "messages": {
    "send_interval_min": 3,
    "send_interval_max": 15,
    "limit_period": "PerMinute",
    "limit_value": 20,
    "sim_selection_mode": "RoundRobin",
    "messages_processing_order": "FIFO"
  },
  "gateway": {
    "notification_channel": "SSE_ONLY"
  },
  "webhooks": {
    "internet_required": true,
    "retry_count": 3
  }
}
```

### 4.8 实时通知通道

局域网内推荐优先支持 SSE 或 WebSocket。FCM 依赖公网服务，不适合作为局域网优先方案的核心通知机制。

```http
GET /mobile/v1/events
Authorization: Bearer <device_token>
Accept: text/event-stream
```

事件示例：

```text
event: message.enqueued
data: {"messageId":"msg_01HZY..."}

event: settings.updated
data: {}

event: webhooks.updated
data: {}
```

手机收到 `message.enqueued` 后立即调用 `GET /mobile/v1/message` 拉取任务。如果 SSE 断开，手机应退回到定时轮询，例如每 10 到 30 秒拉取一次。

## 5. 业务侧 API

业务侧 API 建议使用 `/api/v1` 前缀，认证使用 API Key 或 OAuth/JWT。

### 5.1 创建短信任务

```http
POST /api/v1/messages
Authorization: Bearer <api_token>
Idempotency-Key: order-123-sms-1
Content-Type: application/json
```

请求：

```json
{
  "phoneNumbers": ["+8613900000000"],
  "text": "验证码是 123456",
  "conversationId": null,
  "deviceId": null,
  "deviceGroupId": "grp_cn_mobile",
  "simNumber": null,
  "withDeliveryReport": true,
  "validUntil": "2026-06-14T12:00:00Z",
  "scheduleAt": null,
  "priority": 10,
  "metadata": {
    "orderId": "order-123"
  }
}
```

响应：

```json
{
  "id": "msg_01HZY...",
  "state": "QUEUED",
  "conversationId": "conv_01HZ...",
  "deviceId": "dev_01HZX...",
  "simNumber": 1,
  "createdAt": "2026-06-14T11:50:00Z"
}
```

服务端行为：

- 校验手机号和内容。
- 校验幂等键，如果重复提交则返回原消息。
- 若指定 `conversationId`，优先沿用会话绑定设备和 SIM。
- 若未指定设备，交给 Router 分配。
- 创建消息任务并通知目标设备。

### 5.2 查询短信详情

```http
GET /api/v1/messages/{messageId}
Authorization: Bearer <api_token>
```

响应：

```json
{
  "id": "msg_01HZY...",
  "state": "DELIVERED",
  "phoneNumbers": ["+8613900000000"],
  "deviceId": "dev_01HZX...",
  "simNumber": 1,
  "conversationId": "conv_01HZ...",
  "recipients": [
    {
      "phoneNumber": "+8613900000000",
      "state": "DELIVERED",
      "error": null
    }
  ],
  "states": {
    "QUEUED": "2026-06-14T11:50:00Z",
    "PROCESSED": "2026-06-14T11:50:05Z",
    "SENT": "2026-06-14T11:50:08Z",
    "DELIVERED": "2026-06-14T11:50:15Z"
  }
}
```

### 5.3 查询短信列表

```http
GET /api/v1/messages?state=FAILED&deviceId=dev_01HZX&from=2026-06-14T00:00:00Z&to=2026-06-15T00:00:00Z&page=1&pageSize=50
Authorization: Bearer <api_token>
```

支持筛选：

- `state`
- `deviceId`
- `deviceGroupId`
- `simNumber`
- `phoneNumber`
- `conversationId`
- `createdAt` 时间范围
- `metadata` 业务字段

### 5.4 创建会话

```http
POST /api/v1/conversations
Authorization: Bearer <api_token>
Content-Type: application/json
```

请求：

```json
{
  "externalPhoneNumber": "+8613900000000",
  "deviceId": "dev_01HZX...",
  "simNumber": 1,
  "title": "客户咨询",
  "metadata": {
    "customerId": "cus_123"
  }
}
```

响应：

```json
{
  "id": "conv_01HZ...",
  "status": "OPEN",
  "externalPhoneNumber": "+8613900000000",
  "deviceId": "dev_01HZX...",
  "simNumber": 1,
  "createdAt": "2026-06-14T11:49:00Z"
}
```

### 5.5 会话内发送短信

```http
POST /api/v1/conversations/{conversationId}/messages
Authorization: Bearer <api_token>
Idempotency-Key: conv-123-msg-2
Content-Type: application/json
```

请求：

```json
{
  "text": "请问还有其他问题吗？",
  "priority": 10,
  "validUntil": "2026-06-14T12:10:00Z"
}
```

响应：

```json
{
  "id": "msg_01HZZ...",
  "state": "QUEUED",
  "conversationId": "conv_01HZ...",
  "deviceId": "dev_01HZX...",
  "simNumber": 1
}
```

### 5.6 查询会话消息

```http
GET /api/v1/conversations/{conversationId}/messages?page=1&pageSize=50
Authorization: Bearer <api_token>
```

响应：

```json
{
  "items": [
    {
      "id": "msg_01HZY...",
      "direction": "OUTBOUND",
      "text": "验证码是 123456",
      "state": "DELIVERED",
      "createdAt": "2026-06-14T11:50:00Z"
    },
    {
      "id": "in_01HZ...",
      "direction": "INBOUND",
      "text": "你好，我收到了",
      "createdAt": "2026-06-14T11:55:00Z"
    }
  ]
}
```

### 5.7 查询收件箱

```http
GET /api/v1/inbox?phoneNumber=%2B8613900000000&deviceId=dev_01HZX&page=1&pageSize=50
Authorization: Bearer <api_token>
```

### 5.8 Webhook 配置

```http
POST /api/v1/webhooks
Authorization: Bearer <api_token>
Content-Type: application/json
```

请求：

```json
{
  "url": "https://example.com/sms-webhook",
  "events": [
    "message.sent",
    "message.delivered",
    "message.failed",
    "conversation.message.received",
    "device.offline"
  ],
  "secret": "webhook-signing-secret"
}
```

Webhook 请求头建议：

```text
X-Sms-Gateway-Event: message.delivered
X-Sms-Gateway-Delivery: whd_01HZ...
X-Sms-Gateway-Timestamp: 1781437815
X-Sms-Gateway-Signature: sha256=<hmac>
```

## 6. 管理侧 API

### 6.1 设备管理

| 功能 | 接口 |
| --- | --- |
| 设备列表 | `GET /admin/v1/devices` |
| 设备详情 | `GET /admin/v1/devices/{deviceId}` |
| 启用设备 | `POST /admin/v1/devices/{deviceId}/enable` |
| 禁用设备 | `POST /admin/v1/devices/{deviceId}/disable` |
| 删除设备 | `DELETE /admin/v1/devices/{deviceId}` |
| 设备日志 | `GET /admin/v1/devices/{deviceId}/logs` |
| 设备健康状态 | `GET /admin/v1/devices/{deviceId}/health` |

设备详情应包含：

- 设备 ID、名称、租户、分组。
- 在线状态、最后心跳时间。
- 电量、充电状态、网络类型、App 版本。
- SIM 卡列表。
- 最近 24 小时发送量、成功率、失败率。
- 当前队列长度。
- 最近错误。

### 6.2 SIM 管理

| 功能 | 接口 |
| --- | --- |
| SIM 列表 | `GET /admin/v1/sims` |
| SIM 详情 | `GET /admin/v1/sims/{simId}` |
| 禁用 SIM | `POST /admin/v1/sims/{simId}/disable` |
| 启用 SIM | `POST /admin/v1/sims/{simId}/enable` |
| 配置 SIM 限速 | `PATCH /admin/v1/sims/{simId}/limits` |

SIM 维度建议记录：

- `deviceId`
- `slotIndex`
- `simNumber`
- `phoneNumber`
- `carrierName`
- `iccid`
- `status`
- `dailySentCount`
- `hourlySentCount`
- `failureRate`
- `lastUsedAt`

### 6.3 设备分组与路由策略

| 功能 | 接口 |
| --- | --- |
| 分组列表 | `GET /admin/v1/device-groups` |
| 创建分组 | `POST /admin/v1/device-groups` |
| 修改分组 | `PATCH /admin/v1/device-groups/{groupId}` |
| 设置路由策略 | `PATCH /admin/v1/routing-policies/{policyId}` |

路由策略字段：

```json
{
  "name": "default-cn-sms",
  "deviceGroupId": "grp_cn",
  "strategy": "WEIGHTED_ROUND_ROBIN",
  "requireOnline": true,
  "avoidHighFailureRate": true,
  "maxFailureRate": 0.2,
  "preferSameConversationDevice": true,
  "limits": {
    "perDevicePerMinute": 20,
    "perSimPerDay": 500
  }
}
```

## 7. 数据模型

### 7.1 Tenant

```text
tenants
- id
- name
- status
- created_at
- updated_at
```

### 7.2 Device

```text
devices
- id
- tenant_id
- name
- token_hash
- push_token
- status
- enabled
- group_id
- app_version
- network_type
- battery_level
- is_charging
- last_seen_at
- registered_at
- created_at
- updated_at
```

状态建议：

- `ONLINE`
- `OFFLINE`
- `DISABLED`
- `UNHEALTHY`

### 7.3 SimCard

```text
sim_cards
- id
- tenant_id
- device_id
- slot_index
- sim_number
- phone_number
- carrier_name
- iccid_hash
- status
- last_used_at
- created_at
- updated_at
```

### 7.4 Message

```text
messages
- id
- tenant_id
- conversation_id
- direction
- type
- text
- data
- port
- state
- device_id
- sim_card_id
- sim_number
- priority
- valid_until
- schedule_at
- idempotency_key
- metadata
- created_at
- updated_at
```

方向：

- `OUTBOUND`
- `INBOUND`

出站状态：

- `QUEUED`
- `ASSIGNED`
- `PULLED`
- `PROCESSED`
- `SENT`
- `DELIVERED`
- `FAILED`
- `EXPIRED`
- `CANCELLED`

### 7.5 MessageRecipient

```text
message_recipients
- id
- message_id
- phone_number
- state
- error
- updated_at
```

### 7.6 MessageStateHistory

```text
message_state_history
- id
- message_id
- state
- source
- reason
- created_at
```

### 7.7 Conversation

```text
conversations
- id
- tenant_id
- external_phone_number
- local_phone_number
- device_id
- sim_card_id
- sim_number
- status
- last_message_at
- expires_at
- metadata
- created_at
- updated_at
```

状态：

- `OPEN`
- `WAITING_REPLY`
- `REPLIED`
- `CLOSED`
- `EXPIRED`

### 7.8 Webhook

```text
webhooks
- id
- tenant_id
- url
- events
- secret_hash
- enabled
- created_at
- updated_at
```

### 7.9 WebhookDelivery

```text
webhook_deliveries
- id
- webhook_id
- event
- payload
- status
- attempt_count
- next_retry_at
- last_error
- created_at
- updated_at
```

## 8. 核心流程

### 8.1 设备注册流程

1. 手机启动网关模式，并读取局域网服务器地址。
2. 手机读取设备名、SIM 卡信息、push token。
3. 手机调用 `POST /mobile/v1/device`。
4. 服务器创建设备并返回 `deviceId` 和 `deviceToken`。
5. 手机保存 token。
6. 手机启动周期心跳、SSE 连接和消息拉取任务。

### 8.2 发送短信流程

1. 业务系统调用 `POST /api/v1/messages`。
2. 服务器校验 API token、租户、手机号、内容和幂等键。
3. Router 选择设备和 SIM。
4. 服务器创建出站消息，状态为 `QUEUED`。
5. 服务器通过 SSE/WebSocket 通知目标设备；如果实时连接不可用，手机通过定时轮询拉取。
6. 手机调用 `GET /mobile/v1/message` 拉取任务。
7. 服务器将任务标记为 `PULLED` 或 `PROCESSED`。
8. 手机调用 Android SMS API 发送短信。
9. 手机通过 `PATCH /mobile/v1/message` 回传状态。
10. 服务器更新状态并触发 webhook。

### 8.3 入站短信流程

1. 手机收到短信广播。
2. 手机解析短信内容、发送方、接收 SIM、接收时间。
3. 手机调用 `POST /mobile/v1/inbox`。
4. 服务器按 `tenantId + from + local number/device/sim` 匹配会话。
5. 匹配成功则追加会话消息。
6. 匹配失败则创建新会话。
7. 服务器触发 webhook 给业务系统。

### 8.4 多轮会话流程

1. 业务系统创建会话或发送第一条短信时自动创建会话。
2. 会话绑定外部手机号、设备和 SIM。
3. 后续通过该会话发送的短信优先复用相同设备和 SIM。
4. 外部联系人回复时，服务器根据手机号和接收 SIM 找回会话。
5. 会话超过 `expiresAt` 未活动后变为 `EXPIRED`。
6. 业务系统可主动关闭会话。

## 9. 路由策略

第一阶段推荐支持以下策略：

| 策略 | 说明 |
| --- | --- |
| 指定设备 | 请求中传 `deviceId`，只由该设备发送 |
| 指定设备组 | 请求中传 `deviceGroupId`，在组内选择设备 |
| 会话粘性 | 会话内消息优先使用之前的设备和 SIM |
| 轮询 | 在可用设备间轮询 |
| 随机 | 在可用设备间随机 |
| 最低负载 | 优先选择队列最短的设备 |

可用设备必须满足：

- `enabled=true`
- 最近心跳未超时
- 设备健康状态正常
- 有可用 SIM
- 未超过设备和 SIM 限速
- 失败率未超过策略阈值

## 10. 限流与重试

### 10.1 限流维度

建议至少支持：

- 租户级：每分钟、每小时、每日发送量。
- 设备级：每分钟、每小时、每日发送量。
- SIM 级：每分钟、每小时、每日发送量。
- 目标号码级：同一号码短时间内最大条数。
- 会话级：单会话发送频率。

### 10.2 重试策略

适合重试：

- 设备短暂离线。
- 手机拉取后未回传状态且锁超时。
- 服务器 webhook 投递失败。

不适合自动重试：

- 手机明确返回短信发送失败且原因不可恢复。
- 目标号码格式错误。
- 设备或 SIM 被禁用。
- 消息已过期。

## 11. 安全设计

### 11.1 认证

- 手机侧使用设备 token。
- 业务侧使用 API token 或 OAuth/JWT。
- 管理侧使用管理员账号、角色权限和审计日志。
- 设备注册可配置为私有 token、账号密码、一次性注册码或关闭匿名注册。

### 11.2 权限

建议权限粒度：

- `devices:list`
- `devices:write`
- `messages:send`
- `messages:read`
- `messages:list`
- `inbox:read`
- `conversations:read`
- `conversations:write`
- `webhooks:write`
- `settings:write`

### 11.3 数据保护

- 设备 token 只保存哈希。
- ICCID、手机号等敏感信息可脱敏展示。
- Webhook secret 只保存哈希或加密值。
- 日志中避免明文记录完整短信内容，必要时做字段级脱敏。
- 局域网 MVP 可以先使用 HTTP，但必须限制在可信内网、关闭匿名注册，并使用设备 token。
- 生产环境、跨网访问、VPN 外暴露或经过反向代理时，必须使用 HTTPS。
- Android 端如果连接 HTTP，需要只对指定内网域名或 IP 放开明文访问，不建议全局允许明文流量。

### 11.4 Webhook 签名

使用 HMAC-SHA256：

```text
signature = HMAC_SHA256(secret, timestamp + "." + raw_body)
```

业务系统校验：

- 时间戳未过期。
- 签名匹配。
- `deliveryId` 未重复处理。

## 12. 错误码

统一错误响应：

```json
{
  "code": "DEVICE_OFFLINE",
  "message": "No available device can send this message",
  "requestId": "req_01HZ..."
}
```

建议错误码：

| code | 含义 |
| --- | --- |
| `UNAUTHORIZED` | 未认证或 token 无效 |
| `FORBIDDEN` | 权限不足 |
| `VALIDATION_ERROR` | 请求参数错误 |
| `IDEMPOTENCY_CONFLICT` | 幂等键冲突 |
| `DEVICE_OFFLINE` | 设备离线 |
| `NO_AVAILABLE_DEVICE` | 没有可用设备 |
| `NO_AVAILABLE_SIM` | 没有可用 SIM |
| `RATE_LIMITED` | 触发限流 |
| `MESSAGE_EXPIRED` | 消息已过期 |
| `MESSAGE_NOT_FOUND` | 消息不存在 |
| `CONVERSATION_NOT_FOUND` | 会话不存在 |

## 13. 可观测性

### 13.1 指标

建议采集：

- 在线设备数。
- 可用 SIM 数。
- 待发送队列长度。
- 每分钟发送量。
- 成功率、失败率、送达率。
- 平均发送延迟。
- 设备心跳延迟。
- webhook 成功率和重试次数。

### 13.2 日志

关键日志：

- 设备注册、离线、禁用。
- 消息创建、分配、拉取、状态变化。
- 路由失败原因。
- 入站短信匹配会话结果。
- webhook 投递结果。

### 13.3 告警

建议告警：

- 某租户无可用设备。
- 设备连续离线超过阈值。
- SIM 失败率高。
- 队列堆积超过阈值。
- webhook 连续失败。
- 手机 App 版本过低。

## 14. 部署建议

### 14.1 局域网 MVP 部署

适合早期验证：

- 单体后端服务，部署在办公室、机房或家庭局域网内的一台稳定主机上。
- PostgreSQL 存储核心数据。
- Redis 做队列、锁、限流和 SSE 在线状态。
- 后台管理页面可以后置，先用 API 和简单管理工具。
- 服务监听内网地址，例如 `0.0.0.0:8080`，并通过防火墙只允许局域网网段访问。
- 服务器使用固定内网 IP，例如 `192.168.1.10`，避免路由器重启后地址变化。
- 手机和服务器连接同一个 Wi-Fi，路由器关闭 AP 隔离。
- Android App 的服务器地址配置为 `http://192.168.1.10:8080/mobile/v1`。
- 如果希望局域网内使用 HTTPS，可以用内网域名和自签名/私有 CA 证书，但需要处理 Android 证书信任。

### 14.2 局域网服务发现

如果不想手动输入 IP，可以增加以下能力：

- 管理后台生成二维码，包含服务器 URL、注册 token、租户 ID。
- Android App 扫码后自动写入服务器地址并发起注册。
- 服务器通过 mDNS 广播服务名，例如 `sms-gateway.local`。
- 路由器或内网 DNS 固定解析 `sms-gateway.lan` 到服务器内网 IP。
- 服务器启动时在管理后台显示当前内网 IP 和端口。

### 14.3 跨网络访问扩展

当手机不在同一局域网时，不建议直接把 HTTP 端口暴露到公网。推荐顺序：

1. 使用 VPN，让手机进入服务器所在内网。
2. 使用反向代理暴露 HTTPS，并开启强认证。
3. 使用内网穿透工具，仅开放必要 API，并限制注册 token 和管理后台访问。
4. 最后才考虑完整公网部署。

跨网络后，SSE/WebSocket 仍可使用，但需要配置反向代理的长连接超时。

### 14.4 规模化部署

后续可拆分：

- API Gateway
- Device Service
- Message Service
- Conversation Service
- Webhook Worker
- Scheduler/Router Worker
- Admin UI

队列可使用：

- Redis Streams
- RabbitMQ
- Kafka

## 15. MVP 实施路线

### 阶段 1：手机接入和基础发送

- 实现 `POST /mobile/v1/device`。
- 实现 `PATCH /mobile/v1/device`。
- 实现 `POST /api/v1/messages`。
- 实现 `GET /mobile/v1/message`。
- 实现 `PATCH /mobile/v1/message`。
- 实现设备列表和消息列表。

验收标准：

- 至少两台手机能在同一局域网内注册到服务器。
- 业务 API 创建短信后，目标手机能拉取并发送。
- 业务侧能查询消息状态。

### 阶段 2：入站短信和多轮会话

- 实现 `POST /mobile/v1/inbox`。
- 实现会话表和会话匹配。
- 实现 `POST /api/v1/conversations`。
- 实现 `POST /api/v1/conversations/{id}/messages`。
- 实现 `GET /api/v1/conversations/{id}/messages`。

验收标准：

- 外部号码回复短信后，服务器能归档到正确会话。
- 同一会话后续发送优先复用同一设备和 SIM。

### 阶段 3：设备管理和路由策略

- 实现设备启用、禁用、分组。
- 实现 SIM 管理。
- 实现基础路由策略：指定设备、指定分组、轮询、最低负载。
- 实现设备和 SIM 限流。

验收标准：

- 禁用设备后不会继续接收新任务。
- 设备组内能自动分配短信。
- 超过限流后任务不会被继续分配。

### 阶段 4：Webhook 和实时通知

- 实现 webhook 配置。
- 实现 webhook 签名。
- 实现 webhook 重试。
- 实现 SSE 事件流。
- 消息入队时主动通知手机拉取。

验收标准：

- 状态变化和入站短信能稳定回调业务系统。
- 手机无需长时间轮询即可收到新任务通知。

### 阶段 5：安全、审计和运维增强

- 完善权限模型。
- 增加审计日志。
- 增加指标和告警。
- 增加管理后台。
- 增加数据脱敏和短信内容保留策略。

## 16. 与当前 Android 项目的兼容建议

当前 Android 项目中 `GatewayApi` 已体现以下服务端契约：

- `GET /device`
- `POST /device`
- `PATCH /device`
- `GET /message`
- `PATCH /message`
- `GET /webhooks`
- `GET /settings`
- `GET /user/code`
- `PATCH /user/password`

因此服务器可以将手机侧 API 实现为：

```text
/mobile/v1/device
/mobile/v1/message
/mobile/v1/webhooks
/mobile/v1/settings
/mobile/v1/user/code
/mobile/v1/user/password
```

如果希望完全减少 Android 端改动，也可以直接使用：

```text
/device
/message
/webhooks
/settings
/user/code
/user/password
```

推荐做法：

- 服务器内部使用 `/mobile/v1` 作为正式版本路径。
- 同时保留无前缀路径作为兼容别名。
- 新增入站短信接口 `/mobile/v1/inbox`，Android 端需要补充上报逻辑。
- 新增 SSE 接口 `/mobile/v1/events`，如果当前 Android 端已有 SSE 管理器，可直接对接。
- Android 端需要允许配置局域网 HTTP 地址，例如 `http://192.168.1.10:8080/mobile/v1`。
- 如果现有配置校验强制要求 `https://`，需要调整为允许私有网段 HTTP，或在局域网服务器上配置 HTTPS。
- 建议增加“扫码接入局域网服务器”入口，扫码内容包含 `serverUrl`、`registrationCode` 和可选 `tenantId`。

## 17. 推荐优先级

如果只做最小可用版本，优先级如下：

1. 设备注册和心跳。
2. 创建短信任务。
3. 手机拉取短信。
4. 手机回传状态。
5. 入站短信上报。
6. 会话绑定。
7. 设备列表和禁用。
8. Webhook。
9. SSE 实时通知。
10. 高级路由、限流、告警和后台 UI。

## 18. 结论

这套局域网网关的关键不是简单转发短信，而是把多台手机抽象成一个可调度、可观测、可管理的短信执行集群。服务器需要承担消息状态中心、设备注册中心、路由调度中心和会话上下文中心四个角色。

第一阶段建议实现兼容当前 Android 端的服务端 API，先在同一局域网内跑通多设备注册、短信任务拉取、状态回传和入站短信上报。第二阶段再补齐 conversation、webhook、设备管理和路由策略。这样可以快速验证真实收发链路，同时为后续扩展到 VPN、内网穿透或公网 HTTPS 部署留下清晰边界。
