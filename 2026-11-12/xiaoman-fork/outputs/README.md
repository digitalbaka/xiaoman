# 小满Extra（xiaoman 二创）交付说明

本目录是本次 Android 二创的构建产物与说明。代码改动直接落在工作区根目录 `f:\xiaomanextra`（未新建副本，也未推送到远端）。

## 产物

| 文件 | 说明 |
| --- | --- |
| `qiuyuying-debug.apk` | 可直接安装的 debug 包（文件名沿用历史产物），`applicationId` 仍为 `dev.xiaoman.app`，显示名「小满Extra」 |

## 构建方式

环境：JDK 17、Android SDK（`compileSdk 35`、`minSdk 25`）、Gradle 8.11.1、AGP 8.7.3。
本机把 Android SDK 放在 `F:\sdk`（`local.properties` 里的 `sdk.dir`），Gradle 发行包放在 `C:\g\gradle-8.11.1`。

```powershell
$env:ANDROID_HOME='F:\sdk'; $env:ANDROID_SDK_ROOT='F:\sdk'
C:\g\gradle-8.11.1\bin\gradle.bat :app:assembleDebug
```

> 本机访问 `dl.google.com` / `services.gradle.org` 会超时，因此 `settings.gradle.kts` 使用阿里云 Maven 镜像
> （google / public / gradle-plugin）。换到网络正常的机器时，可以恢复成 `google()` + `mavenCentral()`。

验证：`gradle :core-memory:test :core-skills:test :core-agent:test :core-llm:test :core-sentinel:test` 全部通过；
APK 已安装到 Android 8.0 真机（SM-G6100）实际走查，未出现崩溃。

## 修复：Android 8.0 发送消息闪退

- 现象：Android 8.0（SM-G6100）上配置好聊天模型后，输入内容点发送立即闪退。
- 抓到的崩溃：`java.util.regex.PatternSyntaxException: U_ILLEGAL_ARGUMENT_ERROR`，
  堆栈为 `dev.xiaoman.agent.FlowRunner.<clinit>(FlowRunner.kt:91)`。
- 根因：`FlowRunner` 的占位符正则用了 `\p{IsHan}`。Android 8.x 的 `java.util.regex.Pattern` 不支持这个
  Unicode 脚本属性，类初始化时编译正则就抛异常；`XiaomanAgent` 构造时会创建 `FlowRunner`，所以第一次发送消息必崩。
- 修复：改为显式 CJK 区间 `[\u3400-\u4dbf\u4e00-\u9fa5]`，匹配行为不变，API 25/26 均可运行。
- 验证：重新打包安装后，在同一台 Android 8.0 真机上发送消息，正常收到模型回复，logcat 无 FATAL / PatternSyntaxException。

## 七项需求的实现与验证

### 1. 图标与应用名（正式更名「小满Extra」）

- 桌面图标换成新附件原图：正方形源图按比例居中裁切，不做拉伸。生成
  `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`（48/72/96/144/192）与
  `ic_launcher_round.png`（同尺寸圆形裁切）。
- 自适应图标：同目录生成 `ic_launcher_foreground.png`（108/162/216/324/432，透明画布 + 原图等比铺满）与
  `ic_launcher_background.png`（同尺寸，纯品牌绿）。背景色取自原图 `#1E6B52`，与原图圆角外的底色一致，
  API 26+ 的圆形/圆角遮罩裁切时不会出现色边。
- `mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml` 提供 API 26+ 的自适应图标；API 25 用上面的位图。
- `strings.xml` 的 `app_name`、`assistant_default_name`、聊天/提示/通知文案统一改为「小满Extra」；`hands` 模块
  的中英文无障碍文案同步。界面里原来写死的「求与影」也一并替换。
- 包名仍是 `dev.xiaoman.app`，可直接覆盖安装，旧数据保留。
- APK badging 已确认：`application-label:'小满Extra'`、`sdkVersion:'25'`。

### 2. Android 7.1（API 25）及以上

- `app` 与 `hands` 的 `minSdk` 均为 25；开启 core library desugaring（`desugar_jdk_libs 2.1.5`），
  `core-agent` 里的 `java.time` 在打包时被脱糖，API 25 可用。
- 通知渠道只在 API 26+ 创建（`Notifier.createChannels` 做了版本守卫，渠道创建放进 `@RequiresApi(26)` 方法）。
- 去掉 API 26 才有的 `java.nio.file`（`FileMemoryStore` 改用临时文件 + `renameTo`，路径安全用 canonicalPath 判断）。
- 去掉 API 26 才有的 `java.util.Base64`（`OpenAiCompatibleClient` 自带编码器）。
- `AccessibilityService.takeScreenshot` 是 API 30+ 接口：低于 Android 11 直接返回「无截图」，只用节点树操作，
  不会因缺方法崩溃（`AccessibilityDevice.captureScreenshot` 有 `SDK_INT < R` 守卫）。
- 图片裁剪切图用 `android.graphics.Bitmap`/`Matrix`，EXIF 旋转用 `android.media.ExifInterface(InputStream)`（API 24+）。

### 3. 自定义外观（设置 → 个性化）

