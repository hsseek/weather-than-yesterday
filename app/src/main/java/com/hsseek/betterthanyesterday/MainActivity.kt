package com.hsseek.betterthanyesterday

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.OnTokenCanceledListener
import com.hsseek.betterthanyesterday.location.CoordinatesLatLon
import com.hsseek.betterthanyesterday.location.KoreanGeocoder
import com.hsseek.betterthanyesterday.ui.theme.*
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.viewmodel.Sky
import com.hsseek.betterthanyesterday.viewmodel.Sky.*
import com.hsseek.betterthanyesterday.viewmodel.Sky.Bad.*
import com.hsseek.betterthanyesterday.viewmodel.WeatherViewModel
import kotlinx.coroutines.launch
import java.util.Calendar

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private val viewModel: WeatherViewModel by viewModels { WeatherViewModel.Factory }
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize a launcher to check and request location permission if needed.
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                getLocation()
            } else {
                // Permission not granted after all.
                // TODO: Disable the "Current Location" radio button.
                // Check if we are in a state where the user has denied the permission and
                // selected Don't ask again
                if (!ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                ) {
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", this.packageName, null),
                    ).let {
                        startActivity(it)
                    }
                }
            }
        }

        // Check the location permission and get the location.
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        checkPermissionAndGetLocation()

        // Set the Views.
        setContent {
            BetterThanYesterdayTheme {
                val modifier = Modifier.fillMaxWidth()
                Surface(
                    modifier = modifier,
                    color = MaterialTheme.colors.background
                ) {
                    if (!viewModel.isDataLoaded) {
//                        LandingScreen()
                        SummaryScreen(modifier)  // test
                    } else {
                        SummaryScreen(modifier)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.viewModelScope.launch {
            logCoroutineContext("onCreate")
            viewModel.toastMessage.collect{ event ->
                event.getContentIfNotHandled()?.let { id ->
                    toastOnUiThread(id)
                }
            }
        }
    }

    private fun toastOnUiThread(id: Int) {
        if (id > 0) {
            Log.d(TAG, "Toast res id: $id")
            try {
                runOnUiThread {
                    Toast.makeText(this, getString(id), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Resources.NotFoundException) {
                Log.e(TAG, "Toast res id invalid.")
            }
        }
    }

    private fun refresh() {
        // TODO: Toast "The new data will be released in 23 minutes."
        // TODO: Swipe to refresh(https://stackoverflow.com/questions/67204979/there-is-something-similar-like-swiperefreshlayout-to-pull-to-refresh-in-the-laz)
    }

    private fun checkPermissionAndGetLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            getLocation()
        } else {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            ) {
                // An explanation is required.
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_title_location_permission))
                    .setMessage(getString(R.string.dialog_message_location_permission))
                    .setPositiveButton(getString(R.string.ok)) { _, _ ->
                        //Prompt the user once explanation has been shown
                        requestLocationPermission()
                    }.create().show()
            } else {
                // No explanation needed, we can request the permission.
                requestLocationPermission()
            }
        }
    }

    private fun requestLocationPermission() {
        requestPermissionLauncher.launch((Manifest.permission.ACCESS_COARSE_LOCATION))
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            MY_PERMISSIONS_REQUEST_LOCATION
        )
    }

    private fun getLocation() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location -> setBaseCity(location) }

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                object : CancellationToken() {
                    override fun onCanceledRequested(p0: OnTokenCanceledListener) =
                        CancellationTokenSource().token

                    override fun isCancellationRequested() = false
                }).addOnSuccessListener { location ->
                setBaseCity(location, true)
            }.addOnFailureListener {
                Log.e(TAG, "$it: Cannot retrieve the current location.")
            }
        }
    }

    private fun setBaseCity(location: Location?, isDefinitive: Boolean = false) {
        if (location == null) {
            if (isDefinitive) {
                Log.e(TAG, "FusedLocationProviderClient.getCurrentLocation() returned null.")
            }
        } else {
            val geocoder = KoreanGeocoder(this)
            val cityName = geocoder.getCityName(CoordinatesLatLon(location.latitude, location.longitude))

            if (cityName != null) {
                viewModel.updateLocation(location, cityName, isDefinitive)
            } else {
                Log.e(TAG, "Error retrieving a city name${location.toText()}. (current: $isDefinitive)")
            }
        }
    }

    companion object {
        private const val MY_PERMISSIONS_REQUEST_LOCATION = 99
    }
}

