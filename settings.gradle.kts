pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}
rootProject.name = "tally"
include("platform", "ledger-core", "ledger", "monitor", "auth", "extbank", "proof")