- 聊天背景：从相册选图 → 裁剪（3:4，可拖动/双指缩放）→ 存 `files/custom/background-*.jpg`，聊天页铺满并加
  45% 遮罩保证文字可读；可「恢复默认」。
- 助手头像：选图 → 1:1 裁剪 → 存 `files/custom/avatar-*.png`，聊天页头部和每条助手消息前都会显示。
- 助手名字：保存后全局生效（聊天头部、横幅提示、系统提示词「你是 XXX」都会用新名字）。
- 主题色：6 个预设色板（海蓝/雾紫/桃粉/落日/森绿/石墨），保存后通过 `XiaomanTheme(accentHex)` 全局换色，重启保留。

### 4. 技能下载/导入（底部「技能」Tab → 安装技能）

- 支持三种来源：SkillHub 商店（见第 10 节）；zip 直链 URL；本地 zip（文件选择器）。
- 安装器 `DirectorySkillInstaller`（`core-skills`）：下载 → 解压（防 zip-slip、限制文件数与 20MB）→ 最多向下 3 层
  查找 `SKILL.md`（支持一个 zip 装多个技能）→ `SkillParser` 校验 → 落盘到 `files/skills/<name>/`。
- 已安装列表可启用/停用（`.disabled` 标记，加载时跳过）和删除；内置技能仍然可用。
- 实测：本机起 HTTP 服务，移动端输入 zip 直链安装成功，禁用/启用、删除均正常，磁盘目录随之变化。

### 5. 记忆文件夹（底部「记忆」Tab）

- 界面参考图 3：渐变头部、搜索、`+` 新建文件夹、双列文件夹卡片、面包屑导航、笔记列表。
- 支持新建/重命名/移动到其它文件夹/删除（递归）；笔记可在笔记详情里「移动」到其它文件夹，移动后重新生成
  permalink 并同步索引。`MemoryStore` 新增对应默认方法，`FileMemoryStore` 用真实目录实现。
- AI 工具：`memory_folder`（列出指定文件夹下的子文件夹与笔记摘要）、`memory_search` / `memory_write` 增加
  `folder` 参数；系统提示词已写明「用户让你查看某个记忆文件夹时用 memory_folder」。

### 6. 自定义聊天模型（设置 → 管理模型）

- 参考图 2：模型卡片列表、默认标记、单选切换默认、添加/编辑/删除、测试连通。
- 字段：名称、Base URL、API Key、模型 ID、Temperature、最大输出 Token；API Key 存 `EncryptedSharedPreferences`，
  其它字段存普通 SharedPreferences（JSON 数组）。
- 保存的模型即成为聊天默认模型（同步到聊天的 `ModelSettings`）；删除最后一个模型会清空聊天模型配置。
- 实测：用本地假 OpenAI 兼容服务验证「测试连接 → 连接成功」、保存后成为默认、删除后重启不复活。

### 7. 聊天里安装技能

- 新增工具 `skill_install`（`core-agent/ChatTools`）：模型解析用户消息里的 zip 链接并调用安装器，安装后
  `skills.reload()`，把「装了哪些、是否替换」作为工具结果回给模型，由模型答复用户。
- 该工具只在 `SkillInstaller` 注入时可用（`AppGraph` 已注入）；系统提示词新增对应说明。

### 8. 外部服务 / MCP 服务（设置 → 外部服务 / MCP 服务）

- 页面：顶部「已启用 N 个」+「推荐组合」；内置服务按 出行 / 生活 / 效率 / 资讯 分组，每项一个开关；
  麦当劳、AI 热点资讯标注「免 Key」，其余启用后展开 API Key 与可选的服务地址；「其他 MCP 服务」可添加
  名称、地址（https://…）、Authorization 请求头，并可删除；「调用前确认」三选一；底部「保存」「测试连接」。
- 协议：`core-agent/McpRemote.kt` 用 `HttpURLConnection` 实现 MCP Streamable HTTP（JSON-RPC 2.0）：
  `initialize` → `notifications/initialized` → `tools/list` → `tools/call`，保存 `Mcp-Session-Id`，
  兼容 `application/json` 与 `text/event-stream` 响应；401/403、HTTP 非 2xx、DNS/超时都会转成可读的失败原因。
- 工具层：`XiaomanAgent` 每轮把启用服务的工具以 `mcp_<服务>_<工具>` 注入 `ChatRequest.tools`
  （描述带「【服务名】」前缀），`ChatTools` 负责分发；单个服务发现失败只跳过该服务，不影响聊天。
- 调用前确认：`会修改数据时确认` 依据 MCP 的 `readOnlyHint`，没有该字段时按工具名/描述里的
  创建、修改、删除、发送、下单、支付等动词判断；`每次确认` 所有调用都问；`从不确认` 直接执行。
  确认复用聊天里的提问通道，用户回复「确认/可以/ok/yes」才执行，其它回复视为取消。
- 持久化：非敏感配置存普通 SharedPreferences，Key 与 Authorization 存 `EncryptedSharedPreferences`；
  `operator.sameAsChat`、每个服务的开关/地址、自定义服务、确认策略重启后都保留。
