package com.solartweaks.engine.util

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.TraceClassVisitor
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Context for building a set of [ClassFinder]s, and subsequently transforming classes with them
 * Can accept new classes being loaded. Can be installed using [registerWith]
 * If [debug] is `true` then a [TraceClassVisitor] will be used when a class gets transformed
 */
class FinderContext(private val debug: Boolean = false) {
    private val finders = mutableListOf<ClassFinder>()
    private var instrumentation: Instrumentation? = null

    /**
     * Allows you apply a finder on a [ClassNode].
     * If [clazz] is null, transformation will not work
     */
    fun byNode(
        node: ClassNode,
        clazz: Class<*>? = null,
        requireMatch: Boolean = false,
        block: ClassContext.() -> Unit
    ): ClassFinder {
        require(clazz == null || node.name == clazz.internalName) { "node and clazz do not match!" }

        val finder = ClassFinder(ClassContext().also(block))
        when (finder.offer(node)) {
            ClassFinder.NoTransformRequest -> {
                if (clazz != null) {
                    finders += finder

                    runCatching { instrumentation?.retransformClasses(clazz) }.onFailure {
                        println("Failed to retransform classnode on demand:")
                        it.printStackTrace()
                    }

                    finders -= finder // to avoid concurrent modifications
                }
            }
            ClassFinder.Skip -> error("Newly initialized ClassFinder shouldn't skip!")
            is ClassFinder.TransformRequest ->
                error("ClassFinder shouldn't return a TransformRequest when transform = false")
            ClassFinder.NoMatch -> if (requireMatch) error("Finder for ${node.name} didn't match")
            else -> {
                /* we don't really care */
            }
        }

        return finder
    }

    /**
     * Allows you apply a finder on a [ClassNode].
     */
    fun byNode(
        node: ClassNode,
        classLoader: ClassLoader,
        requireMatch: Boolean = false,
        block: ClassContext.() -> Unit
    ) = byNode(node, classLoader.forName(node.name.replace('/', '.')), requireMatch, block)

    /**
     * Allows you to register a [ClassFinder], defining matchers with
     * a [ClassContext] [block]
     */
    fun findClass(block: ClassContext.() -> Unit) =
        ClassFinder(ClassContext().also(block)).also { finders += it }

    // Requests finders to visit the class and maybe request transformation
    private fun offer(node: ClassNode, transform: Boolean) = finders.map { it.offer(node, transform) }

    private fun offer(node: ClassNode, name: String, transform: Boolean = false) =
        runCatching { offer(node, transform) }.onFailure {
            println("Failed to offer class $name")
            it.printStackTrace()
        }

    /**
     * Registers this finding context with an instrumentation instance.
     * That is, it goes over all loaded classes and offers it to finders,
     * as well as add a hook to classloading.
     */
    fun registerWith(inst: Instrumentation) {
        // Save the instrumentation for later use
        require(instrumentation == null) { "This is not the first time registering!" }
        instrumentation = inst

        // Register transformer
        inst.addTransformer(object : ClassFileTransformer {
            override fun transform(
                loader: ClassLoader,
                className: String,
                classBeingRedefined: Class<*>?,
                protectionDomain: ProtectionDomain,
                classfileBuffer: ByteArray
            ): ByteArray? {
                // Stop if this class is a system class
                // (this prevents circular constructs and incorrect transformation)
                if (isSystemClass(className.replace('/', '.'))) return null

                // Find the class node
                val node = classfileBuffer.asClassNode()

                // Offer this class
                val result = offer(node, className, transform = true).getOrNull() ?: return null

                // Filter out all transform requests
                val transformRequests = result.filterIsInstance<ClassFinder.TransformRequest>()

                // If no transform requests, end the transformation
                if (transformRequests.isEmpty()) return null

                // Find out if frames should be expanded
                val shouldExpand = transformRequests.any { it.shouldExpand }

                // Find all method transforms
                val transforms = transformRequests.flatMap { it.transforms }

                // Don't transform when no transforms were yielded
                if (transforms.isEmpty()) return null

                if (debug) {
                    val prefix = if (classBeingRedefined == null) "Transforming" else "Retransforming"
                    println("$prefix \"$className\" (processing ${transforms.size} transforms)...")
                }

                return try {
                    node.transformDefault(transforms, classfileBuffer, loader, shouldExpand, debug = debug)
                } catch (e: Exception) {
                    println("Failed to transform class $className:")
                    e.printStackTrace()

                    // Return null because failure
                    null
                }
            }
        })
    }
}

