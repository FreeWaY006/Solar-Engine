plugins {
    kotlin("jvm") version "1.7.20" apply false
    kotlin("plugin.serialization") version "1.7.20" apply false
}

allprojects {
    group = "com.solartweaks"
    version = "2.0"

    repositories {
        mavenCentral()
    }
}

tasks {
    register("release") {
        dependsOn(":agent:updaterConfig", ":agent:generateConfigurations")
    }
}