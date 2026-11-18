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
rootProject.name = "Cue"
include(
    ":domain",
    ":brain:api",
    ":brain:rules",
    ":brain:ondevice",
    ":brain:cloud",
    ":agent",
    ":memory",
    ":triggers",
    ":ui",
    ":call",
    ":ingest",
    ":surfaces",
    ":genui",
    ":app",
)
