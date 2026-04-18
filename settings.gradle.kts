plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "ehz-backend"

include(":app", ":discovery-sidecar")
