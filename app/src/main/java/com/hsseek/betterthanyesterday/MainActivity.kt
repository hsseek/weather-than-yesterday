package com.hsseek.betterthanyesterday

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewModelScope
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.hsseek.betterthanyesterday.ui.theme.*
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.viewmodel.Sky
import com.hsseek.betterthanyesterday.viewmodel.Sky.Bad
import com.hsseek.betterthanyesterday.viewmodel.Sky.Bad.*
import com.hsseek.betterthanyesterday.viewmodel.Sky.Good
import com.hsseek.betterthanyesterday.viewmodel.WeatherViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.*
import kotlin.collections.ArrayList

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private val viewModel: WeatherViewModel by viewModel<WeatherViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val locatingMethod = viewModel.locatingMethod
        if (locatingMethod == LocatingMethod.Auto) {
            // As the forecast location is the current location, we need to update the location,
            // which requires context-dependent jobs such as checking permission and show dialogs.
            updateAutomaticLocation()
        } else {
            // No context-dependent jobs: update ViewModel directly.
            viewModel.updateFixedLocation(locatingMethod)
        }

        // Set the Views.
        setContent {
            BetterThanYesterdayTheme {
                // Make the status bar transparent.
                val systemUiController = rememberSystemUiController()
                systemUiController.setSystemBarsColor(
                    color = MaterialTheme.colors.background
                )

                val modifier = Modifier.fillMaxWidth()

                Surface(
                    modifier = modifier,
                    color = MaterialTheme.colors.background
                ) {
                    if (!viewModel.isDataUpToDate) {
//                        LandingScreen()
                        MainScreen(modifier = modifier)  // test
                    } else {
                        MainScreen(
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.viewModelScope.launch {
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
        // TODO: Toast "The new data will be released in 23 minutes." AND Text of "Last checked at 2022-10-03 12:34"
        // TODO: Swipe to refresh(https://stackoverflow.com/questions/67204979/there-is-something-similar-like-swiperefreshlayout-to-pull-to-refresh-in-the-laz)
    }

    /**
     * Check the permission and update the location in ViewModel.
     * Called only if the locating method is [LocatingMethod.Auto].
     * */
    private fun updateAutomaticLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startLocationUpdate()
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
                    .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                        // Request the permission the explanation has been given.
                        requestPermissionLauncher.launch((Manifest.permission.ACCESS_COARSE_LOCATION))
                    }.create().show()
            } else {
                // No explanation needed, request the permission.
                requestPermissionLauncher.launch((Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startLocationUpdate()
        } else {
            // The user must select a location to retrieve weather data.
            viewModel.toShowLocatingDialog.value = true
        }
    }

    @Composable
    private fun MainScreen(
        modifier: Modifier,
    ) {
        val locatingMethod = viewModel.locatingMethod

        Scaffold(
            topBar = { WeatherTopAppBar(
                modifier = modifier,
                onClickChangeLocation = { viewModel.toShowLocatingDialog.value = true }
            ) },
            content = { padding ->
                Column(
                    modifier = modifier
                ) {
                    InformationScreen(modifier, padding, locatingMethod)
                    CustomScreen()
                }
            },
        )

        // A dialog to select locating method.
        if (viewModel.toShowLocatingDialog.value) {
            LocationSelectDialog(
                selectedLocatingMethod = locatingMethod,
                onClickNegative = { viewModel.toShowLocatingDialog.value = false },
                onClickPositive = { selectedMethod ->
                    viewModel.toShowLocatingDialog.value = false  // Dismiss the dialog anyway.
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED && selectedMethod == LocatingMethod.Auto) {
                        // The user selected automatic locating which requires location permission, without the permission.
                        // So, request permission again.
                        requestPermissionLauncher.launch((Manifest.permission.ACCESS_COARSE_LOCATION))

                        // While the ViewModel's variable will be updated, the ViewModel won't take any action.
                        viewModel.updateLocatingMethod(selectedMethod, false)
                    } else {
                        viewModel.updateLocatingMethod(selectedMethod, true)
                    }
                }
            )
        }
    }

    @Composable
    private fun CustomScreen() {
        Box(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxSize()
                .background(color = MaterialTheme.colors.secondary)
        ) {
            Text(
                text = "광고",
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }

    @Composable
    private fun InformationScreen(
        modifier: Modifier,
        padding: PaddingValues,
        locatingMethod: LocatingMethod,
    ) {
        val sky: Sky? by viewModel.rainfallStatus.collectAsState()

        Column(
            modifier = modifier.padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LocationInformation(modifier, viewModel.cityName, viewModel.districtName, locatingMethod)
            CurrentTemperature(modifier, viewModel.hourlyTempDiff, viewModel.hourlyTempToday)
            DailyTemperatures(modifier, viewModel.highestTemps, viewModel.lowestTemps)
            RainfallStatus(modifier, sky)
        }
    }

    @Composable
    private fun WeatherTopAppBar(
        modifier: Modifier,
        onClickChangeLocation: () -> Unit,
    ) {
        val topBarElevation = 0.dp
        val iconSize = 29.dp

        TopAppBar(
            backgroundColor = Color.Transparent,
            elevation = topBarElevation,
            modifier = modifier,
        ) {
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
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

                // Refresh button
                IconButton(
                    onClick = { refresh() },
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.desc_refresh),
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
            DropdownMenuItem(onClick = {
                onDismissRequest()
                // TODO: Use an Intent
            }) {
                Text(text = stringResource(R.string.topbar_share_app))
            }

            DropdownMenuItem(onClick = {
                onDismissRequest()
                // TODO: Launch HelpActivity
            }) {
                Text(text = stringResource(R.string.topbar_help))
            }
        }
    }


    @Composable
    private fun LandingScreen(
        modifier: Modifier,
        viewModel: WeatherViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    ) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // TODO: Loading screen which shows concatenating of emojis.
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

    /**
     * If [locatingMethod] is automatic, request data depending on the [cityName].
     * If [locatingMethod] is not automatic, [cityName] does not matter.
     * */
    @Composable
    private fun LocationInformation(
        modifier: Modifier,
        cityName: String,
        districtName: String,
        locatingMethod: LocatingMethod,
    ) {
        val titleBottomPadding = 2.dp
        val longNameHorizontalPadding = 12.dp

        Column(
            modifier = modifier.padding(horizontal = longNameHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A descriptive title
            Text(
                text = stringResource(R.string.location_title_current),
                style = Typography.h6,
                modifier = Modifier.padding(bottom = titleBottomPadding)
            )

            // The name of the forecast location
            val baseLocationName = if (locatingMethod == LocatingMethod.Auto) {
                cityName
            } else {
                stringResource(id = locatingMethod.regionId)
            }
            Text(
                text = baseLocationName,
                style = Typography.h3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // "Warn" the user if the location has been manually set.
            val specificLocation: String = if (locatingMethod == LocatingMethod.Auto) {
                districtName
            } else {
                stringResource(R.string.location_manually)
            }

            Text(
                text = specificLocation,
                style = Typography.caption,
            )
        }
    }

    @Composable
    fun CurrentTemperature(
        modifier: Modifier,
        hourlyTempDiff: Int,
        currentTemp: Float?,
    ) {
        val tempDiffVerticalOffset = (-20).dp
        val columnTopPadding = 16.dp
        val degreeUnitTopPadding = 43.dp
        val degreeUnitStartPadding = 8.dp

        // A description
        val msg =
            if (hourlyTempDiff > 0) {
                stringResource(R.string.current_temp_higher)
            } else if (hourlyTempDiff < 0) {
                stringResource(R.string.current_temp_lower)
            } else {
                stringResource(R.string.current_temp_same)
            }

        // The temperature difference
        val color: Color = getTemperatureColor(hourlyTempDiff)
        val diffString: String = if (hourlyTempDiff > 0) {
            "\u25B4 $hourlyTempDiff"
        } else if (hourlyTempDiff < 0) {
            "\u25BE ${-hourlyTempDiff}"
        } else {
            "="
        }

        Column(
            modifier = modifier.padding(top = columnTopPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The title
            Text(
                text = stringResource(R.string.current_temp_title),
                style = Typography.h6
            )

            // A description
            if (currentTemp != null) {
                Text(
                    text = stringResource(R.string.current_temp_value, currentTemp.toInt()),
                )
            }

            Text(
                text = msg
            )

            // The temperature difference(HUGE)
            Row(
                Modifier.offset(y = tempDiffVerticalOffset),
            ) {
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
                            .padding(top = degreeUnitTopPadding, start = degreeUnitStartPadding)
                            .align(Alignment.Top),
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    @Composable
    fun DailyTemperatures(
        modifier: Modifier,
        highestTemps: List<Int?>,
        lowestTemps: List<Int?>,
    ) {
        val titleBottomPadding = 4.dp
        val verticalOffset = (-24).dp
        val size = highestTemps.size
        val fraction: Float = 1f/size + 1

        class DailyTemperature(val isToday: Boolean, val day: String, val highest: String, val lowest: String)

        // Dates
        val dates = arrayOfNulls<String>(highestTemps.size)
        val cal = getCurrentKoreanDateTime().also {
            it.add(Calendar.DAY_OF_YEAR, -2)
        }
        val locale = if (Locale.getDefault() == Locale.KOREA) {
            Locale.KOREA
        } else {
            Locale.US
        }

        // Font colors for highest temperatures
        val warmColor: Color = if (isSystemInDarkTheme()) {
            RedTint700
        } else {
            RedShade200
        }

        val hotColor: Color = if (isSystemInDarkTheme()) {
            RedTint400
        } else {
            Red000
        }

        // Font colors for lowest temperatures
        val coolColor: Color = if (isSystemInDarkTheme()) {
            CoolTint700
        } else {
            CoolShade200
        }

        val coldColor: Color = if (isSystemInDarkTheme()) {
            CoolTint400
        } else {
            Cool000
        }

        val dailyTemps: ArrayList<DailyTemperature> = arrayListOf()
        dailyTemps.add(DailyTemperature(false, "",
            stringResource(R.string.daily_highest), stringResource(R.string.daily_lowest)
        ))
        for (i in 0 until size) {
            val isToday = i == 1

            cal.add(Calendar.DAY_OF_YEAR, 1)
            val day = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT_FORMAT, locale)
            val highest = if (highestTemps[i] != null) {
                "${highestTemps[i].toString()}\u00B0"
            } else {
                stringResource(id = R.string.null_value)
            }
            val lowest = if (lowestTemps[i] != null) {
                "${lowestTemps[i].toString()}\u00B0"
            } else {
                stringResource(id = R.string.null_value)
            }

            dailyTemps.add(DailyTemperature(isToday, day ?: "", highest, lowest))
        }

        LazyRow(
            modifier = modifier.offset(y = verticalOffset),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            itemsIndexed(dailyTemps) {index, dailyTemp ->
                // Values for today
                val todayMark: String
                val fontWeight: FontWeight
                val highTempColor: Color
                val lowTempColor: Color

                if (index == 0) {
                    highTempColor = MaterialTheme.colors.onBackground
                    lowTempColor = MaterialTheme.colors.onBackground
                } else {
                    if (dailyTemp.isToday) {
                        highTempColor = hotColor
                        lowTempColor = coldColor
                    } else {
                        highTempColor = warmColor
                        lowTempColor = coolColor
                    }
                }

                if (dailyTemp.isToday) {
                    todayMark = stringResource(id = R.string.daily_today)
                    fontWeight = FontWeight.ExtraBold
                } else {
                    todayMark = ""
                    fontWeight = FontWeight.Normal
                }

                Column {
                    val columnMod = Modifier.align(Alignment.CenterHorizontally)

                    // Today mark
                    Text(
                        text = todayMark,
                        fontWeight = fontWeight,
                        style = Typography.caption,
                        modifier = columnMod,
                    )

                    // Mon, Tue, ...
                    Text(
                        text = dailyTemp.day,
                        fontWeight = fontWeight,
                        modifier = columnMod,
                    )

                    // Highest temperatures
                    Text(
                        text = dailyTemp.highest,
                        fontWeight = fontWeight,
                        color = highTempColor,
                        modifier = columnMod,
                    )

                    // Lowest temperatures
                    Text(
                        text = dailyTemp.lowest,
                        fontWeight = fontWeight,
                        color = lowTempColor,
                        modifier = columnMod,
                    )
                }
            }
        }
    }

    @Composable
    fun RainfallStatus(
        modifier: Modifier,
        sky: Sky?,
    ) {
        val titleBottomPadding = 2.dp
        val imageSpacerSize = 6.dp

        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.rainfall_title),
                style = Typography.h6,
                modifier = Modifier.padding(bottom = titleBottomPadding)
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
                                stringResource(R.string.hour_stops_soon)
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
                    else -> {
                        qualitative = ""
                        quantitative = ""
                    }
                }
                Image(
                    painter = painterResource(id = imageId),
                    contentDescription = stringResource(R.string.desc_weather_status)
                )
                Spacer(modifier = Modifier.size(imageSpacerSize))
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
    fun LocationSelectDialog(
        selectedLocatingMethod: LocatingMethod,
        onClickNegative: () -> Unit,
        onClickPositive: (LocatingMethod) -> Unit,
    ) {
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
                ) },
            backgroundColor = color,
            buttons = {
                val selected = rememberSaveable { mutableStateOf(selectedLocatingMethod) }

                RegionsRadioGroup(
                    padding = bodyPadding,
                    selected = selected.value,
                    onSelect = { selected.value = it },
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onClickNegative) {
                        Text(text = stringResource(R.string.dialog_cancel))
                    }
                    TextButton(onClick = {
                        onClickPositive(selected.value)
                    }) {
                        Text(text = stringResource(R.string.dialog_ok))
                    }
                }
            }
        )
    }

    @Composable
    fun RegionsRadioGroup(
        padding: Dp,
        selected: LocatingMethod,
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

    // TODO: Deal with (start, end, current) = (23, 23, 23) or (23, 23, 0)
    @Composable
    private fun getReadableHour(hour: Int): String {
        val time = getCurrentKoreanDateTime()
        time.add(Calendar.HOUR_OF_DAY, 1)

        // The next forecast baseTime
        val closestHour = getKmaBaseTime(
            time = time,
            roundOff = KmaHourRoundOff.HOUR,
            isQuickPublish = false
        )
        return if (hour == closestHour.hour.toInt()) {
            stringResource(R.string.hour_present)  // The next forecast hour says it's going to be raining.
        } else if (hour == 12) {
            stringResource(id = R.string.hour_noon) // 1200 -> Noon
        } else if (hour == 23) {
            stringResource(R.string.hour_overnight)
        } else if (hour < 12) {
            stringResource(R.string.hour_am, hour) // 800 -> 8 AM
        } else {
            stringResource(R.string.hour_pm, hour - 12) // 1300 -> 1 PM
        }
    }

//    @Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
    @Composable
    fun LocationInformationPreview() {
        BetterThanYesterdayTheme {
            Surface {
                LocationInformation(
                    modifier = Modifier.fillMaxWidth(),
                    cityName = "서울",
                    districtName = "종로구",
                    locatingMethod = LocatingMethod.Auto
                )
            }
        }
    }

//    @Preview(showBackground = true)
//    @Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
    @Composable
    fun ManualLocationInformationPreview() {
        BetterThanYesterdayTheme {
            Surface {
                LocationInformation(
                    modifier = Modifier.fillMaxWidth(),
                    cityName = stringResource(id = R.string.region_south_jl),
                    districtName = "남구",
                    locatingMethod = LocatingMethod.SouthJl
                )
            }
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
                    currentTemp = 23.3f
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
                    currentTemp = 32.6f,
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
                    currentTemp = -9.1f,
                )
            }
        }
    }

//    @Preview(showBackground = true)
//    @Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
    @Composable
    fun CharTempsPreview() {
        BetterThanYesterdayTheme {
            Surface {
                DailyTemperatures(
                    modifier = Modifier.fillMaxWidth(),
                    highestTemps = listOf(25, 22, null, 26),
                    lowestTemps = listOf(16, null, -3, 20),
                )
            }
        }
    }

//    @Preview(showBackground = true)
    @Composable
    fun SunnyPreview() {
        BetterThanYesterdayTheme {
            Surface {
                RainfallStatus(
                    modifier = Modifier.fillMaxWidth(),
                    sky = Good
                )
            }
        }
    }

//    @Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
    @Composable
    fun RainyPreview() {
        BetterThanYesterdayTheme {
            Surface {
                RainfallStatus(
                    modifier = Modifier.fillMaxWidth(),
                    sky = Rainy(300, 1200)
                )
            }
        }
    }

//    @Preview(showBackground = true)
    @Composable
    fun SnowPreview() {
        BetterThanYesterdayTheme {
            Surface {
                RainfallStatus(
                    modifier = Modifier.fillMaxWidth(),
                    sky = Snowy(2000, 2300)
                )
            }
        }
    }

//    @Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
    @Composable
    fun MixedPreview() {
        BetterThanYesterdayTheme {
            Surface {
                RainfallStatus(
                    modifier = Modifier.fillMaxWidth(),
                    sky = Mixed(100, 800)
                )
            }
        }
    }

//    @Preview(showBackground = true)
    @Composable
    fun appBarPreview() {
        BetterThanYesterdayTheme {
            WeatherTopAppBar(
                modifier = Modifier.fillMaxWidth(),
            ) {

            }
        }
    }

//    @Preview(showBackground = true)
//    @Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
    @Composable
    fun LocatingMethodDialogPreview() {
        BetterThanYesterdayTheme {
            LocationSelectDialog(
                selectedLocatingMethod = LocatingMethod.Capital,
                onClickNegative = {},
                onClickPositive = {}
            )
        }
    }

//    @Preview(showBackground = true)
    @Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 320, heightDp = 640)
    @Composable
    fun StackedPreview() {
        BetterThanYesterdayTheme {
            val modifier = Modifier.fillMaxWidth()
            Surface {
                Column(
                    modifier = modifier,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    )
                {
                    LocationInformation(
                        modifier = modifier,
                        cityName = "서울",
                        districtName = "종로구",
                        locatingMethod = LocatingMethod.SouthJl,
                    )

                    CurrentTemperature(
                        modifier = modifier,
                        hourlyTempDiff = 3,
                        currentTemp = 23.6f
                    )

                    DailyTemperatures(
                        modifier = modifier,
                        highestTemps = listOf(32, 7, null, 23),
                        lowestTemps = listOf(null, -12, 8, -10)
                    )
                    RainfallStatus(
                        modifier = modifier,
                        sky = Rainy(300, 1200)
                    )
                    CustomScreen()
                }
            }
        }
    }
}