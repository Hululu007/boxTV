import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)   // Kotlin Android 插件（AGP 8 + 外部 KGP 标准组合）
    alias(libs.plugins.ksp)              // KSP：Room 注解处理
    alias(libs.plugins.serialization)    // kotlinx.serialization 编译器插件
}

// 正式签名凭据从 local.properties 读取（M5 交付：凭据不入库）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.boxtv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.boxtv"
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "1.3.8" // 2026-11-08 播放控制栏新增「菜单」按钮（设置左侧，OK 键呼出侧边菜单面板，等效遥控器菜单键）

    }

    signingConfigs {
        create("release") {
            // 签名配置缺失时保持空配置（local.properties 未配置则无法出正式包）
            val storeFile = localProps.getProperty("boxtv.release.storeFile")
            if (storeFile != null) {
                this.storeFile = file(storeFile)
                storePassword = localProps.getProperty("boxtv.release.storePassword")
                keyAlias = localProps.getProperty("boxtv.release.keyAlias")
                keyPassword = localProps.getProperty("boxtv.release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // M5 交付：正式签名 APK（供电视端侧载安装）
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Kotlin JVM 目标与 Java 编译版本对齐（避免 JVM-target 校验失败）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.glide)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)

    // ---- Room 数据库（细则 5.3：paired_host + playback_progress 表）----
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ---- Ktor Client（细则 5.2：data.remote）----
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ---- JSON 序列化（API 响应解析）----
    implementation(libs.kotlinx.serialization.json)

    // ---- Media3 / ExoPlayer（细则 5.4：HTTP 流式播放 + 控制条）----
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // HLS 播放支持（2026-11-07 M3U8 播放支持：原生 HLS demuxer，电视端零解析负载）
    implementation(libs.media3.exoplayer.hls)

    // ---- 协程（异步网络/发现）----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// ---- 打包产物交付（2026-11-07：APK 统一归档到根目录 deliverables/）----
// assembleDebug / assembleRelease 完成后自动执行，debug 与 release 的
// APK 文件名不同（app-debug.apk / app-release.apk），拍平子目录直接并列
val deliverablesDir = rootProject.layout.projectDirectory.dir("deliverables")
tasks.register<Copy>("deliverApk") {
    group = "build"
    description = "把打包出的电视端 APK 复制到根目录 deliverables/ 统一交付"
    from(layout.buildDirectory.dir("outputs/apk")) {
        include("**/*.apk")
    }
    into(deliverablesDir)
    eachFile { path = name }   // 拍平 debug/release 子目录
    includeEmptyDirs = false
}
tasks.matching { it.name in setOf("assembleDebug", "assembleRelease") }.configureEach {
    finalizedBy("deliverApk")
}