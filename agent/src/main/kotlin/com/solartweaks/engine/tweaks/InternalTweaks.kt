package com.solartweaks.engine.tweaks

import com.solartweaks.engine.*
import com.solartweaks.engine.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.objectweb.asm.Opcodes.ARETURN
import java.net.HttpURLConnection
import java.net.URL

fun initInternalTweaks() {
    finders.findClass {
        node named "net/minecraft/client/ClientBrandRetriever"
        methods {
            namedTransform("getClientModName") {
                overwrite {
                    invokeMethod(::modName)
                    returnMethod(ARETURN)
                }
            }
        }
    }

    findLunarClass {
        node implements "com/lunarclient/bukkitapi/nethandler/client/LCNetHandlerClient"
        methods {
            namedTransform("handleNotification") {
                overwrite {
                    load<Any>(1)
                    invokeMethod(::handleNotification)
                    returnMethod()
                }
            }

            "handle" {
                strings hasPartial "Exception registered"
                transform {
                    callAdvice(
                        matcher = {
                            it.name == "handle"
                        },
                        afterCall = {
                            load<Any>(2)
                            invokeMethod(::handlePacket)
                        }
                    )
                }
            }
        }
    }

    finders.findClass {
        node named "net/minecraft/client/main/Main"
        methods {
            namedTransform("main") {
                methodEnter {
                    load<Array<String>>(0)
                    invokeMethod(::updateArguments)
                    store(0)
                }
            }
        }
    }
}

fun updateArguments(args: Array<String>) = if (isModuleEnabled<AllowCrackedAccounts>()) args + arrayOf(
    "--username",
    getModule<AllowCrackedAccounts>().crackedUsername
) else args

fun handlePacket(packet: Any?) {
    if (globalConfiguration.debugPackets && packet != null) {
        println("Incoming packet ${packet::class.java}")
        packet::class.java.declaredFields.joinToString(System.lineSeparator()) { f ->
            "${f.name}: ${f.also { it.isAccessible = true }[packet]}"
        }
    }
}

fun modName() = runCatching { "solartweaksv$version-lunar${minecraftVersion.id}" }.getOrElse { "solartweaksv$version" }

fun sendLaunch() {
    runCatching {
        val actualType = when (val type = System.getProperty("solar.launchType")) {
            null -> "patcher"
            "shortcut" -> "launcher"
            "launcher" -> return println("Detected usage of the Solar Tweaks launcher")
            else -> return println("Invalid launch type $type, ignoring...")
        }

        val version = minecraftVersion.formatted
        with(URL("https://server.solartweaks.com/api/launch").openConnection() as HttpURLConnection) {
            requestMethod = "POST"
            doOutput = true
            outputStream.bufferedWriter().write(json.encodeToString(LaunchRequest(actualType, version)))
        }
    }
        .onSuccess { println("Sent launch request") }
        .onFailure { println("Couldn't send launch request: $it") }
}

val lunarMain = findLunarClass {
    methods {
        "initLunar" {
            strings has "Starting Lunar client..."
            transform {
                methodExit { invokeMethod(::sendLaunch) }
            }
        }

        withModule<WindowTitle> {
            "getWindowTitle" {
                strings hasPartial "Lunar Client ("
                transform { fixedValue(title + if (showVersion) " (Solar Engine v$version)" else "") }
            }
        }

        withModule<LunarOverlays> {
            "allowOverrideTexture" {
                strings has "assets/lunar/"
                transform { fixedValue(true) }
            }
        }
    }
}

@Serializable
private data class LaunchRequest(val item: String, val version: String)

// I was too lazy to manually import bukkit nethandler thingy
// have some more codegen

val lcPacketNotification = finders.findClass {
    node named "com/lunarclient/bukkitapi/nethandler/client/LCPacketNotification"
    methods {
        named("getMessage")
        named("getLevel")
    }
}

val notifAccess by accessor<_, LCPacketNotification.Static>(lcPacketNotification)

interface LCPacketNotification : InstanceAccessor {
    val message: String
    val level: String

    interface Static : StaticAccessor<LCPacketNotification>
    companion object : Static by notifAccess.static
}

val popupHandler = finders.findClass {
    strings has "popups"
    strings has "sound/friend_message.ogg"

    methods {
        "displayPopup" {
            arguments count 2
            arguments[0] = asmTypeOf<String>()
            arguments[1] = asmTypeOf<String>()
        }
    }
}

val popupHandlerAccess by accessor<_, PopupHandler.Static>(popupHandler)

interface PopupHandler : InstanceAccessor {
    fun displayPopup(title: String, description: String): Any
    interface Static : StaticAccessor<PopupHandler>
    companion object : Static by popupHandlerAccess.static
}

val getHandlerMethod by lunarMain.methods.late { method returns popupHandler() }
val cachedPopupHandler by lazy { PopupHandler.cast(getHandlerMethod().tryInvoke()) }

fun handleNotification(notif: Any) = runCatching {
    val actualNotif = LCPacketNotification.cast(notif)
    cachedPopupHandler.displayPopup("Server Notification - ${actualNotif.level}", actualNotif.message)
}