/**
 * A [ClassFinder] is an entry point for
 * - Accessing a class, defined by a [ClassContext]
 * - Accessing methods, defined by [MethodContext]s
 * - Accessing fields, defined by [FieldContext]s
 */
class ClassFinder(internal val context: ClassContext) {
    private var value: ClassNode? = null
    private var ranFoundHooks = false
    private val foundHooks = mutableListOf<(ClassNode) -> Unit>()
    val methods = MethodsFinder(this)
    val fields = FieldsFinder(this)

    internal fun offer(node: ClassNode, transform: Boolean = false): OfferResult {
        // If we are not transforming, and we already have a value, skip early
        if (!transform && value != null) return Skip

        // If the node name does not match with a potential current value, skip
        if (value != null && value?.name != node.name) return Skip

        // First, validate the top-level class matchers
        if (!context.matches(node)) return NoMatch

        // Extract all method/field data
        val methodData = node.methodData
        val fieldData = node.fieldData

        // Then, do the methods
        val foundMethods = context.methodsContext.methods.map { (name, ctx) ->
            val method = methodData
                .find { ctx.matches(it) }
                ?: return@offer NoMatch

            methods.offer(name, method)
            method to ctx
        }

        // Lastly, fields
        context.fieldsContext.fields.forEach { (name, ctx) ->
            val field = fieldData
                .find { ctx.matches(it) }
                ?: return@offer NoMatch

            fields.offer(name, field)
        }

        // Found it!
        value = node

        // Also handle all found hooks
        if (!ranFoundHooks) {
            ranFoundHooks = true
            (foundHooks + context.foundHooks).forEach {
                runCatching { it(node) }.onFailure {
                    it.printStackTrace()
                    println("Found hook for class ${node.name} was unsuccessful!")
                }
            }
        }

        // Small optimization
        if (!transform) return NoTransformRequest

        // Find all class transforms
        val classTransformations = context.transformations.flatMap { ClassTransformContext(node).also(it).transforms }

        // Find all method transform contexts
        val transformContexts = foundMethods.flatMap { (data, ctx) ->
            ctx.transformations.map { MethodTransformContext(data).also(it) }
        }

        // Find out if the frames should be expanded
        val shouldExpand = transformContexts.any { it.shouldExpandFrames }

        // Convert all method transformations to class transformations
        val methodTransformations = transformContexts.map { it.asClassTransform() }

        // Accumulate all transformations and return the result
        val allTransformations = classTransformations + methodTransformations

        // Return correct transform result
        return if (allTransformations.isEmpty()) NotInterested
        else TransformRequest(allTransformations, shouldExpand)
    }

    sealed class OfferResult

    // Returned when transform = true and this finder is interested in transforming
    class TransformRequest(val transforms: List<ClassTransform>, val shouldExpand: Boolean) : OfferResult()

    // Returned when the class was found but the finder is not interested in transforming
    object NotInterested : OfferResult()

    // Returned when the class didn't match this finder
    object NoMatch : OfferResult()

    // Returned when transform = false, but there was interest to transform
    object NoTransformRequest : OfferResult()

    // Returned when transform = false and the finder already resolved a class
    object Skip : OfferResult()

    /**
     * Equivalent of [assume]
     */
    operator fun invoke() = assume()

    /**
     * Assumes this [ClassFinder] has found the requested class/methods/fields
     */
    fun assume() = value ?: error("Didn't find the correct class, assumption failed!")

    /**
     * Returns the found value or null
     */
    fun nullable() = value

    /**
     * Returns if [value] is not null
     */
    fun hasValue() = value != null

    /**
     * Adds a listener for when the class has been found
     */
    fun onFound(handler: (ClassNode) -> Unit) {
        foundHooks += handler
    }
}

/**
 * Accessor for finding [MethodData]. Can be delegated to by properties
 */
class MethodsFinder(private val finder: ClassFinder) {
    val methods = finder.context.methodsContext.methods.mapValues { ElementFinder<MethodData>() }

    internal fun offer(name: String, node: MethodData) {
        methods[name]?.value = node
    }

    operator fun getValue(thisRef: Nothing?, property: KProperty<*>): ElementFinder<MethodData> {
        val name = property.name
        return methods[name] ?: error("A method finder with name $name does not exist!")
    }

