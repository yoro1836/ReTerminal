package com.rk.terminal.vm

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.net.Inet4Address

/**
 * The guest's DNS server follows the phone's current network: public Wi-Fi
 * often blocks outside resolvers (1.1.1.1), so the guest asks the server the
 * network itself hands out. Given to umnet at boot (--dns) and, when the phone
 * changes networks, set in the running guest (resolvectl over root ssh).
 */
object NetDns {
    const val FALLBACK = "1.1.1.1"

    @Volatile private var applied: String? = null
    @Volatile private var watching = false

    private fun pick(lp: LinkProperties?): String? =
        lp?.dnsServers?.firstOrNull { it is Inet4Address }?.hostAddress

    /** IPv4 DNS server of the phone's default network, or null. */
    fun current(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        pick(cm.getLinkProperties(cm.activeNetwork))
    }.getOrNull()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("debian_vm", Context.MODE_PRIVATE)
    /** Settings > Debian VM > "Use the phone's DNS"; off = always 1.1.1.1. */
    fun follow(ctx: Context) = prefs(ctx).getBoolean("follow_dns", false)
    fun setFollow(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("follow_dns", on).apply()

    /** What the next boot announces to the guest. */
    fun forBoot(ctx: Context): String =
        ((if (follow(ctx)) current(ctx) else null) ?: FALLBACK).also { applied = it }

    /** Point the running guest at [dns] if it isn't already. */
    fun apply(ctx: Context, dns: String?) {
        if (!follow(ctx) || dns == null || dns == applied || DebianVm.state.value != "ready") return
        Thread {
            val (rc, _) = DebianVm.rootSh(ctx, "resolvectl dns vec0 $dns && resolvectl flush-caches", 10)
            if (rc == 0) applied = dns
        }.start()
    }

    /** Follow default-network changes for the life of the process. */
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val ctx = context.applicationContext
        runCatching {
            ctx.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = apply(ctx, pick(lp))
                })
        }.onFailure { watching = false }
    }
}
