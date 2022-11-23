package com.solartweaks.engine.util

import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.TraceClassVisitor
import java.io.ByteArrayOutputStream
import java.io.PrintWriter

/**
 * Way to define class transformations
 */
typealias ClassTransform = (parent: ClassVisitor, node: ClassNode) -> ClassVisitor

/**
 * Way to define method transformations
 */
typealias MethodTransform = (parent: MethodVisitor, data: MethodData) -> MethodVisitor

/**
 * Utility to fold a set of [ClassTransform]s
 */
fun List<ClassTransform>.fold(parent: ClassVisitor, node: ClassNode) =
    fold(parent) { acc, curr -> curr(acc, node) }

/**
 * Utility to fold a set of [MethodTransform]s
 */
fun List<MethodTransform>.fold(parent: MethodVisitor, data: MethodData) =
    fold(parent) { acc, curr -> curr(acc, data) }

/**
 * Produces a [ClassTransform] that applies multiple [MethodTransform]s, for a given [method]
 */
fun List<MethodTransform>.asClassTransform(method: MethodDescription): ClassTransform = { parent, node ->
    object : ClassVisitor(asmAPI, parent) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            val superVisitor = super.visitMethod(access, name, descriptor, signature, exceptions)
            return if (method.name == name && method.descriptor == descriptor) {
                val mNode = node.methods.find { it.name == name && it.desc == descriptor }
                    ?: error("ClassNode and ClassVisitor were called on different class files!")

                fold(superVisitor, MethodData(node, mNode))
            } else superVisitor
        }
    }
}

// Transformation DSL

/**
 * Marker annotation for defining the Transformation DSL
 */
@DslMarker
annotation class TransformDSL

/**
 * Allows you to transform classes using a DSL
 */
@TransformDSL
class ClassTransformContext(val node: ClassNode) {
    internal val transforms = mutableListOf<ClassTransform>()

    /**
     * Shorthand for [add]
     */
    operator fun ClassTransform.unaryPlus() {
        transforms += this
    }

    /**
     * Allows you to add your own custom [ClassTransform]
     */
    fun add(transform: ClassTransform) {
        transforms += transform
    }

    // TODO: dsls for specific sorts of transforms
}

/**
 * Allows you to transform methods using a DSL
 */
@TransformDSL
class MethodTransformContext(val owner: ClassNode, val method: MethodNode) {
    constructor(data: MethodData) : this(data.owner, data.method)

    internal var shouldExpandFrames = false
    internal val transforms = mutableListOf<MethodTransform>()

    /**
     * Shorthand for [add]
     */
    operator fun MethodTransform.unaryPlus() {
        transforms += this
    }

    /**
     * Allows you to add your own custom [MethodTransform]
     */
    fun add(transform: MethodTransform) {
        transforms += transform
    }

    /**
     * Allows you to overwrite the method body of the target method
     * (requires ClassWriter.COMPUTE_MAXS)
     */
    fun overwrite(impl: MethodVisitor.() -> Unit) {
        require(transforms.isEmpty()) { "Cannot overwrite method that has transforms defined!" }
        transforms += { parent, _ ->
            object : MethodVisitor(asmAPI, null) {
                override fun visitCode() {
                    parent.visitCode()
                    parent.impl()
                    parent.visitMaxs(-1, -1)
                    parent.visitEnd()
                }

                override fun visitParameter(name: String, access: Int) {
                    parent.visitParameter(name, access)
                }
            }
        }
    }

    /**
     * Makes the method body equivalent to `return cst;`
     * @see [overwrite]
     */
    fun fixedValue(cst: Any?) = overwrite {
        loadConstant(cst)
        returnMethod(Type.getReturnType(method.desc).getOpcode(IRETURN))
    }

    /**
     * Makes the method body return the default value of the return type
     * That is, the value that would be assigned by the jvm to an uninitialized primitive, or null for an object
     * @see [overwrite]
     */
    fun stubValue() = overwrite {
        val returnType = Type.getReturnType(method.desc)
        visitInsn(returnType.stubLoadInsn)
        returnMethod(returnType.getOpcode(IRETURN))
    }

    /**
     * Allows you to write a custom [MethodVisitor] for the given method
     */
    fun visitor(wrapper: (parent: MethodVisitor) -> MethodVisitor) {
        transforms += { parent, _ -> wrapper(parent) }
    }

    /**
     * Forces the class transformer to use ClassReader.EXPAND_FRAMES
     */
    fun expandFrames() {
        shouldExpandFrames = true
    }

    /**
     * Allows you to add code to the start of the method body
     */
    fun methodEnter(handler: MethodVisitor.() -> Unit) {
        transforms += { parent, _ ->
            object : MethodVisitor(asmAPI, parent) {
                override fun visitCode() {
                    super.visitCode()
                    handler()
                }
            }
        }
    }

    /**
     * Allows you to add code before every xxRETURN or ATHROW operation
     */
    fun methodExit(handler: MethodVisitor.(opcode: Int) -> Unit) {
        transforms += { parent, _ ->
            object : MethodVisitor(asmAPI, parent) {
                override fun visitInsn(opcode: Int) {
                    if (opcode in IRETURN..RETURN || opcode == ATHROW) handler(opcode)
                    super.visitInsn(opcode)
                }
            }
        }
    }