    /**
     * Returns a delegate property by method [name]
     */
    operator fun get(name: String) = ReadOnlyProperty<Nothing?, ElementFinder<MethodData>> { _, _ ->
        methods[name] ?: error("A method finder with name $name does not exist!")
    }

    /**
     * Returns a delegate property that finds a method by a given matcher, lazily
     */
    fun late(block: MethodContext.() -> Unit) = object : ReadOnlyProperty<Nothing?, ElementFinder<MethodData>> {
        private val ef = ElementFinder<MethodData>()
        override fun getValue(thisRef: Nothing?, property: KProperty<*>): ElementFinder<MethodData> {
            if (ef.value == null) {
                val context = MethodContext().also(block)
                ef.value = finder().methodData.find { context.matches(it) }
            }

            return ef
        }
    }
}

/**
 * Accessor for finding [FieldData]. Can be delegated to by properties
 */
class FieldsFinder(private val finder: ClassFinder) {
    val fields = finder.context.fieldsContext.fields.mapValues { ElementFinder<FieldData>() }

    internal fun offer(name: String, node: FieldData) {
        fields[name]?.value = node
    }

    operator fun getValue(thisRef: Nothing?, property: KProperty<*>): ElementFinder<FieldData> {
        val name = property.name
        return fields[name] ?: error("A field finder with name $name does not exist!")
    }

    /**
     * Returns a delegate property by field [name]
     */
    operator fun get(name: String) = ReadOnlyProperty<Nothing?, ElementFinder<FieldData>> { _, _ ->
        fields[name] ?: error("A field finder with name $name does not exist!")
    }

    /**
     * Returns a delegate property that finds a field by a given matcher, lazily
     */
    fun late(block: FieldContext.() -> Unit) = object : ReadOnlyProperty<Nothing?, ElementFinder<FieldData>> {
        private val ef = ElementFinder<FieldData>()
        override fun getValue(thisRef: Nothing?, property: KProperty<*>): ElementFinder<FieldData> {
            if (ef.value == null) {
                val context = FieldContext().also(block)
                ef.value = finder().fieldData.find { context.matches(it) }
            }

            return ef
        }
    }
}

/**
 * Container class to find a specific element, [T]. Like rusts `Option<T>`, but simpler
 */
class ElementFinder<T>(internal var value: T? = null) {
    /**
     * Equivalent of [assume]
     */
    operator fun invoke() = assume()

    /**
     * Assumes this [ElementFinder] has found the requested element
     */
    fun assume() = value ?: error("Didn't find the correct element, assumption failed!")

    /**
     * Returns the found value or null
     */
    fun nullable() = value
}

/**
 * Marker class that allows for defining english-like matchers on the constant pool
 */
object ConstantsMarker

/**
 * Marker class that allows for defining english-like matchers on the strings of a class
 */
object StringsMarker

/**
 * Marker class that allows for defining english-like matchers on a class
 */
object ClassNodeMarker

/**
 * Marker class that allows for defining english-like matchers on a method
 */
object MethodNodeMarker

/**
 * Marker class that allows for defining english-like matchers on a field
 */
object FieldNodeMarker

/**
 * Marker class that allows for defining english-like matchers on arguments of a method
 */
object ArgumentsMarker

/**
 * Marker class that allows for referencing the class that is being matched
 */
object SelfMarker

/**
 * Class Matcher predicate
 */
typealias ClassMatcher = (ClassNode) -> Boolean

/**
 * Method Matcher predicate
 */
typealias MethodMatcher = (MethodData) -> Boolean

/**
 * Field Matcher predicate
 */
typealias FieldMatcher = (FieldData) -> Boolean

/**
 * Method Invocation Matcher predicate
 */
typealias CallMatcher = (MethodInsnNode) -> Boolean

/**
 * Marker annotation for defining the finding DSL
 */
@DslMarker
annotation class FindingDSL

/**
 * DSL for defining matchers for a [ClassNode]
 */
@FindingDSL
class ClassContext {
    /**
     * Allows you to reference infix functions of [ConstantsMarker]
     */
    val constants = ConstantsMarker

    /**
     * Allows you to reference infix functions of [StringsMarker]
     */
    val strings = StringsMarker

