pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-mcp-sdk"

include(":mcp-core")
include(":mcp-intent-api")
include(":llm-intentions")
include(":taichi-android")
include(":tool-device")
include(":tool-people")
include(":tool-files")
include(":tool-files-dev")
include(":tool-notify")
