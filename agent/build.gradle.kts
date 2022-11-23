import java.security.MessageDigest

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":util"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
}

tasks {
    processResources {
        expand("version" to version)
    }

    jar {
        archiveBaseName.set("Solar-Engine")

        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        manifest {
            attributes(
                "Premain-Class" to "com.solartweaks.engine.AgentMainKt",
                "Main-Class" to "com.solartweaks.engine.Main"
            )
        }
    }

    register("updaterConfig") {
        dependsOn("jar")
        doLast {
            val engineFile = jar.get().outputs.files.singleFile
            val hash = MessageDigest.getInstance("SHA-1")
            val sha1 = hash.digest(engineFile.readBytes()).joinToString("") { "%02x".format(it) }

            File(buildDir, "updater.json").writeText(
                """
                {
                    "version": "$version",
                    "filename": "${engineFile.name}",
                    "sha1": "$sha1"
                }
                """.trimIndent()
            )
        }
    }

    register<JavaExec>("generateConfigurations") {
        dependsOn("jar")
        classpath(jar.get().outputs.files.singleFile.absolutePath)
        workingDir = rootDir
        mainClass.set("com.solartweaks.engine.GenerateConfigurations")
    }
}