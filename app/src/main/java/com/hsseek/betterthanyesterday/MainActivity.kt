package com.hsseek.betterthanyesterday

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.text.style.TextAlign
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.OnTokenCanceledListener
import com.hsseek.betterthanyesterday.location.CoordinatesLatLon
import com.hsseek.betterthanyesterday.location.KoreanGeocoder
import com.hsseek.betterthanyesterday.ui.theme.BetterThanYesterdayTheme
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
                        LandingScreen()
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
fun CurrentTempComparison(modifier: Modifier, hourlyTempDiff: Int) {
    val m1 = stringResource(R.string.t_diff_1)
    val m2 =
        if (hourlyTempDiff > 0) {
            stringResource(R.string.t_diff_2_higher)
        } else if (hourlyTempDiff < 0) {
            stringResource(R.string.t_diff_2_lower)
        } else {
            stringResource(R.string.t_diff_2_same)
        }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = m1 + m2)
        Text(text = hourlyTempDiff.toString())
    }
}

@Composable
fun DailyTemperatures(modifier: Modifier, highestTemps: List<Int?>, lowestTemps: List<Int?>) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
                    textAlign = TextAlign.Center)
            }
        }
        LazyRow(modifier = modifier, horizontalArrangement = Arrangement.SpaceAround) {
            items(highestTemps) {
                if (it != null) {
                    Text(
                        text = it.toString(),
                        modifier = modifier.fillParentMaxWidth(fraction),
                        textAlign = TextAlign.Center)
                }
                else {
                    Text(
                        text = stringResource(R.string.null_value),
                        modifier = modifier.fillParentMaxWidth(fraction),
                        textAlign = TextAlign.Center)
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.SpaceEvenly) {
            items(lowestTemps) {
                if (it != null) {
                    Text(
                        text = it.toString(),
                        modifier = modifier.fillParentMaxWidth(fraction),
                        textAlign = TextAlign.Center
                    )
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
fun RainfallStatus(modifier: Modifier, sky: Sky) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The id of the visual icon
        val imageId: Int = when (sky) {
            is Good -> R.drawable.ic_smile_24
            is Rainy -> R.drawable.ic_rainy_24
            is Snowy -> R.drawable.ic_snow_24
            else -> R.drawable.ic_round_umbrella_24
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

                quantitative = "$startingHour ~ $endingHour"
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

@Composable
private fun getReadableHour(startingHour: Int): String {
    val currentHour = getCurrentKoreanDateTime().get(Calendar.HOUR_OF_DAY)
    return if (startingHour == currentHour) {
        stringResource(R.string.hour_present)
    } else if (startingHour == 12) {
        stringResource(id = R.string.hour_noon) // 1200 -> Noon
    } else if (startingHour < 12) {
        stringResource(R.string.hour_am, startingHour) // 800 -> 8 AM
    } else {
        stringResource(R.string.hour_pm, startingHour - 12) // 1300 -> 1 PM
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BetterThanYesterdayTheme {
        SummaryScreen(Modifier.fillMaxWidth())
    }
}