package com.solartweaks.engine.util

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

/**
 * Attempts to get the bytes of this [Class] using its [ClassLoader]
 */
fun Class<*>.getBytes() = classLoader.getResourceAsStream(resourceName)?.readBytes()

// Class cache map. Keyed by class, since classes can be loaded by different loaders
private val classCache = mutableMapOf<Class<*>, ByteArray>()

/**
 * Gets the bytes of this [Class], but caches it as well.
 * Note that this caching process isn't seperated per [ClassLoader]
 */
fun Class<*>.getCachedBytes(): ByteArray? {
    return classCache.getOrPut(this) { getBytes() ?: return null }
}

/**
 * Converts a given [Class] to a [ClassNode]
 */
fun Class<*>.asNode(options: Int = ClassReader.SKIP_DEBUG) = getCachedBytes()?.asClassNode(options)

// ClassNode cached map. Keyed by class, since classes can be loaded by different loaders
private val classNodeCache = mutableMapOf<Class<*>, ClassNode>()

/**
 * Same as [asNode], but caches the results. `null` will not be cached.
 */
fun Class<*>.asCachedNode(): ClassNode? {
    return classNodeCache.getOrPut(this) { asNode() ?: return null }
}

/**
 * Converts a byte array (representing a classfile) to a [ClassNode]
 */
fun ByteArray.asClassNode(options: Int = ClassReader.SKIP_DEBUG) =
    ClassNode().also { ClassReader(this).accept(it, options) }

/**
 * Loads a class by [name] with the given [options] and [loader]
 */
fun loadNodeByName(
    name: String,
    options: Int = ClassReader.SKIP_DEBUG,
    loader: ClassLoader = ClassLoader.getSystemClassLoader()
) = loader.getResourceAsStream(name.classResourceName)?.readBytes()?.asClassNode(options)