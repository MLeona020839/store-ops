rootProject.name = "store-ops"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        mavenCentral()
    }
}

include(
    "events",
    "promotion-service",
    "store-task-service",
)
