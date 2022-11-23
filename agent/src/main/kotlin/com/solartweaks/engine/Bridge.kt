package com.solartweaks.engine

import com.solartweaks.engine.tweaks.withModule
import com.solartweaks.engine.util.*
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type
import java.util.UUID

fun initBridge() = Unit

//

val clientBridge = finders.findBridge {
    methods {
        bridgeMethod("getPlayer")
        bridgeMethod("hasInGameFocus")
        bridgeMethod("isWindowFocused")
        bridgeMethod("getCurrentServerData")
        bridgeMethod("getCurrentScreen")
        bridgeMethod("getWorld")
    }
}

val clientBridgeAccess by accessor<_, ClientBridge.Static>(clientBridge)

interface ClientBridge : InstanceAccessor {
    val player: PlayerBridge
    val world: WorldBridge?
    val currentServerData: ServerData?
    val currentScreen: Any?
    val isWindowFocused: Boolean
    fun hasInGameFocus(): Boolean

    interface Static : StaticAccessor<ClientBridge>
    companion object : Static by clientBridgeAccess.static
}

//

val playerBridge = finders.findBridge {
    methods {
        bridgeMethod("getGameProfile")
        bridgeMethod("getName")
        bridgeMethod("addChatMessage")
    }
}

val playerGetName by playerBridge.methods["getName"]
val playerBridgeAccess by accessor<_, PlayerBridge.Static>(playerBridge)

interface PlayerBridge : InstanceAccessor {
    val gameProfile: GameProfile
    val name: String
    fun addChatMessage(msg: Any)

    interface Static : StaticAccessor<PlayerBridge>
    companion object : Static by playerBridgeAccess.static
}

fun MethodVisitor.getPlayerName() {
    cast(playerBridge().name)
    invokeMethod(playerGetName())
}

//

val gameProfile = finders.findClass {
    node named "com/mojang/authlib/GameProfile"
    methods {
        named("getId")
        named("getName")
    }
}

val gameProfileAccess by accessor<_, GameProfile.Static>(gameProfile)

interface GameProfile : InstanceAccessor {
    val id: UUID?
    val name: String

    interface Static : StaticAccessor<GameProfile>
    companion object : Static by gameProfileAccess.static
}

//

val serverData = finders.findBridge {
    methods {
        bridgeMethod("serverIP")
        bridgeMethod("getServerName")
    }
}

val serverDataAccess by accessor<_, ServerData.Static>(serverData)

interface ServerData : InstanceAccessor {
    fun serverIP(): String
    val serverName: String

    interface Static : StaticAccessor<ServerData>
    companion object : Static by serverDataAccess.static
}

//

val rendererBridge = finders.findBridge {
    methods {
        bridgeMethod("color") {
            arguments count 4
            arguments[0] = Type.FLOAT_TYPE
        }

        bridgeMethod("disableRescaleNormal")
        bridgeMethod("enableCull")
        bridgeMethod("disableCull")
    }
}

val glStateAccess by accessor<_, GlStateBridge.Static>(rendererBridge)

interface GlStateBridge : InstanceAccessor {
    fun color(r: Float, g: Float, b: Float, a: Float)
    fun disableRescaleNormal()
    fun enableCull()
    fun disableCull()

    interface Static : StaticAccessor<GlStateBridge>
    companion object : Static by glStateAccess.static
}

//

val renderPlayer = finders.findClass {
    methods {
        named("bridge\$getMainModel")
        "constructor" {
            method.isConstructor()
            arguments[1] = Type.BOOLEAN_TYPE
            transform {
                val addMethod = method.calls.first { it.name != "<init>" }
                methodExit {
                    loadThis()
                    construct(
                        className = optifineClassName("PlayerItemsLayer", "player"),
                        descriptor = "(L${owner.name};)V"
                    ) { loadThis() }

                    invokeMethod(
                        InvocationType.VIRTUAL,
                        owner.name,
                        addMethod.name,
                        addMethod.desc
                    )
                }
            }
        }
    }
}

val playerGetMainModel by renderPlayer.methods["bridge\$getMainModel"]

//

val worldBridge = finders.findBridge {
    methods {
        bridgeMethod("getPlayerEntities")
    }
}

val worldBridgeAccess by accessor<_, WorldBridge.Static>(worldBridge)

interface WorldBridge {
    val playerEntities: List<Any>

    interface Static : StaticAccessor<WorldBridge>
    companion object : Static by worldBridgeAccess.static
}

val WorldBridge.actualPlayerEntities get() = playerEntities.map(PlayerBridge::cast)

//

val localPlayer = finders.findBridge {
    methods {
        bridgeMethod("sendChatMessage")
        bridgeMethod("isRidingHorse")
    }
}

val localPlayerAccess by accessor<_, LocalPlayer.Static>(localPlayer)

interface LocalPlayer : InstanceAccessor {
    fun sendChatMessage(message: String)

    interface Static : StaticAccessor<LocalPlayer>
    companion object : Static by localPlayerAccess.static
}

//

val bridgeManager = finders.findClass {
    fields {
        "clientInstance" {
            node match { (owner, field) ->
                // == true because nullable boolean
                owner.methods.find { it.hasConstant("Can't reset Minecraft Client instance!") }
                    ?.referencesNamed(field.name) == true
            }
        }
    }

    methods {
        named("getMinecraftVersion") { method.isStatic() }

        withModule<CapeSystem> {
            "getOptifineURL" {
                method.isStatic()
                method returns asmTypeOf<String>()
                transform { fixedValue(serverURL) }
            }
        }

        "getGlStateManager" { matchLazy { method returns rendererBridge() } }
    }
}

//

val bridgeManagerAccess by accessor<_, BridgeManager.Static>(bridgeManager)

interface BridgeManager : InstanceAccessor {
    interface Static : StaticAccessor<BridgeManager> {
        val clientInstance: ClientBridge
        val minecraftVersion: LunarVersion
        val glStateManager: GlStateBridge
    }

    companion object : Static by bridgeManagerAccess.static
}

val client by lazy { BridgeManager.clientInstance }
val minecraftVersion by lazy { BridgeManager.minecraftVersion }
val glStateManager by lazy { BridgeManager.glStateManager }

fun MethodsContext.bridgeMethod(name: String, block: MethodContext.() -> Unit = {}) = name {
    method named "bridge\$$name"
    method match { it.owner.isInterface }
    block()
}

fun FinderContext.findBridge(block: ClassContext.() -> Unit) = findClass {
    node.isInterface()
    block()
}