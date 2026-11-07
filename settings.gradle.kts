pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云 Google Maven 镜像：dl.google.com 在国内网络时常超时，镜像优先补齐缺失构件
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

rootProject.name = "boxTV"
include(":app")
// boxTV-host 为独立复合构建（自带 settings.gradle.kts）：
// 电脑端是纯 JVM + Compose Desktop 项目，插件版本与 Android 主构建独立声明，互不干扰
includeBuild("boxTV-host")
 