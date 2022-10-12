package com.hsseek.betterthanyesterday

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hsseek.betterthanyesterday.location.KoreanGeocoder
import com.hsseek.betterthanyesterday.ui.getRainfallHourDescription

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import java.util.Calendar

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
        val geo = KoreanGeocoder(appContext)
        val coordinates = geo.getLatLng("서울")
        Log.d("geocoder", "(lat, lon): (${coordinates?.lat}, ${coordinates?.lon})")
        assertEquals(37.24293628036336, coordinates!!.lat, 1e-5)
        assertEquals(131.8668420793528, coordinates.lon, 1e-5)
    }

    @Test
    fun rainfallTest() {
        val tag = "Rainfall"
        val cal = Calendar.getInstance()
        Log.d(tag, "23 ~ 23")
        cal.set(2022, 10, 12, 21, 8)
        Log.d(tag, getRainfallHourDescription(appContext, 2300, 2300, cal))

        cal.set(2022, 10, 12, 23, 8)
        Log.d(tag, getRainfallHourDescription(appContext, 2300, 2300, cal))

        Log.d(tag, "21 ~ 23")
        cal.set(2022, 10, 12, 18, 8)
        Log.d(tag, getRainfallHourDescription(appContext, 2100, 2300, cal))

        cal.set(2022, 10, 12, 21, 8)
        Log.d(tag, getRainfallHourDescription(appContext, 2100, 2300, cal))

        cal.set(2022, 10, 12, 22, 8)
        Log.d(tag, getRainfallHourDescription(appContext, 2100, 2300, cal))

        cal.set(2022, 10, 12, 23, 8)
        Log.d(tag, getRainfallHourDescription(appContext, 2100, 2300, cal))

        Log.d(tag, "22 ~ 22")
        cal.set(2022, 10, 12, 18, 8)
        Log.d(tag, getRainfallHourDescription(appContext, 2200, 2200, cal))

        cal.set(2022, 10, 12, 22, 8)
        Log.d(tag, getRainfallHourDescription(appContext, 2200, 2200, cal))

        cal.set(2022, 10, 12, 23, 8)
        Log.d(tag, getRainfallHourDescription(appContext, 2200, 2200, cal))

        Log.d(tag, "20 ~ 23")
        cal.set(2022, 10, 12, 18, 8)
        Log.d(tag, getRainfallHourDescription(appContext, 2000, 2200, cal))

        cal.set(2022, 10, 12, 20, 8)
        Log.d(tag, getRainfallHourDescription(appContext, 2000, 2200, cal))

        cal.set(2022, 10, 12, 21, 8)
        Log.d(tag, getRainfallHourDescription(appContext, 2000, 2200, cal))

        cal.set(2022, 10, 12, 22, 8)
        Log.d(tag, getRainfallHourDescription(appContext, 2000, 2200, cal))
    }
}