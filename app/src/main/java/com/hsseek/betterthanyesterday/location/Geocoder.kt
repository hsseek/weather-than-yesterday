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
                        for (address in addresses) removeNation(address)
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
                if (addresses != null) {
                    for (address in addresses) removeNation(address)
                }
                onSuccessAddress(addresses)
                logAddress(addresses)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while getFromLocation(...): ", e)
        }
    }

    private fun removeNation(address: Address) {
        val withNation = address.getAddressLine(0)
        address.setAddressLine(0, withNation.replace("대한민국 ", "").trim())
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
    return Regex("(\\S.+)\\s\\d-?\\d").find(address)?.groupValues?.get(1) ?: address
}

/**
 * Returns null or String ending with 동 or 리.
 * [toTrim]: If false, "XX시 OO구 ㅁㅁ동" is returned, for example. If true, "ㅁㅁ동" is returned.
 * It also removes numbering from the name.
 * */
fun getDong(address: String, toTrim: Boolean): String? {
    val regexDong = Regex("(\\S.+\\s)(\\S+?[동리])(?:\\s|$)")
    val regexStreet = Regex("(\\S{2,}거리)(?:\\s|\$)")

    val dong = if (regexDong.containsMatchIn(address) && !regexStreet.containsMatchIn(address)) {
        val regexWithNumber = Regex("(\\S.+\\s)(\\S+?)\\d{1,2}([동리])(?:\\s|\$)")
        if (!regexWithNumber.containsMatchIn(address)) {  // No numbers, no need to modify.
            val matches = regexDong.find(address)
            if (toTrim) {
                matches?.groupValues?.get(2)
            } else {
                matches?.destructured?.toList()?.joinToString("")
            }
        } else {  // A number present
            val regexJae = Regex("(.+)제\\d{1,2}([동리])(?:\\s|\$)")
            if (regexJae.containsMatchIn(address)) {
                val matches = regexDong.find(address)
                // Don't risk removing the number.(e.g. "흥제1동(흥제동)" vs "목제1동(목동)"
                if (toTrim) {
                    matches?.groupValues?.get(2)
                } else {
                    matches?.destructured?.toList()?.joinToString("")
                }
            } else {
                val matches = regexWithNumber.find(address)
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
    } else null
    return dong
}

/**
 * Returns null or String ending with 군, 구, 읍, or 면.
 * [toTrim]: If false, "XX시 OO구" is returned, for example. If true, "OO구" is returned.
 * */
fun getGu(address: String, toTrim: Boolean): String? {
    val regexGu = Regex("(\\S.+\\s)(\\S+?[군구읍면])(?:\\s|$)")
    val gu = if (regexGu.containsMatchIn(address)) {
        val matches = regexGu.find(address)
        if (toTrim) {
            matches?.groupValues?.get(2)
        } else {
            matches?.destructured?.toList()?.joinToString("")
        }
    } else null
    return gu
}

/**
 * Returns null or String ending with "OO시" or "OO도".
 * For "XX도 OO시", "OO시" is returned without "XX도" if [toTrim] is true.
 * */
fun getSi(address: String, toTrim: Boolean): String? {
    val si = if (toTrim) {
        Regex("(\\S+?[시])(?:\\s|$)").find(address)?.groupValues?.get(1)
            ?: Regex("(\\S+?[도])(?:\\s|$)").find(address)?.groupValues?.get(1)
    } else {
        // Greedy to capture "OO도 XX시"
        Regex("(\\S.+[시도])(?:\\s|$)").find(address)?.groupValues?.get(1)
    }
    return si
}

fun getGeneralCityName(address: String): String {
    return if (
        address.endsWith("지역") ||
        address.endsWith("region", ignoreCase = true)
    ) address else {
        address.split(" ").first()  // The most upper class name
    }
}

fun getSuitableAddress(addresses: List<Address>): String {
    for (address in addresses) {  // The first loop looking for 동, 리
        val dong = getDong(address.getAddressLine(0), false)
        if (dong != null) return dong
    }
    for (address in addresses) {  // The second loop looking for 군, 구, 읍, 면
        val gu = getGu(address.getAddressLine(0), false)
        if (gu != null) return gu
    }
    for (address in addresses) {  // The third loop looking for 시, 도
        val si = getSi(address.getAddressLine(0), false)
        if (si != null) return si
    }
    return removeTailingNumbers(addresses[0].getAddressLine(0))
}