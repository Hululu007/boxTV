# boxTV 移动端对接技术文档

> **文档目的**：面向后续手机端（Android/iOS/其他）开发，完整描述 boxTV 电脑端（boxTV Host，自制 NAS 服务）的服务架构、通信协议、全部 REST API 规范、视频流传输机制与开发规范。本文档自包含——开发人员或 AI 辅助工具无需阅读原有电视端项目源码，即可基于本文档实现与电脑端服务的无缝对接。
>
> **适配的电脑端版本**：boxTV Host 1.2.2（协议版本 protocol = 1）
> **整理日期**：2026-11-08
> **配套细则**：《boxTV软件开发细则.md》（同目录）

---

## 目录

1. [系统概览与总体架构](#1-系统概览与总体架构)
2. [电脑端服务架构（boxTV Host）](#2-电脑端服务架构boxtv-host)
3. [通信协议总则](#3-通信协议总则)
4. [设备发现协议（UDP）](#4-设备发现协议udp)
5. [配对与鉴权机制](#5-配对与鉴权机制)
6. [REST API 接口规范全表](#6-rest-api-接口规范全表)
7. [视频流传输机制详解](#7-视频流传输机制详解)
8. [客户端参考实现要点](#8-客户端参考实现要点)
9. [移动端对接 Checklist 与开发规范](#9-移动端对接-checklist-与开发规范)
10. [附录：数据模型与参考代码](#10-附录数据模型与参考代码)

---

## 1. 系统概览与总体架构

### 1.1 系统组成

boxTV 是一个「电脑端 NAS 媒体服务 + 局域网播放客户端」系统：

| 端 | 名称 | 技术 | 职责 |
|---|---|---|---|
| 服务端 | boxTV Host（电脑端） | Kotlin + Ktor Netty（纯 JVM 桌面程序，Compose Desktop 提供 GUI） | 管理授权文件夹、设备配对、提供 REST API 与视频流 |
| 客户端 | boxTV app（电视端，已实现） | Kotlin + Android Leanback + Media3/ExoPlayer | 发现主机、配对、浏览文件、播放视频 |
| 客户端 | **移动端（待开发）** | 任意 | 复用电脑端同一套 API，实现手机端浏览/播放 |

### 1.2 架构与数据流总览

```
┌───────────────────────────── 局域网（同一网段） ─────────────────────────────┐
│                                                                              │
│  ┌──────────────┐  ①UDP广播发现(8965)  ┌──────────────────────────────┐     │
│  │   移动端      │ ───────────────────→ │   boxTV Host（电脑端）        │     │
│  │  (待开发)     │ ←─────────────────── │  - HTTP REST API (默认8964)  │     │
│  └──────┬───────┘   应答JSON(主机信息)   │  - UDP发现应答   (默认8965)  │     │
│         │                              │  - ffmpeg/ffprobe (转码/缩略图)│     │
│         │ ②POST /api/pair/request      └──────────┬───────────────────┘     │
│         │ ③电脑端用户点「允许」                    │                          │
│         │ ④POST /api/pair/verify轮询             │ 只读访问                  │
│         │    ← 返回 Token                        ▼                          │
│         │                              ┌──────────────────┐               │
│         │ ⑤携带 X-BoxTV-Token 访问      │  授权文件夹(本地磁盘)│               │
│         │ /api/folders /browse /search │  (用户在电脑端配置)  │               │
│         │ /api/video/stream ...        └──────────────────┘               │
│  ┌──────┴───────┐                                                                │
│  │ 播放器(ExoPlayer│ ⑥GET /api/video/stream（HTTP Range / fMP4转码流 / HLS）      │
│  │ /移动端播放器) │                                                                │
│  └──────────────┘                                                                │
└──────────────────────────────────────────────────────────────────────────────┘
```

**核心流程**：UDP 广播发现主机 → 发起配对 → 电脑端用户人工允许 → 轮询获得 Token → 携带 Token 浏览/搜索/播放 → 播放器直接消费视频流 URL（流请求同样携带 Token 头）。

### 1.3 现有电视端功能模块划分（移动端可对照参考）

电视端项目分层清晰，移动端可按同样思路组织代码：

| 电视端模块 | 路径 | 职责 | 移动端对应建议 |
|---|---|---|---|
| 网络层 | `data/remote/HostApi.kt` | HTTP 封装、统一信封解析、URL 构造 | API Client 层 |
| 数据仓库 | `data/repository/HostRepository.kt` | 网络+本地存储编排、401 自动清理失效主机 | Repository 层 |
| 发现 | `data/discovery/DiscoveryClient.kt` | UDP 广播探测、主机去重、静默收敛 | 发现服务 |
| 本地存储 | `data/local/LocalStore.kt` | 已配对主机、播放进度持久化 | 本地 DB/SP |
| 数据模型 | `model/Models.kt` | PairedHost / VideoEntry / VideoMeta 等 | 同名模型 |
| UI-配对 | `ui/pairing/PairingActivity.kt` | 发现→配对→轮询→落库 | 配对页 |
| UI-浏览 | `ui/browser/HostBrowserActivity.kt` | 文件夹列表/目录浏览/搜索 | 浏览页 |
| UI-播放 | `ui/playback/PlaybackActivity.kt` | ExoPlayer 播放、清晰度、续播、选集 | 播放页 |
| 更新 | `data/update/UpdateManager.kt` | 局域网 APK 更新（移动端可选择性实现） | 可选 |

---

## 2. 电脑端服务架构（boxTV Host）

### 2.1 技术栈与运行方式

- **语言/框架**：Kotlin，Ktor Server（Netty 引擎），kotlinx.serialization JSON
- **运行**：桌面程序（Windows），安装包 `deliverables/boxTVHost-1.0.0.msi`；启动即常驻服务，可最小化到系统托盘
- **源码位置**：`H:\Android\boxTV\boxTV-host\src\main\kotlin\com\example\boxtv\host\`

| 模块 | 关键文件 | 职责 |
|---|---|---|
| server 层 | `server/HostServer.kt` | Ktor 路由、鉴权、全部 API 实现 |
| | `server/Api.kt` | 统一响应信封与业务错误码 |
| | `server/VideoIdCodec.kt` | videoId 无状态编解码 |
| | `server/PathGuard.kt` | 路径越界防护、视频扩展名白名单 |
| | `server/Transcoder.kt` | ffmpeg 实时转码推流、元数据探测 |
| | `server/M3u8Aggregator.kt` | M3U8 识别/聚合/播放列表改写 |
| | `server/ThumbGenerator.kt` | 视频缩略图生成（480px JPEG，缓存） |
| core 层 | `core/Models.kt` / `ConfigStore.kt` | 配置与持久化（`%APPDATA%/boxTV/config.json`） |
| | `core/PairingSession.kt` | 配对会话状态机（300 秒 TTL） |
| | `core/DeviceRegistry.kt` | 已配对设备与 Token 注册表 |
| discovery 层 | `discovery/DiscoveryResponder.kt` | UDP 8965 发现应答 |
| 工具 | `tools/ffmpeg.exe` / `ffprobe.exe` | 项目根 `tools/` 目录，转码/缩略图/元数据依赖 |

### 2.2 端口约定

| 端口 | 协议 | 用途 | 可配置性 |
|---|---|---|---|
| **8964** | HTTP/TCP | REST API + 视频流 | 是（电脑端设置界面可改；`/api/ping` 与 UDP 应答都会携带实际端口） |
| **8965** | UDP | 设备发现应答 | 固定 |

> **注意**：移动端永远不要硬编码假设 HTTP 端口为 8964——应从 UDP 发现应答的 `httpPort` 字段读取，或由用户手动输入。

### 2.3 安全机制（移动端需知晓的行为）

1. **路径越界防护（P0）**：所有来自客户端的相对路径经 canonicalPath 规范化后校验必须位于授权文件夹内，越界返回 `403/40300`。客户端无法用 `../` 访问授权范围外的文件。
2. **视频扩展名白名单**：仅以下扩展名被视为可播放视频：`mp4, mkv, webm, avi, mov, ts, flv, m4v, mpg, mpeg, wmv, 3gp`。其他文件（字幕、图片等）在浏览/搜索结果中被过滤，**不会**出现在响应里。
3. **文件夹信息最小暴露**：`/api/folders` 只返回文件夹的 `id` 与 `name`，不暴露真实磁盘路径。
4. **Host 头校验**：M3U8 改写使用请求 Host 头作为绝对地址 authority，电脑端会校验其合法性（仅允许 ip/域名:port 字符）。
5. **Token 撤销即失效**：电脑端用户删除已配对设备后，该设备 Token 立即失效（客户端访问返回 `401/40100`，客户端应清除本地保存的该主机并引导重新配对）。

---

## 3. 通信协议总则

### 3.1 基本约定

| 项目 | 约定 |
|---|---|
| 传输协议 | HTTP/1.1，明文（局域网场景，无 TLS） |
| URL 前缀 | `http://{host}:{port}/api/...` |
| 请求编码 | UTF-8；所有查询参数（含中文路径、videoId）必须 URL 编码 |
| 响应编码 | UTF-8 JSON |
| 鉴权方式 | 请求头 `X-BoxTV-Token: {token}`（除 ping 与配对接口外全部必填） |
| 客户端 API 超时建议 | 连接 5~10 秒，请求 10 秒（大文件下载/视频流除外，应关闭整体超时） |

### 3.2 统一响应信封

**所有 JSON API**（含错误）都使用统一信封：

```json
// 成功
{ "code": 0, "data": { ... } }

// 失败
{ "code": 40100, "message": "Token 无效或已过期" }
```

- `code`：业务码，`0` 表示成功；此时必有 `data`（部分接口 data 可能为 null）
- `message`：仅失败时出现，中文人类可读错误信息（可直接展示给用户）
- 字段兼容性：JSON 解析建议使用 `ignoreUnknownKeys`（忽略未知字段）策略——电脑端会随版本演进追加新字段，旧字段带默认值，**解析端容忍未知字段即可向前向后兼容**

### 3.3 业务错误码全表

| code | HTTP 状态 | 含义 | 客户端建议处理 |
|---|---|---|---|
| 0 | 200 | 成功 | — |
| 40000 | 400 | 请求参数不合法 / 缺少参数 | 检查参数，一般不需要提示用户 |
| 40100 | 401 | **Token 无效或已过期**（电脑端已撤销授权或 Token 错误） | **清除本地该主机记录，引导用户重新配对** |
| 40300 | 403 | 路径越界（安全防护触发） | 提示「路径非法」，不应重试 |
| 40400 | 404 | 授权文件夹/文件/资源不存在 | 提示并刷新列表 |
| 40900 | 400 | 配对请求无效或已超时失效 | 引导重新发起配对 |
| 40901 | 409 | 等待电脑端点击「允许配对」 | 继续轮询（每 2 秒） |
| 40902 | 403 | 电脑端已拒绝本次配对 | 提示用户，可重新发起 |
| 50000 | 500 | 服务端内部错误（如缺少 ffmpeg） | 提示错误信息 |

> **重要**：`40901`（Pending）使用 HTTP 409 状态码返回，但语义上是**流程中间态而非错误**——客户端必须按业务码而非 HTTP 状态码判断配对是否继续等待。

---

## 4. 设备发现协议（UDP）

### 4.1 协议定义

移动端与电脑端在同一局域网内，通过 UDP 广播发现主机（对应电脑端 `DiscoveryResponder`，监听 UDP **8965**）：

| 项目 | 值 |
|---|---|
| 客户端探测包 | 纯文本 `BOXTV_DISCOVER_V1`（UTF-8，无任何包装） |
| 探测目标 | `255.255.255.255:8965` + 各网卡 /24 定向广播地址（`x.y.z.255`） |
| 重发策略 | 立即首发 1 次，之后每 **1 秒** 重发（丢包容错） |
| 电脑端应答 | 从监听端口（8965）**单播**应答 JSON 到探测方地址 |
| 主机 IP 获取 | **取自 UDP 数据包的来源地址**（不信任应答包体内字段，包体内没有 IP 字段） |
| 去重键 | `hostDeviceId`（同一台电脑可能多网卡多次应答） |

### 4.2 应答包格式（JSON, UTF-8）

```json
{
  "service": "boxtv-host",
  "protocol": 1,
  "hostName": "我的电脑",
  "httpPort": 8964,
  "hostDeviceId": "550e8400-e29b-41d4-a716-446655440000",
  "version": "1.2.2"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| service | string | 固定 `boxtv-host`，客户端据此过滤非 boxTV 设备的应答 |
| protocol | int | 协议版本，当前 1 |
| hostName | string | 主机显示名（电脑端设置中配置） |
| httpPort | int | REST API 实际端口 |
| hostDeviceId | string | 电脑端唯一标识（UUID，持久化），去重与配对落库主键 |
| version | string | 电脑端软件版本 |

### 4.3 推荐扫描策略（电视端实测优化值，可直接复用）

- 扫描窗口最大 10 秒（无任何应答时兜底退出）；
- **静默收敛提前结束**：已发现 ≥1 台主机后，连续 1.2 秒无新主机应答即结束（局域网应答集中在百毫秒级，典型 ~2 秒出结果）；
- 单次收包超时设 400ms，循环内判断发送节拍与收敛条件；
- 解析失败的单包直接忽略，不影响整体扫描；
- 已配对主机（本地已保存 hostDeviceId）在发现列表中过滤或标记为「已连接」。

---

## 5. 配对与鉴权机制

### 5.1 机制概述（2026-11-08 重构后：免配对码）

配对是**人工授权**模式：客户端发起请求 → 电脑端屏幕弹出配对通知（设备名+时间+300 秒倒计时）→ 电脑端用户点击「允许配对」→ 客户端轮询拿到 Token。**全程无配对码输入**。

核心约束（客户端需遵守）：
- 同一时刻电脑端只保留 1 个待确认会话，新请求会顶替旧会话（旧 pairingId 立即失效）；
- 配对会话一次性使用：验证成功后会话立即作废；
- 会话有效期 300 秒，超时自动失效（返回 40900）；
- 「拒绝」结果保留 300 秒供轮询读取（返回 40902），之后查同一 pairingId 变为 40900。

### 5.2 配对时序图

```
移动端                         电脑端(Host)                  电脑端用户
  │  POST /api/pair/request       │                            │
  │  {deviceUuid,deviceName,      │ 创建会话(300s TTL)          │
  │   deviceModel}                │──────────────────────────→ │ 弹出配对通知
  │  ← {pairingId, expiresAt}     │                            │ （允许/拒绝）
  │                               │                            │
  │  POST /api/pair/verify        │                            │
  │  {pairingId}                  │                            │
  │  ← 409/40901 Pending          │（用户未操作，每2秒重试）      │
  │  ...轮询...                   │                            │ 点击「允许」
  │  POST /api/pair/verify        │ 注册设备→签发Token(持久化)   │
  │  ← 200 {token, hostName}      │                            │
  │                               │                            │
  │  之后所有资源请求携带            │                            │
  │  X-BoxTV-Token: {token}       │                            │
```

### 5.3 配对接口详解

#### POST /api/pair/request —— 发起配对

- **鉴权**：无需 Token
- **请求体**（JSON）：

```json
{
  "deviceUuid": "客户端设备唯一UUID（首次生成后永久不变）",
  "deviceName": "boxTV-Pixel8（建议前缀+设备型号）",
  "deviceModel": "Google Pixel 8"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| deviceUuid | 是 | 客户端自身唯一标识。**必须持久化**（参考电视端做法：独立于主数据文件存储，数据文件重建时身份不变，否则电脑端设备列表会出现重复设备） |
| deviceName | 是 | 设备显示名，电脑端配对通知与设备列表中展示 |
| deviceModel | 否 | 设备型号描述 |

- **成功响应** `data`：

```json
{ "pairingId": "会话UUID", "expiresAt": 1699440000000 }
```

- 错误：`40000` 参数不合法。
- **注意**：重复调用会顶替旧会话。客户端「重新发起配对」时应停止旧轮询任务。

#### POST /api/pair/verify —— 轮询配对结果

- **鉴权**：无需 Token
- **请求体**：`{ "pairingId": "..." }`
- **四种结果**：

| 场景 | HTTP | code | data |
|---|---|---|---|
| 电脑端已允许 → **配对成功** | 200 | 0 | `{ "token": "32位随机hex", "hostName": "主机名" }` |
| 等待电脑端允许 | 409 | 40901 | 无（继续每 2 秒轮询） |
| 电脑端已拒绝 | 403 | 40902 | 无（提示用户，可重新发起） |
| 会话无效/已超时/已被顶替 | 400 | 40900 | 无（引导重新发起） |

### 5.4 Token 使用规则

1. Token 为 32 位随机十六进制字符串，**长期有效**（无过期时间），电脑端持久化在 `config.json`；
2. 除 `GET /api/ping` 与两个配对接口外，所有请求必须携带请求头 `X-BoxTV-Token`；
3. **视频流、缩略图、HLS 分片等媒体请求同样必须携带该请求头**（不是 query 参数）；
4. 客户端保存主机信息时建议落库结构：`{hostDeviceId, name, host, port, token, lastConnectedAt}`；
5. 收到 `40100` 即电脑端已撤销授权：清除本地主机记录（电视端会在下次任何请求 401 时自动移除该主机并回到配对引导）；
6. 客户端可随时用 `GET /api/ping`（携带 Token）探测主机在线状态与 Token 有效性。

---

## 6. REST API 接口规范全表

### 6.1 接口总览

| # | 方法 | 路径 | 鉴权 | 用途 |
|---|---|---|---|---|
| 1 | GET | `/api/ping` | 可选（带 Token 则校验） | 健康检查 / 手动连接验证 |
| 2 | POST | `/api/pair/request` | 无 | 发起配对 |
| 3 | POST | `/api/pair/verify` | 无 | 轮询配对结果 |
| 4 | GET | `/api/folders` | **需 Token** | 授权文件夹列表 |
| 5 | GET | `/api/browse` | **需 Token** | 浏览目录 |
| 6 | GET | `/api/search` | **需 Token** | 递归搜索视频 |
| 7 | GET | `/api/video/stream` | **需 Token** | 视频流（直连/转码/HLS） |
| 8 | GET | `/api/video/part` | **需 Token** | HLS 分片/子列表/密钥下发（由改写后的播放列表自动引用，客户端一般不直接调用） |
| 9 | GET | `/api/video/meta` | **需 Token** | 视频元数据（时长/分辨率） |
| 10 | GET | `/api/video/thumb` | **需 Token** | 视频缩略图（JPEG） |
| 11 | GET | `/api/update/check` | **需 Token** | 检查客户端应用更新（局域网分发） |
| 12 | GET | `/api/update/download` | **需 Token** | 下载客户端 APK（支持 Range 断点续传） |

> 所有媒体端点（7/8/9/10/12）**鉴权失败同样返回 401/40100 JSON 信封**（而非流内容）。

---

### 6.2 GET /api/ping —— 健康检查

- **鉴权**：无需 Token；但若请求携带有效 `X-BoxTV-Token`，响应中的 `requiresPair=false`
- **用途**：手动输入 IP 连接验证、主页在线状态检测、Token 有效性探测

**响应 `data`**：

```json
{
  "hostName": "我的电脑",
  "version": "1.2.2",
  "requiresPair": false,
  "hostDeviceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

| 字段 | 说明 |
|---|---|
| hostName | 主机显示名（可能为空字符串，空时客户端可自行回退显示 IP） |
| version | 电脑端版本号 |
| requiresPair | `true` = 该客户端未配对（需走配对流程）；`false` = Token 有效 |
| hostDeviceId | 电脑端唯一标识（**手动连接场景下客户端以它作为本地落库主键**） |

---

### 6.3 GET /api/folders —— 授权文件夹列表

- **鉴权**：需 Token
- **参数**：无
- **响应 `data`**（数组）：

```json
[
  { "id": "folder-uuid-1", "name": "电影" },
  { "id": "folder-uuid-2", "name": "电视剧" }
]
```

- `id`：文件夹唯一标识（后续 browse/search/videoId 均引用它）
- `name`：显示名（默认取文件夹原名，电脑端可改名）
- **注意**：不暴露真实磁盘路径。

---

### 6.4 GET /api/browse —— 浏览目录

- **鉴权**：需 Token
- **查询参数**：

| 参数 | 必填 | 说明 |
|---|---|---|
| folderId | 是 | 授权文件夹 id |
| path | 否 | 子目录相对路径（`/` 分隔，URL 编码；空/缺省 = 文件夹根目录） |

- **排序规则**：文件夹在前、视频在后，名称不区分大小写升序。
- **M3U8 聚合行为**（重要）：
  - 若浏览的目标目录本身是一个「有效 M3U8 影片目录」（内含完整分片的播放列表），响应只包含**单个虚拟视频条目**（`type="video"`, `format="m3u8"`，name = 目录名）；
  - 上级列表中有效影片目录仍显示为普通 `dir` 条目——层级恒为「总目录 → 分集子目录 → 影片条目」三级。

**响应 `data`**：

```json
{
  "entries": [
    { "name": "第一季", "type": "dir", "size": 0, "modifiedAt": 1699000000000, "videoId": null, "format": "file" },
    {
      "name": "EP01.mp4",
      "type": "video",
      "size": 1468006400,
      "modifiedAt": 1699000000000,
      "videoId": "Zm9sZGVyLXV1aWQtMXzR6aCt6KiYLmpwNA",
      "format": "file"
    }
  ]
}
```

**EntryDto 字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| name | string | 文件/目录名 |
| type | string | `"dir"` 子文件夹 / `"video"` 可播放视频 |
| size | long | 字节数（dir 恒为 0） |
| modifiedAt | long | 最后修改时间（毫秒时间戳） |
| videoId | string? | **仅 video 条目有值**；dir 条目为 null。视频流/缩略图/meta 接口的定位凭据 |
| format | string | `"file"` 普通视频 / `"m3u8"` HLS 聚合虚拟条目（带默认值，旧客户端忽略未知字段即可兼容） |

- **错误**：`40000` 缺 folderId；`40400` 文件夹不存在或目录不存在；`40300` 路径越界。

---

### 6.5 GET /api/search —— 递归搜索

- **鉴权**：需 Token
- **查询参数**：

| 参数 | 必填 | 说明 |
|---|---|---|
| folderId | 是 | 授权文件夹 id（在该文件夹内递归搜索） |
| q | 是 | 关键词（URL 编码；文件名不区分大小写包含匹配） |

- **行为**：在授权根目录整棵递归；命中上限 **500 条**；有效 M3U8 影片目录聚合为单条 `format="m3u8"` 条目（其内部分片被剪枝，不出现在结果中）；排序无保证。
- **响应 `data`**：与 browse 相同的 `{ "entries": [EntryDto...] }`。
- **错误**：`40000` 缺 folderId 或 q 为空；`40400` 文件夹不存在。

---

### 6.6 GET /api/video/stream —— 视频流（核心）

- **鉴权**：需 Token（**HTTP 请求头**，ExoPlayer/播放器数据源必须配置该头）
- **查询参数**：

| 参数 | 必填 | 说明 |
|---|---|---|
| videoId | 是 | 来自 browse/search 的条目（URL 编码） |
| quality | 否 | 清晰度档位：缺省/`"1080p"`/`"original"` → 默认档；`"720p"` / `"480p"` → 电脑端实时转码。未知值容错为原画直连 |
| start | 否 | 转码流起播位置（**秒**，可为小数，如 `start=123.500`）。仅转码流有意义 |

**三种传输模式**（电脑端自动决策，客户端只管按 URL 播放）：

| 模式 | 触发条件 | 传输特征 |
|---|---|---|
| ① 原画直连 | M3U8 条目；或 1080p 档且源片高度 ≤ 1080 | 原文件字节流，`Content-Type` 按扩展名推断；**支持 HTTP Range**（206 分段），可秒开、任意拖动 |
| ② 实时转码推流 | quality=720p/480p；或 1080p 档但源片高度 > 1080（如 4K） | ffmpeg 边转边推 **fMP4**（fragmented MP4）流；**不支持 Range**，无法按字节拖动（客户端用「重新起播」模型，见 §7.3） |
| ③ HLS 原生直连 | videoId 对应 M3U8 播放列表 | 响应 `application/vnd.apple.mpegurl` 播放列表文本，其中所有分片 URI 已被电脑端改写为 `/api/video/part?...` 绝对地址；支持原生 HLS seek；**quality/start 参数被忽略** |

- **错误**：`40000` videoId 缺失/非法；`40400` 文件夹或文件不存在；`40300` 路径越界。
- **清晰度决策表**（电脑端逻辑，客户端可据此预判是否为转码流）：

| 请求 quality | 源片高度 ≤1080 | 源片高度 >1080 |
|---|---|---|
| 缺省 / 1080p / original | 原画直连 | 转码 1080p |
| 720p | 转码 720p | 转码 720p |
| 480p | 转码 480p | 转码 480p |

> 判断源片高度需调用 `/api/video/meta`（电脑端 ffprobe 探测）。meta 不可用时客户端应降级为「不带 quality 直接请求」，即原画直连。

---

### 6.7 GET /api/video/part —— HLS 分片下发

- **鉴权**：需 Token
- **参数**：`videoId`（定位授权文件夹）、`u`（Base64URL 编码的授权根目录相对路径——**由电脑端改写播放列表时自动生成**，客户端不需要构造）
- **行为**：`.m3u8` 子列表 → 递归改写后以 `application/vnd.apple.mpegurl` 下发；分片/密钥/初始化段 → 原文件下发（支持 Range）。
- **移动端说明**：播放 HLS 时播放器从改写后的播放列表中自动解析并请求本接口（同样自动带 Token 头，因为沿用同一 DataSource）。客户端唯一要做的是**确保播放器全局 HTTP 数据源都带上 `X-BoxTV-Token` 头**。

---

### 6.8 GET /api/video/meta —— 视频元数据

- **鉴权**：需 Token
- **参数**：`videoId`
- **响应 `data`**：

```json
{ "durationMs": 7200000, "width": 3840, "height": 2160 }
```

| 字段 | 说明 |
|---|---|
| durationMs | 总时长（毫秒）。**转码流本身不携带时长，播放转码流时必须用它补偿进度条与进度保存** |
| width / height | 源片分辨率。`height > 1080` 时 1080p 档将触发电脑端转码 |

- **降级**：电脑端缺 ffprobe 或探测失败 → `404/40400`。客户端应降级为原画直连、不显示时长。
- 依赖 ffmpeg/ffprobe（电脑端项目 `tools/` 目录自带）。

---

### 6.9 GET /api/video/thumb —— 视频缩略图

- **鉴权**：需 Token
- **参数**：`videoId`
- **响应**：JPEG 图片二进制（`image/jpeg`，480px 宽），电脑端缓存（`%APPDATA%/boxTV/thumbCache/`）
- **降级**：生成失败/工具缺失 → `404/40400`，客户端显示占位图
- **建议**：列表页并发请求缩略图时注意限流（每台电脑端首次未命中会现场起 ffmpeg 抽帧）；URL 可直接交给图片库（如 Glide/Coil——但需为其配置带 `X-BoxTV-Token` 头的加载器）。

---

### 6.10 GET /api/update/check & /api/update/download —— 局域网应用更新（可选实现）

**check 响应 `data`**：

```json
{
  "available": true,
  "versionCode": 138,
  "versionName": "1.3.8",
  "changelog": "更新说明文本",
  "size": 32458752,
  "md5": "d41d8cd98f00b204e9800998ecf8427e"
}
```

- `available=false`：无新版本（其余字段为默认值）。
- 客户端用 `versionCode` 与自身比较；下载 `size` 字节；完成后做 **MD5 校验**。

**download**：直接返回 APK 二进制（`respondFile`），支持 Range 断点续传（206/Content-Range）。无发布版本时返回 `404/40400`。
- 电视端参考实现要点：流式下载到 `.part` 文件、支持暂停/继续（续传携带 `Range: bytes={已下载}-`）、MD5 校验失败删除重下、下载客户端应**关闭整体请求超时**（仅保留连接超时）。

---

## 7. 视频流传输机制详解

### 7.1 videoId 编码规则（无状态定位）

```
videoId = Base64URL( UTF-8( "{folderId}|{相对路径}" ) )   # 无填充
```

- `folderId`：授权文件夹 id；`相对路径`：文件相对授权根目录的路径，**统一 `/` 分隔**；
- 无状态、可反解：客户端本地也可以解码出相对路径用于**播放进度存储键**（电视端即用相对路径作为断点续播的 videoPath）；
- 客户端**只应原样透传** videoId（URL 编码后拼进 query），不应自行构造——folderId 无法伪造获得授权文件夹 id 之外的内容，且电脑端有 PathGuard 防越界。

### 7.2 原画直连流（Range）

- 电脑端 `respondFile` + Ktor `PartialContent` 插件自动处理 `Range` 请求（返回 206 + Content-Range）；
- 播放器行为：直接把 stream URL 交给播放器，**起播位置由播放器自行 seek 实现**（Range 拖动），客户端无需任何额外处理；
- 支持拖动进度条、秒开，零电脑端 CPU 开销。

### 7.3 实时转码流（fMP4 管道推流）与「重新起播」模型

电脑端 ffmpeg 参数要点（客户端需理解的行为约束）：
- 输出分段 MP4（`-movflags +frag_keyframe+empty_moov+default_base_moof`），头部即时可播；
- `-ss {start}` 输入侧精确定位；`scale=-2:{targetHeight}` 高度定标；H.264/AAC 重编码；
- **无 Range 能力**：转码流是一个从 `start` 开始的连续字节流。

**客户端「重新起播」模型（必须实现）**：

1. 请求转码流时携带 `start={秒}`；
2. **拖动进度条 / 长按快进快退 / 切换清晰度** → 用目标位置（原片时间线）换算为新 `start`，**重新发起一次 stream 请求**（旧请求直接断开即可，电脑端会立即回收旧 ffmpeg 进程——同设备同视频仅保留最新一条流，回收键为 `videoId|deviceUuid`）；
3. 转码流不携带总时长 → 播放器侧用 `/api/video/meta` 的 `durationMs` 补偿：
   - Android Media3 方案：`ClippingMediaSource(base, 0, durationMs*1000)` 裁剪窗口 + `ForwardingPlayer` 代理（读侧位置加回流起点，写侧 seek 拦截为换起点重播）——完整实现见电视端 `PlaybackActivity.kt`；
   - 其他平台：自实现「窗口位置 + 流起点 = 原片位置」的换算层；
4. **进度保存必须使用原片时间线位置**（`流起点 + 播放器窗口内位置`），否则断点续播错位；
5. seek 目标应夹取到 `[0, 时长-1秒]`，避免起点越界产生空流。

### 7.4 HLS（M3U8）直连

- 电脑端识别「一部影片 = 一个文件夹（m3u8 播放列表 + 全部分片）」的结构，浏览时聚合为单个虚拟条目（`format="m3u8"`）；
- 播放时电脑端把播放列表内所有相对 URI（分片、子列表、AES-128 密钥、EXT-X-MAP）改写为 `http://{Host头}/api/video/part?videoId={videoId}&u={Base64URL相对路径}` 绝对地址后下发；
- 客户端：把 stream URL 交给原生 HLS 播放器即可（Android Media3 建议显式 `setMimeType(MimeTypes.APPLICATION_M3U8)`）；原生精准 seek、自带总时长（**无需 meta 补偿、无重新起播模型**）；
- **限制**：HLS 条目不支持 720p/480p 转码档（电脑端忽略 quality 参数）——UI 上应置灰或隐藏低清晰度选项；
- 电脑端已校验播放列表完整性（分片/密钥齐全才聚合），残缺下载目录不会出现在列表中。

### 7.5 完整播放链路时序（推荐移动端实现）

```
浏览页点击视频条目
  │
  ├─ (非M3U8) GET /api/video/meta?videoId=   ← 获取 durationMs / height（失败则降级）
  │
  ├─ 本地查询断点进度（5%~95% 之间续播，否则从头）
  │
  ├─ 决策：quality=720p/480p？或 1080p 且 height>1080？ → 转码流（带 start=续播秒）
  │        否则 → 直连流（播放器自行 seek 到续播位置）
  │
  ├─ 播放器请求 stream URL（数据源全局注入 X-BoxTV-Token 头）
  │
  ├─ 播放中：每 5 秒节流保存进度（原片时间线）+ 退出/切后台时保存
  │
  └─ 播放自然结束：清除进度（或写 position=0 使续播判定失效）
```

---

## 8. 客户端参考实现要点

### 8.1 HTTP 客户端

电视端参考栈（可平移）：Ktor Client（CIO 引擎）+ kotlinx.serialization。关键配置：

```kotlin
HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 10_000   // API 请求 10 秒
        connectTimeoutMillis = 5_000
    }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
}
```

- 统一信封解析：`code == 0 && data != null` → 成功；否则抛业务错误（携带 code/message）；
- 网络异常独立分类（区别于业务错误），UI 据此提示「网络错误」；
- **40100 专项处理**：任何资源请求返回 40100 → 自动清除本地该主机 → 引导重新配对。

### 8.2 播放器（Android 为例）

```kotlin
// 关键点1：HTTP 数据源工厂全局注入 Token 头（直连/转码/HLS分片/缩略图全部生效）
val dataSourceFactory = DefaultHttpDataSource.Factory()
    .setDefaultRequestProperties(mapOf("X-BoxTV-Token" to token))
    .setConnectTimeoutMs(10_000)
    .setReadTimeoutMs(30_000)
    .setAllowCrossProtocolRedirects(true)

// 关键点2：HLS 条目显式声明 MIME
MediaItem.Builder().setUri(streamUrl).setMimeType(MimeTypes.APPLICATION_M3U8).build()

// 关键点3：转码流 → ClippingMediaSource 裁剪窗口 + ForwardingPlayer 位置补偿
// （完整实现参考电视端 PlaybackActivity.kt 的 buildSeekProxy / applySource）
```

### 8.3 数据模型（与电脑端 DTO 一一对应）

```kotlin
// 统一信封
@Serializable data class ApiEnvelope(val code: Int = -1, val data: JsonElement? = null, val message: String? = null)

// ping
@Serializable data class PingData(val hostName: String, val version: String, val requiresPair: Boolean, val hostDeviceId: String = "")
// 配对
@Serializable data class PairRequestData(val pairingId: String, val expiresAt: Long)
@Serializable data class PairVerifyData(val token: String, val hostName: String)
// 文件夹/条目
@Serializable data class FolderDto(val id: String, val name: String)
@Serializable data class EntryDto(
    val name: String, val type: String, val size: Long = 0, val modifiedAt: Long = 0,
    val videoId: String? = null, val format: String = "file")
@Serializable data class EntriesData(val entries: List<EntryDto> = emptyList())
// 元数据 / 更新
@Serializable data class VideoMeta(val durationMs: Long = 0, val width: Int = 0, val height: Int = 0)
@Serializable data class UpdateCheckData(
    val available: Boolean = false, val versionCode: Long = 0, val versionName: String = "",
    val changelog: String = "", val size: Long = 0, val md5: String = "")
// 客户端本地持久化建议
@Serializable data class PairedHost(
    val hostDeviceId: String, val name: String, val host: String,
    val port: Int, val token: String, val lastConnectedAt: Long = 0)
@Serializable data class PlaybackProgress(
    val hostDeviceId: String, val videoPath: String,
    val positionMs: Long, val durationMs: Long, val updatedAt: Long)
```

### 8.4 URL 构造汇总

```kotlin
val base = "http://$host:$port"
ping      = "$base/api/ping"
pairReq   = "$base/api/pair/request"        // POST
pairVer   = "$base/api/pair/verify"          // POST
folders   = "$base/api/folders"
browse    = "$base/api/browse?folderId=${enc(folderId)}&path=${enc(path)}"
search    = "$base/api/search?folderId=${enc(folderId)}&q=${enc(query)}"
stream    = "$base/api/video/stream?videoId=${enc(videoId)}" +
            (quality?.let { "&quality=${enc(it)}" } ?: "") +
            (startSec?.let { "&start=%.3f".format(it) } ?: "")
meta      = "$base/api/video/meta?videoId=${enc(videoId)}"
thumb     = "$base/api/video/thumb?videoId=${enc(videoId)}"
updCheck  = "$base/api/update/check"
updDown   = "$base/api/update/download"
```

> `enc` = UTF-8 URL 编码。`start` 格式化为 3 位小数秒。

---

## 9. 移动端对接 Checklist 与开发规范

### 9.1 功能对接 Checklist

**连接与配对**
- [ ] UDP 8965 广播 `BOXTV_DISCOVER_V1`，多网卡定向广播 + 1 秒重发 + 静默收敛
- [ ] 主机按 `hostDeviceId` 去重；已配对主机过滤/标记
- [ ] 手动输入 IP + 端口连接（`/api/ping` 验证，`requiresPair` 判断是否需配对）
- [ ] 配对：`pair/request` → 每 2 秒 `pair/verify` 轮询 → 区分 0/40901/40902/40900 四态
- [ ] 配对成功持久化 `{hostDeviceId, name, host, port, token}`；**deviceUuid 永久稳定**
- [ ] 收到 40100 → 清除该主机 → 引导重新配对
- [ ] 电脑端 HTTP 端口可变——一律从发现应答/用户输入取，不硬编码 8964

**浏览与播放**
- [ ] `/api/folders` → `/api/browse` 目录导航（`dir`/`video` 条目区分，path 相对路径拼接）
- [ ] `/api/search` 搜索（上限 500，可提示截断）
- [ ] 播放器数据源**全局注入 `X-BoxTV-Token` 头**
- [ ] M3U8 条目（`format=="m3u8"`）：HLS 直连播放、显式 MIME、隐藏低清晰度选项
- [ ] 普通视频：先 `meta` 探测（失败降级直连）；`height>1080` 时 1080p 档为转码流
- [ ] 转码流：重新起播模型 + meta 时长补偿 + 原片时间线进度换算
- [ ] 断点续播：进度键 = hostDeviceId + videoPath（相对路径）；5%~95% 续播；5 秒节流保存；退出/切后台保存；播放结束清理
- [ ] 缩略图：404 降级占位图；图片加载器配置 Token 头
- [ ] 中文路径/文件名全部 UTF-8 URL 编码

**健壮性**
- [ ] JSON 解析 `ignoreUnknownKeys`（容忍电脑端新增字段）
- [ ] 业务码优先于 HTTP 状态码判断（尤其 40901 Pending）
- [ ] 网络错误与业务错误分类提示
- [ ] 大文件下载（更新包）关闭整体超时、支持 Range 续传

### 9.2 协议注意事项（易踩坑汇总）

1. **`40901` 是 HTTP 409**：轮询配对时不要把非 200 一律当失败。
2. **Token 头而非 query 参数**：所有媒体请求（含 HLS 分片）都是请求头鉴权；部分播放器/图片库需要自定义 Header 的 DataSource/Interceptor。
3. **转码流无 Range**：试图对转码流 Range 拖动会从头播放；必须走重新起播。
4. **转码流无时长**：进度条与进度保存依赖 `/api/video/meta`；meta 404 时应降级直连。
5. **start 单位是秒不是毫秒**。
6. **quality 参数对 HLS 无效**：不会报错，但不会转码。
7. **videoId 是复合编码**（folderId|相对路径），不要试图按「纯文件 id」理解；本地续播键可用解码出的相对路径。
8. **新配对请求顶替旧会话**：重新配对前先取消旧轮询协程/定时器。
9. **同一电脑多网卡会应答多次**：去重键是 hostDeviceId，取第一次应答 IP 即可。
10. **浏览结果不包含非视频文件**：字幕等文件被电脑端过滤，移动端无需（也无法）获取。
11. **授权文件夹真实路径永不暴露**：UI 上只展示 `name`。
12. **电脑端单配对会话 + 一次作废**：verify 成功一次后 pairingId 立即失效，勿重复使用。
13. **`format` 字段是后加的**：解析 EntryDto 必须给默认值 `"file"`，兼容更旧的电脑端。
14. **明文 HTTP**：仅适合局域网/可信网络环境，勿将服务暴露公网。

### 9.3 开发规范建议（延续本项目约定）

- 分层架构：UI → Repository → (Remote API + Local Store)，UI 不直接接触网络；
- 全部模块用**中文注释**解释（项目既有约定）；
- 每个阶段产出可运行版本并验收；
- 技术选型保持**全免费、无账号依赖**（Ktor/kotlinx.serialization/ExoPlayer 均满足）；
- 错误信息面向用户展示电脑端返回的中文 `message`，技术细节进日志。

---

## 10. 附录：数据模型与参考代码

### 10.1 电脑端持久化配置（config.json 结构，仅供理解，客户端无需操作）

```json
{
  "port": 8964,
  "hostName": "我的电脑",
  "hostDeviceId": "uuid",
  "folders": [ { "id": "uuid", "name": "电影", "path": "D:\\Movies" } ],
  "devices": [ { "deviceUuid": "uuid", "name": "boxTV-TV", "model": "...", "token": "32hex",
                 "pairedAt": 0, "lastConnectedAt": 0 } ],
  "closeToTray": true
}
```

- 撤销授权 = 从 `devices` 删除 → 该 Token 立即 401。

### 10.2 最小可用对接示例（Kotlin 伪代码，约 40 行跑通全链路）

```kotlin
// ① 发现
val hosts = DiscoveryClient().discover()                 // UDP 广播，见 §4
val target = hosts.first()

// ② 配对（假设未配对）
val req = api.pairRequest(target.host, target.httpPort, deviceUuid, "boxTV-Mobile", Build.MODEL)
val pairingId = (req as ApiResult.Ok).data.pairingId
var token = ""
while (token.isEmpty()) {                                 // 每 2 秒轮询
    when (val v = api.pairVerify(target.host, target.httpPort, pairingId)) {
        is ApiResult.Ok        -> token = v.data.token    // 0：成功
        is ApiResult.ApiError  -> if (v.code != 40901) error(v.message) // 40902/40900 退出
        is ApiResult.NetworkError -> Unit                    // 忽略瞬时网络抖动，继续轮询
    }
    delay(2_000)
}

// ③ 浏览
val folders = (api.folders(target.host, target.httpPort, token) as ApiResult.Ok).data
val entries = (api.browse(target.host, target.httpPort, token, folders[0].id, "") as ApiResult.Ok).data

// ④ 播放（把 URL 交给播放器，数据源需注入 X-BoxTV-Token 头）
val video = entries.first { it.type == "video" }!!
val url = "http://${target.host}:${target.httpPort}/api/video/stream?videoId=${video.videoId!!.encodeURLParameter()}"
```

### 10.3 源码索引（需要核对细节时查阅）

| 内容 | 文件 |
|---|---|
| 全部路由与 DTO 定义 | `boxTV-host/src/main/kotlin/com/example/boxtv/host/server/HostServer.kt` |
| 统一信封/错误码 | `boxTV-host/.../server/Api.kt` |
| 配对状态机 | `boxTV-host/.../core/PairingSession.kt` |
| videoId 编解码 | `boxTV-host/.../server/VideoIdCodec.kt` |
| 路径防护/白名单 | `boxTV-host/.../server/PathGuard.kt` |
| 转码实现 | `boxTV-host/.../server/Transcoder.kt` |
| M3U8 聚合改写 | `boxTV-host/.../server/M3u8Aggregator.kt` |
| UDP 发现应答 | `boxTV-host/.../discovery/DiscoveryResponder.kt` |
| 电视端 API 客户端 | `app/src/main/java/com/example/boxtv/data/remote/HostApi.kt` |
| 电视端仓库层 | `app/src/main/java/com/example/boxtv/data/repository/HostRepository.kt` |
| 电视端 UDP 发现 | `app/src/main/java/com/example/boxtv/data/discovery/DiscoveryClient.kt` |
| 电视端本地存储 | `app/src/main/java/com/example/boxtv/data/local/LocalStore.kt` |
| 电视端播放实现 | `app/src/main/java/com/example/boxtv/ui/playback/PlaybackActivity.kt` |
| 电视端配对 UI | `app/src/main/java/com/example/boxtv/ui/pairing/PairingActivity.kt` |
| 电视端更新管理 | `app/src/main/java/com/example/boxtv/data/update/UpdateManager.kt` |

---

*本文档基于 boxTV Host 1.2.2 / 电视端 1.3.8 源码整理。若电脑端协议升级（protocol 字段或 version 变化），请先 diff 上述 server 层源码再更新本文档。*
