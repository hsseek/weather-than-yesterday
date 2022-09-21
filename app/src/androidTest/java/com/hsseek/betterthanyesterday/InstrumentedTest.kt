package com.hsseek.betterthanyesterday

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hsseek.betterthanyesterday.location.CoordinatesLatLon
import com.hsseek.betterthanyesterday.location.KoreanGeocoder

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

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
    fun geocoderTest() {
        val tag = "Geocoder"
        val geo = KoreanGeocoder(appContext)
        val addr = geo.getAddress(CoordinatesLatLon(37.55, 126.97))
        Log.d(tag, "Address: $addr")
        val coor = geo.getLatLng("합정동")
        coor?.let {
            Log.d(tag, "Lat: ${it.lat} / Lon: ${it.lon}")
        }
    }
}