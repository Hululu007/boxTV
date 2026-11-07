# boxTV 软件开发细则

> 版本：v1.3（规划稿 + 开发期修订）
> 日期：2026-11-07
> 状态：M1/M2/M3 已按此开发交付；M4 按本版修订开发；本文档记录开发期经需求方确认的修订

## 修订记录

| 日期 | 修订项 | 内容 | 依据 |
|---|---|---|---|
| 2026-11-06 | 4.2 配对显示 | 配对码由「仅电脑端显示」改为「电脑端与电视端同步显示」；`/api/pair/request` 响应增加 `code` 字段随响应下发 | 需求方口头确认（优化配对码显示） |
| 2026-11-06 | 6.2 配对弹窗 | 电脑端配对确认「允许/拒绝」弹窗以「主窗口配对码实时显示区」形态先行交付（大号码+设备名+倒计时），M4 继续完善允许/拒绝交互 | 同上 |
| 2026-11-06 | 工具链 | AGP 9.2.1 → 8.13.2（AGP9 内置 Kotlin 构建工具类冲突，环境级被迫调整；功能与细则一致） | 开发环境实测 |
| 2026-11-06 | 5.4 播放控制条 | leanback PlaybackFragment → Media3 PlayerView（功能等价：暂停/进度/快进快退遥控器可用） | 开发简化决策 |
| 2026-11-06 | 4.2 配对码有效期 | 60 秒 → **300 秒（5 分钟）**，双端 UI 文案同步（用户需求：延长输入时间） | 需求方口头确认 |
| 2026-11-06 | 4.2/6.2 配对确认交互（M4） | 电视端提交正确配对码后须电脑端「允许并签发」才签发 Token：`/api/pair/verify` 新增业务码 `40901 PAIR_PENDING`（等待允许，电视端每 2 秒自动重试）与 `40902 PAIR_REJECTED`（已拒绝，电视端提示可重新发起）；电脑端配对码卡片增加 [允许并签发] [拒绝] 按钮；拒绝后会话作废并记录拒绝标记 | 需求方确认（M4 范围） |
| 2026-11-06 | 4.2 撤销授权闭环 | 电脑端撤销授权后：电视端收到 401 自动清理本地失效主机（Toast「授权已失效」并返回主页）；配对引导发现列表对已配对主机校验授权（401 → 移除并恢复显示在可配对列表） | 需求方口头确认 |
| 2026-11-06 | 5.1 交互方式 | 电视端卡片双击触发改为单击触发（去掉 focusableInTouchMode，符合遥控器操作逻辑；DPAD 导航不变） | 需求方口头确认 |
| 2026-11-07 | 5.1 主页交互 | 「我的主机」卡片增加**长按手势**（遥控器 OK 长按 / 触摸长按）：弹出「删除主机」确认对话框，确认后清除本地配对记录（Token 失效），需重新配对才能访问该电脑 | 需求方口头确认 |
| 2026-11-07 | 4.2 配对等待期交互修复 | 修复「电脑端已允许签发，电视端无法输入配对码/无法点击确认」：等待允许的轮询反馈全部改为状态行**原地刷新**（notifyActionChanged），禁止 setActions 重建动作列表（重建会关闭输入编辑器、清空已输码、重置焦点） | 需求方缺陷反馈 |

---

## 1. 项目概述

### 1.1 产品定位

boxTV 是一套解决「电脑视频 → 电视播放」痛点的局域网流媒体方案，由两个独立软件组成：

| 端 | 名称 | 角色 | 形态 |
|---|---|---|---|
| 电视端 | boxTV (Android TV App) | 访问端 / 播放器 | Android TV 应用（Leanback） |
| 电脑端 | boxTV Host（授权后台） | 服务端 / 自制 NAS | Windows 桌面软件（系统托盘常驻） |

### 1.2 解决的痛点

用户想在大屏电视上看电脑里的视频，目前必须经历「U盘拷贝 / 投屏 / 手动共享 SMB」等繁琐操作。boxTV 让电脑变成一台轻量「自制 NAS」：

1. 用户在电脑端把指定文件夹**授权**给 boxTV；
2. 电视端在局域网内**自动发现**电脑，**一次配对**后长期可访问；
3. 电视端像浏览本地文件一样浏览授权文件夹，**直接点击在线播放**，无需拷贝、无需投屏。

