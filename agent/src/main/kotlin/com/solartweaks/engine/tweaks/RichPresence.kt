package com.solartweaks.engine.tweaks

import com.solartweaks.engine.*
import java.time.OffsetDateTime

fun initRichPresence() = Unit

private val solarBoot = OffsetDateTime.now()

fun updateRichPresence() = with(RPCBuilder.construct()) {
    withModule<DiscordRichPresence> {
        setDetails("Playing Minecraft ${minecraftVersion.formatted}")
        setStartTimestamp(solarBoot)
        if (showIcon) setLargeImage(icon, iconText)

        val serverText = if (showServerIP) {
            client.currentServerData?.let { "Multiplayer on ${it.serverIP()}" }
        } else "Multiplayer"

        if (displayActivity) {
            setState(
                when {
                    !client.isWindowFocused -> afkText
                    client.currentScreen != null -> menuText
                    client.hasInGameFocus() -> serverText ?: singlePlayerText
                    else -> "Unknown status"
                }
            )
        }
    }

    build()
}

val rpcBuilder = finders.findClass {
    node named "com/jagrosh/discordipc/entities/RichPresence\$Builder"
    methods {
        named("setState")
        named("setDetails")
        named("setStartTimestamp")
        named("setLargeImage")
        named("build")
        "construct" { method.isConstructor() }
    }
}

val rpcAccess by accessor<_, RPCBuilder.Static>(rpcBuilder) {
    Class.forName(
        "com.jagrosh.discordipc.entities.RichPresence\$Builder",
        false,
        ClassLoader.getSystemClassLoader()
    )
}

interface RPCBuilder : InstanceAccessor {
    fun setState(state: String): Any
    fun setDetails(details: String): Any
    fun setStartTimestamp(offsetDateTime: OffsetDateTime): Any
    fun setLargeImage(key: String, text: String): Any
    fun build(): Any

    interface Static : StaticAccessor<RPCBuilder> {
        fun construct(): RPCBuilder
    }

    companion object : Static by rpcAccess.static
}