    /**
     * Allows you to reference infix functions of [ClassNodeMarker]
     */
    val node = ClassNodeMarker

    private val matchers = mutableListOf<ClassMatcher>()
    internal val methodsContext = MethodsContext()
    internal val fieldsContext = FieldsContext()
    internal val transformations = mutableListOf<ClassTransformContext.() -> Unit>()
    internal val foundHooks = mutableListOf<(ClassNode) -> Unit>()

    /**
     * Matches the constant pool of a class (must contain [cst])
     */
    infix fun ConstantsMarker.has(cst: Any?) {
        matchers += { cst in it.constants }
    }

    /**
     * Matches the strings of the constant pool of a class (must contain [s])
     */
    infix fun StringsMarker.has(s: String) {
        matchers += { s in it.strings }
    }

    /**
     * Matches the strings of the constant pool of a class (a string must contain [part])
     */
    infix fun StringsMarker.hasPartial(part: String) {
        matchers += { node -> node.strings.any { it.contains(part) } }
    }

    /**
     * Matches the strings of the constant pool of a class (some string must match [matcher])
     */
    infix fun StringsMarker.some(matcher: (String) -> Boolean) {
        matchers += { it.strings.any(matcher) }
    }

    /**
     * Matches the class to a given predicate
     */
    infix fun ClassNodeMarker.match(matcher: ClassMatcher) {
        matchers += matcher
    }

    /**
     * Matches if the class is an enum
     */
    fun ClassNodeMarker.isEnum() {
        matchers += { it.superName == "java/lang/Enum" }
    }

    /**
     * Matches if given access [flag] is present
     */
    infix fun ClassNodeMarker.access(flag: Int) {
        matchers += { it.access and flag != 0 }
    }

    /**
     * Matches if the class is named [name]
     */
    infix fun ClassNodeMarker.named(name: String) {
        matchers += { it.name == name }
    }

    /**
     * Matches if the class extends [name]
     */
    infix fun ClassNodeMarker.extends(name: String) {
        matchers += { it.superName == name }
    }

    /**
     * Matches if the class implements [name]
     */
    infix fun ClassNodeMarker.implements(name: String) {
        matchers += { name in it.interfaces }
    }

    /**
     * Matches if this class is `static`
     */
    fun ClassNodeMarker.isStatic() = access(Opcodes.ACC_STATIC)

    /**
     * Matches if this class is `private`
     */
    fun ClassNodeMarker.isPrivate() = access(Opcodes.ACC_PRIVATE)

    /**
     * Matches if this class is `final`
     */
    fun ClassNodeMarker.isFinal() = access(Opcodes.ACC_FINAL)

    /**
     * Matches if this class is an `interface`
     */
    fun ClassNodeMarker.isInterface() = access(Opcodes.ACC_INTERFACE)

    /**
     * Matches if this class is `public`
     */
    fun ClassNodeMarker.isPublic() = access(Opcodes.ACC_PUBLIC)

    /**
     * Matches if this class is `abstract`
     */
    fun ClassNodeMarker.isAbstract() = access(Opcodes.ACC_ABSTRACT)

    /**
     * Define methods using a [MethodContext]
     */
    fun methods(block: MethodsContext.() -> Unit) = methodsContext.block()

    /**
     * Define fields using a [FieldContext]
     */
    fun fields(block: FieldsContext.() -> Unit) = fieldsContext.block()

    /**
     * Allows you to transform this method when all matchers match
     */
    fun transform(block: ClassTransformContext.() -> Unit) {
        transformations += block
    }

    /**
     * Allows you to register found hooks.
     * That is, when the [ClassFinder] has found a class, a given [hook] will execute
     */
    fun onFound(hook: (ClassNode) -> Unit) {
        foundHooks += hook
    }

    /**
     * Checks if this [ClassContext] matches a given [node]
     */
    fun matches(node: ClassNode) = matchers.all { it(node) }
}

/**
 * DSL for defining [MethodContext]s
 */
@FindingDSL
class MethodsContext {
    internal val methods = mutableMapOf<String, MethodContext>()

    /**
     * Defines a new method for this context
     */
    operator fun String.invoke(block: MethodContext.() -> Unit) {
        methods += this to MethodContext().also(block)
    }

    private var unnamedCounter = 0
        get() = field++

    /**
     * Defines an unnamed method for this context
     */
    // Whoops unnamed methods are also named
    fun unnamedMethod(block: MethodContext.() -> Unit) = "__unnamed$unnamedCounter"(block)
}

