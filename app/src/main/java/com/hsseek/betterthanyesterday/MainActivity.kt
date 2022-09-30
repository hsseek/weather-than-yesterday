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
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.OnTokenCanceledListener
import com.hsseek.betterthanyesterday.location.CoordinatesLatLon
import com.hsseek.betterthanyesterday.location.KoreanGeocoder
import com.hsseek.betterthanyesterday.ui.theme.BetterThanYesterdayTheme
import com.hsseek.betterthanyesterday.util.logCoroutineContext
import com.hsseek.betterthanyesterday.util.toText
import com.hsseek.betterthanyesterday.viewmodel.WeatherViewModel
import kotlinx.coroutines.launch

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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    val showLandingScreen = rememberSaveable { mutableStateOf(true) }
                    if (showLandingScreen.value) {
                        LandingScreen(
                            viewModel = viewModel,
                            onFinish = { showLandingScreen.value = false },
                        )
                    } else {
                        SummaryScreen()
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
private fun MainScreen() {

}

@Composable
private fun SummaryScreen(
    modifier: Modifier = Modifier,
) {
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
                CurrentTemp()
                RainfallStatus()
                DailyTemp()
            }
        },
    )
}

@Composable
private fun LandingScreen(
    modifier: Modifier = Modifier,
    viewModel: WeatherViewModel,
    onFinish: () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LaunchedEffect(true) {
            logCoroutineContext("Launched effect")
            viewModel.refreshAll()
            onFinish.invoke()
        }
        Image(
            painterResource(
                id = R.drawable.logo
            ),
            contentDescription = stringResource(R.string.splash_screen)
        )
    }
}

@Composable
fun CurrentTemp() {
    Column {
        Text(text = "Temperature is higher than yesterday.")
    }
}

@Composable
fun RainfallStatus() {
    // TODO
}

@Composable
fun DailyTemp() {
    // TODO
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BetterThanYesterdayTheme {
        SummaryScreen()
    }
}