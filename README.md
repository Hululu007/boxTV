# boxTV — 局域网自制 NAS 影音方案

boxTV 是一套「电脑端媒体服务 + 电视端播放应用」的局域网影音系统：把家里闲置的 Windows 电脑变成一台私有 NAS 媒体服务器， Android 电视/盒子通过遥控器即可浏览、搜索并播放电脑硬盘里的视频，全程局域网直连，**不依赖任何公网服务、无需注册任何账号、完全免费**。

## 解决的实际问题

- **电视存储太小、USB 外挂硬盘插拔麻烦**：视频都在电脑硬盘上，电视端只做播放，零存储占用；
- **公有网盘限速、开会员、隐私顾虑**：数据始终留在自己电脑里，传输走局域网，千兆内网可跑满带宽；
- **现成 NAS / DLNA 方案门槛高**：不需要群晖等硬件，一台普通 Windows 电脑 + 一个安装包即可；
- **4K / 大文件播放卡顿**：电脑端按需实时转码（ffmpeg），电视端硬解压力小，弱网还能降到 720P/480P；
- **M3U8 下载资源播放困难**：电脑端自动识别「一部影片 = 一个文件夹（m3u8 + 分片）」的结构并聚合为单个影片条目，遥控器点开即播，支持 AES-128 本地密钥与多码率变体。

## 功能特性

### 电脑端（boxTV Host）
- 一键启动的常驻服务（系统托盘），管理授权文件夹与已配对设备
- 局域网 UDP 自动发现 + 免配对码人工授权（电脑端弹窗「允许 / 拒绝」，300 秒有效期）
- 视频原画直连（HTTP Range 秒开拖动）/ ffmpeg 实时转码（720P / 480P / 4K→1080P）/ M3U8 HLS 聚合播放
- 视频缩略图生成（480px JPEG，本地缓存）、视频元数据探测（时长/分辨率）
- 电视端应用局域网更新分发（APK 发布 + Range 断点续传 + MD5 校验）
- 访问日志、路径越界防护（防 `../` 目录穿越）、设备撤销授权即时生效

### 电视端（boxTV app，Android TV）
- 主页「我的主机」多主机管理、在线状态检测、授权文件夹浏览与递归搜索
- Media3/ExoPlayer 播放：断点续播（5%~95%）、清晰度切换、选集列表、10 秒步进快进快退（长按连跳）
- PCM 音量增益 0–200%（适配不同机型输出差异）、遥控器全键盘操作
- 缩略图墙（Glide + ffmpeg 缩略图接口，404 自动占位）

## 技术架构

```
┌──────────────────────────── 局域网（同一网段） ────────────────────────────┐
│                                                                           │
│  ┌──────────────┐  ①UDP广播发现(8965)   ┌─────────────────────────────┐    │
│  │ 电视端 app    │ ────────────────────→ │  boxTV Host（电脑端）        │    │
│  │ Kotlin       │ ←──────────────────── │  · REST API     (默认 8964) │    │
│  │ Leanback     │   应答 JSON(主机信息)  │  · UDP 发现应答  (固定 8965) │    │
│  │ Media3       │                       │  · ffmpeg / ffprobe         │    │
│  └──────┬───────┘  ②配对(人工允许→Token) └──────────┬──────────────────┘    │
│         │ ③携带 X-BoxTV-Token 访问                  │ 只读访问               │
│         │    /api/folders /browse /search ...      ▼                       │
│  ┌──────┴───────┐                        ┌──────────────────┐             │
│  │ 播放器请求流   │ ④GET /api/video/stream │ 授权文件夹(本地磁盘)│             │
│  └──────────────┘  (Range/fMP4转码/HLS)  └──────────────────┘             │
└───────────────────────────────────────────────────────────────────────────┘
```

| 模块 | 技术栈 | 说明 |
|---|---|---|
| 电脑端服务 | Kotlin + Ktor Server (Netty) + Compose Desktop | REST API、UDP 发现、配对、转码、缩略图、托盘常驻 |
| 序列化 | kotlinx.serialization | 前后端统一 JSON 模型，统一信封 `{code, data, message}` |
| 转码/媒体探测 | ffmpeg / ffprobe（电脑端捆绑） | 4K→1080P 降档、720P/480P 实时推流（fMP4 管道）、抽帧缩略图 |
| 电视端 UI | Android Leanback + View | 遥控器焦点导航适配 |
| 电视端播放 | Media3 / ExoPlayer | HTTP 数据源注入 Token 头；ClippingMediaSource + ForwardingPlayer 实现转码流进度补偿 |
| 电视端网络 | Ktor Client (CIO) | 10 秒 API 超时、统一信封解析、401 自动清理失效主机 |
| 电视端图片 | Glide | 缩略图加载（自定义 Header 加载器） |

**协议要点**：Token 请求头鉴权（`X-BoxTV-Token`）；`videoId = Base64URL(folderId|相对路径)` 无状态定位；所有中文路径 UTF-8 URL 编码。完整 API 规范见 [docs/boxTV移动端对接技术文档.md](docs/boxTV移动端对接技术文档.md)——该文档面向后续移动端（手机 App 等）二次开发，可零上下文对接。

