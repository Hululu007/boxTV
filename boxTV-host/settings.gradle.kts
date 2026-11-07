// boxTV-host 独立构建配置（复合构建：由根工程 includeBuild 引入）
// 电脑端是纯 JVM 项目，不依赖 Android 构建体系，因此拥有独立的插件与仓库配置
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()      // Compose Desktop 依赖 androidx.* 构件（lifecycle/savedstate 等），需要 Google Maven
        mavenCentral()
    }
}

rootProject.name = "boxTV-host"
