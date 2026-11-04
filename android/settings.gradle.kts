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
    }
}

rootProject.name = "Conjunction"

// :core = pure Kotlin/JVM engines (astronomy, Chinese zodiac, compatibility, prompts) + tests.
include(":core")
// The Compose UI module is added by the app work; it depends on project(":core").
if (file("app/build.gradle.kts").exists()) {
    include(":app")
}
