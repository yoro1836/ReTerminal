package com.rk.terminal.backend.avf

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

internal object AvfApi {
    private const val PACKAGE = "android.system.virtualmachine"
    private val managerClass by lazy { Class.forName("$PACKAGE.VirtualMachineManager") }
    private val configBuilderClass by lazy { Class.forName("$PACKAGE.VirtualMachineConfig\$Builder") }
    private val customBuilderClass by lazy { Class.forName("$PACKAGE.VirtualMachineCustomImageConfig\$Builder") }
    private val diskClass by lazy { Class.forName("$PACKAGE.VirtualMachineCustomImageConfig\$Disk") }
    private val sharedPathClass by lazy {
        Class.forName("$PACKAGE.VirtualMachineCustomImageConfig\$SharedPath")
    }
    private val callbackClass by lazy { Class.forName("$PACKAGE.VirtualMachineCallback") }
    private const val MEDIA_TAG = "reterminal-media"
    private const val MEDIA_SOCKET = "reterminal-media"
    private const val GUEST_UID = 1000
    private const val GUEST_GID = 100
    private const val SHARED_PATH_MASK = 7

    fun manager(context: Context): Any =
        Context::class.java.getMethod("getSystemService", Class::class.java)
            .invoke(context.applicationContext, managerClass)
            ?: error("VirtualMachineManager is unavailable")

    fun capabilities(manager: Any): Int =
        (invoke(manager, "getCapabilities") as? Int) ?: 0

    fun customConfig(
        name: String,
        kernelPath: String,
        initrdPath: String?,
        diskPath: String,
        seedPath: String,
        cmdline: String,
        networkEnabled: Boolean,
        memoryBalloonEnabled: Boolean,
        sharedMediaPath: String?,
    ): Any {
        val builder = customBuilderClass.getDeclaredConstructor().newInstance()
        invoke(builder, "setName", name)
        invoke(builder, "setKernelPath", kernelPath)
        if (!initrdPath.isNullOrBlank()) invoke(builder, "setInitrdPath", initrdPath)
        Regex("""(?:[^\s"]+|"[^"]*")+""").findAll(cmdline).map { it.value }.forEach {
            invoke(builder, "addParam", it)
        }
        val disk = invokeStatic(diskClass, "RWDisk", diskPath)
            ?: error("Unable to construct AVF Debian disk")
        invoke(builder, "addDisk", disk)
        val seed = invokeStatic(diskClass, "RODisk", seedPath)
            ?: error("Unable to construct AVF cloud-init disk")
        invoke(builder, "addDisk", seed)
        runCatching { invoke(builder, "useNetwork", networkEnabled) }
            .recoverCatching { invoke(builder, "setNetworkSupported", networkEnabled) }
        invoke(builder, "useAutoMemoryBalloon", memoryBalloonEnabled)


        if (sharedMediaPath != null) {
            val uid = android.os.Process.myUid()
            val sharedPathConstructor = sharedPathClass.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 8 ||
                    it.parameterTypes.size == 9 ||
                    it.parameterTypes.size == 10
            }?.apply { isAccessible = true }
                ?: error("Unable to find AVF shared path constructor")
            val sharedPathArgs = when (sharedPathConstructor.parameterTypes.size) {
                8 -> arrayOf<Any?>(
                    sharedMediaPath,
                    uid,
                    uid,
                    GUEST_UID,
                    GUEST_GID,
                    SHARED_PATH_MASK,
                    MEDIA_TAG,
                    MEDIA_SOCKET,
                )
                9 -> arrayOf<Any?>(
                    sharedMediaPath,
                    uid,
                    uid,
                    GUEST_UID,
                    GUEST_GID,
                    SHARED_PATH_MASK,
                    MEDIA_TAG,
                    MEDIA_SOCKET,
                    "",
                )
                10 -> arrayOf<Any?>(
                    sharedMediaPath,
                    uid,
                    uid,
                    GUEST_UID,
                    GUEST_GID,
                    SHARED_PATH_MASK,
                    MEDIA_TAG,
                    MEDIA_SOCKET,
                    false,
                    "",
                )
                else -> error("Unsupported AVF shared path constructor")
            }
            val sharedPath = sharedPathConstructor.newInstance(*sharedPathArgs)
            invoke(builder, "addSharedPath", sharedPath)
        }