@Composable
private fun SummaryScreen(
    modifier: Modifier = Modifier,
    viewModel: WeatherViewModel = viewModel(),
) {
    val sky: Sky by viewModel.rainfallStatus.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.app_name)) },
                backgroundColor = MaterialTheme.colors.primary,
            )
                 },
        content = { padding ->
            Column (
                modifier = modifier.padding(padding)
                    ) {
                // TODO: Add UI for location.
                CurrentTempComparison(modifier, viewModel.hourlyTempDiff)
                DailyTemperatures(modifier, viewModel.highestTemps, viewModel.lowestTemps)
                RainfallStatus(modifier, sky)
            }
        },
    )
}

@Composable
private fun LandingScreen(
    modifier: Modifier = Modifier,
    viewModel: WeatherViewModel = viewModel(),
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            painterResource(id = R.drawable.logo),
            contentDescription = stringResource(R.string.splash_screen)
        )
        // TODO: Show the splash image about 2 sec at most. After that, dispose the landing screen anyway and compose the main screen with "refreshing"
        LaunchedEffect(true) {
            logCoroutineContext("Launched effect")
            viewModel.requestIfNewAvailable()
        }
    }
}

@Composable
fun CurrentTempComparison(
    modifier: Modifier = Modifier,
    hourlyTempDiff: Int,
) {
    // A descriptive message
    val msg =
        if (hourlyTempDiff > 0) {
            stringResource(R.string.t_diff_2_higher)
        } else if (hourlyTempDiff < 0) {
            stringResource(R.string.t_diff_2_lower)
        } else {
            stringResource(R.string.t_diff_2_same)
        }

    // The color of the temperature difference.
    val color: Color = getTemperatureColor(hourlyTempDiff)
    val diffString: String = if (hourlyTempDiff > 0) {
        "\u25B4 $hourlyTempDiff"
    } else if (hourlyTempDiff < 0) {
        "\u25BE ${-hourlyTempDiff}"
    } else {
        "="
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.t_diff_1),
            style = Typography.caption
        )
        Text(
            text = msg
        )
        Text(
            text = diffString,
            style = Typography.h1,
            color = color
        )
    }
}

