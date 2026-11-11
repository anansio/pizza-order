package io.github.kaerbr.pizzaorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * `ACCESS_LOCAL_NETWORK` — mandatory from Android 17 (API 37) on, and the reason a running guest server can
 * silently answer nothing.
 *
 * Below 37 the permission is granted implicitly with `INTERNET`, so asking there would show a prompt for a
 * permission the app doesn't need. The name is spelled out rather than taken from [Manifest.permission]
 * because the constant only exists in SDK 37 jars and this file also compiles against older ones.
 */
object LocalNetworkPermission {

    private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /** False only where the OS actually enforces it and the user hasn't said yes yet. */
    fun isMissing(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 37) return false
        return ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
    }

    val name: String get() = ACCESS_LOCAL_NETWORK
}
