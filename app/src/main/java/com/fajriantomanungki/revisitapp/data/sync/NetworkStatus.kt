package com.fajriantomanungki.revisitapp.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Pemeriksaan jaringan sekali pakai untuk memberi umpan balik yang jelas. */
object NetworkStatus {

    fun isConnected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
