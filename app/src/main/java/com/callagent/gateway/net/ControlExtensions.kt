package com.callagent.gateway.net

import android.content.Context

/**
 * Optional heartbeat fields the paired control server has said it accepts.
 * The server rejects unknown JSON fields, so a gateway only sends an
 * extension after the same server and pairing advertised it, and forgets it
 * when that server later rejects the extended body (for example after a
 * server rollback).
 */
object ControlExtensions {
    private const val PREFS = "control_extensions"

    fun accepts(context: Context, gatewayId: String, controlBaseUrl: String, extension: String): Boolean =
        prefs(context).getStringSet(key(gatewayId, controlBaseUrl), emptySet())?.contains(extension) == true

    fun record(context: Context, gatewayId: String, controlBaseUrl: String, extensions: Set<String>) {
        val key = key(gatewayId, controlBaseUrl)
        val current = prefs(context).getStringSet(key, emptySet()).orEmpty()
        if (current != extensions) prefs(context).edit().putStringSet(key, extensions.toSet()).apply()
    }

    fun forget(context: Context, gatewayId: String, controlBaseUrl: String) {
        prefs(context).edit().remove(key(gatewayId, controlBaseUrl)).apply()
    }

    private fun key(gatewayId: String, controlBaseUrl: String) = "$gatewayId|${controlBaseUrl.trimEnd('/')}"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
