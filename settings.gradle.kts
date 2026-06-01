pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            name = "MinecraftForge"
            url = uri("https://maven.minecraftforge.net/")
        }
        maven {
            name = "FancyGradle"
            url = uri("https://maven.gofancy.wtf/releases")
        }
        maven { url = uri("https://plugins.gradle.org/m2/") }
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
        }
    }
}

rootProject.name = "AdvancedRocketry"

if(file("libVulpes").exists()) {
    includeBuild("libVulpes") {
        dependencySubstitution {
            substitute(module("zmaster587.libVulpes:LibVulpes")).using(project(":"))
        }
    }
}