package com.solartweaks.engine.tweaks

import com.solartweaks.engine.*
import com.solartweaks.engine.Module
import com.solartweaks.engine.util.*
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import java.net.URI

fun initTweaks() {
    withModule<ChangeModStrings> {
        findLunarClass {
            strings has "lastKnownHypixelNick"
            constantReplacement("You", nickhiderText)
        }

        findLunarClass {
            strings has "[1466 FPS]"
            constantReplacement("\u0001 FPS", "\u0001 $fpsText")
        }

        findLunarClass {
            strings has "[16 CPS]"
            constantReplacement(" CPS", " $cpsText")
        }

        findLunarClass {
            methods {
                "constructor" {
                    method.isConstructor()
                    arguments[0] = asmTypeOf<String>()
                }

                unnamedMethod {
                    strings hasPartial "CPS"
                    method calls { method named "bridge\$keybindJump" }
                    transform { replaceString("CPS", cpsText) }
                }
            }
        }

        findLunarClass {
            strings has "hypixel_mod"
            strings has "auto_gg"
            constantReplacements(
                "/achat gg" to autoGGCommand,
                "Level: " to "$levelHeadText: "
            )
        }

        findLunarClass {
            strings has "[1.3 blocks]"
            constantReplacement("\u0001 blocks", "\u0001 $reachText")
        }
    }

    withModule<Metadata> {
        findLunarClass {
            strings has "metadata_fallback.json"
            methods {
                "loadActions" {
                    strings has "blogPosts"
                    transform {
                        if (removeBlogPosts) replaceConstant("blogPosts", "xdd")
                        if (removeClientSettings) replaceConstant("clientSettings", "xdd")
                        if (removeModSettings) replaceConstant("modSettings", "xdd")
                        if (removeServerIntegration) replaceConstant("serverIntegration", "xdd")
                        if (removePinnedServers) replaceConstant("pinnedServers", "xdd")
                    }
                }
            }
        }
    }

    withModule<DiscordRichPresence> {
        initRichPresence()
        findLunarClass {
            strings has "Connected to Discord IPC"
            methods {
                "updateRPC" {
                    strings has "Lunar Client"
                    transform {
                        overwrite {
                            val ipcClient = "com/jagrosh/discordipc/IPCClient"
                            val field = owner.fieldData.first { it.field.desc == "L$ipcClient;" }

                            loadThis()
                            getField(field)
                            invokeMethod(
                                invocationType = InvocationType.VIRTUAL,
                                owner = ipcClient,
                                name = "getStatus",
                                descriptor = "()Lcom/jagrosh/discordipc/entities/pipe/PipeStatus;"
                            )

                            val pipeStatus = "com/jagrosh/discordipc/entities/pipe/PipeStatus"
                            visitFieldInsn(
                                GETSTATIC,
                                pipeStatus,
                                "CONNECTED",
                                "L$pipeStatus;"
                            )

                            val label = Label()
                            visitJumpInsn(IF_ACMPEQ, label)
                            returnMethod()
                            visitLabel(label)

                            loadThis()
                            getField(field)
                            invokeMethod(::updateRichPresence)

                            val rpcType = "com/jagrosh/discordipc/entities/RichPresence"
                            cast(rpcType)
                            invokeMethod(
                                invocationType = InvocationType.VIRTUAL,
                                name = "sendRichPresence",
                                owner = ipcClient,
                                descriptor = "(L$rpcType;)V"
                            )
                            returnMethod()
                        }
                    }
                }

                "init" {
                    val defaultID = 562286213059444737L
                    method.isConstructor()
                    transform { replaceConstant(defaultID, clientID.toLongOrNull() ?: defaultID) }
                }
            }
        }
    }

    withModule<Privacy> {
        val fixPrivacy = { path: String ->
            findLunarClass {
                methods {
                    "sendPacket" {
                        strings has path
                        transform { stubValue() }
                    }
                }
            }
        }

        fixPrivacy("\u0001\\system32\\tasklist.exe")
        fixPrivacy("\u0001\\system32\\drivers\\etc\\hosts")
    }

    withModule<RemoveFakeLevelHead> {
        findLunarClass {
            methods {
                "showLevelHead" {
                    strings has "Level: "
                    transform {
                        replaceCall(matcher = { it.name == "current" })
                        replaceCall(matcher = { it.name == "nextInt" }) {
                            pop()
                            loadConstant(-26)
                        }
                    }
                }
            }
        }
    }

    withModule<WebsocketURL> {
        findLunarClass {
            node extends "org/java_websocket/client/WebSocketClient"
            methods {
                "constructor" {
                    method.isConstructor()
                    strings has "Assets"
                    transform {
                        callAdvice(matcher = { it.isConstructor && it.owner == internalNameOf<URI>() }, beforeCall = {
                            pop()
                            loadConstant(url)
                        })
                    }
                }
            }
        }
    }

    withModule<ClothCapes> {
        findLunarClass {
            strings has "Refreshed render target textures."
            methods {
                "checkCloth" {
                    method returns Type.BOOLEAN_TYPE
                    strings has "LunarPlus"
                    transform { fixedValue(true) }
                }
            }
        }
    }

    withModule<HurtCamShake> {
        finders.findClass {
            isMinecraftClass()
            strings has "Failed to load shader: "
            constantReplacement(14.0f, 14.0f * multiplier)
        }
    }

    withModule<RemoveMousePopup> {
        findLunarClass {
            strings has "PollingRateDetectionThread"

            methods {
                namedTransform("start") { stubValue() }
            }
        }
    }

    withModule<RemoveProfilesCap> {
        findLunarClass {
            strings has "saveNewProfile"
            methods {
                "handleNewProfile" {
                    strings has "profile"
                    // I hope nobody is going to create 2.1B profiles and complain
                    transform { replaceConstant(8, Int.MAX_VALUE) }
                }
            }
        }
    }

    withModule<ToggleSprintText> {
        findLunarClass {
            node.isEnum()
            strings has "settings"
            strings has "flying"

            constantReplacements(
                "flying" to flyingText,
                "boost" to flyingBoostText,
                "riding" to ridingText,
                "descending" to descendingText,
                "dismounting" to dismountingText,
                "sneaking" to sneakingText,
                "toggled" to toggledText,
                "sprinting" to sprintingText
            )
        }
    }

    withModule<AllowCrackedAccounts> {
        findLunarClass {
            strings has "launcher_accounts.json"
            methods {
                "checkCracked" {
                    method calls { method named "canPlayOnline" }
                    transform { fixedValue(true) }
                }
            }
        }
    }
}

inline fun <reified T : Module> withModule(
    checkEnabled: Boolean = true,
    block: T.() -> Unit
) {
    val module = getModule<T>()
    if (module.isEnabled || !checkEnabled) block(module)
}

inline fun findLunarClass(crossinline block: ClassContext.() -> Unit) = finders.findClass {
    isLunarClass()
    block()
}

fun ClassContext.constantReplacement(from: Any, to: Any) = methods {
    unnamedMethod {
        constants has from
        transform { replaceConstant(from, to) }
    }
}

fun ClassContext.constantReplacements(map: Map<Any, Any>) = methods {
    map.forEach { (from, to) ->
        unnamedMethod {
            constants has from
            transform { replaceConstant(from, to) }
        }
    }
}

fun ClassContext.constantReplacements(vararg pairs: Pair<Any, Any>) = constantReplacements(pairs.toMap())