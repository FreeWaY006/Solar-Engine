package com.solartweaks.engine.util

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter

/**
 * Utility that ensures that asm can find inheritance info when writing a class.
 */
class LoaderClassWriter(
    private val loader: ClassLoader,
    reader: ClassReader? = null,
    flags: Int,
) : ClassWriter(reader, flags) {
    override fun getClassLoader() = loader
}