## 项目结构

```
boxTV/
├── app/                 # 电视端 Android 应用（Kotlin, minSdk 29, targetSdk 36）
│   └── src/main/java/com/example/boxtv/
│       ├── data/        # remote(API客户端) / repository / discovery(UDP) / local(持久化) / update
│       ├── model/       # 数据模型
│       └── ui/          # pairing(配对) / browser(浏览) / playback(播放) / main / common
├── boxTV-host/          # 电脑端服务（Kotlin JVM + Compose Desktop）
│   └── src/main/kotlin/com/example/boxtv/host/
│       ├── server/      # HostServer(全部API) / Transcoder / M3u8Aggregator / ThumbGenerator / PathGuard
│       ├── core/        # 配置持久化 / 配对会话 / 设备注册表 / 托盘 / 开机自启 / 防火墙
│       └── discovery/   # UDP 发现应答
├── docs/                # 协议文档（移动端对接技术文档、开发细则）
├── tools/               # ffmpeg/ffprobe（gitignore，见下方说明）
└── deliverables/        # 已构建的交付物（APK 等）
```

## 使用方法

### 1. 安装电脑端
- 运行 `deliverables/boxTVHost-1.0.0.msi`（或从源码构建，见下文），启动后程序驻留系统托盘；
- 在设置中添加**授权文件夹**（即允许电视端访问的本地视频目录）、修改主机名与端口（默认 8964）；
- 建议在 Windows 防火墙放行该程序（应用内提供一键放行）。

### 2. 安装电视端
- 将 `deliverables/boxTV-1.3.8.apk` 安装到 Android TV / 盒子（U 盘侧载或 `adb install`）；
- 要求 Android 10（API 29）及以上。

### 3. 配对连接
1. 电视端启动 → 「添加主机」→ 自动搜索局域网内的电脑（也可手动输入 IP:端口）；
2. 选中主机发起配对 → **电脑端弹出通知，点击「允许配对」**（300 秒内有效，无需输入任何配对码）；
3. 配对成功，电视端自动保存主机与 Token，之后每次使用自动连接。

### 4. 播放
- 主页进入主机 → 选择授权文件夹 → 遥控器浏览（文件夹在前、视频在后）/ 搜索 → 点击即播；
- 播放中按遥控器「菜单」键呼出面板：播放信息、清晰度切换（1080P 原画 / 720P / 480P 转码）、选集；
- 默认清晰度与音量增益自动记忆；退出播放自动保存进度，下次 5%~95% 区间自动续播。

## 构建与开发

### 环境要求
- JDK 17+、Android SDK 36（电视端）；电脑端为纯 Kotlin JVM 项目
- Windows 10/11（电脑端使用了托盘、防火墙等 Windows 特性）

### 构建电脑端
```powershell
./gradlew :boxTV-host:packageDistributionForCurrentOS   # 生成 MSI 安装包
./gradlew :boxTV-host:run                               # 开发模式直接运行
```

### 构建电视端
```powershell
./gradlew :app:assembleRelease
```

### 获取 ffmpeg（转码/缩略图/元数据功能依赖）
仓库不含 ffmpeg 二进制（超出 GitHub 单文件 100MB 限制）。电脑端按以下顺序自动定位，任选其一即可，缺失时相关功能自动降级（原画直连播放不受影响）：
1. 将 `ffmpeg.exe` 与 `ffprobe.exe` 放到电脑端**工作目录的 `tools/` 子目录**（即项目根 `tools/`，开发模式）；
2. `winget install Gyan.FFmpeg`（winget 链接目录自动识别）；
3. 将 ffmpeg 加入系统 `PATH`。

## 适用场景

- **家庭影音中心**：电脑硬盘里积累的电影/剧集/纪录片，电视上遥控器直接看；
- **多房间多设备**：同一台电脑可配对多台电视/盒子，移动端可按 `docs/` 协议文档自行开发手机 App 复用同一服务；
- **大码率 4K 片源**：电视硬解不动时由电脑端实时转 1080P，弱网环境降到 720P/480P 省流量；
- **离线内网环境**：无公网、无云服务依赖，适合宿舍、办公室、隔离网络等场景；
- **技术学习参考**：完整演示了局域网设备发现（UDP 广播）、人工授权配对、Token 鉴权、HTTP Range 流式传输、ffmpeg 管道推流、HLS 播放列表改写等工程实践。

## 安全说明

- 服务面向**可信局域网**设计，明文 HTTP 传输，请勿将端口暴露到公网；
- 未配对设备无法访问任何资源（仅 ping 与配对接口开放），授权范围限定在用户显式添加的文件夹内，路径穿越攻击被服务端拦截；
- 电脑端可随时撤销任意设备授权，撤销后该设备 Token 立即失效。

## 相关文档

- [移动端对接技术文档](docs/boxTV移动端对接技术文档.md) — 完整 REST API 规范、鉴权/配对/视频流协议，面向手机端二次开发
