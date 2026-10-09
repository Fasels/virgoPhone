# 可靠收发试点版本

基站正式身份使用 `me.capcom.smsgateway`，本轮版本为 1.1.3 / code 5。客服端五个正式身份为 `com.example.virgo.client1` 至 `client5`，本轮聊天时间显示版本为 1.1.1 / code 3。已安装软件必须沿用各自原签名覆盖升级，不能通过卸载旧包迁移设备身份。

## 变更

- SSE 连接按用户运行意图恢复，重复启动不会创建多个连接；锁屏和网络恢复可继续同步。
- 设备协议 v2 将出站 payload、claim、原 SIM 路由和执行日志持久化，再 ACK 并进入发送队列；已执行或执行结果未知的任务不会自动重发。
- 状态回执与入站 SMS/MMS 使用持久 outbox。旧失败任务不阻挡新任务，WorkManager 临时失败不丢弃文件，恢复连接时自动重试。
- 相同文字在不同时间收到时分别入库，同一广播重试仍幂等；日期格式化并发访问安全。
- SIM 变更按原卡槽显式同步完整号码，未知号码保留后台原值；原身份、绑定及会话归属保留。
- 升级首次启动只接收新彩信，不补扫升级前的历史彩信；仅对已识别的 GIF 兼容失败保留有界恢复规则。
- 客服端和监视端采用 `MM-dd HH:mm:ss`，不显示年份；客服请求携带稳定的幂等键。

基站 Room 数据库由 23 增量迁移至 24，保留旧数据并增加可靠派发与状态 outbox 表。服务器需要兼容的 v2 ACK/状态接口，见服务器仓库 `docs/reliable-message-delivery.md`；不要让已接受任务降级到 v1 重发。

## 构建和签名

基站在 `android-sms-gateway` 目录以 `-PmmsReleaseBuild` 选择正式版本号；测试身份另用 `-PmmsTestBuild`，两者不要混用。release 签名通过本地 `app/keystore.jks` 和 `SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD` 环境变量配置。不要提交私钥或密码，发布前必须比较安装包与现有手机证书。

客服端在 `virgo_kf` 目录以 `-PreleaseClientNumber=1`（依次 1–5）选择正式身份；测试包用 `-PmmsTestClientNumber=1`，不能同时指定。`-PagentBaseUrl=https://api.example.com` 可覆盖默认 API。release 签名配置保存在本地 `keystore.properties`，同样不纳入 Git。

例如在配置好本地 SDK、Google Services 和原签名后，在 `android-sms-gateway` 目录运行：

```text
./gradlew :app:assembleRelease -PmmsReleaseBuild
```

在 `virgo_kf` 目录运行：

```text
./gradlew :app:assembleRelease -PreleaseClientNumber=1
```

Windows 使用对应目录的 `gradlew.bat`。本轮内网试点实际使用 `:app:assembleDebug -PmmsReleaseBuild` 生成正式身份和不可调试版本，再由本地发布流程用原私钥重新签名；它使用内网兼容的 network security 配置。普通 release 使用严格的网络配置，部署时应匹配 HTTPS 服务。Debug 构建的默认签名不能直接用于原正式手机的无损覆盖升级。

## 验证

本轮基站 JVM 测试 130 项通过，另验证 Room 23→24 的原数据保留。单台真机验证普通/长短信、出站 ACK 幂等、断网收信补传、两条相同文字分别入库、GIF/JPEG 彩信及公网签名图片下载；用户已确认客服端实际查看图片正常。原安装 UID、首次安装时间、权限和设备/SIM 身份保持，未卸载或清空数据。

普通短信到 Delivered 回执约 1.1 秒，长短信约 1.9 秒；新图片从手机接收记录到服务器约 7.9 秒。锁屏观察约 10 分钟且断网恢复无需点击上线。这些是本轮样本，不能作为整夜稳定性或运营商延迟承诺。定时短信仍有轮询延迟，本次晚约 27 秒。

`tools/test_reliability_migration.py` 可独立检查迁移 SQL；JVM 测试通过各项目的 `:app:testDebugUnitTest` 运行。原签名 APK、生产备份、实机日志及真实号码均保留在本地，不随 PR 上传。
