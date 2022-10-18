package com.hsseek.betterthanyesterday.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import java.util.*

private const val TAG = "Geocoder"

class KoreanGeocoder(context: Context) {
    private val geoCoder = Geocoder(context, Locale.KOREA)

    fun updateLatLng(
        commonName: String,
        maxResult: Int = 6,
        onSuccessLatLon: (List<CoordinatesLatLon>?) -> Unit,
    ) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val geocoderListener = object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        logAddress(addresses)
                        if (addresses.size > 0) {
                            onSuccessLatLon(convertToLatLonList(addresses))
                        } else {
                            Log.e(TAG, "0 result from getFromLocationName(...)")
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
                logAddress(addresses)

                if (addresses != null && addresses.size > 0) {
                    onSuccessLatLon(convertToLatLonList(addresses))
                } else {
                    Log.e(TAG, "0 result from getFromLocationName(...) [Deprecated]")
                    onSuccessLatLon(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while getFromLocationName(...)", e)
        }
    }

    fun updateAddresses(
        position: CoordinatesLatLon,
        maxResult: Int = 6,
        onSuccessAddress: (List<Address>?) -> Unit
    ) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val geocoderListener = object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
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
            val latlon = CoordinatesLatLon(lat = address.latitude, lon = address.longitude)
            if (latLonList.contains(latlon)) {  // CoordinatesLatLon is a data class.
                latLonList.add(latlon)
            }
        }
        return latLonList
    }

    private fun logAddress(addresses: List<Address>?) {
        addresses?.also {
            for (address in it) {
                Log.d(TAG, "Address candidate: ${address.getAddressLine(0)}(${address.latitude}, ${address.latitude})")
            }
        } ?: kotlin.run {
            Log.e(TAG, "Addresses null.")
        }
    }
}