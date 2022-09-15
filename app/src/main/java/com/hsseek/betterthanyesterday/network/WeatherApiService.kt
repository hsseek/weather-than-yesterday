package com.hsseek.betterthanyesterday.network

import com.hsseek.betterthanyesterday.SERVICE_KEY
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

private const val BASE_URL = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/"
private val retrofit = Retrofit.Builder()
    .addConverterFactory(GsonConverterFactory.create())
    .baseUrl(BASE_URL)
    .build()

// TODO: Use Calendar
private val currentDateFormatted = "20220915"
private val currentTimeFormatted = "0200"


interface WeatherApiService {
    @GET("getVilageFcst?serviceKey=$SERVICE_KEY")
    suspend fun getWeather(
        @Query("dataType") dataType: String = "JSON",
        @Query("numOfRows") numOfRows: Int = 12,
        @Query("pageNo") pageNo: Int = 1,
        @Query("base_date") baseDate: String = currentDateFormatted,
        @Query("base_time") baseTime: String = currentTimeFormatted,
        @Query("nx") nx: Int = 60,
        @Query("ny") ny: Int = 127,
    ): Response<KmaResponse>
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