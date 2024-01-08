package com.hsseek.betterthanyesterday

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.*
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.SwipeRefreshIndicator
import com.google.accompanist.swiperefresh.SwipeRefreshState
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.hsseek.betterthanyesterday.data.ForecastRegion
import com.hsseek.betterthanyesterday.data.PresetRegion
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.location.CoordinatesXy
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.*
import kotlin.math.abs
import kotlin.system.measureTimeMillis

private const val TAG = "WeatherActivityLog"

class WeatherActivity : ComponentActivity() {
    private lateinit var viewModel: WeatherViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = System.currentTimeMillis()
        if (DEBUG_FLAG) Log.d(TAG, "onCreated() called.")

        val defaultDispatcher = Dispatchers.Default
        val prefsRepo = UserPreferencesRepository(this)

        viewModel = ViewModelProvider(
            this,
            WeatherViewModelFactory(application, prefsRepo)
        )[WeatherViewModel::class.java]

        runBlocking {
            val prefs = prefsRepo.preferencesFlow.first()
            // Set the language first, to avoid recreating the Activity.
            viewModel.updateLanguage(prefs.languageCode, false)

            // Determine the appearance.
            viewModel.updateDarkMode(prefs.darkModeCode, resources.configuration.uiMode)

            // Retrieve the stored forecast region settings, without which weather data are random.
            // Hence, wait for the values to be set.
            viewModel.updateAutoRegionEnabled(prefs.isForecastRegionAuto, false)

            val region = ForecastRegion(
                prefs.forecastRegionAddress,
                CoordinatesXy(prefs.forecastRegionNx, prefs.forecastRegionNy)
            )
            viewModel.initiateForecastRegions(region, getString(R.string.region_auto))

            // Whether a notice should be drawn.
            viewModel.initiateConsumedSnackBar(this@WeatherActivity, prefs.consumedSnackBar)
        }

        // Observe to Preferences changes.
        viewModel.viewModelScope.launch(defaultDispatcher) {
            logCoroutineContext("Preferences Flow observation from MainActivity")
            prefsRepo.preferencesFlow.collect { userPrefs ->
                viewModel.updateLanguage(userPrefs.languageCode, true)
                viewModel.updateDarkMode(userPrefs.darkModeCode, resources.configuration.uiMode)
                viewModel.updateSimplifiedEnabled(userPrefs.isSimplified)
                viewModel.updateDaybreakEnabled(userPrefs.isDaybreak)
                // TODO: Enable Search Region dialog
                viewModel.updatePresetRegionEnabled(true)
            }
        }

        // Toast message listener from ViewModel
        viewModel.viewModelScope.launch(defaultDispatcher) {
            viewModel.toastMessage.collect { event ->
                event.getContentIfNotHandled()?.let { id ->
                    toastOnUiThread(this@WeatherActivity, id)
                }
            }
        }
        logElapsedTime(TAG, "Wiring before rendering", start)

