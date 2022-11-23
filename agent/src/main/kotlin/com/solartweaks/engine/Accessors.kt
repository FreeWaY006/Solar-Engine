package com.solartweaks.engine

import com.solartweaks.engine.util.*
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.lang.reflect.Method

// Registry for accessors
val accessors = mutableListOf<AccessorData<*, *>>()

private var accessorCounter = 0
    get() = field++

data class AccessorData<V : Any, S : StaticAccessor<V>>(
    val finder: ClassFinder,
    val virtualType: Class<V>,
    val staticType: Class<S>,
    private val virtualGenerator: (AccessorData<V, S>) -> Class<*>,
    private val staticGenerator: (AccessorData<V, S>) -> S,
    private val shouldReflect: (ClassNode) -> Boolean = { !it.isPublic }
) {
    val virtualAccessorName = "${virtualType.simpleName}Virtual$accessorCounter"
    val staticAccessorName = "${virtualType.simpleName}Static$accessorCounter"
    val virtualAccessor by lazy { virtualGenerator(this) }
    val staticAccessor by lazy { staticGenerator(this) }

    val fullReflect get() = shouldReflect(finder())
    val delegateFieldName = "\$\$delegate"
    private val delegateFieldType: String get() = if (fullReflect) "java/lang/Object" else finder().name
    val delegateFieldDesc get() = "L$delegateFieldType;"
}

/**
 * Finds [AccessorData] by an interface type's internal [name]
 */
fun findAccessor(name: String) = accessors.find { it.virtualType.internalName == name }

private sealed interface AccessorImplementation
private data class FieldImplementation(
    val receiverMethod: Method,
    val target: FieldData,
    val type: Type = if (receiverMethod.name.startsWith("set")) Type.SETTER else Type.GETTER
) : AccessorImplementation {
    enum class Type(val paramCount: Int, val namePrefix: String) {
        GETTER(0, "get"), SETTER(1, "set");

        fun match(params: Int, name: String) = paramCount == params && name.startsWith(namePrefix)
    }
}

private data class MethodImplementation(val receiverMethod: Method, val target: MethodData) : AccessorImplementation

/**
 * Internal utility for generating an accessor
 */
