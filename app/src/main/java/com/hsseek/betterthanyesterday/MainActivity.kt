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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewModelScope
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
        modifier: Modifier = Modifier,
        viewModel: WeatherViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    ) {
        val locatingMethod = viewModel.locatingMethod

        Scaffold(
            topBar = { TopAppBar(
                onClickChangeLocation = { viewModel.toShowLocatingDialog.value = true }
            ) },
            content = { padding ->
                InformationScreen(modifier, padding, viewModel, locatingMethod)
            },
        )

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
    private fun InformationScreen(
        modifier: Modifier,
        padding: PaddingValues,
        viewModel: WeatherViewModel,
        locatingMethod: LocatingMethod,
    ) {
        val sky: Sky? by viewModel.rainfallStatus.collectAsState()

        Column(
            modifier = modifier.padding(padding)
        ) {
            val rainfallSpacerSize = 20.dp

            LocationInformation(modifier, viewModel.cityName, locatingMethod)
            CurrentTempComparison(modifier, viewModel.hourlyTempDiff)
            DailyTemperatures(modifier, viewModel.highestTemps, viewModel.lowestTemps)
            Spacer(modifier = Modifier.size(rainfallSpacerSize))
            RainfallStatus(modifier, sky)
        }
    }

    @Composable
    private fun TopAppBar(
        onClickChangeLocation: () -> Unit,
    ) {
        val topBarElevation = 0.dp

        TopAppBar(
            title = {  },
            backgroundColor = Color.Transparent,
            elevation = topBarElevation,
            navigationIcon = {
                IconButton(
                    onClick = { onClickChangeLocation() }
                ) {
                    val iconId = if (isSystemInDarkTheme()) {
                        R.drawable.ic_edit_location_dark
                    } else {
                        R.drawable.ic_edit_location
                    }
                    Icon(
                        painter = painterResource(id = iconId),
                        contentDescription = stringResource(R.string.desc_edit_location)
                    )
                }
            }
        )
    }

    @Composable
    private fun LandingScreen(
        modifier: Modifier = Modifier,
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
        modifier: Modifier = Modifier,
        cityName: String,
        locatingMethod: LocatingMethod,
    ) {
        val titleBottomPadding = 2.dp

        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A descriptive title
            Text(
                text = stringResource(R.string.location_title_current),
                style = Typography.caption,
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
            )

            // "Warn" the user if the location has been manually set.
            val manualDesc: String = if (locatingMethod == LocatingMethod.Auto) {
                ""
            } else {
                stringResource(R.string.location_manually)
            }

            Text(
                text = manualDesc,
                style = Typography.caption,
                fontStyle = FontStyle.Italic,
            )
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
        val titleBottomPadding = 4.dp

        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The title
            Text(
                text = stringResource(R.string.daily_temperature_title),
                style = Typography.caption,
                modifier = Modifier.padding(bottom = titleBottomPadding)
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
                itemsIndexed(days) { index, item ->
                    if (index == 1) {
                        Text(
                            text = item,
                            modifier = modifier.fillParentMaxWidth(fraction),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    } else {
                        Text(
                            text = item,
                            modifier = modifier.fillParentMaxWidth(fraction),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // Font color for highest temperatures
            val warmColor: Color = if (isSystemInDarkTheme()) {
                RedTint700
            } else {
                RedShade600
            }

            val hotColor: Color = if (isSystemInDarkTheme()) {
                RedTint400
            } else {
                RedShade200
            }

            LazyRow(modifier = modifier, horizontalArrangement = Arrangement.SpaceAround) {
                itemsIndexed(highestTemps) { index, item ->
                    if (item != null) {
                        if (index == 1) {  // Today's
                            Text(
                                text = item.toString(),
                                modifier = modifier.fillParentMaxWidth(fraction),
                                textAlign = TextAlign.Center,
                                color = hotColor,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        } else {
                            Text(
                                text = item.toString(),
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

            // Font color for lowest temperatures
            val coolColor: Color = if (isSystemInDarkTheme()) {
                CoolTint700
            } else {
                CoolShade600
            }

            val coldColor: Color = if (isSystemInDarkTheme()) {
                CoolTint400
            } else {
                CoolShade200
            }

            LazyRow(horizontalArrangement = Arrangement.SpaceEvenly) {
                itemsIndexed(lowestTemps) { index, item ->
                    if (item != null) {
                        if (index == 1) {  // Today's
                            Text(
                                text = item.toString(),
                                modifier = modifier.fillParentMaxWidth(fraction),
                                textAlign = TextAlign.Center,
                                color = coldColor,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        } else {
                            Text(
                                text = item.toString(),
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
                style = Typography.caption,
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

        AlertDialog(
            onDismissRequest = onClickNegative,
            title = { Text(text = stringResource(R.string.dialog_location_title)) },
            buttons = {
                val selected = rememberSaveable { mutableStateOf(selectedLocatingMethod) }

                RegionsRadioGroup(
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
        selected: LocatingMethod,
        onSelect: (LocatingMethod) -> Unit
    ) {
        Column {
            enumValues<LocatingMethod>().forEach { locating ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(text = stringResource(id = locating.regionId))
                        Text(text = stringResource(id = locating.citiesId), style = Typography.caption)
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
                    sky = Good
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
    fun Location() {
        BetterThanYesterdayTheme {
            Surface {
                LocationInformation(
                    cityName = "서울",
                    locatingMethod = LocatingMethod.SouthCh,
                )
            }
        }
    }

    @Preview(showBackground = true)
    @Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
    @Composable
    fun DefaultPreview() {
        BetterThanYesterdayTheme {
            MainScreen(
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}