@file:JvmName("Main")

package com.solartweaks.engine

fun main() {
    println("Welcome to Solar Engine v$version!")
    println("Starting Lunar Client...")
    println()
    Thread.sleep(5000)
    println(
        """
        Ok, who am I kidding, this file onto itself does nothing.
        In order to use Solar Engine, download and install Solar Tweaks (https://github.com/Solar-Tweaks/Solar-Tweaks)
        Alternatively, add this agent to the Lunar Client launch arguments (this agent does not support runtime attaching)

        (If you see this, tell us on Discord)
        """.trimIndent()
    )
}