// name here is the internal name of the generating class
fun internalGenerateAccessor(
    data: AccessorData<*, *>,
    accessorName: String,
    typeToImplement: Class<*>,
    constructorImpl: ClassVisitor.(name: String, reflectedMethods: Set<ReflectedElementData>) -> Unit,
    beforeGenerate: ClassVisitor.(name: String) -> Unit,
    receiverLoader: MethodVisitor.(name: String, isReflected: Boolean) -> Unit,
): Class<*> {
    require(typeToImplement.isInterface) { "$typeToImplement is not an interface to implement!" }
    val fullAccessorName = "$generatedPrefix/$accessorName"

    // Start generating class
    return generateDefaultClass(
        name = accessorName,
        debug = true,
        implements = listOf(typeToImplement.internalName),
        defaultConstructor = false
    ) {
        // Check if we need to implement StaticAccessor
        val isStaticAccessor = StaticAccessor::class.java in typeToImplement.interfaces
        val staticAccessorIllegal = listOf("isInstance", "cast")

        // Find how to implement the methods requested
        val foundMethods = data.finder.methods.methods
        val foundFields = data.finder.fields.fields
        val implementations = typeToImplement.declaredMethods
            .filter { !it.isDefault && (!isStaticAccessor || it.name !in staticAccessorIllegal) }
            .map { receiverMethod ->
                val name = receiverMethod.name

                // Check if this is a getter/setter and therefore could be a field
                val paramCount = receiverMethod.parameterCount
                val isGetter = FieldImplementation.Type.GETTER.match(paramCount, name)
                val isSetter = FieldImplementation.Type.SETTER.match(paramCount, name)
                val field = if ((isGetter || isSetter) && name.length > 3) {
                    val fieldName = name[3].lowercase() + name.drop(4)
                    foundFields[fieldName]?.nullable()
                        ?.let { FieldImplementation(receiverMethod, it) }
                } else null

                // If not a field, or no field was found, it could be a method
                field ?: foundMethods[name]?.nullable()
                    ?.let { MethodImplementation(receiverMethod, it) }
                ?: error(
                    """
                    |No implementation was found for $receiverMethod
                    |
                    |Methods in finder:
                    |${foundMethods.toList().joinToString { (name, m) -> "${name}: ${m.nullable()}" }}
                    |
                    |Fields in finder:
                    |${foundFields.toList().joinToString { (name, f) -> "${name}: ${f.nullable()}" }}
                """.trimMargin()
                )
            }

        val methods = implementations.filterIsInstance<MethodImplementation>()
        val fields = implementations.filterIsInstance<FieldImplementation>()

        // Before generating class hook
        beforeGenerate(fullAccessorName)

        // Keep track of all method/field access that has to be reflected
        val reflectedElements = hashSetOf<ReflectedElementData>()

        // Start implementing methods
        for ((receiverMethod, target) in methods) {
            val (targetOwner, targetMethod) = target
            val (name, desc) = receiverMethod.asDescription()
            val arguments = Type.getArgumentTypes(desc)
            val targetArguments = targetMethod.arguments

            require(arguments.size == targetArguments.size) {
                "Argument count mismatch for $receiverMethod"
            }

            // Check if the method is a constructor
            val isCtor = targetMethod.isConstructor

            // If the method is private, generate a special field
            // to store the reflected method in
            val isReflected = !targetMethod.isPublic || data.fullReflect
            val rmd = ReflectedMethodData(target)
            if (isReflected) {
                reflectedElements += rmd
                implementRED(rmd)
            }

            // If the method is an "abstract ctor", do not allow
            require(!targetOwner.isAbstract || !isCtor) {
                val t = "${targetMethod.name}${targetMethod.desc} in ${targetOwner.name}"
                "Constructor accessors cannot be in an abstract class. Targeted method: $t"
            }

            // Start actually generating the method
            generateMethod(name, desc) {
                if (isReflected) {
                    // If the method is reflected, load the correct field
                    loadThis()
                    getRED(rmd, fullAccessorName)
                }

                if (isCtor) {
                    if (!isReflected) {
                        // If the method is a constructor, push a new instance
                        visitTypeInsn(NEW, targetOwner.name)
                        dup()
                    }
                } else {
                    // Load receiver on stack
                    receiverLoader(fullAccessorName, isReflected)
                }

                // Also make sure to create an array for the arguments when reflected
                if (isReflected) {
                    loadConstant(arguments.size)
                    visitTypeInsn(ANEWARRAY, "java/lang/Object")
                }

                // Load all parameters on stack
                loadAccessorParams(arguments, targetArguments, isReflected)

                // Call the target method
                val returnType = Type.getReturnType(desc)
                if (isReflected) {
                    // When method is reflected, invoke with reflection
                    invokeMethod(
                        InvocationType.VIRTUAL,
                        rmd.fieldType,
                        if (isCtor) "newInstance" else "invoke",
                        "(${if (!isCtor) "Ljava/lang/Object;" else ""}[Ljava/lang/Object;)Ljava/lang/Object;"
                    )

                    // Lastly, unbox primitives and cast when necessary
                    if (returnType.isPrimitive) {
                        // Unbox when primitive (primitive != Object, which is the return type of invoke)
                        unbox(returnType)
                    }
                } else {
                    // Normal invocation
                    invokeMethod(targetMethod.asDescription(targetOwner))
                }

                // Return&cast result
                returnAccessedValue(returnType)
            }
        }

        // Implement all fields
        // Sadly, this implementation differs a slight bit too much from the methods,
        // so copy-pasting was easier in practice
        for ((receiverMethod, target, type) in fields) {
            val (targetOwner, targetField) = target
            val (name, desc) = receiverMethod.asDescription()
            val desiredType = Type.getReturnType(desc)

            // If the field is private, generate a special field
            // to store the reflected Field in
            val isReflected = !targetField.isPublic
            val rfd = ReflectedFieldData(target)
            if (isReflected && reflectedElements.add(rfd)) implementRED(rfd)

            // Start generating the method
            generateMethod(name, desc) {
                val isSetter = type == FieldImplementation.Type.SETTER

                if (isReflected) {
                    // If the field is reflected, load the correct field
                    loadThis()
                    getRED(rfd, fullAccessorName)
                }

                // Load receiver on stack
                receiverLoader(fullAccessorName, isReflected)

                if (isSetter) {
                    // When field is setter, load new value for the field
                    // Type of the setter param
                    val setterType = Type.getArgumentTypes(desc).first()

                    // Type of the target field
                    val fieldType = Type.getType(targetField.desc)

                    // Load setter param
                    load(1, setterType)

                    // If primitive, box when reflected
                    // else cast to desired target type
                    if (setterType.isPrimitive) {
                        if (isReflected) box(setterType)
                    } else cast(fieldType)
                }

                if (isReflected) {
                    // When field is reflected, apply with reflection
                    val mDesc =
                        if (isSetter) "(Ljava/lang/Object;Ljava/lang/Object;)V"
                        else "(Ljava/lang/Object;)Ljava/lang/Object;"

                    invokeMethod(
                        InvocationType.VIRTUAL,
                        owner = "java/lang/reflect/Field",
                        name = if (isSetter) "set" else "get",
                        descriptor = mDesc
                    )

                    // Lastly, unbox primitives and cast when necessary
                    if (!isSetter && desiredType.isPrimitive) {
                        // Unbox when primitive (primitive != Object, which is the return type of get)
                        unbox(desiredType)
                    }
                } else {
                    // Normal retrieval
                    val fieldDesc = targetField.asDescription(targetOwner)
                    if (isSetter) setField(fieldDesc) else getField(fieldDesc)
                }

                // Return&cast result
                if (isSetter) returnMethod() else returnAccessedValue(desiredType)
            }
        }

        constructorImpl(fullAccessorName, reflectedElements)
    }
}