        return invoke(builder, "build") ?: error("Unable to build custom image config")
    }


    fun vmConfig(context: Context, customConfig: Any, memoryBytes: Long, cpuTopology: Int): Any {
        val builder = configBuilderClass.getDeclaredConstructor(Context::class.java).newInstance(context)
        invoke(builder, "setProtectedVm", false)
        invoke(builder, "setMemoryBytes", memoryBytes)
        invoke(builder, "setCpuTopology", cpuTopology)
        invoke(builder, "setDebugLevel", 1)
        invoke(builder, "setConsoleInputDevice", "hvc0")
        invoke(builder, "setConnectVmConsole", false)
        invoke(builder, "setVmOutputCaptured", true)
        runCatching { invoke(builder, "setVmConsoleInputSupported", true) }
        invoke(builder, "setCustomImageConfig", customConfig)
        return invoke(builder, "build") ?: error("Unable to build VM config")
    }

    fun getOrCreate(manager: Any, name: String, config: Any): Any {
        val vm = invoke(manager, "getOrCreate", name, config)
            ?: error("VirtualMachineManager.getOrCreate returned null")
        return runCatching {
            invoke(vm, "setConfig", config)
            vm
        }.getOrElse {
            runCatching { invoke(manager, "delete", name) }
            invoke(manager, "create", name, config)
                ?: error("VirtualMachineManager.create returned null")
        }
    }

    fun callback(onError: (Int, String?) -> Unit, onStopped: (Int) -> Unit): Any {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "onError" -> onError(args?.getOrNull(1) as? Int ?: -1, args?.getOrNull(2) as? String)
                "onStopped", "onDied" -> onStopped(args?.getOrNull(1) as? Int ?: -1)
                "toString" -> "ReTerminalAvfCallback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
            }
            null
        }
        return Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass), handler)
    }

    fun setCallback(vm: Any, executor: Executor, callback: Any) {
        invoke(vm, "setCallback", executor, callback)
    }

    fun consoleOutput(vm: Any): InputStream =
        invoke(vm, "getConsoleOutput") as? InputStream
            ?: error("AVF console output is unavailable")

    fun consoleInput(vm: Any): OutputStream =
        invoke(vm, "getConsoleInput") as? OutputStream
            ?: error("AVF console input is unavailable")

    fun connectVsock(vm: Any, port: Long): ParcelFileDescriptor =
        invoke(vm, "connectVsock", port) as? ParcelFileDescriptor
            ?: error("AVF vsock connection is unavailable")

    fun run(vm: Any) { invoke(vm, "run") }
    fun stop(vm: Any) { invoke(vm, "stop") }

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val method = findMethod(target.javaClass, name, args)
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    private fun invokeStatic(type: Class<*>, name: String, vararg args: Any?): Any? {
        val method = findMethod(type, name, args)
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    private fun findMethod(type: Class<*>, name: String, args: Array<out Any?>): Method {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name && parametersMatch(method.parameterTypes, args)
            }?.let { return it }
            current = current.superclass
        }
        return type.methods.firstOrNull { method ->
            method.name == name && parametersMatch(method.parameterTypes, args)
        } ?: throw NoSuchMethodException("${type.name}.$name/${args.size}")
    }

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        return types.indices.all { index ->
            val value = args[index] ?: return@all !types[index].isPrimitive
            boxed(types[index]).isAssignableFrom(value.javaClass)
        }
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
        java.lang.Integer.TYPE -> Int::class.javaObjectType
        java.lang.Long.TYPE -> Long::class.javaObjectType
        else -> type
    }
}
