package com.solartweaks.engine

object VersionDummy

val version by lazy {
    VersionDummy::class.java.classLoader.getResourceAsStream("version.txt")?.readBytes()?.decodeToString() ?: "unknown"
}

val lunarVersion = finders.findClass {
    strings has "https://launchermeta.mojang.com/mc/game/version_manifest.json"
    fields { "id" { node named "id" } }
}

val versionAccess by accessor<_, LunarVersion.Static>(lunarVersion)

interface LunarVersion : InstanceAccessor {
    val id: String

    interface Static : StaticAccessor<LunarVersion>
    companion object : Static by versionAccess.static
}

val LunarVersion.formatted get() = id.drop(1).replace('_', '.')