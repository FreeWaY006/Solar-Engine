package com.solartweaks.engine.util

import java.lang.instrument.Instrumentation

/**
 * Attempts to find the application/Minecraft [ClassLoader],
 * based on the currently loaded classes from [Instrumentation]
 */
fun Instrumentation.findMainLoader(): ClassLoader {
    // First, exclude some classes from all loaded classes
    val appClasses = getAppClasses()

    // Check if there are any net.minecraft instances
    val mcClass = appClasses.firstOrNull { it.name.startsWith("net.minecraft.") }
    if (mcClass != null) return mcClass.classLoader

    // Didn't find minecraft related classes, resorting to finding the most common one
    val allLoaders = appClasses.map { it.classLoader }

    // For the non-kotlin users amongst us, this essentially counts how often
    // a specific loader exists in the class, and gets the entry with the most
    // occurrences.
    return allLoaders.groupingBy { it }.eachCount()
        .maxByOrNull { (_, count) -> count }?.key
        ?: ClassLoader.getSystemClassLoader() // default is system loader
}