package com.hsseek.betterthanyesterday

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.*
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.ui.theme.*
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.viewmodel.DailyTemperature
import com.hsseek.betterthanyesterday.viewmodel.Sky
import com.hsseek.betterthanyesterday.viewmodel.Sky.Bad
import com.hsseek.betterthanyesterday.viewmodel.Sky.Bad.*
import com.hsseek.betterthanyesterday.viewmodel.Sky.Good
import com.hsseek.betterthanyesterday.viewmodel.WeatherViewModel
import com.hsseek.betterthanyesterday.viewmodel.WeatherViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

private const val TAG = "MainActivityLog"

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: WeatherViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreated() called.")

        val prefsRepo = UserPreferencesRepository(this)

        viewModel = ViewModelProvider(
            this,
            WeatherViewModelFactory(application, prefsRepo)
        )[WeatherViewModel::class.java]

        // Observe to Preferences changes.
        viewModel.viewModelScope.launch(Dispatchers.Default) {
            logCoroutineContext("Preferences Flow observation from MainActivity")
            prefsRepo.preferencesFlow.collect { userPrefs ->
                viewModel.updateSimplifiedEnabled(userPrefs.isSimplified)
                viewModel.updateAutoRefreshEnabled(userPrefs.isAutoRefresh)
            }
        }

        // Toast message listener from ViewModel
        viewModel.viewModelScope.launch {
            viewModel.toastMessage.collect { event ->
                event.getContentIfNotHandled()?.let { id ->
                    toastOnUiThread(id)
                }
            }
        }

        setContent {
            BetterThanYesterdayTheme {
                Surface(color = MaterialTheme.colors.background) {
                    val transitionDuration = 340
                    Crossfade(
                        targetState = viewModel.showLandingScreen,
                        animationSpec = tween(durationMillis = transitionDuration)
                    ) { showLandingScreenState ->
                        if (showLandingScreenState) {
                            LandingScreen(
                                timeout = 2400L,
                                onTimeout = { viewModel.onLandingScreenTimeout() },
                            )
                        } else {
                            val modifier = Modifier
                            MainScreen(modifier = modifier)
                        }
                    }

                    // A dialog to select locating method.
                    LocationSelectDialog(
                        isShowing = viewModel.toShowLocatingDialog.value,
                        selectedLocatingMethod = viewModel.locatingMethod,
                        onClickNegative = { viewModel.toShowLocatingDialog.value = false },
                        onClickPositive = { selectedLocation ->
                            viewModel.toShowLocatingDialog.value = false  // Dismiss the dialog anyway.
                            onSelectLocatingMethod(selectedLocation)
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart() called.")
        requestRefreshImplicitly()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopLocationUpdate()
    }

    private fun onSelectLocatingMethod(selectedLocatingMethod: LocatingMethod) {
        Log.d(TAG, "Selected LocatingMethod: ${selectedLocatingMethod.code}")
        // Weather it is permitted or not, the user intended to use the locating method. Respect the selection.
        viewModel.updateLocatingMethod(selectedLocatingMethod)

        // Now check the validity of the selection.
        requestRefreshIfValid(selectedLocatingMethod)
    }

    private fun requestRefreshImplicitly() {
        val minInterval: Long = if (viewModel.isAutoRefresh) 60 * 1000 else 60 * 60 * 1000  // 1 min or 1 hour
        val lastChecked = viewModel.lastCheckedTime
        if (lastChecked == null || getCurrentKoreanDateTime().timeInMillis - lastChecked.timeInMillis > minInterval) {
            requestRefreshIfValid(viewModel.locatingMethod)
        } else {
            Log.d(TAG, "Too soon, skip refresh. (Last checked at ${lastChecked.get(Calendar.HOUR_OF_DAY)}:${lastChecked.get(Calendar.MINUTE)}, while interval is ${minInterval / 1000}s)")
        }
    }

    private fun requestRefreshIfValid(selectedLocatingMethod: LocatingMethod) {
        if (isLocatingMethodValid(selectedLocatingMethod)) {
            Log.d(TAG, "LocatingMethod valid.")
            viewModel.refreshWeatherData()
        } else {
            Log.d(TAG, "LocatingMethod invalid.")
            showRequestPermissionLauncher()
        }
    }

    private fun isLocatingMethodValid(locatingMethod: LocatingMethod): Boolean {
        return if (locatingMethod == LocatingMethod.Auto) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                return true
            } else {
                Log.w(TAG, "Permission required.")
                return false
            }
        } else {
            true
        }
    }

    private fun showRequestPermissionLauncher() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            // An explanation is required.
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_location_permission))
                .setMessage(getString(R.string.dialog_message_location_permission))
                .setPositiveButton(getString(R.string.dialog_dismiss_ok)) { _, _ ->
                    // Request the permission the explanation has been given.
                    requestPermissionLauncher.launch((Manifest.permission.ACCESS_FINE_LOCATION))
                }.create().show()
        } else {
            // No explanation needed, request the permission.
            requestPermissionLauncher.launch((Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    private fun toastOnUiThread(id: Int) {
        if (id > 0) {
            try {
                runOnUiThread {
                    Toast.makeText(this, getString(id), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Resources.NotFoundException) {
                Log.e(TAG, "Toast res id invalid.")
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Permission granted.")
            viewModel.refreshWeatherData()
        } else {
            viewModel.stopLocationUpdate()

            // The user must select a location to retrieve weather data.
            viewModel.toShowLocatingDialog.value = true
        }
        // Regardless of the result, the launcher dialog has been dismissed.
    }

    // TODO: SEPARATE
    @Composable
    private fun MainScreen(modifier: Modifier) {
        // Make the status bar transparent.
        val systemUiController = rememberSystemUiController()
        systemUiController.setSystemBarsColor(
            color = MaterialTheme.colors.background
        )

        Scaffold(
            topBar = { WeatherTopAppBar(
                modifier = modifier.fillMaxWidth(),
                onClickChangeLocation = { viewModel.toShowLocatingDialog.value = true }
            ) },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = modifier
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val sky: Sky by viewModel.rainfallStatus.collectAsState()

                    LocationInformation(modifier, viewModel.isSimplified, viewModel.cityName, viewModel.districtName, viewModel.locatingMethod)
                    CurrentTemperature(modifier, viewModel.isSimplified, viewModel.hourlyTempDiff, viewModel.hourlyTempToday)
                    DailyTemperatures(modifier, viewModel.isSimplified, viewModel.dailyTemps)
                    RainfallStatus(modifier, viewModel.isSimplified, sky)
                    CustomScreen(modifier)
                }

                LoadingScreen(isLoading = viewModel.isLoading)
            }
        }
    }

    @Composable
    private fun CustomScreen(
        modifier: Modifier
    ) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val gapFromContent = 30.dp
            Spacer(modifier = modifier.height(gapFromContent))
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colors.secondary),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "광고")
            }
        }
    }

    @Composable
    private fun LandingScreen(timeout: Long, onTimeout: () -> Unit) {
        val letterFraction = .4f
        val iconFraction = .18f
        val space = 30.dp

        LaunchedEffect(true) {
            delay(timeout)
            onTimeout()
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_thermostat),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth(iconFraction),
            )
            Spacer(modifier = Modifier.height(space))
            Image(
                painter = painterResource(id = R.drawable.landing_letter_00),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth(letterFraction),
            )
        }
    }

    @Composable
    private fun WeatherTopAppBar(
        modifier: Modifier,
        onClickChangeLocation: () -> Unit,
    ) {
        val topAppBarElevation = 0.dp
        val iconSize = 29.dp

        TopAppBar(
            backgroundColor = Color.Transparent,
            elevation = topAppBarElevation,
            modifier = modifier,
        ) {
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Refresh button
                IconButton(onClick = { requestRefreshIfValid(viewModel.locatingMethod) }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.desc_refresh),
                        tint = MaterialTheme.colors.onSurface,
                        modifier = Modifier.size(iconSize),
                    )
                }

                // Edit location button
                IconButton(
                    onClick = { onClickChangeLocation() },
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_edit_location),
                        contentDescription = stringResource(R.string.desc_edit_location),
                        tint = MaterialTheme.colors.onSurface,
                        modifier = Modifier.size(iconSize),
                    )
                }

                // Overflow dropdown menu
                Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                    val expanded = rememberSaveable { mutableStateOf(false) }

                    IconButton(onClick = { expanded.value = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.desc_overflow_menu)
                        )
                    }

                    OverflowMenu(
                        expanded = expanded.value,
                        onDismissRequest = { expanded.value = false }
                    )
                }
            }
        }
    }

    @Composable
    private fun OverflowMenu(
        expanded: Boolean,
        onDismissRequest: () -> Unit,
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onDismissRequest() }
        ) {
            /*DropdownMenuItem(onClick = {
                onDismissRequest()
            }) {  // TODO: Use an implicit intent with extras.
                Text(text = stringResource(R.string.topbar_share_app))
            }*/

            DropdownMenuItem(onClick = {
                onDismissRequest()
                val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                this@MainActivity.startActivity(intent)
            }) {
                Text(text = stringResource(R.string.title_activity_settings))
            }

            DropdownMenuItem(onClick = {
                onDismissRequest()
                val intent = Intent(this@MainActivity, FaqActivity::class.java)
                this@MainActivity.startActivity(intent)
            }) {
                Text(text = stringResource(R.string.topbar_help))
            }
        }
    }

    @Composable
    private fun LoadingScreen(isLoading: Boolean) {
        if (isLoading) {
            val width = 6.dp
            val alpha = 0.85f
            val size = 40.dp
            val offset = (-160).dp
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colors.background.copy(alpha = alpha),
            ) {
                Box {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(size)
                            .align(Alignment.Center)
                            .offset(y = offset),
                        strokeWidth = width,
                    )
                }
            }
        }
    }

    /**
     * If [locatingMethod] is automatic, request data depending on the [cityName].
     * If [locatingMethod] is not automatic, [cityName] does not matter.
     * */
    @Composable
    private fun LocationInformation(
        modifier: Modifier,
        isSimplified: Boolean,
        cityName: String,
        districtName: String,
        locatingMethod: LocatingMethod?,
    ) {
        val titleBottomPadding = 2.dp
        val longNameHorizontalPadding = 12.dp

        Column(
            modifier = modifier.padding(horizontal = longNameHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A descriptive title
            if (!isSimplified) {
                Text(
                    text = stringResource(R.string.location_title_current),
                    style = Typography.h6,
                    modifier = Modifier.padding(bottom = titleBottomPadding)
                )
            }

            // The name of the forecast location
            Text(
                text = cityName,
                style = Typography.h2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (locatingMethod == LocatingMethod.Auto) {
                Text(text = districtName, style = Typography.caption)
            } else {  // "Warn" the user if the location has been manually set.
                Text(text = stringResource(R.string.location_manually), style = Typography.caption)
            }
        }
    }

    @Composable
    fun CurrentTemperature(
        modifier: Modifier,
        isSimplified: Boolean,
        hourlyTempDiff: Int?,
        currentTemp: Int?,
    ) {
        val columnTopPadding = 16.dp
        val titleBottomPadding = 4.dp

        Column(
            modifier = modifier.padding(top = columnTopPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The title
            val currentHour = getCurrentKoreanDateTime().get(Calendar.HOUR_OF_DAY)
            val hourString =
                if (currentHour < 12) {
                    stringResource(id = R.string.hour_am, currentHour)
                } else if (currentHour > 12) {
                    stringResource(id = R.string.hour_pm, currentHour - 12)
                } else {
                    stringResource(id = R.string.hour_pm, currentHour)  // i.e. 12 PM
                }

            val title: String = if (!isSimplified) {
                stringResource(R.string.current_temp_title) + "\n" + stringResource(R.string.current_temp_time) + " " + hourString
                } else {
                    hourString
                }

            Text(
                text = title,
                style = Typography.h6,
                modifier = modifier.padding(bottom = titleBottomPadding),
            )

            // The current temperature
            val currentTempString = if (currentTemp == null) {
                ""
            } else {
                if (!isSimplified) {
                    stringResource(R.string.current_temp_value) + " $currentTemp \u2103"
                } else {
                    "$currentTemp \u2103"
                }
            }

            Text(text = currentTempString)

            if (!isSimplified) {  // A description
                val description = if (hourlyTempDiff == null) {
                    stringResource(id = R.string.null_value)
                } else if (hourlyTempDiff > 0) {
                    stringResource(R.string.current_temp_higher)
                } else if (hourlyTempDiff < 0) {
                    stringResource(R.string.current_temp_lower)
                } else {
                    stringResource(R.string.current_temp_same)
                }

                Text(text = description)
            }

            // The temperature difference(HUGE)
            TemperatureDifference(hourlyTempDiff)
        }
    }

    @Composable
    private fun TemperatureDifference(hourlyTempDiff: Int?) {
        val tempDiffVerticalOffset = (-20).dp
        val degreeUnitTopPadding = 46.dp
        val degreeUnitStartPadding = 8.dp
        val degreeUnitFontSize = 23.sp

        Row(
            Modifier.offset(y = tempDiffVerticalOffset),
        ) {
            if (hourlyTempDiff != null) {
                // The temperature difference
                val color: Color = getTemperatureColor(hourlyTempDiff)
                val diffString: String = if (hourlyTempDiff > 0) {
                    "\u25B4 $hourlyTempDiff"
                } else if (hourlyTempDiff < 0) {
                    "\u25BE ${-hourlyTempDiff}"
                } else {
                    "="
                }

                Text(
                    text = diffString,
                    style = Typography.h1,
                    color = color,
                )
                if (hourlyTempDiff != 0) {
                    Text(
                        text = "\u2103",
                        color = color,
                        modifier = Modifier
                            .padding(
                                top = degreeUnitTopPadding,
                                start = degreeUnitStartPadding
                            )
                            .align(Alignment.Top),
                        fontSize = degreeUnitFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Text(
                    text = stringResource(id = R.string.null_value),
                    style = Typography.h1
                )
            }
        }
    }

    @Composable
    fun DailyTemperatureColumn(
        dailyTemp: DailyTemperature?,
        isSimplified: Boolean,
    ) {
        val columnWidth = 54.dp
        val columnPadding = 6.dp

        Column(
            modifier = Modifier
                .widthIn(min = columnWidth)
                .padding(horizontal = columnPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // Font colors for highest temperatures
            val warmColor: Color = if (isSystemInDarkTheme()) RedTint700 else RedShade200
            val hotColor: Color = if (isSystemInDarkTheme()) RedTint400 else Red000

            // Font colors for lowest temperatures
            val coolColor: Color = if (isSystemInDarkTheme()) CoolTint700 else CoolShade200
            val coldColor: Color = if (isSystemInDarkTheme()) CoolTint400 else Cool000

            // Values for today
            val todayMark: String
            val fontWeight: FontWeight
            val highTempColor: Color
            val lowTempColor: Color

            if (dailyTemp?.isToday == true) {
                highTempColor = hotColor
                lowTempColor = coldColor
            } else {
                highTempColor = warmColor
                lowTempColor = coolColor
            }

            if (dailyTemp?.isToday == true) {
                todayMark = stringResource(id = R.string.daily_today)
                fontWeight = FontWeight.ExtraBold
            } else {
                todayMark = ""
                fontWeight = FontWeight.Normal
            }

            // Today mark (empty for the header column)
            Text(
                text = if (dailyTemp != null) todayMark else "",
                fontWeight = fontWeight,
                style = Typography.caption,
            )

            // Mon, Tue, ... (empty for the header column)
            if (!isSimplified) {
                Text(text = dailyTemp?.day ?: "", fontWeight = fontWeight)
            }

            // Highest temperatures
            Text(
                text = dailyTemp?.highest ?: stringResource(id = R.string.daily_highest),
                fontWeight = fontWeight,
                color = if (dailyTemp != null) highTempColor else MaterialTheme.colors.onBackground,
            )

            // Lowest temperatures
            Text(
                text = dailyTemp?.lowest ?: stringResource(id = R.string.daily_lowest),
                fontWeight = fontWeight,
                color = if (dailyTemp != null) lowTempColor else MaterialTheme.colors.onBackground,
            )
        }
    }

    @Composable
    fun DailyTemperatures(
        modifier: Modifier,
        isSimplified: Boolean,
        dailyTemps: List<DailyTemperature>,
    ) {
        val verticalOffset = (-24).dp
        val arrangement = if (!isSimplified) Arrangement.SpaceEvenly else Arrangement.Center

        // Day by day
        Row(
            modifier = modifier
                .fillMaxWidth()
                .offset(y = verticalOffset),
            horizontalArrangement = arrangement
        ) {
            // The header column
            if (!isSimplified) DailyTemperatureColumn(dailyTemp = null, isSimplified = false)

            for (dailyTemp in dailyTemps) {
                DailyTemperatureColumn(dailyTemp = dailyTemp, isSimplified = isSimplified)
            }
        }
    }

    @Composable
    fun RainfallStatus(
        modifier: Modifier,
        isSimplified: Boolean,
        sky: Sky,
    ) {
        val titleBottomPadding = 2.dp
        val imageSpacerSize = 6.dp
        val rowHeight = 56.dp

        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!isSimplified) {// Title
                Text(
                    text = stringResource(R.string.rainfall_title),
                    style = Typography.h6,
                    modifier = modifier.padding(bottom = titleBottomPadding)
                )
            }

            // The icon and the description
            Row(
                modifier = modifier.height(rowHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The id of the visual icon
                val imageId: Int = when (sky) {
                    is Good -> R.drawable.ic_smile
                    is Rainy -> R.drawable.ic_rainy
                    is Snowy -> R.drawable.ic_snow
                    else -> R.drawable.ic_umbrella
                }

                // Text description
                val qualitative: String
                val hourDescription: String
                when (sky) {
                    is Good -> {
                        qualitative = stringResource(R.string.today_sunny)
                        hourDescription = ""
                    }
                    is Bad -> {
                        if (sky.endingHour < getCurrentKoreanDateTime().get(Calendar.HOUR_OF_DAY)) {
                            // It has stopped. The rest of the day is sunny.
                            qualitative = stringResource(R.string.today_sunny)
                            hourDescription = ""
                        } else {
                            hourDescription =
                                getRainfallHourDescription(this@MainActivity, sky.startingHour, sky.endingHour)
                            qualitative = when (sky) {
                                is Rainy -> stringResource(R.string.today_rainy)
                                is Snowy -> stringResource(R.string.today_snowy)
                                else -> stringResource(R.string.today_mixed)
                            }
                        }
                    }
                    else -> {
                        qualitative = ""
                        hourDescription = ""
                    }
                }
                Image(
                    painter = painterResource(id = imageId),
                    contentDescription = stringResource(R.string.desc_rainfall_status),
                )

                if (!isSimplified) {
                    Spacer(modifier = Modifier.size(imageSpacerSize))
                    Column {
                        Text(text = qualitative)
                        if (sky is Bad) {
                            Text(text = hourDescription)
                        }
                    }
                } else {
                    if (sky is Bad) {
                        Spacer(modifier = Modifier.size(imageSpacerSize))
                        Text(text = hourDescription)
                    }
                }
            }
        }
    }

    @Composable
    fun LocationSelectDialog(
        isShowing: Boolean,
        selectedLocatingMethod: LocatingMethod?,
        onClickNegative: () -> Unit,
        onClickPositive: (LocatingMethod) -> Unit,
    ) {
        if (isShowing) {

            val bodyPadding = 15.dp
            val titlePadding = 0.dp
            val color = if (isSystemInDarkTheme()) {
                Gray400
            } else {
                MaterialTheme.colors.surface
            }

            AlertDialog(
                onDismissRequest = onClickNegative,
                title = {
                    Text(
                        text = stringResource(R.string.dialog_location_title),
                        modifier = Modifier.padding(titlePadding),
                    )
                },
                backgroundColor = color,
                buttons = {
                    val selected = rememberSaveable { mutableStateOf(selectedLocatingMethod) }

                    // TODO: Make it scrollable.
                    // RadioGroups
                    RegionsRadioGroup(
                        padding = bodyPadding,
                        selected = selected.value,
                        onSelect = { selected.value = it },
                    )

                    // The Cancel and Ok buttons
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = onClickNegative) {
                            Text(text = stringResource(R.string.dialog_dismiss_cancel))
                        }
                        TextButton(onClick = {
                            val selectedLocation: LocatingMethod? = selected.value
                            if (selectedLocation != null) {
                                onClickPositive(selectedLocation)
                            } else {
                                Log.e(TAG, "The selected LocatingMethod is null.")
                            }
                        }) {
                            Text(text = stringResource(R.string.dialog_dismiss_ok))
                        }
                    }
                }
            )
        }
    }

    @Composable
    fun RegionsRadioGroup(
        padding: Dp,
        selected: LocatingMethod?,
        onSelect: (LocatingMethod) -> Unit
    ) {
        val radioTextStartPadding = 4.dp

        Column(
            modifier = Modifier.padding(padding)
        ) {
            enumValues<LocatingMethod>().forEach { locating ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onSelect(locating) }
                ) {
                    Column(
                        modifier = Modifier.padding(start = radioTextStartPadding)
                    ) {
                        Text(text = stringResource(id = locating.regionId))
                        Text(text = stringResource(id = locating.citiesId), style = Typography.h6)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    RadioButton(
                        selected = selected == locating,
                        onClick = { onSelect(locating) },
                    )
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
            hourlyTempDiff == 2 -> RedShade200
            hourlyTempDiff == 1 -> RedShade300
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

//    @Preview(showBackground = true)
//    @Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
    @Composable
    fun ColdCurrentTempPreview() {
        BetterThanYesterdayTheme {
            Surface {
                CurrentTemperature(
                    modifier = Modifier.fillMaxWidth(),
                    hourlyTempDiff = -9,
                    currentTemp = 23,
                    isSimplified = false,
                )
            }
        }
    }

//    @Preview(showBackground = true)
//    @Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
    @Composable
    fun HotCurrentTempPreview() {
        BetterThanYesterdayTheme {
            Surface {
                CurrentTemperature(
                    modifier = Modifier.fillMaxWidth(),
                    hourlyTempDiff = 9,
                    currentTemp = 32,
                    isSimplified = false,
                )
            }
        }
    }

//    @Preview(showBackground = true)
//    @Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
    @Composable
    fun SameCurrentTempPreview() {
        BetterThanYesterdayTheme {
            Surface {
                CurrentTemperature(
                    modifier = Modifier.fillMaxWidth(),
                    hourlyTempDiff = 0,
                    currentTemp = -9,
                    isSimplified = false,
                )
            }
        }
    }

//    @Preview(showBackground = true)
    @Composable
    fun LandingScreenPreview() {
        BetterThanYesterdayTheme {
            Surface {
                LandingScreen(timeout = 0) {}
            }
        }
    }
}

/**
 * [startingHour] and [endingHour] in HH00 format (e.g. 1100 for 11:00 AM, 20:00 for 8:00 PM)
 * */
@Suppress("LiftReturnOrAssignment")
internal fun getRainfallHourDescription(
    context: Context,
    startingHour: Int,
    endingHour: Int,
    cal: Calendar = getCurrentKoreanDateTime(),
): String {
    val openingString: String
    val closingString: String
    val currentHour = cal.get(Calendar.HOUR_OF_DAY) * 100
    val lastHour = 2300
    val through = " ~ "

    // (Start, Current, End)
    if (endingHour == lastHour) {  // (?, ?, 23)
        closingString = context.getString(R.string.hour_overnight)
        if (startingHour == endingHour) {  // (23, ?, 23)
            if (startingHour > currentHour) {
                // (23, 9, 23)  11 PM ~ Overnight
                openingString = context.getString(R.string.hour_23) + through
            } else {
                // (23, 23, 23) Ongoing ~ Overnight
                openingString = context.getString(R.string.hour_present) + through
            }
        } else {  // (11, ?, 23)
            if (endingHour <= currentHour) {  // if ending < current, this won't be called from the first place.
                openingString = context.getString(R.string.hour_present) + through
            } else {
                if (startingHour <= currentHour) {
                    // (11, 11, 23)
                    openingString = context.getString(R.string.hour_present) + through
                } else {
                    // (11, 7, 23)
                    openingString = getReadableHour(context, startingHour) + through
                }
            }
        }
    } else {  // (?, ?, 18)
        if (startingHour == endingHour) {  // (18, ?, 18)
            closingString = context.getString(R.string.hour_stops_soon)
            if (startingHour <= currentHour) {
                // (18, 18, 18)
                openingString = ""
            } else {
                // (18, 7, 18)
                openingString = getReadableHour(context, startingHour)
            }
        } else {  // (15, ?, 18)
            if (endingHour <= currentHour) {  // if ending < current, this won't be called from the first place.
                closingString = context.getString(R.string.hour_stops_soon)
                openingString = ""
            } else {
                closingString = getReadableHour(context, endingHour, true)
                if (startingHour <= currentHour) {
                    // (15, 16, 18)
                    openingString = context.getString(R.string.hour_present) + through
                } else {
                    // (15, 12, 18)
                    openingString = getReadableHour(context, startingHour) + through
                }
            }
        }
    }

    return openingString + closingString
}

/**
 * [hour] in HH00 format (e.g. 1100 for 11:00 AM, 20:00 for 8:00 PM)
 * */
private fun getReadableHour(context: Context, hour: Int, roundUp: Boolean = false): String {
    val modifiedHour = if (roundUp) hour + 100 else hour

    return if (modifiedHour == 1200) {
        context.getString(R.string.hour_noon) // 1200 -> Noon
    } else if (modifiedHour == 2300) {
        context.getString(R.string.hour_overnight)
    } else if (modifiedHour < 1200) {
        context.getString(R.string.hour_am, modifiedHour / 100) // 800 -> 8 AM
    } else {
        context.getString(R.string.hour_pm, modifiedHour / 100 - 12) // 1300 -> 1 PM
    }
}