/**
 * DSL for defining [FieldContext]s
 */
@FindingDSL
class FieldsContext {
    internal val fields = mutableMapOf<String, FieldContext>()

    /**
     * Defines a new field for this context
     */
    operator fun String.invoke(block: FieldContext.() -> Unit) {
        fields += this to FieldContext().also(block)
    }
}

/**
 * DSL for defining matchers for [MethodData]
 */
@FindingDSL
class MethodContext {
    /**
     * Allows you to reference infix functions of [ConstantsMarker]
     */
    val constants = ConstantsMarker

    /**
     * Allows you to reference infix functions of [StringsMarker]
     */
    val strings = StringsMarker

    /**
     * Allows you to reference infix functions of [MethodNodeMarker]
     */
    val method = MethodNodeMarker

    /**
     * Allows you to reference infix functions of [ArgumentsMarker]
     */
    val arguments = ArgumentsMarker

    /**
     * Allows you to reference infix functions of [SelfMarker]
     */
    val self = SelfMarker

    private val matchers = mutableListOf<MethodMatcher>()
    internal val transformations = mutableListOf<MethodTransformContext.() -> Unit>()

    /**
     * Matches the constant pool of a method (must contain [cst])
     */
    infix fun ConstantsMarker.has(cst: Any?) {
        matchers += { cst in it.method.constants }
    }

    /**
     * Matches the strings of the constant pool of a method (must contain [s])
     */
    infix fun StringsMarker.has(s: String) {
        matchers += { s in it.method.strings }
    }

    /**
     * [has] but with more strings
     */
    infix fun StringsMarker.has(strings: List<String>) {
        matchers += { it.method.strings.containsAll(strings) }
    }

    /**
     * Matches the strings of the constant pool of a method (a string must contain [part])
     */
    infix fun StringsMarker.hasPartial(part: String) {
        matchers += { (_, node) -> node.strings.any { it.contains(part) } }
    }

    /**
     * Matches the strings of the constant pool of a class (some string must match [matcher])
     */
    infix fun StringsMarker.some(matcher: (String) -> Boolean) {
        matchers += { it.method.strings.any(matcher) }
    }

    /**
     * Matches the method for a given [matcher]
     */
    infix fun MethodNodeMarker.match(matcher: MethodMatcher) {
        matchers += matcher
    }

    /**
     * Matches when the method has a given [descriptor]
     */
    infix fun MethodNodeMarker.hasDesc(descriptor: String) {
        matchers += { it.method.desc == descriptor }
    }

    /**
     * Matches if this method calls a method defined by the [CallContext]
     */
    infix fun MethodNodeMarker.calls(matcher: CallContext.() -> Unit) {
        val context = CallContext().also(matcher)
        matchers += { (_, method) -> method.calls { context.matches(it) } }
    }

    /**
     * Matches if this method calls a method defined by a [MethodDescription]
     */
    infix fun MethodNodeMarker.calls(desc: MethodDescription) {
        matchers += { data -> data.asDescription().isSimilar(desc) }
    }

    /**
     * Matches if argument index [n] == [type]
     */
    @Suppress("unused") // for consistency
    fun ArgumentsMarker.nth(n: Int, type: Type) {
        matchers += { it.method.arguments.getOrNull(n) == type }
    }

    /**
     * Equivalent of `arguments.nth(n, type)`
     */
    operator fun ArgumentsMarker.set(n: Int, type: Type) {
        matchers += { it.method.arguments.getOrNull(n) == type }
    }

    /**
     * Matches if any argument is a given [type]
     */
    infix fun ArgumentsMarker.has(type: Type) {
        matchers += { type in it.method.arguments }
    }

    /**
     * Matches if this method has no arguments
     */
    fun ArgumentsMarker.hasNone() = count(0)

    /**
     * Matches if this method has a given [amount] of arguments
     */
    infix fun ArgumentsMarker.count(amount: Int) {
        matchers += { it.method.arguments.size == amount }
    }

    /**
     * Matches if the list of [arguments] matches exactly
     */
    infix fun ArgumentsMarker.hasExact(arguments: List<Type>) {
        matchers += { it.method.arguments.zip(arguments).all { (a, b) -> a.internalName == b.internalName } }
    }

