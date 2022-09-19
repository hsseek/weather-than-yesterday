package com.hsseek.betterthanyesterday

import com.hsseek.betterthanyesterday.util.KmaHourRoundOff.*
import com.hsseek.betterthanyesterday.util.getKmaBaseTime
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
}