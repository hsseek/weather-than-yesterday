package com.hsseek.betterthanyesterday.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import com.hsseek.betterthanyesterday.util.LOCATION_TAG
import java.util.*

private const val TAG = "Geocoder"

class KoreanGeocoder(context: Context) {
    private val geoCoder = Geocoder(context, Locale.KOREA)

    fun getLatLng(commonName: String): CoordinatesLatLon? {
        val list = geoCoder.getFromLocationName(commonName, 1)

        return if (list != null && list.size > 0) {
            val addressLatLng = list[0]
            CoordinatesLatLon(
                lat = addressLatLng.latitude,
                lon = addressLatLng.longitude
            )
        } else {
            Log.e(TAG, "Cannot retrieve the corresponding lat/lon.")
            null
        }
    }

    fun getAddresses(position: CoordinatesLatLon, maxResult: Int = 3): List<Address>? {
        return try {
            val addresses = geoCoder.getFromLocation(position.lat, position.lon, maxResult)
            for (address in addresses) {
                Log.d(LOCATION_TAG, "Address candidate: ${address.getAddressLine(0)}")
            }
            addresses
        } catch (e: Exception) {
            Log.e(TAG, "$e: Cannot retrieve the corresponding address.", e)
            null
        }
    }
}