    /**
     * Matches if this method returns [type]
     */
    infix fun MethodNodeMarker.returns(type: Type) {
        matchers += { it.method.returnType == type }
    }

    /**
     * Matches if this method returns typeof [node]
     */
    infix fun MethodNodeMarker.returns(node: ClassNode) = returns(node.type)

    /**
     * Matches if this method returns itself
     */
    infix fun MethodNodeMarker.returns(@Suppress("UNUSED_PARAMETER") self: SelfMarker) {
        matchers += { (owner, method) -> method.returnType.internalName == owner.name }
    }

    /**
     * Matches if this method returns a primitive
     */
    fun MethodNodeMarker.returnsPrimitive() {
        matchers += { (_, m) -> m.returnType.isPrimitive }
    }

    /**
     * Checks if this method has a given access [flag]
     */
    infix fun MethodNodeMarker.access(flag: Int) {
        matchers += { it.method.access and flag != 0 }
    }

    /**
     * Matches if this method is `static`
     */
    fun MethodNodeMarker.isStatic() = access(Opcodes.ACC_STATIC)

    /**
     * Matches if this method is not `static`
     */
    fun MethodNodeMarker.isVirtual() {
        matchers += { it.method.access and Opcodes.ACC_STATIC == 0 }
    }

    /**
     * Matches if this method is `private`
     */
    fun MethodNodeMarker.isPrivate() = access(Opcodes.ACC_PRIVATE)

    /**
     * Matches if this method is `final`
     */
    fun MethodNodeMarker.isFinal() = access(Opcodes.ACC_FINAL)

    /**
     * Matches if this method is `protected`
     */
    fun MethodNodeMarker.isProtected() = access(Opcodes.ACC_PROTECTED)

    /**
     * Matches if this method is `public`
     */
    fun MethodNodeMarker.isPublic() = access(Opcodes.ACC_PUBLIC)

    /**
     * Matches if this method is `abstract`
     */
    fun MethodNodeMarker.isAbstract() = access(Opcodes.ACC_ABSTRACT)

    /**
     * Checks if a method has a given [name]
     */
    infix fun MethodNodeMarker.named(name: String) {
        matchers += { it.method.name == name }
    }

    /**
     * Checks if a method is a constructor
     * That is, the name is equal to <init>
     */
    fun MethodNodeMarker.isConstructor() = named("<init>")

    /**
     * Checks if a method is a static initializer
     * That is, the name is equal to <clinit>
     */
    fun MethodNodeMarker.isStaticInit() = named("<clinit>")

    /**
     * Allows you to transform this method when all matchers match
     */
    fun transform(block: MethodTransformContext.() -> Unit) {
        transformations += block
    }

    /**
     * Defines a matcher that is reevaluated every time
     * Useful when referencing other class finders
     */
    fun matchLazy(block: MethodContext.() -> Unit) {
        matchers += { MethodContext().apply(block).matches(it) }
    }

    /**
     * Checks if this [MethodContext] matches given [data]
     */
    fun matches(data: MethodData) = matchers.all { it(data) }
}

/**
 * DSL for defining matchers for a [MethodInsnNode]
 */
@FindingDSL
class CallContext {
    /**
     * Allows you to access infix functions of [MethodNodeMarker]
     */
    val method = MethodNodeMarker

    /**
     * Allows you to access infix functions of [ArgumentsMarker]
     */
    val arguments = ArgumentsMarker
    private val matchers = mutableListOf<CallMatcher>()

    /**
     * Matches when the called method has a given [name]
     */
    infix fun MethodNodeMarker.named(name: String) {
        matchers += { it.name == name }
    }

    /**
     * Matches if argument [n] is typeof [type]
     */
    operator fun ArgumentsMarker.set(n: Int, type: Type) {
        matchers += { Type.getArgumentTypes(it.desc).getOrNull(n) == type }
    }

    /**
     * Matches if any argument is of type [type]
     */
    infix fun ArgumentsMarker.has(type: Type) {
        matchers += { type in Type.getArgumentTypes(it.desc) }
    }

    /**
     * Matches if this method has no arguments
     */
    fun ArgumentsMarker.hasNone() = count(0)

    /**
     * Matches if this method has a given [amount] of arguments
     */
    infix fun ArgumentsMarker.count(amount: Int) {
        matchers += { Type.getArgumentTypes(it.desc).size == amount }
    }

