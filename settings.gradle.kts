pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack is kept as a repository only so the documented fallback
        // (com.github.opencv:opencv:4.9.0) can be enabled by uncommenting
        // one line in app/build.gradle.kts. Nothing resolves from it today.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "8ball-pool-guideline-tool"
include(":app")
