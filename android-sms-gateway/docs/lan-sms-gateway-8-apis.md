# 局域网多手机 SMS 网关服务器 8 个接口开发文档

## 1. 目标

实现一个局域网服务器，用来管理多台 Android 手机发送短信。

服务器职责：

- 管理手机设备注册。
- 记录设备在线状态和 SIM 卡信息。
- 接收业务系统创建的短信任务。
- 将短信任务分配给在线手机。
- 让手机主动拉取短信任务。
- 接收手机回传的短信发送状态。
- 提供业务系统查询短信状态和设备列表的接口。
- 通过 SSE 通知手机有新任务。

手机是真正执行短信发送的节点。服务器不直接调用运营商短信通道。

## 2. 基础地址

手机侧 API：

```text
http://<server-ip>:8080/mobile/v1
```

业务侧 API：

```text
http://<server-ip>:8080/api/v1
```

示例：

```text
http://192.168.1.10:8080/mobile/v1
http://192.168.1.10:8080/api/v1
```

## 3. 必须兼容的 Android 端约定

当前 Android 客户端会使用以下短信状态：

```text
Pending
Processed
Sent
Delivered
Failed
```

当前 Android 客户端认识以下 SSE 事件：

```text
MessageEnqueued
WebhooksUpdated
MessagesExportRequested
SettingsUpdated
```

本 MVP 只必须实现：

```text
MessageEnqueued
```

## 4. 认证规则

### 4.1 设备注册认证

设备第一次注册时使用注册码：

```http
Authorization: Code 836492
```

服务器必须校验：

- 注册码存在。
- 注册码未使用。
- 注册码未过期。

### 4.2 设备请求认证

设备注册成功后，服务器返回 `token`。

手机后续请求使用：

```http
Authorization: Bearer <device_token>
```

服务器存储 token 时不要保存明文，只保存 hash。

### 4.3 业务 API 认证

业务系统调用 `/api/v1/*` 时使用：

```http
Authorization: Bearer <api_token>
```

第一版可以在服务端配置文件或环境变量中配置一个固定 `api_token`。

## 5. 最小数据模型

### 5.1 devices

```text
id              string, primary key
name            string
token_hash      string
login           string
password_hash   string, nullable
enabled         boolean
status          string, online/offline/disabled
last_seen_at    datetime
registered_at   datetime
created_at      datetime
updated_at      datetime
```

### 5.2 sim_cards

```text
id              string, primary key
device_id       string
slot_index      integer
sim_number      integer
phone_number    string, nullable
carrier_name    string, nullable
iccid           string, nullable
enabled         boolean
created_at      datetime
updated_at      datetime
```

### 5.3 messages

```text
id              string, primary key
text            string
phone_numbers   json array
state           string, Pending/Processed/Sent/Delivered/Failed
device_id       string
sim_number      integer, nullable
priority        integer
valid_until     datetime, nullable
created_at      datetime
updated_at      datetime
pulled_at       datetime, nullable
```

### 5.4 message_recipients

```text
id              string, primary key
message_id      string
phone_number    string
state           string
error           string, nullable
updated_at      datetime
```

### 5.5 registration_codes

```text
code            string, primary key
used            boolean
expires_at      datetime
created_at      datetime
```

## 6. 接口 1：注册设备

### 6.1 请求

```http
POST /mobile/v1/device
Authorization: Code 836492
Content-Type: application/json
```

### 6.2 请求体

```json
{
  "name": "Samsung/SM-G9910",
  "pushToken": null,
  "simCards": [
    {
      "slotIndex": 0,
      "simNumber": 1,
      "phoneNumber": "****0000",
      "carrierName": "****",
      "iccid": "****1234"
    }
  ]
}
```

### 6.3 成功响应

```json
{
  "id": "dev_001",
  "token": "device_token_xxx",
  "login": "phone-001",
  "password": null
}
```

### 6.4 服务器逻辑

```text
1. 读取 Authorization 请求头。
2. Authorization 必须以 "Code " 开头。
3. 取出注册码，例如 836492。
4. 查询 registration_codes。
5. 如果注册码不存在、已使用、已过期，返回 401。
6. 生成 deviceId，例如 dev_001。
7. 生成随机 device_token。
8. 保存 devices 记录，token 只保存 hash。
9. 保存 sim_cards。
10. 将注册码标记为 used=true。
11. 返回 id/token/login/password。
```

### 6.5 错误响应

```json
{
  "code": "UNAUTHORIZED",
  "message": "Invalid registration code"
}
```

## 7. 接口 2：设备心跳 / 更新设备信息

### 7.1 请求