    /**
     * Matches if the list of [arguments] matches exactly
     */
    infix fun ArgumentsMarker.hasExact(arguments: List<Type>) {
        matchers += {
            Type.getArgumentTypes(it.desc).zip(arguments)
                .all { (a, b) -> a.internalName == b.internalName }
        }
    }

    /**
     * Matches if this method call returns [type]
     */
    infix fun MethodNodeMarker.returns(type: Type) {
        matchers += { Type.getReturnType(it.desc) == type }
    }

    /**
     * Matches if this method call is owned by [type]
     */
    infix fun MethodNodeMarker.ownedBy(type: Type) {
        matchers += { it.owner == type.internalName }
    }

    /**
     * Matches if the given [matcher] matches the method call
     */
    infix fun MethodNodeMarker.match(matcher: CallMatcher) {
        matchers += matcher
    }

    /**
     * Matches if the method is invoked with a given [opcode]
     */
    infix fun MethodNodeMarker.calledWith(opcode: Int) {
        matchers += { it.opcode == opcode }
    }

    /**
     * Checks if this [CallContext] matches a given [call]
     */
    fun matches(call: MethodInsnNode) = matchers.all { it(call) }
}

/**
 * DSL for defining matchers for [FieldData]
 */
@FindingDSL
class FieldContext {
    /**
     * Allows you to access infix functions of [FieldNodeMarker]
     */
    val node = FieldNodeMarker
    private val matchers = mutableListOf<FieldMatcher>()

    /**
     * Matches if this field is typeof [type]
     */
    infix fun FieldNodeMarker.isType(type: Type) {
        matchers += { it.field.desc == type.descriptor }
    }

    /**
     * Matches if this field is typeof [desc]
     */
    infix fun FieldNodeMarker.isType(desc: String) {
        matchers += { it.field.desc == desc }
    }

    /**
     * Matches if this field is typeof the given [node]
     */
    infix fun FieldNodeMarker.isType(node: ClassNode) = isType(node.type)

    /**
     * Matches if this `public static final` field has a given [constant] as value
     */
    infix fun FieldNodeMarker.staticValue(constant: Any?) {
        matchers += { it.field.value == constant }
    }

    /**
     * Matches if this field has given access [flags]
     */
    infix fun FieldNodeMarker.access(flags: Int) {
        matchers += { it.field.access and flags != 0 }
    }

    infix fun FieldNodeMarker.named(name: String) {
        matchers += { it.field.name == name }
    }

    /**
     * Matches if this field is `static`
     */
    fun FieldNodeMarker.isStatic() = access(Opcodes.ACC_STATIC)

    /**
     * Matches if this field is `private`
     */
    fun FieldNodeMarker.isPrivate() = access(Opcodes.ACC_PRIVATE)

    /**
     * Matches if this field is `final`
     */
    fun FieldNodeMarker.isFinal() = access(Opcodes.ACC_FINAL)

    /**
     * Matches if this field is `public`
     */
    fun FieldNodeMarker.isPublic() = access(Opcodes.ACC_PUBLIC)

    /**
     * Matches if this field is `protected`
     */
    fun FieldNodeMarker.isProtected() = access(Opcodes.ACC_PROTECTED)

    /**
     * Matches on a given [matcher]
     */
    infix fun FieldNodeMarker.match(matcher: (FieldData) -> Boolean) {
        matchers += matcher
    }

    /**
     * Checks if this [FieldContext] matches given [data]
     */
    fun matches(data: FieldData) = matchers.all { it(data) }
}

/**
 * Wraps an owner-method pair into a datastructure for convenience
 */
data class MethodData(val owner: ClassNode, val method: MethodNode)

/**
 * Wraps an owner-field pair into a datastructure for convenience
 */
data class FieldData(val owner: ClassNode, val field: FieldNode)

/**
 * Utility to find a method with a [MethodContext] for a [ClassNode]
 */
inline fun ClassNode.methodByFinder(block: MethodContext.() -> Unit): MethodData? {
    val context = MethodContext().apply(block)
    return methodData.find { context.matches(it) }
}

/**
 * Utility to find all method that match a [MethodContext] for a [ClassNode]
 */
inline fun ClassNode.allMethodsByFinder(block: MethodContext.() -> Unit): List<MethodData> {
    val context = MethodContext().apply(block)
    return methodData.filter { context.matches(it) }
}