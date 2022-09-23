package com.hsseek.betterthanyesterday.location

import android.content.Context
import android.location.Geocoder
import android.util.Log
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

    fun getCityName(position: CoordinatesLatLon): String? {
        val address = try {
            geoCoder.getFromLocation(position.lat, position.lon, 1)
                .first().getAddressLine(0)
        } catch (e: Exception) {
            Log.e(TAG, "$e: Cannot retrieve the corresponding address.")
            e.printStackTrace()
            null
        }
        return if (address == null) null else {
            val regex = Regex("\\s(.+?[시군])\\s")
            val cityFullName = regex.find(address)?.groupValues?.get(1)
            if (cityFullName == null) {
                null
            } else {
                for (special in listOf("특별시", "광역시", "특별자치")) {
                    if (cityFullName.contains(special)) {
                        return cityFullName.replace(special, "")  // e.g. "서울특별시" -> "서울"
                    }
                }
                return cityFullName
            }
        }
    }
}