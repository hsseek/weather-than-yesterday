package com.hsseek.betterthanyesterday.network

import android.util.Log
import com.google.gson.GsonBuilder
import com.hsseek.betterthanyesterday.util.DEBUG_FLAG
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

private const val BASE_URL = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/"
private const val DATA_TYPE_JSON = "JSON"
private const val TAG = "WeatherApiService"
private const val RESPONSE_BODY_LOG_BYTE_MAX: Long = 40 * 1024
const val NETWORK_TIMEOUT_MIN = 2_400L
const val NETWORK_ADDITIONAL_TIMEOUT = 1_200L
const val NETWORK_TIMEOUT_MAX = 7_200L
const val NETWORK_PAUSE = 150L
const val NETWORK_MAX_RETRY = 8

private val interceptor = KmaResponseInterceptor()

private var client = OkHttpClient.Builder()
    .readTimeout(NETWORK_TIMEOUT_MAX * 4, TimeUnit.MILLISECONDS)
    .connectTimeout(NETWORK_TIMEOUT_MAX * 4, TimeUnit.MILLISECONDS)
    .addInterceptor(interceptor)
    .build()

private val gson = GsonBuilder().setLenient().create()
private val retrofit = Retrofit.Builder()
    .baseUrl(BASE_URL)
    .client(client)
    .addConverterFactory(GsonConverterFactory.create(gson))
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
        @Query("dataType") dataType: String = DATA_TYPE_JSON,
        @Query("numOfRows") numOfRows: Int,
        @Query("pageNo") pageNo: Int = 1,
        @Query("base_date") baseDate: String,
        @Query("base_time") baseTime: String,
        @Query("nx") nx: Int,
        @Query("ny") ny: Int,
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
    @Suppress("unused")
    @GET("getUltraSrtNcst?serviceKey=$SERVICE_KEY")
    suspend fun getObservedWeather(
        @Query("dataType") dataType: String = DATA_TYPE_JSON,
        @Query("numOfRows") numOfRows: Int = 8,
        @Query("pageNo") pageNo: Int = 1,
        @Query("base_date") baseDate: String,
        @Query("base_time") baseTime: String,
        @Query("nx") nx: Int,
        @Query("ny") ny: Int,
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
        @Query("dataType") dataType: String = DATA_TYPE_JSON,
        @Query("numOfRows") numOfRows: Int,
        @Query("pageNo") pageNo: Int = 1,
        @Query("base_date") baseDate: String,
        @Query("base_time") baseTime: String,
        @Query("nx") nx: Int,
        @Query("ny") ny: Int,
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
    private val responseBuilder = interceptor.responseBuffer

    fun getResponseString(): String = responseBuilder.toString()
    fun clearResponseString() {
        if (responseBuilder.isNotEmpty()) {
            if (DEBUG_FLAG) Log.d(TAG, "Weather response cleared.")
            responseBuilder.setLength(0)
        }
    }
}

private class KmaResponseInterceptor: Interceptor {
    // StringBuilder is not synchronized and throws ArrayOutOfIndex Exception on Okhttp logging occasionally.
    // Use StringBuffer instead.
    val responseBuffer = StringBuffer()
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val response: okhttp3.Response = chain.proceed(request)

        try {
            val t1 = System.currentTimeMillis()
            if (DEBUG_FLAG) Log.d(TAG, "-> Sending request ${request.url} on ${chain.connection()} ${request.headers}")
            val url = response.request.url.toString().replace(SERVICE_KEY, "")

            val t2 = System.currentTimeMillis()
            val responseSummary = "\n<- Received response in ${(t2 - t1)} ms\n" +
                    "${url}\n" +
                    "${response.peekBody(RESPONSE_BODY_LOG_BYTE_MAX).string()}\n"
            if (DEBUG_FLAG) Log.d(TAG, responseSummary)
            responseBuffer.append(responseSummary)
        } catch (e: Exception) {
            Log.e(TAG, "Error while intercept(...).")
        }
        return response
    }
}