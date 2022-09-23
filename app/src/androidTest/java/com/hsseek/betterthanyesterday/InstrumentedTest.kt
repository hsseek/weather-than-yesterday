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
    fun geocoderCityNameTest() {
        val geo = KoreanGeocoder(appContext)
        assertEquals("충청남도 연기군", geo.getCityName(CoordinatesLatLon(36.4800121, 127.2890691)))
        assertEquals("경상북도 울릉군", geo.getCityName(CoordinatesLatLon(37.24293628036336, 131.8668420793528)))
    }

    @Test
    fun geocoderLatLonTest() {
        val geo = KoreanGeocoder(appContext)
        val coordinates = geo.getLatLng("서울")
        Log.d("geocoder", "(lat, lon): (${coordinates?.lat}, ${coordinates?.lon})")
        assertEquals(37.24293628036336, coordinates!!.lat, 1e-5)
        assertEquals(131.8668420793528, coordinates.lon, 1e-5)
    }
}