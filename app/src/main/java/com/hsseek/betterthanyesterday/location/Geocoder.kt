package com.hsseek.betterthanyesterday.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import com.hsseek.betterthanyesterday.util.logElapsedTime
import java.util.*

private const val TAG = "Geocoder"

class KoreanGeocoder(context: Context) {
    private val geoCoder = Geocoder(context, Locale.KOREA)

    fun updateLatLng(
        commonName: String,
        maxResult: Int = 8,
        onSuccessLatLon: (List<CoordinatesLatLon>?) -> Unit,
    ) {
        val start = System.currentTimeMillis()
        if (commonName.isNotBlank()) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val geocoderListener = object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            logLatLon(addresses)
                            logElapsedTime(TAG, "Update lat/long", start)
                            if (addresses.size > 0) {
                                onSuccessLatLon(convertToLatLonList(addresses))
                            } else {
                                Log.d(TAG, "0 result from getFromLocationName(...)")
                                onSuccessLatLon(null)
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            super.onError(errorMessage)
                            Log.e(TAG, "Error while getFromLocationName(...): $errorMessage")
                        }
                    }
                    geoCoder.getFromLocationName(commonName, maxResult, geocoderListener)
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geoCoder.getFromLocationName(commonName, maxResult)
                    logLatLon(addresses)
                    logElapsedTime(TAG, "Update lat/long", start)

                    if (addresses != null && addresses.size > 0) {
                        onSuccessLatLon(convertToLatLonList(addresses))
                    } else {
                        Log.d(TAG, "0 result from getFromLocationName(...) [Deprecated]")
                        onSuccessLatLon(null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error while getFromLocationName(...)", e)
                onSuccessLatLon(null)
            }
        } else {
            // Blank query
            onSuccessLatLon(null)
        }
    }

    fun updateAddresses(
        position: CoordinatesLatLon,
        maxResult: Int = 8,
        onSuccessAddress: (List<Address>?) -> Unit
    ) {
        val start = System.currentTimeMillis()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val geocoderListener = object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        logElapsedTime(TAG, "Update addresses", start)  // Not RDS, takes < 50 ms.
                        onSuccessAddress(addresses)
                        logAddress(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        super.onError(errorMessage)
                        Log.e(TAG, "Error while getFromLocation(...): $errorMessage")
                    }
                }
                geoCoder.getFromLocation(position.lat, position.lon, maxResult, geocoderListener)
            } else {
                @Suppress("DEPRECATION")
                val addresses = geoCoder.getFromLocation(position.lat, position.lon, maxResult)
                logElapsedTime(TAG, "Update addresses", start)
                onSuccessAddress(addresses)
                logAddress(addresses)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while getFromLocation(...): ", e)
        }
    }

    private fun convertToLatLonList(addresses: List<Address>): List<CoordinatesLatLon> {
        val latLonList: MutableList<CoordinatesLatLon> = mutableListOf()
        for (address in addresses) {
            val ll = CoordinatesLatLon(lat = address.latitude, lon = address.longitude)
            if (!latLonList.contains(ll)) {  // CoordinatesLatLon is a data class.
                latLonList.add(ll)
            }
        }
        return latLonList
    }

    private fun logLatLon(addresses: List<Address>?) {
        addresses?.also {
            for (address in it) {
                Log.d(TAG, "Lat, Lon candidate: ${address.latitude}, ${address.longitude}")
            }
        } ?: kotlin.run {
            Log.e(TAG, "Addresses null.")
        }
    }

    private fun logAddress(addresses: List<Address>?) {
        addresses?.also {
            for (address in it) {
                Log.d(TAG, "Address candidate: ${address.getAddressLine(0)}(${address.latitude}, ${address.longitude})")
            }
        } ?: kotlin.run {
            Log.e(TAG, "Addresses null.")
        }
    }
}