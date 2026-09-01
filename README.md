# Awake 课表

版本：**2.0.0**

Awake 是一个本地优先的 Android 课表应用，支持华南理工大学（SCUT）官方 CAS 登录和教务课表导入。

## 当前能力

- 官方 CAS WebView 登录，不在 App 内保存密码、Cookie、ticket 或 token。
- 支持直连教务入口和学校 WebVPN 官方门户入口。
- 课程按「主课程 + 时段」规范化存储：同一门课（课程名 + 教学班号）的所有时段归并到一条记录，颜色、学分等课程信息随主课程保存。
- 支持多个学年/学期课表，可在主界面选择「添加新课表 / 覆盖当前课表」两种导入方式。
- 课表 JSON 分享与导入：右上角可导出完整课表 JSON，导入页可粘贴分享文本暂存后统一导入；分享课表首次同步前需要确认。
- 周次以 1–30 的网格选择器设置（支持按住拖动区间选择），相邻周自动合并显示。
- 节次时间可随课表独立配置，切换课表时设置跟随课表（无独立配置时回退全局默认）。
- 课前提醒、桌面小组件只读取当前选中的本地课表。
- 学校适配器通过 `SchoolAdapterRegistry` 注册。当前实际支持学校只有 SCUT。

## 2.0.0 主要变更

- 数据模型重构：`courses` 拆分为课程主记录 + `course_sections` 时段表，周次关系挂随时段（数据库 v6，含 2→6 全链路迁移）。
- 新增「创建课表」流程：主界面 + 号与「创建课表」统一走「添加新课表 / 覆盖当前课表」二选一；覆盖模式只保留一份待导入课表，导入前二次确认。
- 暂存制导入：教务学期、JSON 文本、空课表统一进入「待导入课表」列表，可逐项删除，最后统一导入。
- 课程颜色：马卡龙色板默认分配 + 详情页可点选预设/RGB 输入/随机取色，颜色随主课程即时保存，同步时保留用户自定义颜色。
- 默认打开当前周：按学期第一周日期自动定位，顶部显示「第 X 周 · 本周」；周次切换改为滑块。
- 分享课表同步保护：JSON 导入的课表首次刷新前弹窗确认，避免被当前账号课程静默覆盖。
- 移除日历（ICS）导出与离线演示课表入口；空态页文案与操作按钮更新。
- 构建工具链升级支持：Room 2.7.1（适配 KSP2）、阿里云镜像仓库。

## 构建

项目使用 Gradle Wrapper，Windows 下执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK 输出到：

```text
app\build\outputs\apk\debug\app-debug.apk
```

编译本地单元测试源码：

```powershell
.\gradlew.bat :app:compileDebugUnitTestKotlin
```

运行单元测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

> 推荐使用 JDK 17 运行 Gradle 构建和单元测试。默认 JDK 25 可能触发 AGP/Gradle 兼容问题。

Release 构建目前未配置正式签名密钥，产物为 unsigned APK；发布到应用商店或分发前，需要在本地安全配置签名信息，不要将密钥提交到 Git。

如需设备验证：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 发布与自动更新（GitHub Releases）

- 发版流程：更新 `versionCode`/`versionName` → 构建签名 APK → 打 tag `v<versionName>` 并推送 → 在 GitHub Releases 创建正式 Release（不要勾选 pre-release），上传 APK。
- Release body 前几行按约定字段填写（App 端更新检查读取）：

```text
versionName: 2.0.0
versionCode: 200
apk-sha256: <APK 的 SHA-256>
更新内容：……
```

- 检测地址：`https://api.github.com/repos/Lunaunde/awake/releases/latest`（取最近的非预发布 Release）。
- 国内网络直连 GitHub 可能不稳定，发布时可考虑给浏览器下载链接加镜像。

## 隐私与网络边界

- 所有登录操作在学校官方页面完成，App 不实现或绕过密码加密、验证码、二次认证和风控。
- 认证 Cookie 只保存在进程内存中；退出登录会清理内存会话。
- 网络明文只对用户指定的 SCUT 教务直连域名开放，其他域名保持禁止明文通信。
- 自动备份和设备迁移规则排除本地数据库、会话偏好和文件目录，避免课表及会话被无意转移。
- 日志和测试 fixture 不应包含学号、密码、Cookie、ticket、token、完整认证 URL 或完整个人课表。
- 请仅使用本人账号进行低频手动同步，并遵守学校信息化系统使用规定。

## 验收重点

1. 先登录官方 CAS，再选择学年、学期和课表名称导入；导入前可选择覆盖当前课表或新建。
2. 同学期再次导入时确认提示符合预期；JSON/空课表与教务学期统一暂存、统一导入。
3. 检查第 1 周、当前周、单周/双周课程及跨节次课程。
4. 断网后确认已保存课表仍可查看；网络失败不能清空旧课表。
5. 切换学期后确认课表、提醒、小组件不串数据；节次时间随课表切换。
6. 删除数据后确认提醒和会话一并清理；可删除最后一个课表回到空态页。