### 1.3 明确排除项（本期不做）

- ❌ 外挂字幕加载（srt/ass）——用户未选择，列为二期候选
- ❌ 海报刮削（豆瓣/TMDB）——仅使用视频缩略帧
- ❌ 公网/跨网段访问——仅限局域网
- ❌ 音轨切换、倍速播放——使用 ExoPlayer 默认能力即可

---

## 2. 已确认的技术决策

以下决策已与需求方确认，开发过程中不得随意变更：

| # | 决策项 | 结论 |
|---|---|---|
| D1 | 电视端技术栈 | Kotlin + Android Leanback（沿用现有模板，minSdk 29，targetSdk 36） |
| D2 | 电视端播放器 | Media3 / ExoPlayer 1.x（替换模板自带的 MediaPlayerAdapter） |
| D3 | 电脑端技术栈 | Kotlin + Compose Desktop（含 JBR 打包，可生成 exe） |
| D4 | 电脑端 HTTP 服务 | Ktor Embedded Server（Netty 引擎），支持 HTTP Range |
| D5 | 设备发现 | mDNS/UDP 广播自动发现 + 手动输入 IP:端口 兜底 |
| D6 | 安全机制 | 配对码（4位数字）+ Token 签发，后续请求凭 Token 鉴权 |
| D7 | 电视端主界面 | 文件夹浏览式（逐层进入，类似文件管理器） |
| D8 | 电视端功能范围 | 断点续播、视频缩略图、文件搜索 |
| D9 | 电脑端功能范围 | 多文件夹管理、设备管理、访问日志、托盘常驻/开机自启 |
| D10 | 传输协议 | HTTP REST + 流式播放（支持 Range 断点续传） |

---

## 3. 总体架构

### 3.1 架构图

```
┌─────────────────────────┐          ┌──────────────────────────────┐
│      电视端 boxTV        │          │       电脑端 boxTV Host        │
│  (Android TV / Leanback) │          │  (Windows / Compose Desktop) │
│                          │          │                              │
│  ┌────────────────────┐  │          │  ┌────────────────────────┐  │
│  │ 连接管理器          │◄─┼── UDP ───┼─►│ 发现应答器 (:8965/udp)  │  │
│  └────────────────────┘  │          │  └────────────────────────┘  │
│  ┌────────────────────┐  │          │  ┌────────────────────────┐  │
│  │ API 客户端 (Ktor)   │◄─┼── HTTP ──┼─►│ Ktor HTTP 服务 (:8964)  │  │
│  └────────────────────┘  │          │  │  · 鉴权中间件(Token)     │  │
│  ┌────────────────────┐  │          │  │  · 浏览/搜索/流式API     │  │
│  │ ExoPlayer 播放器    │◄─┼── HTTP ──┼─►│  · 缩略图生成器          │  │
│  └────────────────────┘  │  Range   │  └───────────┬────────────┘  │
│  ┌────────────────────┐  │          │  ┌───────────▼────────────┐  │
│  │ Room 本地数据库      │  │          │  │ 授权管理器 (config.json) │  │
│  │ ·配对信息 ·播放进度  │  │          │  └───────────┬────────────┘  │
│  └────────────────────┘  │          │  ┌───────────▼────────────┐  │
└─────────────────────────┘          │  │ Compose Desktop UI      │  │
                                     │  │ ·文件夹授权 ·设备管理    │  │
                                     │  │ ·访问日志 ·设置/托盘     │  │
                                     │  └────────────────────────┘  │
                                     └──────────────┬───────────────┘
                                                    ▼
                                          只读访问已授权文件夹
```

### 3.2 通信总览

| 阶段 | 协议 | 端口 | 说明 |
|---|---|---|---|
| 设备发现 | UDP 广播 | 8965/udp | TV 广播探测，Host 应答自身信息 |
| 配对 | HTTP | 8964/tcp | 请求配对 → 输码验证 → 签发 Token |
| 浏览/搜索/缩略图 | HTTP (REST) | 8964/tcp | 全部携带 Token |
| 视频流播放 | HTTP (Range) | 8964/tcp | ExoPlayer 标准 HTTP 数据源 |

> 端口号可在电脑端设置中修改；电视端手动连接时需输入与设置一致的端口。

---

## 4. 通信协议细则

### 4.1 设备发现协议（UDP）

