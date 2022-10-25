package com.hsseek.betterthanyesterday

import com.hsseek.betterthanyesterday.dummy.DUMMY_LONG_TERM_FORECAST_RAINY
import com.hsseek.betterthanyesterday.dummy.DUMMY_SHORT_TERM_FORECAST_SNOWY
import com.hsseek.betterthanyesterday.location.*
import com.hsseek.betterthanyesterday.network.ForecastResponse
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.util.KmaHourRoundOff.*
import com.hsseek.betterthanyesterday.viewmodel.*
import org.junit.Test

import org.junit.Assert.*
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
        val time = getKmaBaseTime(cal = cal, roundOff = Village)
        assertEquals("20220915", time.date)
        assertEquals("2300", time.hour)
    }

    @Test
    fun rest_of_the_day() {
        val cal = Calendar.getInstance()
        cal.set(2022, 8, 16, 5, 11)

        val longTermBaseTime = getKmaBaseTime(
            cal = cal,
            roundOff = Village,
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
    fun yesterdayBaseTime() {
        val cal = Calendar.getInstance()
        cal.set(2022, 9, 19, 2, 20)
        val yesterdayCal = getYesterdayVillageCalendar(cal = cal)
        val yesterdayVillageBaseTime = getKmaBaseTime(cal = yesterdayCal, roundOff = Village)
        assertEquals(yesterdayVillageBaseTime.date, "20221017")
        assertEquals(yesterdayVillageBaseTime.hour, "2300")
    }

    @Test
    fun is_raining() {
        val RAIN_TAG = "PTY"
        val todayHourlyData = DUMMY_SHORT_TERM_FORECAST_SNOWY
        val futureData = DUMMY_LONG_TERM_FORECAST_RAINY

        val today = 20220914
        val primaryCoveredHourMax: Int? = todayHourlyData.maxOfOrNull { it.fcstTime }

        // Remove duplicate data according to the priority (More recent data is preferred.)
        val hourlyRainfallData = todayHourlyData.filter { it.category == RAIN_TAG }
        val futureTodayRainfallData: List<ForecastResponse.Item> = futureData.filter {
            (it.category == RAIN_TAG) and (it.fcstDate == today) and (it.fcstTime > (primaryCoveredHourMax ?: 0))
        }
        val rainfallData: List<ForecastResponse.Item> = hourlyRainfallData + futureTodayRainfallData

        // Process the organized data.
        val rainingHours = arrayListOf<Int>()
        val snowingHours = arrayListOf<Int>()
        for (i in rainfallData) {
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

        val _rainfallStatus: Sky

        // Finally, update the variable.
        val hours = rainingHours + snowingHours
        if (rainingHours.size == 0) {
            if (snowingHours.size == 0) {
                // No raining, no snowing
                _rainfallStatus = Sky.Good
            } else {
                // No raining, but snowing
                _rainfallStatus = Sky.Bad.Snowy(snowingHours.min(), snowingHours.max())
            }
        } else {  // Raining
            if (snowingHours.size == 0) {
                _rainfallStatus = Sky.Bad.Rainy(rainingHours.min(), rainingHours.max())
            } else {  // Raining + Snowing
                _rainfallStatus = Sky.Bad.Mixed(hours.min(), hours.max())
            }
        }

        println("PTY: ${_rainfallStatus::class.simpleName}\t(${hours.minOrNull()} ~ ${hours.maxOrNull()})")
    }
}