/**
 * Generates a non-static accessor type for [data]
 */
fun generateAccessor(data: AccessorData<*, *>): Class<*> {
    val foundClass = data.finder.nullable()
        ?: error("finder hasn't found a matching class for ${data.virtualType} yet!")

    // Return newly generated class
    return internalGenerateAccessor(
        data = data,
        accessorName = data.virtualAccessorName,
        typeToImplement = data.virtualType,
        beforeGenerate = { name ->
            // Generate delegate field
            visitField(ACC_PUBLIC or ACC_FINAL, data.delegateFieldName, data.delegateFieldDesc, null, null)

            // Generate getDelegate method
            generateMethod("getDelegate", "()Ljava/lang/Object;") {
                loadThis()
                visitFieldInsn(GETFIELD, name, data.delegateFieldName, data.delegateFieldDesc)
                returnMethod(ARETURN)
            }
        },
        receiverLoader = { name, _ ->
            loadThis()
            visitFieldInsn(GETFIELD, name, data.delegateFieldName, data.delegateFieldDesc)
        },
        constructorImpl = { name, reflectedElements ->
            // Create constructor
            generateMethod("<init>", "(Ljava/lang/Object;)V") {
                val binaryFoundName = foundClass.name.replace('/', '.')

                // Call super() on Object
                loadThis()
                visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

                // Initialize delegate field
                loadThis()
                load<Any>(1)

                if (data.fullReflect) {
                    loadConstant(binaryFoundName)
                    invokeMethod(::getAppClass)
                    visitInsn(SWAP)
                    invokeMethod(Class<*>::cast)
                } else cast(foundClass.name)

                visitFieldInsn(PUTFIELD, name, data.delegateFieldName, data.delegateFieldDesc)

                // Initialize reflected methods
                implInitREDs(
                    reflectedElements,
                    foundClass = binaryFoundName,
                    fullAccessorName = name
                )

                // End constructor
                returnMethod()
            }
        }
    )
}

/**
 * Generates a static accessor type for [data]
 */
