package com.solartweaks.engine

import com.solartweaks.engine.util.*
import java.lang.invoke.MethodType
import java.lang.reflect.Method

fun MethodsContext.named(name: String, block: MethodContext.() -> Unit = {}) = name {
    method named name
    block()
}

fun MethodsContext.namedTransform(name: String, block: MethodTransformContext.() -> Unit) = name {
    method named name
    transform(block)
}

fun ClassContext.isMinecraftClass() = node match { it.name.startsWith(minecraftPackage) }
fun ClassContext.isOptifineClass() = node match { it.name.startsWith(optifinePackage) }
fun ClassContext.isLunarClass() = node match { it.name.startsWith(lunarPackage) }

fun optifineClassName(name: String, subpackage: String) = when (BridgeManager.minecraftVersion.id) {
    "v1_7" -> optifinePackage
    else -> "$optifinePackage$subpackage/"
} + name

fun loadOptifineClass(name: String, subpackage: String) =
    mainLoader.loadClass(optifineClassName(name, subpackage).replace('/', '.'))

private fun ClassLoader.loadInternal(name: String) = loadClass(name.replace('/', '.'))

fun MethodData.asMethod(loader: ClassLoader = mainLoader) = loader.loadInternal(owner.name).getDeclaredMethod(
    method.name,
    *MethodType.fromMethodDescriptorString(method.desc, loader).parameterArray()
).also { it.isAccessible = true }

fun MethodData.tryInvoke(receiver: Any? = null, vararg params: Any?, method: Method = asMethod()) =
    method(receiver, *params)