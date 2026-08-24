package com.rk.terminal.backend.avf

import java.util.concurrent.atomic.AtomicReference

/**
 * Registry for the running AVF [vm] handle so vsock tabs (which outlive a
 * single backend instance) can open additional vsock connections to the guest.
 * The console-tab backend publishes the machine after `AvfApi.getOrCreate`
 * and clears it when the VM stops.
 */
internal object AvfVmRegistry {
    private val vm = AtomicReference<Any?>()

    fun publish(machine: Any?) {
        vm.set(machine)
    }

    fun acquire(): Any? = vm.get()
}
