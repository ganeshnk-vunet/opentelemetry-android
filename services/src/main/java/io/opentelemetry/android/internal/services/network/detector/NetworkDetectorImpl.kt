/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.internal.services.network.detector

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.common.internal.features.networkattributes.data.CurrentNetwork
import io.opentelemetry.android.common.internal.features.networkattributes.data.NetworkState
import io.opentelemetry.android.common.internal.features.networkattributes.data.NetworkState.TRANSPORT_CELLULAR
import io.opentelemetry.android.internal.services.network.CarrierFinder
import io.opentelemetry.android.internal.services.network.CurrentNetworkProvider
import io.opentelemetry.android.internal.services.network.getNetworkTypeName
import io.opentelemetry.android.internal.services.network.hasPhoneStatePermission
import io.opentelemetry.android.internal.services.network.hasTelephonyFeature

/**
 * Implementation of NetworkDetector interface.
 * This class is internal and not for public use. Its APIs are unstable and can change at any time.
 */
internal class NetworkDetectorImpl(
    private val context: Context,
) : NetworkDetector {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val carrierFinder = CarrierFinder(context, telephonyManager)

    override fun detectCurrentNetwork(): CurrentNetwork {
        val network = connectivityManager.activeNetwork
        if (network == null) {
            return CurrentNetworkProvider.NO_NETWORK
        }

        val metered = connectivityManager.isActiveNetworkMetered
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        return when {
            capabilities == null -> {
                CurrentNetwork(NetworkState.TRANSPORT_UNKNOWN, metered = metered)
            }

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                buildCellularNetwork(metered)
            }

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                buildNetwork(NetworkState.TRANSPORT_WIFI, metered)
            }

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> {
                buildNetwork(NetworkState.TRANSPORT_VPN, metered)
            }

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                buildNetwork(NetworkState.TRANSPORT_WIRED, metered)
            }

            else -> {
                CurrentNetwork(NetworkState.TRANSPORT_UNKNOWN, metered = metered)
            }
        }
    }

    /**
     * Builds a network for non-cellular networks.
     */
    private fun buildNetwork(
        networkState: NetworkState,
        metered: Boolean,
    ) = CurrentNetwork(networkState, metered = metered)

    /**
     * Builds a cellular network with carrier and subtype information.
     */
    private fun buildCellularNetwork(metered: Boolean): CurrentNetwork {
        val carrier = carrierFinder.get()
        val subType = findSubtype()
        return CurrentNetwork(
            state = TRANSPORT_CELLULAR,
            carrier = carrier,
            subType = subType,
            metered = metered,
        )
    }

    /**
     * Gets the current cellular network subtype with proper permission and API level handling.
     *
     * @return Network subtype name or null if unavailable or permission denied
     */
    @Suppress("MissingPermission")
    private fun findSubtype(): String? {
        if (!(hasTelephonyFeature(context) && hasPhoneStatePermission(context))) {
            Log.w(
                RumConstants.OTEL_RUM_LOG_TAG,
                "Cannot determine network subtype: missing telephony feature or read phone state permission.",
            )
            return null
        }

        return try {
            val networkType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    getNetworkSubTypePostApi24()
                } else {
                    @Suppress("DEPRECATION")
                    telephonyManager.networkType
                }
            getNetworkTypeName(networkType)
        } catch (e: SecurityException) {
            Log.w(
                RumConstants.OTEL_RUM_LOG_TAG,
                "SecurityException when accessing network type",
                e,
            )
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @Suppress("MissingPermission")
    private fun getNetworkSubTypePostApi24(): Int = telephonyManager.dataNetworkType
}
