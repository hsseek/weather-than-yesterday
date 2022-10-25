package com.hsseek.betterthanyesterday.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import com.hsseek.betterthanyesterday.util.DEBUG_FLAG
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
                                if (DEBUG_FLAG) Log.d(TAG, "0 result from getFromLocationName(...)")
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
                        if (DEBUG_FLAG) Log.d(TAG, "0 result from getFromLocationName(...) [Deprecated]")
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
                if (DEBUG_FLAG) Log.d(TAG, "Lat, Lon candidate: ${address.latitude}, ${address.longitude}")
            }
        } ?: kotlin.run {
            Log.e(TAG, "Addresses null.")
        }
    }

    private fun logAddress(addresses: List<Address>?) {
        addresses?.also {
            for (address in it) {
                if (DEBUG_FLAG) Log.d(TAG, "Address candidate: ${address.getAddressLine(0)}(${address.latitude}, ${address.longitude})")
            }
        } ?: kotlin.run {
            Log.e(TAG, "Addresses null.")
        }
    }
}

fun getCityName(address: String): String? {
    return Regex("(\\S+?[시])(?:\\s|$)").find(address)?.groupValues?.get(1)
        ?: Regex("(\\S+?[도])(?:\\s|$)").find(address)?.groupValues?.get(1)
}

fun getGeneralCityName(address: String): String {
    return removeNation(address)  // Always 대한민국, so omit it.
        .split(" ").first()  // The most upper class name
}

fun String?.removeSpecialCitySuffix(): String? {
    this?.let {
        if (Regex("시(?:\\s|$)").containsMatchIn(this)) {
            for (special in listOf("특별시", "광역시", "특별자치시")) {
                if (this.contains(special)) {
                    return this.replace(special, "")  // e.g. "서울특별시" -> "서울"
                }
            }
        }
        return this
    }
    return null
}

fun removeTailingNumbers(address: String): String {
    val modifiedAddress = removeNation(address)
    return Regex("(\\S.+)\\s\\d-?\\d").find(modifiedAddress)?.groupValues?.get(1) ?: modifiedAddress
}

/**
 * [toTrim] is true to return "OO동", "OO리" only, false to return the whole address.
 * */
fun getSuitableAddress(
    address: String,
    includeSi: Boolean,
    toTrim: Boolean,
): String? {
    val modifiedAddress = removeNation(address)
    val regexDong = Regex("(\\S.+\\s)(\\S+?[동리])(?:\\s|$)")
    val regexGu = Regex("(\\S.+\\s)(\\S+?[군구읍면])(?:\\s|$)")

    val suitableAddress = if (regexDong.containsMatchIn(modifiedAddress)) {
        val regexWithNumber = Regex("(\\S.+\\s)(\\S+?)\\d{1,2}([동리])(?:\\s|\$)")
        if (!regexWithNumber.containsMatchIn(modifiedAddress)) {  // No numbers, no need to modify.
            val matches = regexDong.find(modifiedAddress)
            if (toTrim) {
                matches?.groupValues?.get(2)
            } else {
                matches?.destructured?.toList()?.joinToString("")
            }
        } else {  // A number present
            val regexJae = Regex("(.+)제\\d{1,2}([동리])(?:\\s|\$)")
            if (regexJae.containsMatchIn(modifiedAddress)) {
                val matches = regexDong.find(modifiedAddress)
                // Don't risk removing the number.(e.g. "흥제1동(흥제동)" vs "목제1동(목동)"
                if (toTrim) {
                    matches?.groupValues?.get(2)
                } else {
                    matches?.destructured?.toList()?.joinToString("")
                }
            } else {
                val matches = regexWithNumber.find(modifiedAddress)
                // We can safely remove the number.
                if (toTrim) {
                    try {
                        val groups = matches?.groupValues
                        groups?.get(2) + groups?.get(3)
                    } catch (e: Exception) {
                        if (DEBUG_FLAG) Log.w(TAG, "Cannot process 'O제1동', $e")
                        null
                    }
                } else {
                    matches?.destructured?.toList()?.joinToString("")
                }
            }
        }
    } else if (regexGu.containsMatchIn(modifiedAddress)) {
        val matches = regexGu.find(modifiedAddress)
        if (toTrim) {
            matches?.groupValues?.get(2)
        } else {
            matches?.destructured?.toList()?.joinToString("")
        }
    } else {
        if (includeSi) {
            if (toTrim) null  // [시도] is not accepted.
            else {
                // Greedy to capture "OO도 XX시"
                Regex("(\\S.+[시도])(?:\\s|$)").find(modifiedAddress)?.groupValues?.get(1)
            }
        } else null
    }
    if (DEBUG_FLAG) Log.d(TAG, "Suitable address: $modifiedAddress -> $suitableAddress")
    return suitableAddress
}

private fun removeNation(address: String): String {
    val nation = "대한민국"
    return if (address.startsWith(nation)) {
        address.replace("$nation ", "")
    } else address
}