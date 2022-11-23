package com.solartweaks.engine.util

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Wraps information about a method (invocation) into a datastructure
 */
data class MethodDescription(
    val name: String,
    val descriptor: String,
    val owner: String,
    val access: Int,
    val isInterface: Boolean = false
)

fun MethodDescription.isSimilar(desc: MethodDescription) =
    name == desc.name && descriptor == desc.descriptor && owner == desc.owner

/**
 * Whether the described method is `public`
 */
val MethodDescription.isPublic get() = access and ACC_PUBLIC != 0

/**
 * Whether the described method is `private`
 */
val MethodDescription.isPrivate get() = access and ACC_PRIVATE != 0

/**
 * Whether the described method is `protected`
 */
val MethodDescription.isProtected get() = access and ACC_PROTECTED != 0

/**
 * Whether the described method is `static`
 */
val MethodDescription.isStatic get() = access and ACC_STATIC != 0

/**
 * Whether the described method is `final`
 */
val MethodDescription.isFinal get() = access and ACC_FINAL != 0

/**
 * Whether the described method is a constructor
 */
val MethodDescription.isConstructor get() = name == "<init>"

/**
 * Wraps information about a field (reference) into a datastructure
 */
data class FieldDescription(
    val name: String,
    val descriptor: String,
    val owner: String,
    val access: Int
)

/**
 * Whether the described field is `public`
 */
val FieldDescription.isPublic get() = access and ACC_PUBLIC != 0

/**
 * Whether the described field is `private`
 */
val FieldDescription.isPrivate get() = access and ACC_PRIVATE != 0

/**
 * Whether the described field is `static`
 */
val FieldDescription.isStatic get() = access and ACC_STATIC != 0

/**
 * Whether the described field is `final`
 */
val FieldDescription.isFinal get() = access and ACC_FINAL != 0

/**
 * Converts [MethodData] to a [MethodDescription]
 */
fun MethodData.asDescription() =
    MethodDescription(method.name, method.desc, owner.name, method.access, owner.isInterface)


/**
 * Converts a [MethodNode] to a [MethodDescription]
 */
fun MethodNode.asDescription(owner: String, interfaceMethod: Boolean = false) =
    MethodDescription(name, desc, owner, access, interfaceMethod)

/**
 * Converts a [MethodNode] to a [MethodDescription]
 */
fun MethodNode.asDescription(owner: ClassNode) = asDescription(owner.name, owner.isInterface)

/**
 * Converts a [Method] to a [MethodDescription]
 */
fun Method.asDescription() = MethodDescription(
    name = name,
    descriptor = Type.getMethodDescriptor(this),
    owner = declaringClass.internalName,
    access = modifiers,
    isInterface = declaringClass.isInterface
)

/**
 * Converts a [Constructor] to a [MethodDescription]
 */
fun <T> Constructor<T>.asDescription() = MethodDescription(
    name = "<init>",
    descriptor = Type.getConstructorDescriptor(this),
    owner = declaringClass.internalName,
    access = modifiers
)

/**
 * Converts [FieldData] to a [FieldDescription]
 */
fun FieldData.asDescription() =
    FieldDescription(field.name, field.desc, owner.name, field.access)

/**
 * Converts a [FieldNode] to a [FieldDescription]
 */
fun FieldNode.asDescription(owner: String) =
    FieldDescription(name, desc, owner, access)

/**
 * Converts a [FieldNode] to a [FieldDescription]
 */
fun FieldNode.asDescription(owner: ClassNode) = asDescription(owner.name)

/**
 * Converts a [Field] to a [FieldDescription]
 */
fun Field.asDescription() = FieldDescription(
    name = name,
    descriptor = Type.getDescriptor(type),
    owner = declaringClass.internalName,
    access = modifiers
)

/**
 * Describes how a method should be invoked. Is associated with an [opcode].
 */
enum class InvocationType(val opcode: Int) {
    SPECIAL(INVOKESPECIAL),
    DYNAMIC(INVOKEDYNAMIC),
    VIRTUAL(INVOKEVIRTUAL),
    INTERFACE(INVOKEINTERFACE),
    STATIC(INVOKESTATIC);

    companion object {
        fun getFromOpcode(opcode: Int) = enumValues<InvocationType>().find { it.opcode == opcode }
    }
}

/**
 * Finds the way to invoke the described method
 */
val MethodDescription.invocationType
    get() = when {
        isPrivate || name == "<init>" -> InvocationType.SPECIAL
        isStatic -> InvocationType.STATIC
        isInterface -> InvocationType.INTERFACE
        else -> InvocationType.VIRTUAL
    }

/**
 * Finds a way to invoke [MethodData]
 */
val MethodData.invocationType
    get() = when {
        method.isPrivate || method.name == "<init>" -> InvocationType.SPECIAL
        method.isStatic -> InvocationType.STATIC
        owner.isInterface -> InvocationType.INTERFACE
        else -> InvocationType.VIRTUAL
    }