inline fun <reified T : StaticAccessor<*>> generateStaticAccessor(data: AccessorData<*, *>): T {
    val foundClass = data.finder.nullable()
        ?: error("finder hasn't found a matching class for ${data.virtualType} yet!")

    val virtualAccessor = data.virtualAccessor

    return internalGenerateAccessor(
        data = data,
        accessorName = data.staticAccessorName,
        typeToImplement = data.staticType,
        beforeGenerate = {
            // Implement cast and isInstance
            generateMethod("cast", "(Ljava/lang/Object;)Ljava/lang/Object;") {
                construct(virtualAccessor.internalName, "(Ljava/lang/Object;)V") {
                    // Load receiver/first constructor param
                    load(1, ALOAD)
                }

                returnMethod(ARETURN)
            }

            generateMethod("isInstance", "(Ljava/lang/Object;)Z") {
                // Load receiver
                load(1, ALOAD)

                // Check with INSTANCEOF
                visitTypeInsn(INSTANCEOF, foundClass.name)

                returnMethod(IRETURN)
            }
        },
        receiverLoader = { _, isReflected -> if (isReflected) loadConstant(null) },
        constructorImpl = { name, reflectedMethods ->
            // Create constructor
            generateMethod("<init>", "()V") {
                // Call super() on Object
                loadThis()
                visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)

                // Initialize reflected methods
                implInitREDs(
                    reflectedElements = reflectedMethods,
                    foundClass = foundClass.name.replace('/', '.'),
                    fullAccessorName = name
                )

                // End constructor
                returnMethod()
            }
        }
    ).instance()
}

/**
 * Internal utility for loading all params given by [arguments] and [targetArguments] to the stack
 * Will also wrap into Object[] when [isReflected] is true
 */
private fun MethodVisitor.loadAccessorParams(
    arguments: Array<Type>,
    targetArguments: Array<Type>,
    isReflected: Boolean = false
) {
    // Load all parameters on stack
    var paramIndex = 1 // 1 because 0=this
    for ((idx, paramType) in arguments.withIndex()) {
        // When reflected, push the element index and dup args array
        if (isReflected) {
            dup()
            loadConstant(idx)
        }

        // Load the actual parameter on stack
        load(paramIndex, paramType)

        // Check if we need to unwrap an accessor
        val accessorType = findAccessor(paramType.internalName)
        if (accessorType != null) {
            // Unwrap the accessor
            val vAccessorName = "$generatedPrefix/${accessorType.virtualAccessorName}"
            cast(vAccessorName)
            visitFieldInsn(GETFIELD, vAccessorName, accessorType.delegateFieldName, accessorType.delegateFieldDesc)
        }

        // When reflected, push the parameter to the array
        if (isReflected) {
            // Box primitives (since Object[] cannot contain primitives)
            if (paramType.isPrimitive) box(paramType)
            visitInsn(AASTORE)
        } else if (accessorType?.fullReflect != true) {
            // Find the correct target type of this parameter
            val targetType = targetArguments[idx].internalName

            // Cast when not matching type (could fail, Prayge)
            if (!paramType.isPrimitive && paramType.internalName != targetType) cast(targetType)
        }

        // Increment param index, keeping doubles and longs in mind
        paramIndex += paramType.size
    }
}

private fun MethodVisitor.wrapAccessor(accessorData: AccessorData<*, *>) {
    val vAccessorName = "$generatedPrefix/${accessorData.virtualAccessorName}"

    // Ensure loaded when called
    loadConstant(accessorData.virtualType.internalName)
    invokeMethod(::findAccessor)
    getProperty(AccessorData<*, *>::virtualAccessor)
    pop()

    // on stack: obj
    visitTypeInsn(NEW, vAccessorName)
    // on stack: obj, uninit vAccessor
    visitInsn(DUP_X1)
    // on stack: uninit vAccessor, obj, uninit vAccessor
    visitInsn(SWAP)
    // on stack: 2x uninit vAccessor, obj
    visitMethodInsn(INVOKESPECIAL, vAccessorName, "<init>", "(Ljava/lang/Object;)V", false)
    // on stack: vAccessor (initialized)
}