@Composable
fun DailyTemperatures(
    modifier: Modifier = Modifier,
    highestTemps: List<Int?>,
    lowestTemps: List<Int?>,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The title
        Text(
            text = stringResource(R.string.daily_temperature_title),
            style = Typography.caption,
        )
        
        // The header row of descriptors
        val days = listOf(
            stringResource(R.string.char_t_yesterday),
            stringResource(R.string.char_t_d0),
            stringResource(R.string.char_t_D1),
            stringResource(R.string.char_t_D2)
        )
        val fraction: Float = 1f/days.size

        LazyRow {
            items(days) {
                Text(
                    text = it,
                    modifier = modifier.fillParentMaxWidth(fraction),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Highest temperatures of the days
        // Font color for highest temperatures
        val warmColor: Color = if (isSystemInDarkTheme()) {
            RedTint700
        } else {
            RedShade400
        }

        // Font color for the highest of highest temperatures
        val hotColor: Color = if (isSystemInDarkTheme()) {
            RedTint500
        } else {
            RedShade100
        }
        val highest = highestTemps.maxOf { it ?: -100 }

        LazyRow(modifier = modifier, horizontalArrangement = Arrangement.SpaceAround) {
            items(highestTemps) {
                if (it != null) {
                    if (it == highest) {
                        Text(
                            text = it.toString(),
                            modifier = modifier.fillParentMaxWidth(fraction),
                            textAlign = TextAlign.Center,
                            color = hotColor,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    } else {
                        Text(
                            text = it.toString(),
                            modifier = modifier.fillParentMaxWidth(fraction),
                            textAlign = TextAlign.Center,
                            color = warmColor,
                        )
                    }
                }
                else {
                    Text(
                        text = stringResource(R.string.null_value),
                        modifier = modifier.fillParentMaxWidth(fraction),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Lowest temperatures of the days
        // Font color for lowest temperatures
        val coolColor: Color = if (isSystemInDarkTheme()) {
            CoolTint700
        } else {
            CoolShade400
        }

        // Font color for the lowest of lowest temperatures
        val coldColor: Color = if (isSystemInDarkTheme()) {
            CoolTint500
        } else {
            CoolShade100
        }
        val lowest = lowestTemps.minOf { it ?: 200 }

        LazyRow(horizontalArrangement = Arrangement.SpaceEvenly) {
            items(lowestTemps) {
                if (it != null) {
                    if (it == lowest) {
                        Text(
                            text = it.toString(),
                            modifier = modifier.fillParentMaxWidth(fraction),
                            textAlign = TextAlign.Center,
                            color = coldColor,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    } else {
                        Text(
                            text = it.toString(),
                            modifier = modifier.fillParentMaxWidth(fraction),
                            textAlign = TextAlign.Center,
                            color = coolColor,
                        )
                    }
                }
                else {
                    Text(
                        text = stringResource(R.string.null_value),
                        modifier = modifier.fillParentMaxWidth(fraction),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun RainfallStatus(
    modifier: Modifier = Modifier,
    sky: Sky,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.rainfall_title),
            style = Typography.caption,
        )
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The id of the visual icon
            val imageId: Int = if (isSystemInDarkTheme()) {
                when (sky) {
                    is Good -> R.drawable.ic_smile_dark
                    is Rainy -> R.drawable.ic_rainy_dark
                    is Snowy -> R.drawable.ic_snow_dark
                    else -> R.drawable.ic_umbrella_dark
                }
            } else {
                when (sky) {
                    is Good -> R.drawable.ic_smile
                    is Rainy -> R.drawable.ic_rainy
                    is Snowy -> R.drawable.ic_snow
                    else -> R.drawable.ic_umbrella
                }
            }

            // Text description
            val qualitative: String
            val quantitative: String
            when (sky) {
                is Good -> {
                    qualitative = stringResource(R.string.today_sunny)
                    quantitative = ""
                }
                is Bad -> {
                    val startingHour: String = getReadableHour(sky.startingHour.hour())
                    val endingHour: String = getReadableHour(sky.endingHour.hour())

                    quantitative = if (startingHour == endingHour) {
                        if (endingHour == stringResource(id = R.string.hour_present)) {
                            stringResource(R.string.hour_soon)
                        } else {
                            "~ $endingHour"
                        }
                    } else {
                        "$startingHour ~ $endingHour"
                    }
                    qualitative = when (sky) {
                        is Rainy -> stringResource(R.string.today_rainy)
                        is Snowy -> stringResource(R.string.today_snowy)
                        else -> stringResource(R.string.today_mixed)
                    }
                }
            }
            Image(
                painter = painterResource(id = imageId),
                contentDescription = stringResource(R.string.weather_status_desc)
            )
            Column {
                Text(text = qualitative)
                if (sky is Bad) {
                    Text(text = quantitative)
                }
            }
        }
    }
}

@Composable
private fun getTemperatureColor(hourlyTempDiff: Int) = if (isSystemInDarkTheme()) {
    when {
        hourlyTempDiff > 8 -> Red000
        hourlyTempDiff == 7 -> RedTint100
        hourlyTempDiff == 6 -> RedTint200
        hourlyTempDiff == 5 -> RedTint300
        hourlyTempDiff == 4 -> RedTint400
        hourlyTempDiff == 3 -> RedTint500
        hourlyTempDiff == 2 -> RedTint600
        hourlyTempDiff == 1 -> RedTint700
        hourlyTempDiff == 0 -> White
        hourlyTempDiff == -1 -> CoolTint700
        hourlyTempDiff == -2 -> CoolTint600
        hourlyTempDiff == -3 -> CoolTint500
        hourlyTempDiff == -4 -> CoolTint400
        hourlyTempDiff == -5 -> CoolTint300
        hourlyTempDiff == -6 -> CoolTint200
        hourlyTempDiff == -7 -> CoolTint100
        else -> Cool000
    }
} else {
    when {
        hourlyTempDiff > 8 -> Red000
        hourlyTempDiff == 7 -> Red000
        hourlyTempDiff == 6 -> Red000
        hourlyTempDiff == 5 -> Red000
        hourlyTempDiff == 4 -> RedShade100
        hourlyTempDiff == 3 -> RedShade200
        hourlyTempDiff == 2 -> RedShade300
        hourlyTempDiff == 1 -> RedShade400
        hourlyTempDiff == 0 -> Black
        hourlyTempDiff == -1 -> CoolShade400
        hourlyTempDiff == -2 -> CoolShade300
        hourlyTempDiff == -3 -> CoolShade200
        hourlyTempDiff == -4 -> CoolShade100
        hourlyTempDiff == -5 -> Cool000
        hourlyTempDiff == -6 -> Cool000
        hourlyTempDiff == -7 -> Cool000
        else -> Cool000
    }
}

@Composable
private fun getReadableHour(hour: Int): String {
    val currentHour = getCurrentKoreanDateTime().get(Calendar.HOUR_OF_DAY)
    return if (hour == currentHour) {
        stringResource(R.string.hour_present)
    } else if (hour == 12) {
        stringResource(id = R.string.hour_noon) // 1200 -> Noon
    } else if (hour < 12) {
        stringResource(R.string.hour_am, hour) // 800 -> 8 AM
    } else {
        stringResource(R.string.hour_pm, hour - 12) // 1300 -> 1 PM
    }
}

@Composable
private fun Menus() {
    // TODO: Share this app / Help(FAQ)
}

@Preview(showBackground = true)
@Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ColdCurrentTemp() {
    BetterThanYesterdayTheme {
        Surface {
            CurrentTempComparison(hourlyTempDiff = -9)
        }
    }
}

@Preview(showBackground = true)
@Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HotCurrentTemp() {
    BetterThanYesterdayTheme {
        Surface {
            CurrentTempComparison(hourlyTempDiff = 9)
        }
    }
}

@Preview(showBackground = true)
@Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SameCurrentTemp() {
    BetterThanYesterdayTheme {
        Surface {
            CurrentTempComparison(hourlyTempDiff = 0)
        }
    }
}

@Preview(showBackground = true)
@Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CharTemps() {
    BetterThanYesterdayTheme {
        Surface {
            DailyTemperatures(
                highestTemps = listOf(25, 22, null, 26),
                lowestTemps = listOf(16, null, -3, 20),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun Sunny() {
    BetterThanYesterdayTheme {
        Surface {
            RainfallStatus(
                sky = Good()
            )
        }
    }
}

@Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Rainy() {
    BetterThanYesterdayTheme {
        Surface {
            RainfallStatus(
                sky = Rainy(300, 1200)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun Snow() {
    BetterThanYesterdayTheme {
        Surface {
            RainfallStatus(
                sky = Snowy(2000, 2300)
            )
        }
    }
}

@Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Mixed() {
    BetterThanYesterdayTheme {
        Surface {
            RainfallStatus(
                sky = Mixed(100, 800)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BetterThanYesterdayTheme {
        SummaryScreen(Modifier.fillMaxWidth())
    }
}