- 实测（Android 8.0 真机）：用本机起的测试 MCP 服务（`adb reverse` 到 127.0.0.1）添加为自定义服务，
  「测试连接」返回「连接成功，发现 2 个工具」；聊天中让模型调用只读工具会直接返回结果，调用写工具时
  先出现「等你确认」，回复 ok 后才真正发出 `tools/call`。另有 7 个单测覆盖三种确认策略、拒绝取消和错误回传。
- 注意：内置服务的默认地址是常见官方/公开端点，个别服务（途牛、滴滴、AI 热点资讯等）可能需要你按服务方
  文档改地址；在对应服务启用后直接改「服务地址」即可，不需要改代码。

### 9. 操作模型「与聊天模型相同」

- 设置 →「操作模型（需支持图片）」顶部新增复选框。勾选后 Base URL、模型名称、API Key、Temperature
  四个输入框回填聊天模型的值并禁用；取消勾选恢复可编辑，原来的独立配置值不会被覆盖。
- 保存时勾选状态写入 `operator.sameAsChat`，`AppSettings.effectiveOperator` 在勾选时直接把聊天模型交给
  操作模型使用；重启后复选框保持勾选、字段仍为禁用状态。实测覆盖安装并重启验证过。

### 10. SkillHub 商店（技能 Tab → 商店）

- 入口：底部「技能」Tab 右上角「商店」，打开即加载 SkillHub 热门榜单
  （`GET /api/v1/showcase/hot`），顶部搜索框走 `GET /api/v1/search?q=&limit=`。
- 说明与卡点：官方 `https://skillhub.cn/install/skillhub.md` 只提供 bash 安装脚本
  （`curl … install.sh | bash`）和 Python CLI（`skillhub install <skill> --dir <skills 目录>`）。
  Android 端既不能执行 bash，也不能运行 Python CLI，因此没有照搬安装脚本；改为按官方 CLI 源码
  `skills_store_cli.py` 里真实使用的公开 HTTP 接口实现——搜索 `/api/v1/search`、榜单
  `/api/v1/showcase/*`、下载 `/api/v1/download?slug=`（失败时回退 CLI 里的静态地址
  `…/skills/<slug>.zip`）。没有新增或猜测任何接口，这些接口匿名可用、不需要 Key。
- 安装：下载 zip → 复用现有 `DirectorySkillInstaller`（防 zip-slip、20MB/文件数限制、最多向下 3 层找
  `SKILL.md`）→ 落到应用内 `files/skills/<name>/`；装完立即刷新，「已安装」列表里可启用/停用/删除。
- 格式兼容：SkillHub 的 `SKILL.md` 使用更宽的 Agent Skills frontmatter（`slug`/`version`/`tags`/`tools`、
  多行 `summary`/`description`），本应用的解析器要求 `name`/`description` 单行且只认固定字段。安装时会做
  一次规范化（只保留 `name`/`description`、说明压成单行并写回磁盘），否则商店技能会被判为不合法而装不上。
- 来源标注：安装成功后写入 `files/skills/<name>/.origin`（如 `SkillHub · @zcwl/multi-search-engine · v3.0.2`），
  技能列表卡片直接显示；删除技能时随目录一起清掉。
- 失败提示：DNS 失败、超时、HTTP 非 2xx、返回内容不是 zip、JSON 格式不符都会在商店页给出中文原因，
  不会静默失败。
- 实测（Android 8.0 真机）：热门榜单与英文关键词搜索都能返回真实条目；安装 `@zcwl/multi-search-engine`
  后 `files/skills/multi-search-engine/` 出现规范化后的 `SKILL.md` 与包内资源，列表显示来源且可启用/停用/删除；
  关闭网络后搜索显示「无法解析 api.skillhub.cn，请检查网络或 DNS」。
- 注意：商店技能多为面向 Agent 的说明文档，部分附带 Python 脚本。本应用只使用 `SKILL.md` 的说明文本，
  **不会执行技能包内的脚本**；依赖脚本的步骤仍需由聊天模型或操作模型完成。

## 已知限制 / 需要你拍板的点

1. **Skills 下载源**：现已接入 SkillHub 商店（热门榜单 + 关键词搜索 + 一键安装），同时保留「zip 直链 / 本地 zip」。
   仍不支持 `owner/repo` 形式，也没有做技能自动更新检查。
2. **包名保留** `dev.xiaoman.app`（未改成新包名），这样老用户可直接覆盖安装、数据不丢；若要换包名需另说。
3. **Android 11 以下无截屏**：操作模型只能靠无障碍节点树，部分纯图像界面可能识别不到，这是系统限制。
4. **debug 签名**：产物是 debug APK，可直接侧载；上架或长期分发需要你自己的 release keystore。
5. 头像/背景图片没有做云同步，只存在应用私有目录（卸载会清掉）。
6. **内置 MCP 地址大多为公开常见端点**：部分服务（途牛酒店/机票、滴滴、AI 热点资讯）没有公开稳定的
   Streamable HTTP 地址，页面里允许直接改地址；如果你有官方地址或申请 Key 的方式，告诉我可以改成默认值。
