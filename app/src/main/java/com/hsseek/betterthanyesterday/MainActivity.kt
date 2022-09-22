package com.hsseek.betterthanyesterday

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.OnTokenCanceledListener
import com.hsseek.betterthanyesterday.ui.theme.BetterThanYesterdayTheme
import com.hsseek.betterthanyesterday.util.toText
import com.hsseek.betterthanyesterday.viewmodel.WeatherViewModel

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        checkLocationPermission()
        updateLocation()
        setContent {
            BetterThanYesterdayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    Text(text = "Hello, world!")
                }
                // A surface container using the 'background' color from the theme
                SummaryScreen()
            }
        }
    }

    private fun checkLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)) {
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
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                updateLocation()
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
        requestPermissionLauncher.launch((Manifest.permission.ACCESS_COARSE_LOCATION))

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            MY_PERMISSIONS_REQUEST_LOCATION
        )
    }

    private fun updateLocation() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val lastLocation = fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    Log.d(TAG, "Last location on ${location.toText()}")
                }.addOnFailureListener {
                    Log.e(TAG, "$it: Cannot retrieve the last location.")
                }
            // TODO: Update viewModel to query based on Location values.

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                object : CancellationToken() {
                    override fun onCanceledRequested(p0: OnTokenCanceledListener) = CancellationTokenSource().token
                    override fun isCancellationRequested() = false
                }).addOnSuccessListener { location: Location? ->
                if (location == null) {
                    Toast.makeText(this, getString(R.string.na_location), Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Location NA")
                } else {
                    Log.d(TAG, "Location determined.${location.toText()}")
                }
            }.addOnFailureListener {
                Log.e(TAG, "$it: Cannot retrieve the current location.")
            }
        }
    }

    companion object {
        private const val MY_PERMISSIONS_REQUEST_LOCATION = 99
    }
}

@Composable
fun SummaryScreen(
    modifier: Modifier = Modifier,
    weatherViewModel: WeatherViewModel = viewModel()
) {
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BetterThanYesterdayTheme {
        SummaryScreen()
    }
}