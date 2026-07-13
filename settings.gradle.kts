pluginManagement {
    val jdeskSource: String by settings
    includeBuild(jdeskSource)
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jdesk-editor"

val jdeskSource: String by settings
includeBuild(jdeskSource)

include(":evidence-core")
include(":editor-api")
include(":editor-core")
include(":e2e:gate-app")
