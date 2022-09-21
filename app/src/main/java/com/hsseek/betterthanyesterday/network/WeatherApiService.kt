package com.hsseek.betterthanyesterday.network

import com.hsseek.betterthanyesterday.SERVICE_KEY
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

private const val BASE_URL = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/"
private const val DATA_TYPE = "JSON"
private val retrofit = Retrofit.Builder()
    .addConverterFactory(GsonConverterFactory.create())
    .baseUrl(BASE_URL)
    .build()


interface WeatherApiService {
    /**
     * Fetches extensive weather conditions of 1 hour after the [baseTime] and afterwards.
     * Available after every hour 10 at most.
     * (e.g. Conditions of 03:00, 04:00, ... are available since 02:10.)
     * Expires after 72 hours.
     * ForecastResponse.items.size is 12 for regular Items(each hour),
     * while two Items have size of 13 as they contain additional TMX and TMN.
     * Therefore, a data set of span of a day consists of 12 * 24 + 2 = 290 rows.
     * @param
     * [baseTime] The time at which the data created. Must be "0200", "0500", ..., "2300".
     * It DIFFERS from the expected conditions of each hour.(e.g. Data created at 02:00 expecting 32 degrees at 13:00)
     * [baseDate] The date at which the data created. Must be in yyyyMMdd format.
     * */
    @GET("getVilageFcst?serviceKey=$SERVICE_KEY")
    suspend fun getVillageWeather(
        @Query("dataType") dataType: String = DATA_TYPE,
        @Query("numOfRows") numOfRows: Int = 290,
        @Query("pageNo") pageNo: Int = 1,
        @Query("base_date") baseDate: String,
        @Query("base_time") baseTime: String,
        @Query("nx") nx: Int = 60,
        @Query("ny") ny: Int = 127,
    ): Response<ForecastResponse>

    /**
     * Fetches the weather conditions of [baseTime].
     * Available after every hour 40 at most.
     * (e.g. Conditions of 03:00 are guaranteed to be available since 03:40, might be available at 03:28.)
     * Expires after 24 hours.
     * Note that ObservationResponse.items.size is always 8.
     * (Returns up to 8 items even if [numOfRows] > 8)
     * @param
     * [baseTime] The time at which the data created. Must be "0200", "0500", ..., "2300".
     * It DIFFERS from the expected conditions of each hour.(e.g. Data created at 02:00 expecting 32 degrees at 13:00)
     * [baseDate] The date at which the data created. Must be in yyyyMMdd format.
     * */
    @GET("getUltraSrtNcst?serviceKey=$SERVICE_KEY")
    suspend fun getObservedWeather(
        @Query("dataType") dataType: String = DATA_TYPE,
        @Query("numOfRows") numOfRows: Int = 8,
        @Query("pageNo") pageNo: Int = 1,
        @Query("base_date") baseDate: String,
        @Query("base_time") baseTime: String,
        @Query("nx") nx: Int = 60,
        @Query("ny") ny: Int = 127,
    ): Response<ObservationResponse>

    /**
     * Fetches extensive weather conditions of 1 ~ 6 hours after the [baseTime].
     * Available after every hour 45 at most.
     * (e.g. Conditions of 03:00 are guaranteed to be available since 03:45, might be available at 03:28.)
     * (10 categories) * (6 hours) = 60 items for each [baseTime].
     * Unlike other responses, items with the same category are present consecutively
     * (i.e. T1H of 01:00, 02:00, ..., 06:00, then REH of 01:00, ...).
     * @param
     * [baseTime] The time at which the data created. Must be "0200", "0500", ..., "2300".
     * It DIFFERS from the expected conditions of each hour.(e.g. Data created at 02:00 expecting 32 degrees at 13:00)
     * [baseDate] The date at which the data created. Must be in yyyyMMdd format.
     * */
    @GET("getUltraSrtFcst?serviceKey=$SERVICE_KEY")
    suspend fun getShortTermWeather(
        @Query("dataType") dataType: String = DATA_TYPE,
        @Query("numOfRows") numOfRows: Int = 8,
        @Query("pageNo") pageNo: Int = 1,
        @Query("base_date") baseDate: String,
        @Query("base_time") baseTime: String,
        @Query("nx") nx: Int = 60,
        @Query("ny") ny: Int = 127,
    ): Response<ForecastResponse>
}

/*
* The call to create() function on a Retrofit object is expensive
* and the app needs only one instance of Retrofit API service.
* So, expose the service to the rest of the app using object declaration.
* */
object WeatherApi {
    // lazy initialization to avoid unnecessary use of resources
    val service: WeatherApiService by lazy {
        retrofit.create(WeatherApiService::class.java)
    }
}