package com.hsseek.betterthanyesterday

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hsseek.betterthanyesterday.location.CoordinatesLatLon
import com.hsseek.betterthanyesterday.location.KoreanGeocoder
import com.hsseek.betterthanyesterday.location.getSuitableAddress
import com.hsseek.betterthanyesterday.util.DEBUG_FLAG

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import java.util.Calendar
import kotlin.system.measureTimeMillis

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class InstrumentedTest {
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.hsseek.betterthanyesterday", appContext.packageName)
    }

    @Test
    fun geocoderLatLonTest() {
        val tag = "Geocoder"
        val geo = KoreanGeocoder(appContext)
        val maxResult = 20
        val query = "서울"

        val time = measureTimeMillis { geo.updateLatLng(query, maxResult) {} }
        if (DEBUG_FLAG) Log.d(tag, "Done in $time ms")
        // assertEquals(37.24293628036336, coordinate.lat, 1e-5)
        // assertEquals(131.8668420793528, coordinate.lon, 1e-5)

    }

    @Test
    fun geocoderAddress() {
        val tag = "Geocoder"
        val geo = KoreanGeocoder(appContext)
//        val lat = 37.514748555
//        val lon = 126.908006124
        val lat = 37.6227597
        val lon = 127.0776255
        val maxResult = 20

        val time = measureTimeMillis {
            geo.updateAddresses(CoordinatesLatLon(lat, lon), maxResult) { addresses ->
                if (addresses != null) {
                    val suitableAddress = getSuitableAddress(addresses)
                    Log.d(tag, "Address: $suitableAddress")
                } else Log.d(tag, "List null.")
            }
        }
        if (DEBUG_FLAG) Log.d(tag,"Done in $time ms")
    }

    @Test
    fun rainfallTest() {
        val tag = "Rainfall"
        val cal = Calendar.getInstance()
        if (DEBUG_FLAG) Log.d(tag, "Rainfall:23 ~ 23")
        cal.set(2022, 10, 12, 21, 8)
        if (DEBUG_FLAG) Log.d(tag, getRainfallHourDescription(appContext, 2300, 2300, cal))

        cal.set(2022, 10, 12, 23, 8)
        if (DEBUG_FLAG) Log.d(tag, getRainfallHourDescription(appContext, 2300, 2300, cal))

        if (DEBUG_FLAG) Log.d(tag, "Rainfall:21 ~ 23")
        cal.set(2022, 10, 12, 18, 8)
        if (DEBUG_FLAG) Log.d(tag, getRainfallHourDescription(appContext, 2100, 2300, cal))

        cal.set(2022, 10, 12, 21, 8)
        if (DEBUG_FLAG) Log.d(tag, getRainfallHourDescription(appContext, 2100, 2300, cal))

        cal.set(2022, 10, 12, 22, 8)
        if (DEBUG_FLAG) Log.d(tag, getRainfallHourDescription(appContext, 2100, 2300, cal))

        cal.set(2022, 10, 12, 23, 8)
        if (DEBUG_FLAG) Log.d(tag, getRainfallHourDescription(appContext, 2100, 2300, cal))

        if (DEBUG_FLAG) Log.d(tag, "Rainfall:22 ~ 22")
        cal.set(2022, 10, 12, 18, 8)
        if (DEBUG_FLAG) Log.d(tag, getRainfallHourDescription(appContext, 2200, 2200, cal))

        cal.set(2022, 10, 12, 22, 8)
        if (DEBUG_FLAG) Log.d(tag, getRainfallHourDescription(appContext, 2200, 2200, cal))

        cal.set(2022, 10, 12, 23, 8)
        if (DEBUG_FLAG) Log.d(tag, getRainfallHourDescription(appContext, 2200, 2200, cal))

        if (DEBUG_FLAG) Log.d(tag, "Rainfall:20 ~ 23")
        cal.set(2022, 10, 12, 18, 8)
        if (DEBUG_FLAG) Log.d(tag, getRainfallHourDescription(appContext, 2000, 2200, cal))

        cal.set(2022, 10, 12, 20, 8)
        if (DEBUG_FLAG) Log.d(tag, getRainfallHourDescription(appContext, 2000, 2200, cal))

        cal.set(2022, 10, 12, 21, 8)
        if (DEBUG_FLAG) Log.d(tag, getRainfallHourDescription(appContext, 2000, 2200, cal))

        cal.set(2022, 10, 12, 22, 8)
        if (DEBUG_FLAG) Log.d(tag, getRainfallHourDescription(appContext, 2000, 2200, cal))
    }
}