        setContent {
            BetterThanYesterdayTheme(viewModel.isDarkTheme) {
                Surface(color = MaterialTheme.colors.background) {
                    val transitionDuration = 340
                    Crossfade(
                        targetState = viewModel.showLandingScreen,
                        animationSpec = tween(durationMillis = transitionDuration),
                        label = "Landing Screen"
                    ) { showLandingScreenState ->
                        if (showLandingScreenState) {
                            LandingScreen(
                                timeout = 3200L,
                                onTimeout = { viewModel.onLandingScreenTimeout() },
                            )
                        } else {
                            MainScreen()
                        }
                    }

                    // A Dialog to select ForecastRegion
                    if (viewModel.toShowSearchRegionDialog) {
                        if (!viewModel.isPresetRegion) {
                            SearchRegionDialog(
                                items = viewModel.forecastRegionCandidates,
                                isDarkMode = viewModel.isDarkTheme,
                                selected = viewModel.selectedForecastRegionIndex,
                                onSelect = { viewModel.updateSelectedForecastRegionIndex(it) },
                                onTextChanged = { viewModel.searchRegionCandidateDebounced(it) },
                                onClickSearch = { query ->
                                    val time = measureTimeMillis {
                                        viewModel.searchRegionCandidates(query)
                                    }
                                    if (DEBUG_FLAG) Log.d(TAG, "Region search done in $time ms")
                                    // Toast.makeText(this, "Done in $time ms", Toast.LENGTH_SHORT).show()
                                },
                                onClickNegative = { viewModel.invalidateSearchDialog() },
                                onClickPositive = { index ->
                                    if (viewModel.forecastRegionCandidates.isNotEmpty()) {
                                        val selectedRegion = viewModel.forecastRegionCandidates[index]
                                        onSelectedForecastRegion(selectedRegion)
                                    }
                                    viewModel.invalidateSearchDialog()
                                })
                        } else {
                            // A Dialog to select PresetRegion.
                            PresetRegionRadioDialog()
                        }
                    }

                    // ProgressIndicator for Dialog.
                    // Note that a Composable ON the Surface(not a Dialog) would be set BELOW the SearchRegionDialog
                    // and might not be shown.
                    if (!viewModel.isPresetRegion && viewModel.toShowSearchRegionDialogLoading) {
                        ProgressIndicatorDialog(onDismissRequest = { viewModel.dismissSearchRegionLoading() })
                    }
                }
                if (viewModel.isRefreshing) {
                    BackHandler { viewModel.stopRefreshing() }
                }
            }
        }
    }

    private fun onSelectedForecastRegion(forecastRegion: ForecastRegion) {
        if (DEBUG_FLAG) Log.d(TAG, "Selected ForecastRegion: ${forecastRegion.address}")

        // Weather it is permitted or not, the user intended to use the locating method. Respect the selection.
        val isAutoRegion = forecastRegion.xy == viewModel.autoRegionCoordinate
        viewModel.updateAutoRegionEnabled(isAutoRegion)
        checkPermissionThenRefresh(forecastRegion)
    }

    @Composable
    private fun PresetRegionRadioDialog() {
        var selectedIndex = 0
        val presetRegionRadioItems: MutableList<RadioItem> = mutableListOf()
        val candidates = enumValues<PresetRegion>().toList()
        candidates.forEachIndexed { index, presetRegion ->
            // Build the RadioItem then add it.
            presetRegionRadioItems.add(
                RadioItem(
                    code = index,
                    title = stringResource(id = presetRegion.regionId),
                    desc = stringResource(id = presetRegion.examplesId)
                )
            )

            if (presetRegion.xy == viewModel.forecastRegion.xy) selectedIndex = index
        }

        RadioSelectDialog(
            title = stringResource(R.string.dialog_location_title),
            items = presetRegionRadioItems,
            isDarkMode = viewModel.isDarkTheme,
            selectedItemIndex = selectedIndex,
            onClickNegative = { viewModel.dismissRegionDialog() },
            onClickPositive = { newlySelectedIndex ->
                viewModel.dismissRegionDialog()  // Dismiss the dialog anyway.
                onSelectPresetRegion(candidates[newlySelectedIndex])
            }
        )
    }

    override fun onStart() {
        super.onStart()
        if (DEBUG_FLAG) Log.d(TAG, "onStart() called.")
        if (viewModel.isLanguageChanged) {  // Restart activity
            if (DEBUG_FLAG) Log.d(TAG, "Language changed, recreate the Activity.")
            // Turn of the flag and recreate the Activity.
            viewModel.isLanguageChanged = false
            recreate()
        } else {  // Do regular tasks
            requestRefreshImplicitly()
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopLocationUpdate()
    }

    override fun attachBaseContext(newBase: Context?) {
        if (DEBUG_FLAG) Log.d(TAG, "attachBaseContext(Context) called.")
        newBase?.also { context ->
            runBlocking {
                val config = createConfigurationWithStoredLocale(context)
                super.attachBaseContext(context.createConfigurationContext(config))
            }
        } ?: kotlin.run {
            if (DEBUG_FLAG) Log.w(TAG, "newBase is null.")
            super.attachBaseContext(null)
        }
    }

    private fun onSelectPresetRegion(selectedPresetRegion: PresetRegion) {
        val region = ForecastRegion(
            address = getString(selectedPresetRegion.regionId),
            xy = selectedPresetRegion.xy,
        )
        onSelectedForecastRegion(region)
    }

    private fun requestRefreshImplicitly() {
        val minInterval: Long = 60 * 1000
        val lastChecked = viewModel.lastImplicitlyCheckedTime
        val current = Calendar.getInstance().also {
            viewModel.updateLastImplicitChecked(it)
        }

        if (lastChecked == null || current.timeInMillis - lastChecked.timeInMillis > minInterval) {
            // Implicit request, on the previously selected ForecastRegion.
            checkPermissionThenRefresh()
        } else {
            if (DEBUG_FLAG) Log.d(TAG, "Too soon, skip refresh. (Last checked at ${lastChecked.get(Calendar.HOUR_OF_DAY)}:${lastChecked.get(Calendar.MINUTE)}, while interval is ${minInterval / 1000}s)")
        }
    }

    private fun checkPermissionThenRefresh(region: ForecastRegion = viewModel.forecastRegion) {
        if (isLocatingPermitted()) {  // Good to go.
            viewModel.onClickRefresh(region)
        } else {
            // Not request refreshing now.
            showRequestPermissionLauncher()
            // If the permission is granted, the launcher will request refreshing later.
        }
    }

    private fun isLocatingPermitted(): Boolean {
        return if (viewModel.isForecastRegionAuto) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                return true  // Permission required to perform the task, but already granted.
            } else {
                if (DEBUG_FLAG) Log.w(TAG, "Permission required.")
                return false  // The bad case
            }
        } else true  // Always possible to calculate for fixed coordinates
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

    private fun toastOnUiThread(activity: Activity, id: Int) {
        if (id > 0) {
            try {
                activity.runOnUiThread {
                    Toast.makeText(activity, activity.getString(id), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Resources.NotFoundException) {
                Log.e(TAG, "Toast res id invalid.")
            } catch (e: Exception) {
                if (DEBUG_FLAG) Log.d(TAG, "Error while toastOnUiThread(Int)", e)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (DEBUG_FLAG) Log.d(TAG, "Permission granted.")
            viewModel.onClickRefresh()  // It is equivalent to click Refresh.
        } else {
            viewModel.stopLocationUpdate()

            // The user must select a location to retrieve weather data.
            // It is equivalent to click Change Location.
            viewModel.onClickChangeRegion()
        }
        // Regardless of the result, the launcher dialog has been dismissed.
    }

    @Composable
    private fun MainScreen() {
        // Make the status bar look transparent.
        val systemUiController = rememberSystemUiController()
        systemUiController.setSystemBarsColor(color = if (viewModel.isDarkTheme) DarkBackground else Color.White)
        val screenHeight = LocalConfiguration.current.screenHeightDp

        val scaffoldState = rememberScaffoldState()
        Scaffold(
            scaffoldState = scaffoldState,
            topBar = { WeatherTopAppBar(
                modifier = Modifier.fillMaxWidth(),
                onClickRefresh = { checkPermissionThenRefresh() },
                onClickChangeLocation = { viewModel.onClickChangeRegion() }
            ) },
//            snackbarHost = {
//                           SnackbarHost(it) { data ->
//                               Snackbar(
//                                   actionColor = Color.Green,
//                                   snackbarData = data
//                               )
//                           }
//            },
        ) { padding ->
            SwipeRefresh(
                modifier = Modifier.fillMaxSize(),
                state = rememberSwipeRefreshState(isRefreshing = viewModel.isRefreshing),
                onRefresh = { checkPermissionThenRefresh() },
                indicator = { state, trigger ->
                    InProgressIndicator(
                        refreshState = state,
                        refreshTriggerDistance = trigger,
                    )
                }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    val enlargedFontSize = 178.sp

                    if (isWidthLong()) {
                        Column(
                            modifier = Modifier
                                .padding(padding)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceAround,
                        ) {
                            val horizontalPadding = 50.dp
                            val spacer12 = 25.dp
                            val spacer23 = 40.dp
                            val leftHalfVerticalOffset = (-8).dp
                            val minHeight = (screenHeight * 0.67).dp

                            Row (
                                modifier = Modifier
                                    .padding(horizontal = horizontalPadding)
                                    .heightIn(min = minHeight)
                                ,
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceAround,
                            ) {
                                val weight = 0.5f

                                Box(
                                    modifier = Modifier
                                        .offset(y = leftHalfVerticalOffset)
                                        .weight(weight),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val diff = viewModel.hourlyTempDiff
                                    HourlyTemperature(
                                        isSimplified = viewModel.isSimplified,
                                        hourOffset = viewModel.hourOffset,
                                        hourlyTempDiff = viewModel.hourlyTempDiff,
                                        hourlyTemp = viewModel.hourlyTempToday,
                                        hugeFontSize = if (diff != null && abs(diff) < 10) enlargedFontSize else 130.sp,
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(weight),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    LocationInformation(viewModel.isSimplified, viewModel.cityName, viewModel.districtName, viewModel.isForecastRegionAuto)
                                    Spacer(modifier = Modifier.height(spacer12))
                                    RainfallStatus(viewModel.isSimplified, viewModel.isDarkTheme, viewModel.rainfallStatus.collectAsState().value)
                                    Spacer(modifier = Modifier.height(spacer23))
                                    DailyTemperatures(viewModel.isSimplified, viewModel.isDarkTheme, viewModel.dailyTemps)
                                }
                            }
                            CustomScreen()
                        }
                    } else {
                        val minHeight = (screenHeight * 0.75).dp

                        Column(
                            modifier = Modifier
                                .padding(padding)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceAround,
                        ) {
                            Column(
                                modifier = Modifier.heightIn(min = minHeight),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceAround,
                            ) {
                                LocationInformation(viewModel.isSimplified, viewModel.cityName, viewModel.districtName, viewModel.isForecastRegionAuto)
                                if (viewModel.isSimplified) {
                                    HourlyTemperature(viewModel.isSimplified, viewModel.hourOffset, viewModel.hourlyTempDiff, viewModel.hourlyTempToday, enlargedFontSize)
                                } else {
                                    HourlyTemperature(viewModel.isSimplified, viewModel.hourOffset, viewModel.hourlyTempDiff, viewModel.hourlyTempToday)
                                }
                                DailyTemperatures(viewModel.isSimplified, viewModel.isDarkTheme, viewModel.dailyTemps)
                                RainfallStatus(viewModel.isSimplified, viewModel.isDarkTheme, viewModel.rainfallStatus.collectAsState().value)
                            }
                            CustomScreen()
                        }
                    }
                }
            }

            // A SnackBar for Exceptions
            val exceptionSnackBarContent = viewModel.exceptionSnackBarEvent.collectAsState().value.getContentIfNotHandled()
            if (exceptionSnackBarContent?.messageId != null) {
                LaunchedEffect(scaffoldState.snackbarHostState, viewModel.exceptionSnackBarEvent.collectAsState().value) {
                    val actionLabel = if (exceptionSnackBarContent.actionId == null) null else getString(exceptionSnackBarContent.actionId)

                    val snackBarResult = scaffoldState.snackbarHostState.showSnackbar(
                        message = getString(exceptionSnackBarContent.messageId),
                        actionLabel = actionLabel,
                        duration = SnackbarDuration.Long,
                    )

                    when (snackBarResult) {
                        SnackbarResult.Dismissed -> { }  // Nothing to do.
                        SnackbarResult.ActionPerformed -> { exceptionSnackBarContent.action() }
                    }
                }
            }

            // A SnackBar for notices
            val noticeSnackBarContent = viewModel.noticeSnackBarEvent.collectAsState().value.getContentIfNotHandled()
            if (noticeSnackBarContent?.messageId != null) {
                LaunchedEffect(scaffoldState.snackbarHostState, viewModel.noticeSnackBarEvent.collectAsState().value) {
                    val actionLabel = if (noticeSnackBarContent.actionId == null) null else getString(noticeSnackBarContent.actionId)

                    val snackBarResult = scaffoldState.snackbarHostState.showSnackbar(
                        message = getString(noticeSnackBarContent.messageId),
                        actionLabel = actionLabel,
                        duration = SnackbarDuration.Long,
                    )

                    when (snackBarResult) {
                        SnackbarResult.Dismissed -> { }  // Nothing to do.
                        SnackbarResult.ActionPerformed -> { noticeSnackBarContent.action() }
                    }
                }
            }
        }
    }

    @Composable
    private fun CustomScreen(
        height: Dp = 120.dp,
    ) {
        val ad = if (viewModel.adNumber <= 30) {
            Ad.Eng
        } else {
            Ad.Eng
        }

        val onClick = {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(ad.url)
            }
            startActivity(intent)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Column {
                Text(
                    modifier = Modifier.padding(vertical = 7.dp),
                    text = "Ad",
                    style = Typography.h6
                )
                ad.content(viewModel.isDarkTheme)
            }
        }
    }

    @Composable
    private fun LandingScreen(
        timeout: Long,
        onTimeout: () -> Unit,
    ) {
        val space: Dp
        val letterSize: TextUnit
        val letter = "어제보다"
        val iconFraction: Float

        LaunchedEffect(true) {
            delay(timeout)
            onTimeout()
        }

        val configuration = LocalConfiguration.current
        when (configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                space = 50.dp
                letterSize = (LocalConfiguration.current.screenWidthDp * 0.08).sp
                iconFraction = .12f

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_thermostat),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth(iconFraction),
                    )
                    Spacer(modifier = Modifier.width(space))
                    Text(
                        text = letter,
                        fontSize = letterSize,
                        fontWeight = FontWeight.Bold,
                        fontFamily = gmarket,
                    )
                }
            }
            else -> {
                space = 30.dp
                letterSize = (LocalConfiguration.current.screenWidthDp * 0.12).sp
                iconFraction = .18f

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
                    Text(
                        text = letter,
                        fontSize = letterSize,
                        fontWeight = FontWeight.Bold,
                        fontFamily = gmarket,
                    )
                }
            }
        }
    }

    @Composable
    private fun WeatherTopAppBar(
        modifier: Modifier,
        onClickRefresh: () -> Unit,
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
                IconButton(onClick = onClickRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.desc_refresh),
                        tint = MaterialTheme.colors.onSurface,
                        modifier = Modifier.size(iconSize),
                    )
                }

                // TODO: Enable Search Region dialog
                // Edit location button
                IconButton(
                    onClick = onClickChangeLocation,
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
            DropdownMenuItem(onClick = {
                onDismissRequest()
                val intent = Intent(this@WeatherActivity, SettingsActivity::class.java)
                this@WeatherActivity.startActivity(intent)
            }) {
                Text(text = stringResource(R.string.title_activity_settings))
            }

            DropdownMenuItem(onClick = {
                onDismissRequest()
                val intent = getSharingIntent()
                startActivity(Intent.createChooser(intent, getString(R.string.share_app_guide)))
            }) {
                Text(text = stringResource(R.string.top_bar_share_app))
            }

            DropdownMenuItem(onClick = {
                viewModel.onClickReportError(R.string.top_bar_report_error_explanation)
            }) {
                Text(text = stringResource(R.string.top_bar_report_error))
            }

            DropdownMenuItem(onClick = {
                onDismissRequest()
                val intent = Intent(this@WeatherActivity, WebViewActivity::class.java).apply {
                    putExtra(EXTRA_URL_KEY, POLICY_URL)
                }
                startActivity(intent)
            }) {
                Text(text = stringResource(R.string.top_bar_policy))
            }

            DropdownMenuItem(onClick = {
                onDismissRequest()
                val intent = Intent(this@WeatherActivity, WebViewActivity::class.java).apply {
                    putExtra(EXTRA_URL_KEY, FAQ_URL)
                }
                startActivity(intent)
            }) {
                Text(text = stringResource(R.string.top_bar_help))
            }
        }
    }

    private fun getSharingIntent(): Intent {
        val tempDiff = viewModel.hourlyTempDiff
        val hourlyTemp = viewModel.hourlyTempToday

        val negativeEmojiCodes = listOf(
            0x1F914,  // Thinking face 🤔
            0x1F9D0,  // Monocle 🧐
            0x1F615,  // Confused 😕
        )
        val positiveEmojiCodes = listOf(
            0x1F609,  // Wink 😉
        )

        val negativeEmoji = negativeEmojiCodes.random().toEmojiString()
        val thenDescription = "\"${getString(R.string.share_app_bad_before, hourlyTemp ?: 18)}\" $negativeEmoji"

        val positiveEmoji = positiveEmojiCodes.random().toEmojiString()
        val nowDescription = if (tempDiff != null && tempDiff != 0) {
            "\"${getString(getTempDiffDescription(tempDiff), abs(tempDiff))}\" $positiveEmoji"
        } else {
            "\"${getString(R.string.share_app_now_good_higher, 1)}\" $positiveEmoji"
        }
        val googlePlayUrl = "https://play.google.com/store/apps/details?id=com.hsseek.betterthanyesterday"
        val opening = getString(R.string.share_app_message_opening)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
            putExtra(
                Intent.EXTRA_TEXT, "$opening\n\n" +
                        "$thenDescription\n" +
                        "$nowDescription\n\n" +
                        googlePlayUrl
            )
        }
        return intent
    }

    private fun getTempDiffDescription(tempDiff: Int): Int =
        if (tempDiff < 0) {
            R.string.share_app_now_good_lower
        } else {
            R.string.share_app_now_good_higher
            // R.string.current_temp_same: Don't. It's not showing the essence of the app.
        }

    // https://google.github.io/accompanist/swiperefresh/
    @Composable
    private fun InProgressIndicator(
        refreshState: SwipeRefreshState,
        refreshTriggerDistance: Dp,
        color: Color = MaterialTheme.colors.primary,
        background: Color = MaterialTheme.colors.background.copy(alpha = 0.85f),
    ) {
        val elevation = 1.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    onDrawBehind {
                        val distance = refreshTriggerDistance.toPx()
                        val progress = (refreshState.indicatorOffset / distance).coerceIn(0f, 1f)
                        drawRect(
                            color = background,
                            alpha = FastOutSlowInEasing.transform(progress)
                        )
                    }
                },
        ) {
            val modifier = if (viewModel.isRefreshing) {
                Modifier
                    .fillMaxSize()
                    .background(color = background)
            } else {
                Modifier.fillMaxWidth()
            }

            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SwipeRefreshIndicator(
                    state = refreshState,
                    refreshTriggerDistance = refreshTriggerDistance,
                    contentColor = color,
                    largeIndication = true,
                    elevation = elevation,
                    arrowEnabled = false,
                )
            }
        }
    }

    @Composable
    private fun LocationInformation(
        isSimplified: Boolean,
        cityName: String,
        districtName: String,
        isForecastRegionAuto: Boolean,
    ) {
        val titleBottomPadding = 2.dp
        val longNameHorizontalPadding = 12.dp

        Column(
            modifier = Modifier.padding(horizontal = longNameHorizontalPadding),
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

            val districtText = if (isForecastRegionAuto) {
                districtName
            } else {  // "Warn" the user if the location has been manually set.
                if (districtName.isNotBlank()) {
                    districtName + "\n" + getString(R.string.location_manually)
                } else {
                    getString(R.string.location_manually)
                }
            }
            Text(
                text = districtText,
                style = Typography.caption,
                textAlign = TextAlign.Center,
            )
        }
    }

    @Composable
    fun HourlyTemperature(
        isSimplified: Boolean,
        hourOffset: Int,
        hourlyTempDiff: Int?,
        hourlyTemp: Int?,
        hugeFontSize: TextUnit = Typography.h1.fontSize,
        cal: Calendar = viewModel.referenceCal,
    ) {
        val columnTopPadding = 16.dp
        val titleBottomPadding = 4.dp

        Column(
            modifier = Modifier.padding(top = columnTopPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The title
            cal.add(Calendar.HOUR_OF_DAY, hourOffset)
            val title = getHourString(this@WeatherActivity, cal, hourlyTemp, isSimplified)

            Text(
                text = title,
                style = Typography.h6,
                modifier = Modifier.padding(bottom = titleBottomPadding),
            )

            if (!isSimplified) {  // A description
                val description = if (hourlyTempDiff == null) {
                    stringResource(id = R.string.null_value)
                } else if (hourlyTempDiff > 0) {
                    stringResource(R.string.temp_status_higher)
                } else if (hourlyTempDiff < 0) {
                    stringResource(R.string.temp_status_lower)
                } else {
                    stringResource(R.string.temp_status_same)
                }

                Text(text = description)
            }

            // The temperature difference(HUGE)
            TemperatureDifference(hourlyTempDiff, hugeFontSize)
        }
    }

    @Composable
    private fun TemperatureDifference(hourlyTempDiff: Int?, hugeFontSize: TextUnit) {
        val tempDiffVerticalOffset = (-20).dp
        val degreeUnitTopPadding = 46.dp
        val degreeUnitStartPadding = 8.dp
        val degreeUnitFontSize = 23.sp

        Row(
            Modifier.offset(y = tempDiffVerticalOffset),
        ) {
            if (hourlyTempDiff != null) {
                // The temperature difference
                val color = if (viewModel.isDarkTheme) {
                    getDarkTempDiffColor(this@WeatherActivity, hourlyTempDiff)
                } else {
                    getLightTempDiffColor(this@WeatherActivity, hourlyTempDiff)
                }
                val diffString: String = getTempDiffString(hourlyTempDiff)

                Text(
                    text = diffString,
                    style = Typography.h1,
                    fontSize = hugeFontSize,
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
        isSimplified: Boolean,
        isDarkMode: Boolean,
        dailyTemp: DailyTemperature?,
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
            val warmColor: Color = if (isDarkMode) RedTint100 else RedShade600
            val hotColor: Color = if (isDarkMode) RedTint400 else Red800

            // Font colors for lowest temperatures
            val coolColor: Color = if (isDarkMode) CoolTint100 else CoolShade600
            val coldColor: Color = if (isDarkMode) CoolTint400 else Cool800

            // Values for today
            val todayMark: String
            val fontWeight: FontWeight
            val plainColor = MaterialTheme.colors.onBackground
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

            val highestTempFontStyle = if (dailyTemp?.isHighestButCold == true) FontStyle.Italic else FontStyle.Normal
            val lowestTempFontStyle = if (dailyTemp?.isLowestButHot == true) FontStyle.Italic else FontStyle.Normal

            // Row 1: Today mark (empty for the header column)
            Text(
                text = if (dailyTemp != null) todayMark else "",
                fontWeight = fontWeight,
                style = Typography.caption,
            )

            // Row 2: Mon, Tue, ... (empty for the header column)
            if (!isSimplified) {
                Text(text = dailyTemp?.day ?: "", fontWeight = fontWeight)
            }

            // Row 3 and 4
            if (viewModel.isDaybreakMode) {
                // Lowest temperatures
                Text(
                    text = dailyTemp?.lowest ?: stringResource(id = R.string.daily_daybreak),
                    fontWeight = fontWeight,
                    fontStyle = lowestTempFontStyle,
                    color = if (dailyTemp != null) lowTempColor else plainColor,
                )

                // Highest temperatures
                Text(
                    text = dailyTemp?.highest ?: stringResource(id = R.string.daily_heat),
                    fontWeight = fontWeight,
                    fontStyle = highestTempFontStyle,
                    color = if (dailyTemp != null) highTempColor else plainColor,
                )
            } else {
                // Highest temperatures
                Text(
                    text = dailyTemp?.highest ?: stringResource(id = R.string.daily_highest),
                    fontWeight = fontWeight,
                    fontStyle = highestTempFontStyle,
                    color = if (dailyTemp != null) highTempColor else plainColor,
                )

                // Lowest temperatures
                Text(
                    text = dailyTemp?.lowest ?: stringResource(id = R.string.daily_lowest),
                    fontWeight = fontWeight,
                    fontStyle = lowestTempFontStyle,
                    color = if (dailyTemp != null) lowTempColor else plainColor,
                )
            }
        }
    }

    @Composable
    fun DailyTemperatures(
        isSimplified: Boolean,
        isDarkMode: Boolean,
        dailyTemps: List<DailyTemperature>,
    ) {
        val verticalOffset = (-24).dp
        val arrangement = if (!isSimplified) Arrangement.SpaceEvenly else Arrangement.Center

        // Day by day
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = verticalOffset)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = arrangement,
        ) {
            // The header column
            if (!isSimplified) DailyTemperatureColumn(
                isSimplified = false,
                isDarkMode = isDarkMode,
                dailyTemp = null,
            )

            // Yesterday through the day after tomorrow
            for (dailyTemp in dailyTemps) {
                DailyTemperatureColumn(
                    isSimplified = isSimplified,
                    isDarkMode = isDarkMode,
                    dailyTemp = dailyTemp,
                )
            }
        }
    }

    @Composable
    fun RainfallStatus(
        isSimplified: Boolean,
        isDarkMode: Boolean,
        sky: Sky,
    ) {
        val titleBottomPadding = 2.dp
        val imageSpacerSize = 6.dp
        val rowHeight = 56.dp

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!isSimplified) {// Title
                Text(
                    text = stringResource(R.string.rainfall_title),
                    style = Typography.h6,
                    modifier = Modifier.padding(bottom = titleBottomPadding)
                )
            }

            // The icon and the description
            Row(
                modifier = Modifier.height(rowHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The id of the visual icon
                val imageId: Int = when (sky) {
                    is Good -> R.drawable.ic_smile
                    is Rainy -> R.drawable.ic_rainy
                    is Mixed -> R.drawable.ic_rainy
                    is Snowy -> R.drawable.ic_snow
                    is Sky.Undetermined -> R.drawable.ic_umbrella
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
                        if (sky.endingHour < viewModel.referenceCal.get(Calendar.HOUR_OF_DAY)) {
                            // It has stopped. The rest of the day is sunny.
                            qualitative = stringResource(R.string.today_sunny)
                            hourDescription = ""
                        } else {
                            qualitative = when (sky) {
                                is Rainy -> stringResource(R.string.today_rainy)
                                is Snowy -> stringResource(R.string.today_snowy)
                                else -> stringResource(R.string.today_mixed)
                            }
                            hourDescription = getRainfallHourDescription(
                                    this@WeatherActivity,
                                    sky.startingHour,
                                    sky.endingHour,
                                    cal = viewModel.referenceCal)
                        }
                    }
                    else -> {
                        qualitative = ""
                        hourDescription = ""
                    }
                }
                Image(
                    painter = painterResource(id = imageId),
                    colorFilter = ColorFilter.tint(if (isDarkMode) Color.White else Color.Black),
                    contentDescription = stringResource(R.string.desc_rainfall_status),
                )

                if (!isSimplified) {
                    Spacer(modifier = Modifier.size(imageSpacerSize))
                    Column {
                        Text(text = qualitative)
                        if (sky is Bad) {
                            Text(text = "($hourDescription)")
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

    private enum class Ad(
        val url: String,
        val content: @Composable (darkMode: Boolean) -> Unit
    ) {
        Eng(BLOG_URL, content = { darkMode ->
            Image(
                painter = painterResource(id = R.drawable.blog_logo),
                colorFilter = ColorFilter.tint(if (darkMode) Color.White else Color.Black),
                contentDescription = stringResource(R.string.desc_banner_ad),
                contentScale = ContentScale.Fit,
            )
        }),
        /*Sci("https://blog.naver.com/scientificbowwow", content = {
            Text(
                text = stringResource(id = R.string.ad_sci),
                style = Typography.h3,
                fontFamily = euljiro,
            )
        }),*/
    }

//    @Preview(showBackground = true, heightDp = 640)
    @Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 640)
    @Preview("Landscape", device = Devices.AUTOMOTIVE_1024p, widthDp = 1920, heightDp = 960)
    @Composable
    fun LandingScreenPreview() {
        BetterThanYesterdayTheme(true) {
            Surface(color = MaterialTheme.colors.background) {
                LandingScreen(timeout = 0) {}
            }
        }
    }
}

@Composable
fun SearchRegionDialog(
    title: String = stringResource(R.string.dialog_title_search_region),
    items: List<ForecastRegion>,
    isDarkMode: Boolean,
    titleBottomPadding: Dp = 7.dp,
    selected: (Int),
    onSelect: (Int) -> Unit,
    onTextChanged: (String) -> Unit,
    onClickSearch: (String) -> Unit,
    onClickNegative: () -> Unit,
    onClickPositive: (Int) -> Unit,
) {
    AlertDialog(
        modifier = Modifier.wrapContentHeight(),
        title = { Text(
            text = title,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = titleBottomPadding)
        ) },
        onDismissRequest = onClickNegative,
        backgroundColor = if (isDarkMode) DarkDialogBackground else MaterialTheme.colors.surface,
        buttons = {
            val query = rememberSaveable{ mutableStateOf("") }
            Column {
                Row {
                    TextField(
                        modifier = Modifier.weight(1f),
                        value = query.value,
                        onValueChange = {
                            query.value = it
                            onTextChanged(it)
                        },
                        placeholder = { Text(text = stringResource(R.string.dialog_search_region_hint)) },
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { onClickSearch(query.value) }
                        ),
                    )
                    IconButton(onClick = { onClickSearch(query.value) }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.dialog_title_search_region)
                        )
                    }
                }

                RadioGroup(
                    radioItems = convertToRadioItemList(items),
                    selected = selected,
                    onSelect = onSelect,
                    onClickNegative = onClickNegative,
                    onClickPositive = onClickPositive,
                )
            }
        }
    )
}

