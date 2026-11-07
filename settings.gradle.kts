pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        val token = providers.environmentVariable("N2K02_PACKAGES_TOKEN").orNull
        require(!token.isNullOrBlank()) {
            "N2K02_PACKAGES_TOKEN required for dev.n2k02 shared libraries (configure CI secret)"
        }
        listOf("n2k02theme" to "theme", "n2k02updater" to "updater").forEach { (repository, name, "n2k02AdbCoreLibrary" to "adb-core") ->
            maven {
                url = uri("https://maven.pkg.github.com/N2K02/" + repository)
                credentials { username = "N2K02"; password = token }
                content { includeModule("dev.n2k02", name) }
            }
        }
    }
}
rootProject.name = "McpAdbAndroidLogger"
include(":app")
