package com.solartweaks.engine

import com.solartweaks.engine.tweaks.*
import com.solartweaks.engine.util.FinderContext
import com.solartweaks.engine.util.findMainLoader
import java.io.File
import java.lang.instrument.Instrumentation

lateinit var globalInstrumentation: Instrumentation
lateinit var globalConfiguration: Configuration

val finders = FinderContext(debug = true)
val mainLoader by lazy {
    globalInstrumentation.findMainLoader().also {
        if (it == ClassLoader.getSystemClassLoader()) error("Invalid main Lunar Loader $it")
    }
}

fun premain(arg: String?, inst: Instrumentation) {
    println("Solar Engine version $version")

    val configFile = File(arg ?: "config.json")
    println("Using config file $configFile")

    globalConfiguration = loadConfig(configFile)
    println("Configuration: $globalConfiguration")

    globalInstrumentation = inst

    finders.registerWith(inst)
    inst.installAntiClassLoader()

    initTweaks()
    initInternalTweaks()
    initCapeSystemTweaks()
    initCustomCommands()
    initBridge()
    initRichPresence()
}