# Mobile Inbox Upload API

本文档描述 Android 端方案 B 对服务器的要求：手机收到 SMS 或 Data SMS 后，主动调用服务器的 `/mobile/v1/inbox` 接口上报入站短信。服务器负责幂等入库，并可再向业务系统提供查询或 webhook 通知。

## 1. 手机侧接口：上报收到的短信

```http
POST /mobile/v1/inbox
Authorization: Bearer <device_token>
Content-Type: application/json
```

### 1.1 SMS 请求体

```json
{
  "id": "text:-123456789",
  "type": "SMS",
  "sender": "+8613800000000",
  "recipient": "+8613900000000",
  "simNumber": 1,
  "subscriptionId": 3,
  "receivedAt": "2026-06-16T12:00:00.000+08:00",
  "textMessage": {
    "text": "验证码是 123456"
  },
  "dataMessage": null
}
```

### 1.2 Data SMS 请求体

```json
{
  "id": "data:-987654321",
  "type": "DATA_SMS",
  "sender": "+8613800000000",
  "recipient": "+8613900000000",
  "simNumber": 1,
  "subscriptionId": 3,
  "receivedAt": "2026-06-16T12:00:00.000+08:00",
  "textMessage": null,
  "dataMessage": {
    "data": "AQJ/"
  }
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
|---|---:|---:|---|
| `id` | string | 是 | 手机端生成的幂等 ID；同一设备同一 ID 重复提交必须只入库一次 |
| `type` | string | 是 | 当前 Android 端会发送 `SMS` 或 `DATA_SMS` |
| `sender` | string | 是 | 发信号码 |
| `recipient` | string/null | 否 | 接收这条短信的本机号码，可能因系统权限或 SIM 信息缺失而为空 |
| `simNumber` | number/null | 否 | App 中展示的 SIM 编号，通常从 1 开始 |
| `subscriptionId` | number/null | 否 | Android 系统 subscription id，用于排查多 SIM 问题 |
| `receivedAt` | string | 是 | 手机收到短信的时间，ISO-8601 格式 |
| `textMessage.text` | string | SMS 必填 | SMS 文本内容 |
| `dataMessage.data` | string | DATA_SMS 必填 | 原始 data SMS payload 的 Base64 字符串 |

### 1.3 成功响应

推荐返回 `201 Created` 或 `200 OK`：

```json
{
  "id": "text:-123456789",
  "created": true
}
```

如果是重复上报，建议仍返回 `200 OK`，并告诉客户端没有新建：

```json
{
  "id": "text:-123456789",
  "created": false
}
```

Android 端只要求服务器返回 2xx；非 2xx 或网络错误会触发 WorkManager 重试。

### 1.4 服务端处理规则

1. 校验 `Authorization: Bearer <device_token>`。
2. 根据 token 找到设备，设备不存在、禁用或 token 无效时返回 `401` 或 `403`。
3. 校验 `id`、`type`、`sender`、`receivedAt` 和对应消息体字段。
4. 使用 `(device_id, id)` 做唯一键，实现幂等入库。
5. 更新设备 `last_seen_at`，可选更新在线状态。
6. 入库后可触发业务 webhook，例如 `inbox.message.received`。

建议唯一索引：

```sql
CREATE UNIQUE INDEX inbox_messages_device_id_client_id_uq
ON inbox_messages (device_id, client_message_id);
```

建议表结构：

```text
inbox_messages
- id                 string, primary key
- device_id          string, not null
- client_message_id  string, not null
- type               string, SMS/DATA_SMS
- sender             string, not null
- recipient          string, nullable
- sim_number         integer, nullable
- subscription_id    integer, nullable
- text               text, nullable
- data_base64        text, nullable
- received_at        datetime, not null
- created_at         datetime, not null
```

## 2. 业务侧接口：查询入站短信

如果业务系统需要从服务器查询收件箱，建议实现：

```http
GET /api/v1/inbox?deviceId=<device_id>&phoneNumber=<sender_or_recipient>&type=SMS&page=1&pageSize=50
Authorization: Bearer <api_token>
```

成功响应：

```json
{
  "items": [
    {
      "id": "srv_inbox_001",
      "deviceId": "dev_001",
      "clientMessageId": "text:-123456789",
      "type": "SMS",
      "sender": "+8613800000000",
      "recipient": "+8613900000000",
      "simNumber": 1,
      "textMessage": {
        "text": "验证码是 123456"
      },
      "dataMessage": null,
      "receivedAt": "2026-06-16T12:00:00.000+08:00",
      "createdAt": "2026-06-16T12:00:01.000+08:00"
    }
  ],
  "page": 1,
  "pageSize": 50,
  "total": 1
}
```

## 3. 错误响应格式

建议所有错误统一使用：

```json
{
  "code": "VALIDATION_ERROR",
  "message": "textMessage.text is required when type is SMS"
}
```

常见状态码：

| HTTP | code | 场景 |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | 参数缺失或格式错误 |
| 401 | `UNAUTHORIZED` | token 缺失、无效或过期 |
| 403 | `FORBIDDEN` | 设备被禁用 |
| 409 | `CONFLICT` | 幂等键冲突但请求内容不一致 |
| 500 | `INTERNAL_ERROR` | 服务端内部错误 |

## 4. 当前 Android 端支持范围

本次 Android 端实现会在 gateway 已启用且设备已注册时，上报：

- `SMS`
- `DATA_SMS`

MMS 仍保留现有 webhook 能力。若后续要把 MMS 也纳入 `/mobile/v1/inbox`，建议先确定附件策略：只上报元数据、上报正文和小附件、或另建附件上传接口。
