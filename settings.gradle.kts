enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("core/gradle/libs.versions.toml"))
        }
        create("lspatch") {
            from(files("gradle/lspatch.versions.toml"))
        }
    }
}

rootProject.name = "YTP"

val coreModules = listOf(
    "core",
    "xposed",
    "external:apache",
    "external:axml",
    "services:daemon-service",
    "hiddenapi:bridge",
    "hiddenapi:stubs",
)

include(
    ":manager",
    ":meta-loader",
    ":patch",
    ":patch-loader",
    ":share:android",
    ":share:java",
)
include(*coreModules.map { ":$it" }.toTypedArray())

coreModules
    .flatMap { module ->
        val segments = module.split(":")
        segments.indices.map { index ->
            segments.take(index + 1).joinToString(":", prefix = ":")
        }
    }
    .distinct()
    .forEach { modulePath ->
        project(modulePath).projectDir = file("core/${modulePath.removePrefix(":").replace(':', '/')}")
    }