```http
PATCH /mobile/v1/device
Authorization: Bearer <device_token>
Content-Type: application/json
```

### 7.2 请求体

```json
{
  "id": "dev_001",
  "pushToken": null,
  "simCards": [
    {
      "slotIndex": 0,
      "simNumber": 1,
      "phoneNumber": "****0000",
      "carrierName": "****",
      "iccid": "****1234"
    }
  ]
}
```

### 7.3 成功响应

```json
{
  "ok": true
}
```

### 7.4 服务器逻辑

```text
1. 校验 Bearer token。
2. 根据 token_hash 找到设备。
3. 如果设备 disabled，返回 403。
4. 校验 body.id 是否等于当前设备 id。
5. 更新 devices.last_seen_at = 当前时间。
6. 更新 devices.status = online。
7. 删除或覆盖该设备旧 sim_cards。
8. 保存新的 sim_cards。
9. 返回 ok=true。
```

## 8. 接口 3：业务系统创建短信任务

### 8.1 请求

```http
POST /api/v1/messages
Authorization: Bearer <api_token>
Content-Type: application/json
```

### 8.2 请求体

```json
{
  "phoneNumbers": ["+8613900000000"],
  "text": "验证码是 123456",
  "deviceId": null,
  "simNumber": null,
  "priority": 10,
  "validUntil": "2026-06-16T12:00:00Z"
}
```

### 8.3 成功响应

```json
{
  "id": "msg_001",
  "state": "Pending",
  "deviceId": "dev_001",
  "simNumber": 1
}
```

### 8.4 服务器逻辑

```text
1. 校验业务 api_token。
2. 校验 phoneNumbers 非空。
3. 校验 text 非空。
4. 如果传了 deviceId，使用指定设备。
5. 如果没传 deviceId，从在线设备中选择一台。
6. 在线设备条件：
   - enabled=true
   - status=online
   - last_seen_at 距当前时间不超过 60 秒
7. 如果传了 simNumber，使用指定 SIM。
8. 如果没传 simNumber，使用该设备第一张 enabled SIM。
9. 创建 messages 记录，state=Pending。
10. 为每个 phoneNumber 创建 message_recipients，state=Pending。
11. 如果目标设备有 SSE 连接，推送 MessageEnqueued 事件。
12. 返回短信任务 id。
```

### 8.5 无可用设备响应

```json
{
  "code": "NO_AVAILABLE_DEVICE",
  "message": "No online device can send this message"
}
```

## 9. 接口 4：手机拉取待发送短信

### 9.1 请求

```http
GET /mobile/v1/message?order=fifo
Authorization: Bearer <device_token>
```

### 9.2 成功响应

```json
[
  {
    "id": "msg_001",
    "textMessage": {
      "text": "验证码是 123456"
    },
    "phoneNumbers": ["+8613900000000"],
    "simNumber": 1,
    "withDeliveryReport": true,
    "isEncrypted": false,
    "validUntil": "2026-06-16T12:00:00Z",
    "scheduleAt": null,
    "priority": 10,
    "createdAt": "2026-06-16T11:50:00Z"
  }
]
```

### 9.3 没有任务时响应

```json
[]
```

### 9.4 服务器逻辑

```text
1. 校验设备 Bearer token。
2. 更新设备 last_seen_at。
3. 查询 messages：
   - device_id = 当前设备 id
   - state = Pending
   - valid_until 为空，或 valid_until > 当前时间
4. 根据 order 排序：
   - fifo: created_at asc
   - lifo: created_at desc
5. 限制返回数量，第一版建议 limit=10。
6. 返回前将这些消息标记为 Processed，或至少设置 pulled_at。
7. 返回 Android 端需要的字段格式。
```

重要要求：

```text
返回字段必须叫 textMessage。
不要只返回 text，否则当前 Android 端不能按预期解析。
```

## 10. 接口 5：手机回传短信状态

### 10.1 请求

```http
PATCH /mobile/v1/message
Authorization: Bearer <device_token>
Content-Type: application/json
```

### 10.2 请求体

```json
[
  {
    "id": "msg_001",
    "state": "Delivered",
    "recipients": [
      {
        "phoneNumber": "+8613900000000",
        "state": "Delivered",
        "error": null
      }
    ],
    "states": {
      "Pending": "2026-06-16T11:50:00Z",
      "Processed": "2026-06-16T11:50:03Z",
      "Sent": "2026-06-16T11:50:08Z",
      "Delivered": "2026-06-16T11:50:15Z"
    }
  }
]
```

### 10.3 成功响应

```json
{
  "ok": true
}
```