**电视端 → 局域网广播（每 2 秒一次，共 5 次）：**

```
BOXTV_DISCOVER_V1
```

**电脑端 → 单播应答（收到探测包后回复探测方）：**

```json
{
  "service": "boxtv-host",
  "protocol": 1,
  "hostName": "我的电脑",
  "httpPort": 8964,
  "hostDeviceId": "uuid-of-pc",
  "version": "1.0.0"
}
```

规则：
- 电视端对同一 `hostDeviceId` 去重，展示「主机名 + IP:端口」列表；
- 电脑端仅绑定局域网网卡监听，应答前不校验 Token（发现阶段无需鉴权）；
- 手动连接兜底：电视端提供「手动输入 IP:端口」入口，直接调用 `GET /api/ping` 验证可达性。

### 4.2 配对流程（Token 签发）

```
电视端                          电脑端
  │  POST /api/pair/request       │
  │  {deviceName, deviceModel}    │
  │ ─────────────────────────────►│  生成 4 位配对码 + pairingId
  │  {pairingId, expiresAt}       │  弹窗显示配对码（300秒有效）
  │ ◄─────────────────────────────│
  │                               │  用户看到电视上显示的输入框
  │  (用户在电视端输入配对码)        │
  │  POST /api/pair/verify        │
  │  {pairingId, code, deviceName}│
  │ ─────────────────────────────►│  校验配对码 → 生成 token
  │                               │  持久化设备记录(绑定 deviceUuid)
  │  {token, hostName}            │  界面提示"新设备已接入"
  │ ◄─────────────────────────────│
  │                               │
  │  之后所有请求 Header:           │
  │  X-BoxTV-Token: <token>       │
```

规则：
- 电视端首次启动生成 UUID（`deviceUuid`，持久化），作为自身唯一标识；
- 配对码 300 秒（5 分钟）过期，过期后需重新发起；同一时刻最多存在 1 个待确认配对；
- **允许/拒绝确认（M4）**：电视端提交正确配对码后，电脑端须点击「允许并签发」才签发 Token（验证返回 `40901`，电视端每 2 秒自动重试）；电脑端点击「拒绝」后会话立即作废（验证返回 `40902`，电视端提示可重新发起）；
- Token 为 32 位随机十六进制，电脑端持久化存储（设备与 Token 一一对应）；
- 电脑端「设备管理」中移除设备 = 删除 Token，该电视立即失去访问权（下次请求返回 401）；
- 电视端请求收到 401 时：UI 提示「授权已失效，请在电脑端重新配对」，自动清理本地失效主机，重新搜索时该电脑可再次被发现并配对。

### 4.3 REST API 全表

统一前缀 `/api`，统一响应包裹（流式接口除外）：

```json
// 成功
{ "code": 0, "data": { ... } }
// 失败
{ "code": 40100, "message": "Token 无效或已过期" }
```

