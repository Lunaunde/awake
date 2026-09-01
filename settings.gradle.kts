pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // 国内镜像加速；Maven Central 兜底，保证镜像缺失的构件仍可从官方仓库获取。
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
    }
}

rootProject.name = "awake"
include(":app")