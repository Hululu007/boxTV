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

// ---- 打包公共参数（MSI 安装器与卸载入口共用，保持单一来源）----
val msiPackageName = "boxTVHost"
val msiVersion = "1.0.0"
val msiVendor = "boxTV"
val msiDescription = "boxTV 电脑端授权后台（局域网视频共享服务）"
val msiCopyright = "© 2026 boxTV"
// 固定升级码：后续版本安装时覆盖升级而非并存
val msiUpgradeUuid = "b7e3f2a1-4c5d-4e6f-8a9b-0c1d2e3f4a5b"

// Compose Desktop 应用入口配置
compose.desktop.application {
    mainClass = "com.example.boxtv.host.MainKt"

    // ---- M5 桌面化交付：打包配置（细则 6.1 打包 / 里程碑 M5）----
    nativeDistributions {
        // 产出 MSI 安装包（细则 6.1：packageDistributionForCurrentOS）
        targetFormats(TargetFormat.Msi)
        packageName = msiPackageName
        packageVersion = msiVersion
        vendor = msiVendor
        description = msiDescription
        copyright = msiCopyright
        // JVM 模块：Ktor Netty 依赖 java.naming/unsafe 等，直接全量包含避免缺模块
        includeAllModules = true

        // 捆绑 ffmpeg 工具链（细则 4.4）：resources/windows/tools/* 会随安装包
        // 落到 <安装目录>\app\resources\tools\，运行时经
        // System.getProperty("compose.application.resources.dir") 定位
        appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

        windows {
            menu = true            // 开始菜单项
            shortcut = true        // 桌面快捷方式
            dirChooser = true      // 安装时允许选择目录
            perUserInstall = true  // 按用户安装（无需管理员权限）
            upgradeUuid = msiUpgradeUuid
        }
    }
}

// ---- MSI 卸载程序支持（2026-11-07）----
// 需求：安装完 boxTVHost 后随时可卸载。MSI 本身已注册系统标准卸载入口
// （Windows「设置→应用→已安装的应用」），本节额外注入「开始菜单卸载快捷方式」。
//
// 原理：jpackage 用 WiX 生成 MSI，其内置 main.wxs 模板允许被 --resource-dir
// 中的同名文件覆盖。packaging/msi/main.wxs 基于 jpackage(JDK21) 原装模板修改，
// 新增「Uninstall boxTVHost」快捷方式组件（msiexec /x [ProductCode]，
// ProductCode 为安装期标准属性，无需硬编码）。
//
// 为什么不走 compose 自带的 packageMsi：该任务把 --resource-dir 固定指向
// build/compose/tmp/resources（任务 LocalState，执行前被清空），无法注入，
// 故基于 createDistributable 产出的应用镜像自建 jpackage 打包任务，
// 安装器参数与上方 windows{} 配置保持一致（menu/shortcut/dirChooser/perUser）。
val jpackageExe = file(System.getProperty("java.home")).resolve("bin/jpackage.exe")

tasks.register<Exec>("packageMsiWithUninstaller") {
    group = "build"
    description = "打包带「开始菜单卸载快捷方式」的 MSI（jpackage --app-image + 自定义 main.wxs）"
    dependsOn("createDistributable")

    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/$msiPackageName")
    val msiOutDir = layout.buildDirectory.dir("compose/binaries/main/msi")
    val resDir = layout.projectDirectory.dir("packaging/msi")
    // WiX 工具集目录（配置期解析为 File，避免 doFirst 闭包捕获 Project，
    // 兼容 Gradle 配置缓存）
    val wixDir = layout.buildDirectory.dir("wix311").get().asFile

    doFirst {
        // WiX 工具集：compose 插件会把 WiX 3.11 解压到 build/wix311 并加入其
        // jpackage 子进程的 PATH；本任务独立调用 jpackage，需自行补充 PATH
        check(wixDir.resolve("light.exe").isFile) {
            "缺少 WiX 工具集：$wixDir 下没有 light.exe。" +
            "请先执行一次 :boxTV-host:packageDistributionForCurrentOS 让 compose 插件自动下载 WiX 3.11"
        }
        environment("PATH", "$wixDir;" + System.getenv("PATH"))
    }

    commandLine(
        jpackageExe.absolutePath,
        "--type", "msi",
        "--app-image", appImage.get().asFile.absolutePath,
        "--dest", msiOutDir.get().asFile.absolutePath,
        "--name", msiPackageName,
        "--app-version", msiVersion,
        "--vendor", msiVendor,
        "--description", msiDescription,
        "--copyright", msiCopyright,
        "--win-menu", "--win-shortcut", "--win-dir-chooser", "--win-per-user-install",
        "--win-upgrade-uuid", msiUpgradeUuid,
        "--resource-dir", resDir.asFile.absolutePath
    )
}

// ---- 打包产物交付（2026-11-07：MSI 统一归档到根仓库 deliverables/）----
// 本模块是 included build，rootProject 指向 boxTV-host 自身，
// 故用 projectDirectory 的相对路径定位主构建根目录下的 deliverables/
//
// 注意：交付统一走 deliverMsi（依赖 packageMsiWithUninstaller，先打包后复制）。
// 不再把 deliverMsi 挂到 packageDistributionForCurrentOS 的 finalizedBy 上：
// compose 的 packageMsi 会清理应用镜像且无法注入卸载入口，二者同图执行
// 会出现镜像被删/产物被覆盖的竞态。
tasks.register<Copy>("deliverMsi") {
    group = "build"
    description = "打包带卸载入口的 MSI 并复制到根目录 deliverables/ 统一交付"
    dependsOn("packageMsiWithUninstaller")
    from(layout.buildDirectory.dir("compose/binaries/main/msi"))
    include("*.msi")
    into(layout.projectDirectory.dir("../deliverables"))
}