| 方法 | 路径 | 鉴权 | 请求参数 | 响应 data | 说明 |
|---|---|---|---|---|---|
| GET | `/api/ping` | 否 | - | `{hostName, version, requiresPair}` | 健康检查/手动连接验证 |
| POST | `/api/pair/request` | 否 | `{deviceUuid, deviceName, deviceModel}` | `{pairingId, expiresAt, code}` | 发起配对（code：配对码，两端同步显示） |
| POST | `/api/pair/verify` | 否 | `{pairingId, code}` | `{token, hostName}` | 验码并签发 Token |
| GET | `/api/folders` | 是 | - | `[{id, name}]` | 已授权文件夹列表（只暴露名称与 id） |
| GET | `/api/browse` | 是 | `?folderId=&path=` | `{entries: [{name, type: dir/video, size, modifiedAt, videoId?}]}` | 浏览目录，path 为相对路径 |
| GET | `/api/search` | 是 | `?folderId=&q=` | `{entries: [...]}` | 在授权文件夹内递归搜索视频文件 |
| GET | `/api/video/thumb` | 是 | `?videoId=` | image/jpeg 二进制 | 缩略图（无缓存时现场生成） |
| GET | `/api/video/stream` | 是 | `?videoId=` | video/* 二进制 | 视频流，必须支持 `Range` 请求头 |
| GET | `/api/download` | 是 | `?videoId=` | 二进制 | 预留（本期可不实现） |

**关键规则：**

1. **videoId 生成**：`videoId = Base64URL(相对路径 UTF-8 字节)`，无状态、可反解，电脑端反解出相对路径后拼接授权根目录得到绝对路径，避免服务端存储映射表；
2. **路径安全（强制）**：所有 `path`/`videoId` 反解后必须做规范化（`File.canonicalPath`），规范化结果**必须仍在对应授权文件夹之内**，否则返回 40300「路径越界」，杜绝 `../` 目录穿越；
3. **可播放判定**：扩展名白名单：`mp4 mkv webm avi mov ts flv m4v mpg mpeg wmv 3gp`；
4. **浏览响应排序**：文件夹在前、视频在后，均按名称升序；
5. **401 处理**：Token 校验失败统一返回 `code: 40100` + HTTP 401。

### 4.4 缩略图生成方案（电脑端）

- 电脑端内置/捆绑 `ffmpeg.exe`（随安装包分发，放在程序目录 `tools/ffmpeg.exe`）；
- 首次收到缩略图请求时：提取视频 10% 处关键帧，缩放为宽 480px 的 JPEG，缓存至 `%APPDATA%/boxTV/thumbCache/<md5(videoId)>.jpg`；
- 缓存命中直接返回；生成失败（损坏文件/不支持编码）返回 404，电视端降级显示占位图标；
- 若 `ffmpeg.exe` 缺失：缩略图接口统一返回 404，功能整体降级，不影响其他功能。

---

## 5. 电视端设计（boxTV）

### 5.1 页面结构（Leanback 导航流）

```
[MainActivity: BrowseSupportFragment]
 ├─ 第 1 行「我的主机」：已配对主机卡片（含在线状态）
 ├─ 第 2 行「添加主机」：搜索到的未配对主机 / 手动输入入口
 │
 ├─► [HostBrowserActivity]（进入某主机）
 │    ├─ 顶部：文件夹列表（授权文件夹，相当于根目录 Tab）
 │    ├─ 顶部：搜索入口（遥控器键盘输入）
 │    ├─ 未选择文件夹时：Grid 显示视频卡片（文件夹内视频）
 │    └─ 视频卡片 → [DetailsActivity]
 │
 ├─► [DetailsActivity] 视频详情
 │    ├─ 标题 / 文件大小 / 修改时间 / 时长
 │    ├─ 按钮：「继续播放 (mm:ss)」（有进度时）/「从头播放」
 │    └─ ─► [PlaybackActivity]
 │
 ├─► [PlaybackActivity] ExoPlayer 播放
 │    ├─ Leanback 自带播放控制条（快进/快退/暂停/进度）
 │    ├─ 每 5 秒上报进度 → Room（断点续播）
 │    └─ 播放错误 → 错误提示 + 返回
 │
 └─► [PairingFragment: GuidedStepFragment] 配对引导
      ├─ 步骤1：选择主机（自动发现列表）
      ├─ 步骤2：显示配对码输入（电视端软键盘）
      └─ 步骤3：成功提示 → 返回主页刷新
```

### 5.2 模块划分

```
com.example.boxtv
├── data
│   ├── local          # Room：PairedHostDao / PlaybackProgressDao
│   ├── remote         # Ktor Client：HostApi（browse/search/stream/pair）
│   ├── discovery      # UDP 发现（DatagramSocket 广播+监听）
│   └── repository     # HostRepository：统一数据出口，UI 不直接接触网络
├── ui
│   ├── main           # MainActivity / MainFragment（主页两行）
│   ├── browser        # HostBrowserActivity（文件夹浏览+搜索）
│   ├── details        # DetailsActivity（详情+续播入口）
│   ├── playback       # PlaybackActivity + ExoPlayer 封装
│   └── pairing        # 配对引导（GuidedStepFragment）
├── model              # Movie(改造) / Folder / VideoEntry / PairedHost
└── util               # 时间格式化 / 文件大小格式化 / 错误映射
```

### 5.3 数据库设计（Room）

```sql
-- 已配对主机
CREATE TABLE paired_host (
  hostDeviceId TEXT PRIMARY KEY,   -- 电脑端 UUID
  name         TEXT NOT NULL,      -- 电脑端主机名
  host         TEXT NOT NULL,      -- IP
  port         INTEGER NOT NULL,   -- HTTP 端口
  token        TEXT NOT NULL,
  lastConnectedAt INTEGER
);

-- 播放进度（断点续播）
CREATE TABLE playback_progress (
  hostDeviceId TEXT NOT NULL,
  videoPath    TEXT NOT NULL,      -- 相对路径（浏览接口返回的原样）
  positionMs   INTEGER NOT NULL,
  durationMs   INTEGER NOT NULL,
  updatedAt    INTEGER NOT NULL,
  PRIMARY KEY (hostDeviceId, videoPath)
);
-- 触发规则：退出播放页 / 切后台 / 每 5 秒节流写入
-- 清理规则：播放进度条 > 95% 或 < 5% 时视为看完/刚开始，不续播
```

### 5.4 播放器实现要点

- ExoPlayer + `DefaultHttpDataSource`，请求头统一携带 `X-BoxTV-Token`；
- 利用 HTTP Range 实现秒开与 seek（ExoPlayer 默认支持，无需额外处理）；
- 视频渲染规格：`surfaceView` + 适配 16:9，黑边策略 `ResizeMode.FIT`；
- 音轨/字幕使用系统默认行为，不做自定义切换 UI（见排除项）。

---

## 6. 电脑端设计（boxTV Host）

### 6.1 技术组成

| 组件 | 选型 | 说明 |
|---|---|---|
| UI | Compose Desktop (Material3) | 单窗口 + 系统托盘 |
| HTTP 服务 | Ktor Server (Netty) | 默认端口 8964 |
| 持久化 | JSON 配置文件 | `%APPDATA%/boxTV/config.json`，无需数据库 |
| 缩略图 | ffmpeg.exe（捆绑） | 缓存目录 `%APPDATA%/boxTV/thumbCache/` |
| 开机自启 | 注册表 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` | 设置页开关 |
| 打包 | Compose Desktop `packageDistributionForCurrentOS` | 产出 MSI/EXE 安装包 |

### 6.2 UI 结构

```
主窗口（1024×680，可最小化到托盘）
├── 左栏：授权文件夹
│    ├── 文件夹列表（名称 + 实际路径 + 视频数量统计）
│    ├── [＋ 添加文件夹]（系统目录选择对话框）
│    └── [移除] [在资源管理器中打开]
├── 右栏（Tab 切换）
│    ├── Tab1 设备管理
│    │     ├── 已配对设备表：设备名 / 最近连接时间 / 状态
│    │     └── 操作：[断开（撤销Token）]
│    ├── Tab2 访问日志
│    │     └── 滚动日志：时间 / 设备 / 动作（浏览/播放/搜索）/ 目标文件
│    └── Tab3 设置
│          ├── HTTP 端口（修改后需重启服务）
│          ├── 主机显示名
│          ├── [√] 开机自启
│          └── [√] 关闭窗口时最小化到托盘
└── 状态栏：服务运行状态 / IP:端口 / 当前连接数

配对弹窗（服务层触发，模态）
├── 提示：「电视设备 xxx 请求访问」
├── 大号配对码显示（4 位数字，300 秒倒计时）
└── 按钮：[允许并签发] [拒绝]（允许前电视端验证返回 40901，拒绝返回 40902）
```

### 6.3 模块划分

```
boxTV-host/
├── server                 # Ktor 服务
│   ├── Routing.kt         # 路由注册（按 4.3 API 表）
│   ├── AuthInterceptor    # Token 鉴权中间件
│   ├── PathGuard          # 路径越界防护（规范化 + 前缀校验）
│   ├── StreamRoute        # Range 流式响应
│   └── ThumbGenerator     # ffmpeg 缩略图（异步 + 缓存）
├── discovery              # UDP :8965 应答器
├── core
│   ├── ConfigStore        # config.json 读写（kotlinx.serialization）
│   ├── DeviceRegistry     # 配对设备/Token 管理
│   ├── PairingSession     # 配对码会话（60s 过期）
│   └── AccessLogger       # 内存环形日志（500 条）→ UI 订阅
└── ui                     # Compose 界面（文件夹/设备/日志/设置）+ 托盘
```

### 6.4 电脑端行为细节

- **启动即服务**：应用启动即拉起 HTTP + UDP 服务，无需手动「开启服务」；
- **托盘**：关闭窗口默认最小化到托盘（可设置改为退出），托盘菜单：显示主窗口 / 退出；
- **多网卡**：监听所有非回环 IPv4 网卡上的 UDP 广播，应答包附带收到请求的网卡 IP（保证多网卡机器电视端拿到正确 IP）；
- **文件变更**：不做文件系统监听，每次浏览实时读取目录（授权文件夹内视频数量不会太大，实时列举性能足够）；
- **日志**：内存保留最近 500 条，不落盘（本期）。

---

## 7. 非功能需求

| 类别 | 要求 |
|---|---|
| 性能 | 视频流支持 ≥ 30 Mbps 稳定吞吐（千兆局域网实测）；浏览接口响应 < 300ms（千级文件目录） |
| 超时 | 电视端 API 超时 10s；发现探测总时长 10s 后展示「未找到主机」+ 手动输入入口 |
| 编码 | 所有接口 UTF-8；URL 参数中的路径必须 URL 编码（中文路径） |
| 安全 | Token 随机数 ≥ 128bit；路径穿越防护为最高优先级安全项（P0） |
| 健壮性 | 电视端所有网络错误都有 UI 兜底（Toast/错误页），不允许白屏卡死 |
| 兼容 | 电视端 minSdk 29；电脑端 Win10/11 x64 |

---

## 8. 工程结构规划

```
h:/Android/boxTV/
├── app/                    # 电视端（现有模块，原地改造）
├── boxTV-host/             # 电脑端（新建 Compose Desktop 模块）
│   ├── build.gradle.kts
│   └── src/main/kotlin/...
├── docs/
│   └── boxTV软件开发细则.md   # 本文档
└── build.gradle.kts        # 根工程（settings 引入 boxTV-host）
```

> 电视端与电脑端同属一个 Gradle 根工程，但相互独立、互不依赖；电脑端使用独立的 Kotlin/JVM 插件链，不引入 Android 插件。

---

## 9. 开发里程碑

| 里程碑 | 内容 | 验收标准 |
|---|---|---|
| **M1 服务端基础** | 电脑端工程搭建 + Ktor 服务 + 授权文件夹 API + Token 鉴权 + 路径防护 | 用 curl 完成配对→浏览→取流全链路 |
| **M2 连接与浏览** | 电视端 UDP 发现 + 配对 UI + 主机/文件夹浏览 + 搜索 | 电视端可浏览并列出真实视频文件 |
| **M3 播放核心** | ExoPlayer 接入 + Range 流式播放 + 断点续播 | 电视端流畅播放 mp4/mkv，可 seek，重进续播 |
| **M4 体验完善** | 缩略图（ffmpeg）+ 访问日志 + 设备管理 + 详情页 | 卡片有缩略图；电脑端可撤销授权并实时生效 |
| **M5 桌面化交付** | 托盘常驻 + 开机自启 + 端口设置 + 打包 MSI + 电视端打包 APK | 双端安装包可用，完整流程演示通过 |

每个里程碑完成后在对话中同步演示/验证，再进入下一个。

---

## 10. 测试要点清单

- [ ] 中文/空格/特殊字符路径与文件名的编码与播放
- [ ] 大文件（>4GB，mkv 高码率）seek 与拖动进度条
- [ ] 电视端休眠/断网重连后 Token 会话恢复
- [ ] 电脑端撤销授权 → 电视端下一次请求收到 401 并正确提示
- [ ] 构造 `path=../../` 恶意请求 → 必须返回 40300
- [ ] 配对码过期 / 错误配对码 / 等待电脑端允许（40901 自动重试）/ 拒绝配对（40902）四种分支 UI
- [ ] 多台设备同时连接、同时播放互不干扰
- [ ] 电脑端修改端口后电视端手动连接与自动发现均正常

---

## 11. 风险与预案

| 风险 | 影响 | 预案 |
|---|---|---|
| ffmpeg 捆绑使安装包增大约 80MB | 中 | 缩略图为可选降级功能，缺失时返回占位图，不阻塞其他功能 |
| 老电视盒子硬解不支持某些编码（如 HEVC） | 中 | ExoPlayer 自动降级软解；文档注明推荐格式 H.264/MP4 |
| Windows 防火墙拦截 8964/8965 | 高 | 电脑端首次启动检测并引导用户放行（弹防火墙授权提示）；文档写明手动放行步骤 |
| 部分 TV 设备无 Google Play | 低 | 直接侧载 APK 交付 |

---

**文档结束。请确认本细则，确认后按 M1 → M5 顺序开发。**
