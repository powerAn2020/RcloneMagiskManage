# 任务编辑、新任务启动与远端测试连接修复计划

## 任务拆解与验证

- [x] 1. 客户端与文案扩充 (`GatewayClient.kt`, `strings.xml`) → 验证方式: 确认 `updateJob` 与 `testRemoteConfig(remoteId)` 签名及中英文资源完备
- [x] 2. 任务界面增强 (`JobsScreen.kt`) → 验证方式: 确认 `CREATED` 状态具备启动按钮，卡片具备编辑入口，弹窗完整支持回填与 `updateJob` 调用
- [x] 3. 远端编辑测试凭证继承 (`RemotesScreen.kt`) → 验证方式: 确认 `RemoteFormDialog` 接收 `remoteId` 并在 `runTest` 中透传
- [x] 4. 项目编译验证 → 验证方式: 运行 Gradle 编译确保类型安全与零编译报错