@Composable
private fun ProgressIndicatorDialog(
    width: Dp = 4.dp,
    size: Dp = 35.dp,
    offset: Dp = (-30).dp,
    onDismissRequest: () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
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

fun getHourString(
    context: Context,
    cal: Calendar,
    hourlyTemp: Int?,
    isSimplified: Boolean,
): String {
    val nextHour = cal.get(Calendar.HOUR_OF_DAY)
    val hourString =
        if (nextHour < 12) {
            context.getString(R.string.hour_am, nextHour)
        } else if (nextHour > 12) {
            context.getString(R.string.hour_pm, nextHour - 12)
        } else {
            context.getString(R.string.hour_pm, nextHour)  // i.e. 12 PM
        }
    val hourAndTemp = if (hourlyTemp == null) "" else {
        if (!isSimplified) {
            context.getString(R.string.temp_status_value, hourString, hourlyTemp)
        } else {
            context.getString(R.string.temp_status_value_simple, hourString, hourlyTemp)
        }
    }
    val title = if (!isSimplified && hourlyTemp != null) {
        "${context.getString(R.string.temp_status_title)}\n($hourAndTemp)"
    } else hourAndTemp
    return title
}

fun getTempDiffString(hourlyTempDiff: Int): String {
    val diffString: String = if (hourlyTempDiff > 0) {
        "\u25B4 $hourlyTempDiff"
    } else if (hourlyTempDiff < 0) {
        "\u25BE ${-hourlyTempDiff}"
    } else {
        "="
    }
    return diffString
}

fun convertToRadioItemList(searchResults: List<ForecastRegion>): List<RadioItem> {
    val builder = mutableListOf<RadioItem>()

    searchResults.forEachIndexed { index, result ->
        builder.add(RadioItem(code = index, title = result.address))
    }
    return builder.toList()
}

@Composable
fun isWidthLong(): Boolean {
    val config = LocalConfiguration.current
    val screenWidth = config.screenWidthDp
    return if (screenWidth < 400) false else {
        val screenHeight = config.screenHeightDp
        val ratio = (screenHeight/screenWidth.toFloat())
        ratio < 1.28
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
    cal: Calendar,
): String {
    val openingString: String
    val closingString: String
    val currentHour = cal.get(Calendar.HOUR_OF_DAY) * 100
    val lastHour = 2300
    val through = " ~ "
    val comma = ", "

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
                openingString = context.getString(R.string.hour_present) + comma
            } else {
                // (18, 7, 18)
                openingString = getReadableHour(context, startingHour) + comma
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