### 10.4 服务器逻辑

```text
1. 校验设备 Bearer token。
2. 遍历请求数组。
3. 根据 id 查询 message。
4. 必须校验 message.device_id 等于当前设备 id。
5. 更新 messages.state。
6. 更新 messages.updated_at。
7. 遍历 recipients，更新 message_recipients.state 和 error。
8. 该接口必须幂等：同一个状态重复提交不能报错。
9. 返回 ok=true。
```

### 10.5 状态说明

```text
Pending    服务器已创建，等待手机拉取
Processed  手机已拉取或正在处理
Sent       手机已调用系统 SMS API 发送
Delivered 运营商报告已送达
Failed     发送失败
```

## 11. 接口 6：业务系统查询短信状态

### 11.1 请求

```http
GET /api/v1/messages/{messageId}
Authorization: Bearer <api_token>
```

示例：

```http
GET /api/v1/messages/msg_001
Authorization: Bearer api_token_xxx
```

### 11.2 成功响应

```json
{
  "id": "msg_001",
  "state": "Delivered",
  "text": "验证码是 123456",
  "phoneNumbers": ["+8613900000000"],
  "deviceId": "dev_001",
  "simNumber": 1,
  "priority": 10,
  "validUntil": "2026-06-16T12:00:00Z",
  "createdAt": "2026-06-16T11:50:00Z",
  "updatedAt": "2026-06-16T11:50:15Z",
  "recipients": [
    {
      "phoneNumber": "+8613900000000",
      "state": "Delivered",
      "error": null
    }
  ]
}
```

### 11.3 服务器逻辑

```text
1. 校验业务 api_token。
2. 根据 messageId 查询 messages。
3. 如果不存在，返回 404。
4. 查询 message_recipients。
5. 返回消息详情。
```

### 11.4 不存在响应

```json
{
  "code": "MESSAGE_NOT_FOUND",
  "message": "Message not found"
}
```

## 12. 接口 7：SSE 实时通知

### 12.1 请求

```http
GET /mobile/v1/events
Authorization: Bearer <device_token>
Accept: text/event-stream
```

### 12.2 响应头

```http
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
```

### 12.3 新短信事件

```text
event: MessageEnqueued
data: {"messageId":"msg_001"}

```

注意：

```text
SSE 每个事件最后必须有一个空行。
```

### 12.4 服务器逻辑

```text
1. 校验设备 Bearer token。
2. 建立 SSE 长连接。
3. 将连接保存到内存 map：
   key = deviceId
   value = response stream
4. 当 POST /api/v1/messages 创建了分配给该设备的新短信：
   向该设备 SSE stream 写入 MessageEnqueued。
5. 客户端断开时，从 map 删除连接。
6. 每 15 到 30 秒发送一次 ping，防止连接被中间设备关闭。
```

### 12.5 ping 示例

```text
event: ping
data: {}

```

手机端不认识 `ping` 也没关系，可以忽略。

## 13. 接口 8：业务系统查询设备列表

### 13.1 请求

```http
GET /api/v1/devices
Authorization: Bearer <api_token>
```

### 13.2 成功响应

```json
[
  {
    "id": "dev_001",
    "name": "Samsung/SM-G9910",
    "status": "online",
    "enabled": true,
    "lastSeenAt": "2026-06-16T11:59:00Z",
    "registeredAt": "2026-06-16T10:00:00Z",
    "simCards": [
      {
        "slotIndex": 0,
        "simNumber": 1,
        "phoneNumber": "****0000",
        "carrierName": "****",
        "enabled": true
      }
    ]
  }
]
```

### 13.3 服务器逻辑

```text
1. 校验业务 api_token。
2. 查询所有 devices。
3. 对每个设备计算在线状态：
   - enabled=false => disabled
   - last_seen_at 距当前时间 <= 60 秒 => online
   - 否则 offline
4. 查询每个设备的 sim_cards。
5. 返回设备列表。
```

## 14. 统一错误格式

所有错误统一返回：

```json
{
  "code": "ERROR_CODE",
  "message": "Human readable message"
}
```

建议错误码：

```text
UNAUTHORIZED
FORBIDDEN
VALIDATION_ERROR
DEVICE_NOT_FOUND
MESSAGE_NOT_FOUND
NO_AVAILABLE_DEVICE
NO_AVAILABLE_SIM
MESSAGE_EXPIRED
INTERNAL_ERROR
```

HTTP 状态码建议：

```text
400 参数错误
401 未认证
403 无权限或设备禁用
404 资源不存在
409 状态冲突
500 服务器内部错误
```

## 15. 路由选择规则

