import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// boxTV-host：电脑端（授权后台）模块（独立复合构建）
// 技术栈：Kotlin JVM + Compose Desktop（UI）+ Ktor（内嵌 HTTP 服务）
// 注：作为 included build 与 Android 主构建隔离，插件版本在此独立声明
plugins {
    kotlin("jvm") version "2.4.21"                        // Kotlin/JVM 编译
    kotlin("plugin.compose") version "2.4.21"             // Compose 编译器插件（KGP 2.x 起必须）
    kotlin("plugin.serialization") version "2.4.21"       // kotlinx.serialization（config.json 序列化）
    id("org.jetbrains.compose") version "1.12.2"          // Compose Desktop 桌面 UI
}

kotlin {
    // 与 Gradle 守护进程一致的 JDK 21 工具链，避免额外下载
    jvmToolchain(21)
}

dependencies {
    // ---- Compose Desktop（桌面 UI）----
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // ---- Ktor 内嵌 HTTP 服务器（Netty 引擎，3.6.1）----
    implementation("io.ktor:ktor-server-netty:3.6.1")                  // Netty 引擎，承载 8964 端口
    implementation("io.ktor:ktor-server-content-negotiation:3.6.1")    // JSON 内容协商
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")    // kotlinx.serialization JSON 编解码
    implementation("io.ktor:ktor-server-partial-content:3.6.1")        // HTTP Range 分段传输（视频拖动进度条必需）

    // ---- 数据序列化（config.json 持久化）----
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    // ---- 单元测试（2026-11-07 M3U8 播放支持：识别/改写/防越界回归）----
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 测试框架：JUnit 5 平台
tasks.test {
    useJUnitPlatform()
}

// ---- 便携版打包公共参数（绿色免安装版，单一来源）----
// 2026-11-09 改造：移除 MSI 安装包方案，改为免安装绿色便携版。
// 产物 = jpackage 应用镜像（自带 JRE）打成的 zip，解压后直接运行 boxTVHost.exe。
// 升级只需整目录替换文件，无安装/卸载/注册表操作。
val portablePackageName = "boxTVHost"
// 便携包版本号：与 HostServer.VERSION 保持一致（升级时两处同步修改）
val appVersion = "1.2.3"
val appVendor = "boxTV"
val appDescription = "boxTV 电脑端授权后台（局域网视频共享服务）"
val appCopyright = "© 2026 boxTV"

// Compose Desktop 应用入口配置
compose.desktop.application {
    mainClass = "com.example.boxtv.host.MainKt"

    // ---- M5 桌面化交付：打包配置（细则 6.1 打包 / 里程碑 M5）----
    nativeDistributions {
        // 便携版交付形态：createDistributable 产出的 jpackage 应用镜像
        // （自带 JRE + 全部依赖 + boxTVHost.exe 启动器），由下方 packagePortable 打成 zip
        targetFormats(TargetFormat.AppImage)
        packageName = portablePackageName
        packageVersion = appVersion
        vendor = appVendor
        description = appDescription
        copyright = appCopyright
        // JVM 模块：Ktor Netty 依赖 java.naming/unsafe 等，直接全量包含避免缺模块
        includeAllModules = true

        // 捆绑 ffmpeg 工具链（细则 4.4）：resources/windows/tools/* 会随便携包
        // 落到 <解压目录>\app\resources\tools\，运行时经
        // System.getProperty("compose.application.resources.dir") 定位
        appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
    }
}

// ---- 便携版（绿色免安装）打包任务（2026-11-09）----
// 需求：解压即用，取消「改代码→重打 MSI→重装」流程。
//
// 原理：createDistributable 产出 jpackage 应用镜像（目录结构）：
//   boxTVHost/
//     ├── boxTVHost.exe        # 启动器（AutoStartManager 据此识别打包模式）
//     ├── app/                 # 应用 jar + 捆绑的 ffmpeg（resources/tools）
//     └── runtime/             # 自带精简 JRE，用户机器无需安装 Java
// 将整个镜像目录打成 zip，解压后双击 boxTVHost.exe 即可运行。
//
// 功能保留情况（与原 MSI 安装版完全一致）：
// - 配置/配对/缩略图缓存：均在 %APPDATA%\boxTV\，与程序目录无关；
// - 开机自启：AutoStartManager 写 HKCU Run 键，指向 exe 自身路径（便携版可用）；
// - 防火墙引导/托盘/UDP 发现/HTTP 服务：不依赖安装方式。
val appImageDir = layout.buildDirectory.dir("compose/binaries/main/app/$portablePackageName")

tasks.register<Zip>("packagePortable") {
    group = "build"
    description = "打包免安装绿色便携版（jpackage 应用镜像 → zip，解压即用）"
    dependsOn("createDistributable")

    // 解压后得到 boxTVHost/ 文件夹，避免文件散落当前目录
    from(appImageDir)
    into(portablePackageName)
    archiveFileName.set("$portablePackageName-$appVersion-portable-win64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/portable"))

    // 产出前清理旧 zip，避免残留旧版本文件
    doFirst {
        archiveFile.get().asFile.delete()
    }
}

// ---- 打包产物交付（便携 zip 统一归档到根仓库 deliverables/）----
// 本模块是 included build，rootProject 指向 boxTV-host 自身，
// 故用 projectDirectory 的相对路径定位主构建根目录下的 deliverables/
tasks.register<Copy>("deliverPortable") {
    group = "build"
    description = "打包免安装绿色便携版并复制到根目录 deliverables/ 统一交付"
    dependsOn("packagePortable")
    from(layout.buildDirectory.dir("compose/binaries/main/portable"))
    include("*.zip")
    into(layout.projectDirectory.dir("../deliverables"))
}