    /**
     * Allows you to add code before a given exit operation [opcode]
     */
    fun methodExit(opcode: Int, handler: MethodVisitor.() -> Unit) =
        methodExit { op -> if (op == opcode) handler() }

    /**
     * Allows you to add code before/after a method call
     */
    fun callAdvice(
        matcher: (MethodDescription) -> Boolean,
        beforeCall: MethodVisitor.() -> Unit = {},
        afterCall: MethodVisitor.() -> Unit = {},
        handleOnce: Boolean = false
    ) {
        transforms += { parent, _ ->
            object : MethodVisitor(asmAPI, parent) {
                private var hasHandled = false

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean
                ) {
                    if (hasHandled && handleOnce)
                        return super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)

                    val desc = MethodDescription(name, descriptor, owner, -1, isInterface)
                    val matches = matcher(desc)

                    if (matches) {
                        hasHandled = true
                        beforeCall()
                    }

                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    if (matches) afterCall()
                }
            }
        }
    }

    /**
     * Allows you to replace a method call with some code
     */
    fun replaceCall(
        matcher: (MethodDescription) -> Boolean,
        replacement: MethodVisitor.() -> Unit = {},
    ) {
        transforms += { parent, _ ->
            object : MethodVisitor(asmAPI, parent) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean
                ) {
                    val desc = MethodDescription(name, descriptor, owner, -1, isInterface)
                    if (matcher(desc)) replacement()
                    else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                }
            }
        }
    }

    /**
     * Combines [methodEnter] and [methodExit] into a utility method
     */
    fun advice(
        enter: MethodVisitor.() -> Unit = {},
        exit: MethodVisitor.(opcode: Int) -> Unit = {}
    ) {
        methodEnter(enter)
        methodExit(exit)
    }

    /**
     * Replaces/maps a constant value
     */
    fun replaceConstant(from: Any, to: Any) = replaceConstants(mapOf(from to to))

    /**
     * Replaces/maps some constant values
     */
    fun replaceConstants(map: Map<Any, Any>) = visitor { parent ->
        object : MethodVisitor(asmAPI, parent) {
            override fun visitLdcInsn(value: Any) =
                super.visitLdcInsn(map[value] ?: value)

            override fun visitInvokeDynamicInsn(
                name: String,
                desc: String,
                handle: Handle,
                vararg bsmArgs: Any
            ) {
                val newArgs = bsmArgs.map { map[it] ?: it }.toTypedArray()
                super.visitInvokeDynamicInsn(name, desc, handle, *newArgs)
            }

            override fun visitIntInsn(opcode: Int, operand: Int) {
                parent.loadConstant(
                    if (opcode == BIPUSH || opcode == SIPUSH) map[operand] as? Int ?: operand else operand
                )
            }
        }
    }

    /**
     * Replaces/maps a string by replacing the [from] occurrences with [to]
     */
    fun replaceString(from: String, to: String) = visitor { parent ->
        object : MethodVisitor(asmAPI, parent) {
            override fun visitLdcInsn(value: Any?) {
                super.visitLdcInsn(if (value is String) value.replace(from, to) else value)
            }

            override fun visitInvokeDynamicInsn(
                name: String,
                desc: String,
                handle: Handle,
                vararg bsmArgs: Any
            ) {
                val newArgs = bsmArgs.map { if (it is String) it.replace(from, to) else it }.toTypedArray()
                super.visitInvokeDynamicInsn(name, desc, handle, *newArgs)
            }
        }
    }

    /**
     * Converts this [MethodContext] to a [ClassTransform]
     */
    fun asClassTransform() = transforms.asClassTransform(method.asDescription(owner))
}

/**
 * Utility for transforming a class. Returns a [ByteArray] with the new class
 */
fun ClassNode.transform(
    transforms: List<ClassTransform>,
    reader: ClassReader,
    writer: ClassWriter,
    readerOptions: Int,
    debug: Boolean = false
): ByteArray {
    val acc = ByteArrayOutputStream()
    val visitor = if (debug) TraceClassVisitor(writer, PrintWriter(acc)) else writer
    val transformer = transforms.fold(visitor, this)
    reader.accept(transformer, readerOptions)

    if (debug) {
        println("Bytecode:")
        println(acc.toByteArray().decodeToString())
    }

    return writer.toByteArray()
}

/**
 * Utility for transforming a class. Defines some default values
 */
fun ClassNode.transformDefault(
    transforms: List<ClassTransform>,
    originalBuffer: ByteArray,
    loader: ClassLoader,
    expand: Boolean = false,
    readerOptions: Int = ClassReader.SKIP_DEBUG,
    debug: Boolean = false
): ByteArray {
    val reader = ClassReader(originalBuffer)
    val writer = LoaderClassWriter(loader, reader, ClassWriter.COMPUTE_FRAMES)
    return transform(
        transforms, reader, writer,
        if (expand) readerOptions or ClassReader.EXPAND_FRAMES else readerOptions, debug
    )
}