第一版使用最简单策略即可。

如果业务创建短信时指定了 `deviceId`：

```text
使用指定设备。
```

如果没有指定 `deviceId`：

```text
选择一台在线且 enabled=true 的设备。
```

在线判断：

```text
last_seen_at 距当前时间 <= 60 秒
```

如果业务请求指定了 `simNumber`：

```text
使用指定 SIM。
```

如果没有指定 `simNumber`：

```text
使用该设备第一张 enabled=true 的 SIM。
```

## 16. 开发顺序

按以下顺序实现：

```text
1. POST /mobile/v1/device 注册设备
2. PATCH /mobile/v1/device 设备心跳
3. POST /api/v1/messages 创建短信
4. GET /mobile/v1/message 手机拉取短信
5. PATCH /mobile/v1/message 手机回传状态
6. GET /api/v1/messages/{id} 查询状态
7. GET /mobile/v1/events SSE 实时通知
8. GET /api/v1/devices 设备列表
```

## 17. 验收流程

开发完成后，必须能跑通以下流程：

```text
1. 创建注册码 836492。
2. 手机调用 POST /mobile/v1/device 注册成功，拿到 token。
3. 手机调用 PATCH /mobile/v1/device，服务器设备列表显示 online。
4. 业务系统调用 POST /api/v1/messages 创建短信。
5. 服务器把短信分配给在线设备。
6. 手机调用 GET /mobile/v1/message 拉到短信。
7. 手机调用 PATCH /mobile/v1/message 回传 Sent 或 Delivered。
8. 业务系统调用 GET /api/v1/messages/{id} 能看到最新状态。
9. GET /api/v1/devices 能看到设备和 SIM 信息。
10. 如果手机连接了 GET /mobile/v1/events，新短信创建后能收到 MessageEnqueued。
```

## 18. curl 验收示例

### 18.1 注册设备

```bash
curl -X POST "http://192.168.1.10:8080/mobile/v1/device" \
  -H "Authorization: Code 836492" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Samsung/SM-G9910",
    "pushToken": null,
    "simCards": [
      {
        "slotIndex": 0,
        "simNumber": 1,
        "phoneNumber": "****0000",
        "carrierName": "****",
        "iccid": "****1234"
      }
    ]
  }'
```

### 18.2 设备心跳

```bash
curl -X PATCH "http://192.168.1.10:8080/mobile/v1/device" \
  -H "Authorization: Bearer device_token_xxx" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "dev_001",
    "pushToken": null,
    "simCards": [
      {
        "slotIndex": 0,
        "simNumber": 1,
        "phoneNumber": "****0000",
        "carrierName": "****",
        "iccid": "****1234"
      }
    ]
  }'
```

### 18.3 创建短信

```bash
curl -X POST "http://192.168.1.10:8080/api/v1/messages" \
  -H "Authorization: Bearer api_token_xxx" \
  -H "Content-Type: application/json" \
  -d '{
    "phoneNumbers": ["+8613900000000"],
    "text": "验证码是 123456",
    "deviceId": null,
    "simNumber": null,
    "priority": 10,
    "validUntil": "2026-06-16T12:00:00Z"
  }'
```

### 18.4 手机拉取短信

```bash
curl -X GET "http://192.168.1.10:8080/mobile/v1/message?order=fifo" \
  -H "Authorization: Bearer device_token_xxx"
```

### 18.5 手机回传状态

```bash
curl -X PATCH "http://192.168.1.10:8080/mobile/v1/message" \
  -H "Authorization: Bearer device_token_xxx" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "id": "msg_001",
      "state": "Delivered",
      "recipients": [
        {
          "phoneNumber": "+8613900000000",
          "state": "Delivered",
          "error": null
        }
      ],
      "states": {
        "Pending": "2026-06-16T11:50:00Z",
        "Processed": "2026-06-16T11:50:03Z",
        "Sent": "2026-06-16T11:50:08Z",
        "Delivered": "2026-06-16T11:50:15Z"
      }
    }
  ]'
```

### 18.6 查询短信状态

```bash
curl -X GET "http://192.168.1.10:8080/api/v1/messages/msg_001" \
  -H "Authorization: Bearer api_token_xxx"
```

### 18.7 连接 SSE

```bash
curl -N "http://192.168.1.10:8080/mobile/v1/events" \
  -H "Authorization: Bearer device_token_xxx" \
  -H "Accept: text/event-stream"
```

### 18.8 查询设备列表

```bash
curl -X GET "http://192.168.1.10:8080/api/v1/devices" \
  -H "Authorization: Bearer api_token_xxx"
```

