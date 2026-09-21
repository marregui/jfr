rootProject.name = "jfrq"

include("core", "cli", "live", "netty-demo")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