fun MethodVisitor.returnAccessedValue(returnType: Type) {
    // Let's see if we need to return an accessor
    val returnedAccessor = findAccessor(returnType.internalName)
    if (returnedAccessor != null) {
        // Two paths: if returned value is null, do not make an accessor
        val label = Label()
        dup()
        visitJumpInsn(IFNONNULL, label)
        returnMethod(returnType.getOpcode(IRETURN))

        visitLabel(label)
        wrapAccessor(returnedAccessor)
    }

    // To make sure it works out, perform checked cast
    if (!returnType.isPrimitive) cast(returnType.internalName)

    // Return result
    returnMethod(returnType.getOpcode(IRETURN))
}

/**
 * Internal utility for initializing all reflected method/field access
 * [foundClass] is the binary name of the class that we are delegating to
 * [fullAccessorName] is required in order to get the field successfully
 */
fun MethodVisitor.implInitREDs(
    reflectedElements: Set<ReflectedElementData>,
    foundClass: String,
    fullAccessorName: String
) {
    if (reflectedElements.isNotEmpty()) {
        // Find class to get all the methods from
        loadConstant(foundClass)
        invokeMethod(::getAppClass)

        for (red in reflectedElements) {
            // Dup class so it can be reused
            dup()

            // Push this
            loadThis()

            // Swap top stack values out (stack is now class - this - class)
            visitInsn(SWAP)

            // Get the values of the fields
            when (red) {
                is ReflectedFieldData -> {
                    val targetField = red.target.field
                    loadConstant(targetField.name)

                    // Get Field
                    invokeMethod(Class<*>::getDeclaredField)

                    // Make Field accessible
                    dup()
                    loadConstant(true)
                    invokeMethod(Field::class.java.getMethod("setAccessible", Boolean::class.javaPrimitiveType))
                }
                is ReflectedMethodData -> {
                    val targetMethod = red.target.method
                    if (!targetMethod.isConstructor) {
                        // Load method name
                        loadConstant(targetMethod.name)
                    }

                    // Load param types
                    val params = Type.getArgumentTypes(targetMethod.desc)
                    loadConstant(params.size)
                    visitTypeInsn(ANEWARRAY, "java/lang/Class")

                    params.forEachIndexed { idx, param ->
                        dup()
                        loadConstant(idx)
                        if (param.sort == Type.OBJECT) {
                            loadConstant(param.className)
                            invokeMethod(::getAppClass)
                        } else loadTypeClass(param)
                        visitInsn(AASTORE)
                    }

                    // Get method
                    if (targetMethod.isConstructor) invokeMethod(Class<*>::getDeclaredConstructor)
                    else invokeMethod(Class<*>::getDeclaredMethod)

                    // Make method accessible
                    dup()
                    loadConstant(true)
                    invokeMethod(
                        AccessibleObject::class.java.getMethod(
                            "setAccessible",
                            Boolean::class.javaPrimitiveType
                        )
                    )
                }
            }

            // Set the accessor field
            setRED(red, fullAccessorName)
        }

        // App class still on stack, pop
        pop()
    }
}

sealed class ReflectedElementData(val fieldName: String, val fieldType: String) {
    val fieldDesc get() = "L$fieldType;"
}

private fun ClassVisitor.implementRED(red: ReflectedElementData) =
    visitField(ACC_PRIVATE or ACC_FINAL, red.fieldName, red.fieldDesc, null, null)

private fun MethodVisitor.getRED(red: ReflectedElementData, owner: String) =
    visitFieldInsn(GETFIELD, owner, red.fieldName, red.fieldDesc)

private fun MethodVisitor.setRED(red: ReflectedElementData, owner: String) =
    visitFieldInsn(PUTFIELD, owner, red.fieldName, red.fieldDesc)

/**
 * A container for holding all method access that needs to be reflected
 */
data class ReflectedMethodData(val target: MethodData) : ReflectedElementData(
    fieldName = "reflectedMethod_${target.method.name}\$${escapeDesc(target.method.desc)}",
    fieldType = if (target.method.isConstructor) "java/lang/reflect/Constructor" else "java/lang/reflect/Method"
)

/**
 * A container for holding all field access that needs to be reflected
 */
