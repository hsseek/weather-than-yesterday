package com.hsseek.betterthanyesterday

import android.content.Context
import com.hsseek.betterthanyesterday.dummy.DUMMY_LONG_TERM_FORECAST_RAINY
import com.hsseek.betterthanyesterday.dummy.DUMMY_LONG_TERM_FORECAST_SUNNY
import com.hsseek.betterthanyesterday.dummy.DUMMY_SHORT_TERM_FORECAST_RAINY
import com.hsseek.betterthanyesterday.dummy.DUMMY_SHORT_TERM_FORECAST_SNOWY
import com.hsseek.betterthanyesterday.location.*
import com.hsseek.betterthanyesterday.util.KmaHourRoundOff.*
import com.hsseek.betterthanyesterday.util.getKmaBaseTime
import com.hsseek.betterthanyesterday.viewmodel.RainfallStatus
import com.hsseek.betterthanyesterday.viewmodel.RainfallType
import org.junit.Test

import org.junit.Assert.*
import org.junit.Before
import java.util.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */

class UnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun coordinateTest() {
        val coordinates = convertToXy(CoordinatesLatLon(37.567936111111116, 127.02396666666667))
        println("${coordinates.nx} / ${coordinates.ny}")
    }

    @Test
    fun village_time_correct() {
        val cal = Calendar.getInstance()
        cal.set(2022, 8, 16, 2, 5)
        val time = getKmaBaseTime(time = cal, roundOff = VILLAGE)
        assertEquals("20220915", time.date)
        assertEquals("2300", time.hour)
    }

    @Test
    fun noon_time_correct() {
        val cal = Calendar.getInstance()
        cal.set(2022, 8, 16, 23, 11)
        val time = getKmaBaseTime(time = cal, roundOff = NOON)
        assertEquals("20220916", time.date)
        assertEquals("2300", time.hour)
    }

    @Test
    fun rest_of_the_day() {
        val cal = Calendar.getInstance()
        cal.set(2022, 8, 16, 5, 11)

        val longTermBaseTime = getKmaBaseTime(
            time = cal,
            roundOff = VILLAGE,
            isQuickPublish = true,
        )
        val numOfHours: Int = if (longTermBaseTime.hour == "2300") {
            24  // 23:00 of the previous day: whole day's data should be examined.
        } else {
            // Otherwise, only the later hours should be examined.
            (23 - longTermBaseTime.hour.toInt()/100)
        }

        assertEquals(18, numOfHours)
    }

    @Test
    fun is_raining() {
        val primaryItems = DUMMY_SHORT_TERM_FORECAST_SNOWY
        val secondaryItems = DUMMY_LONG_TERM_FORECAST_RAINY

        val rainingHours = arrayListOf<Int>()
        val snowingHours = arrayListOf<Int>()
        val RAIN_TAG = "PTY"

        try {
            val primaryRainfallData = primaryItems.filter { it.category == RAIN_TAG }
            val primaryCoveredHourMax = primaryRainfallData.maxOf { it.fcstTime }
            val secondaryRainfallData = secondaryItems.filter {
                (it.category == RAIN_TAG) and (it.fcstTime > primaryCoveredHourMax)
            }

            for (i in primaryRainfallData + secondaryRainfallData) {
                val status = i.fcstValue.toInt()  // Must be integers of 0 through 7
                if (
                    status == RainfallType.Raining.code ||
                    status == RainfallType.Mixed.code ||
                    status == RainfallType.Shower.code
                ) {
                    rainingHours.add(i.fcstTime)
                } else if (
                    status == RainfallType.Snowing.code
                ) {
                    snowingHours.add(i.fcstTime)
                }
            }

            val rainfallStatus = RainfallStatus(RainfallType.None, null, null)
            rainfallStatus.let {
                it.startHour = (rainingHours + snowingHours).minOrNull()  // Needs an umbrella anyway
                it.endHour = (rainingHours + snowingHours).maxOrNull()
                if (rainingHours.size > 0) {
                    if (snowingHours.size > 0) {
                        it.type = RainfallType.Mixed
                    } else {
                        it.type = RainfallType.Raining
                    }
                } else if (snowingHours.size > 0) {
                    it.type = RainfallType.Snowing
                } else {
                    it.type = RainfallType.None
                }
                println("PTY: ${it.type}\t(${it.startHour} ~ ${it.endHour})")
            }
        } catch (e: Exception) {
            println("$e: Cannot retrieve the short-term rainfall status(PTY).")
        }
    }

    @Test
    fun cityName() {
        val regex = Regex("\\s(.+?[시군])\\s")
        val address = "대한민국 세종특별자치시 중구 서석동 465-2"
        val cityFullName = regex.find(address)?.groupValues?.get(1)
        var cityName: String? = ""
        for (special in listOf("특별시", "광역시", "특별자치")) {
            if (cityFullName!!.contains(special)) {
                cityName = cityFullName.replace(special, "")
                break
            }
        }
        assertEquals("서울", cityName)
    }

    @Test
    fun temp() {
        val emptyArrayList = arrayListOf<Int>()
        println("size: ${emptyArrayList.size}")
        println(emptyArrayList.minOrNull())
    }
}