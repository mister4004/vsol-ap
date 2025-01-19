pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        id("com.android.application") version "8.1.1"
        id("org.jetbrains.kotlin.android") version "2.1.0" // Понизить до совместимой версии
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RouterSetup"
include(":app")