data class ReflectedFieldData(val target: FieldData) : ReflectedElementData(
    fieldName = "reflectedField_${target.field.name}\$${escapeDesc(target.field.desc)}",
    fieldType = "java/lang/reflect/Field"
)

private fun escapeDesc(desc: String) = desc
    .replace(".", "PD\$") // PerioD
    .replace(";", "SC\$") // SemiColon
    .replace("[", "AR\$") // ARray
    .replace("/", "SL\$") // SLash

/**
 * Type used for representing accessor pairs
 * Useful for creating instances and such
 * [static] can be delegated to with a companion object for ease of access
 */
class GeneratedAccessor<V : Any, S : StaticAccessor<V>>(virtualAccessor: Class<*>, val static: S) {
    private val ctor = virtualAccessor.getConstructor(Any::class.java)

    /**
     * Creates a new instance of the delegate accessor represented by [V]
     */
    @Suppress("UNCHECKED_CAST") // sorry, I have failed you D:
    fun instance(receiver: Any): V = ctor.newInstance(receiver) as V
}

/**
 * Implements accessors for type [V], with a static accessor represented by type [S]
 */
inline fun <reified V : Any, reified S : StaticAccessor<V>> accessor(
    finder: ClassFinder,
    crossinline preinit: () -> Unit = {}
): Lazy<GeneratedAccessor<V, S>> {
    // Early interface check
    val virtualType = V::class.java
    val staticType = S::class.java
    require(virtualType.isInterface) { "Type V is not an interface type" }
    require(staticType.isInterface) { "Type S is not an interface type" }

    val ad = AccessorData(
        finder,
        virtualType,
        staticType,
        virtualGenerator = {
            runCatching { generateAccessor(it) }
                .onFailure {
                    println("Error generating virtual accessor:")
                    it.printStackTrace()
                }.getOrThrow()
        },
        staticGenerator = {
            runCatching { generateStaticAccessor<S>(it) }.onFailure {
                println("Error generating static accessor:")
                it.printStackTrace()
            }.getOrThrow()
        },
//        fullReflect = true
    )

    accessors += ad
    return lazy {
        runCatching {
            preinit()
            GeneratedAccessor(ad.virtualAccessor, ad.staticAccessor)
        }.onFailure {
            println("Failed initializing accessor ${ad.virtualAccessorName}")
            it.printStackTrace()
        }.getOrThrow()
    }
}

/**
 * Interface that a static accessor will implement (and therefore accessors must extend)
 * [T] is the type of the non-static accessor
 */
interface StaticAccessor<T : Any> {
    /**
     * Checks if [obj] is an instance of the type this is being called on
     */
    fun isInstance(obj: Any): Boolean

    /**
     * Casts [obj] to a delegate of this type
     */
    fun cast(obj: Any): T
}

/**
 * Utility for accessors to "smart cast" certain objects
 * That is, an accessor static type checks if the [obj] can safely be wrapped into its virtual accessor
 * The [block] will only execute if this cast succeeds
 */
inline fun <V : Any> StaticAccessor<V>.smartCast(obj: Any, block: (V) -> Unit) {
    if (isInstance(obj)) block(cast(obj))
}

/**
 * Utility to do a [StaticAccessor.cast], but it accepts [InstanceAccessor]s
 */
fun <T : Any> StaticAccessor<T>.castAccessor(obj: InstanceAccessor) = cast(obj.delegate)

/**
 * Utility to do a [StaticAccessor.isInstance] check, but it accepts [InstanceAccessor]s
 */
fun <T : Any> StaticAccessor<T>.accessorIsInstance(obj: InstanceAccessor) = isInstance(obj.delegate)

/**
 * Utility for accessors to "smart cast" [InstanceAccessor]s
 */
inline fun <V : Any> StaticAccessor<V>.smartCast(obj: InstanceAccessor, block: (V) -> Unit) =
    smartCast(obj.delegate, block)

/**
 * Optional interface that an instance accessor is allowed to implement
 * This allows you to get the receiver/delegate of an instance of the accessor
 */
interface InstanceAccessor {
    val delegate: Any
}