package com.hsseek.betterthanyesterday

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hsseek.betterthanyesterday.data.Language
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.ui.theme.*
import com.hsseek.betterthanyesterday.util.createConfigurationWithStoredLocale
import com.hsseek.betterthanyesterday.viewmodel.SettingsViewModel
import com.hsseek.betterthanyesterday.viewmodel.SettingsViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.compose.foundation.lazy.items

private const val ROW_PADDING = 14
private const val TAG = "SettingsActivity"

class SettingsActivity : ComponentActivity() {
    private lateinit var viewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val userPrefsRepo = UserPreferencesRepository(this)

        viewModel = ViewModelProvider(
            this,
            SettingsViewModelFactory(userPrefsRepo)
        )[SettingsViewModel::class.java]

        viewModel.viewModelScope.launch(Dispatchers.Default) {
            // No need to observe the Preferences as the ViewModel processes the user input directly.
            val prefs = userPrefsRepo.preferencesFlow.first()
            viewModel.updateLanguageCode(prefs.languageCode, false)
            viewModel.updateSimpleViewEnabled(prefs.isSimplified, false)
            viewModel.updateAutoRefreshEnabled(prefs.isAutoRefresh, false)
            viewModel.updateDaybreakEnabled(prefs.isDaybreak, false)
            viewModel.updatePresetRegionEnabled(prefs.isPresetRegion, false)
        }

        setContent {
            BetterThanYesterdayTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    MainScreen(this@SettingsActivity, viewModel)

                    // Preferences dialog
                    // Language
                    if (viewModel.showLanguageDialog) {
                        val items = mutableListOf<RadioItem>()
                        enumValues<Language>().forEach {
                            val title = when (it) {
                                Language.System -> getString(R.string.radio_lang_system)
                                Language.English -> getString(R.string.radio_lang_en)
                                Language.Korean -> getString(R.string.radio_lang_kr)
                            }
                            items.add(it.code, RadioItem(it.code, title))
                        }
                        RadioSelectDialog(
                            title = getString(R.string.dialog_title_language),
                            selectedItemIndex = viewModel.languageCode,
                            onClickNegative = { viewModel.onDismissLanguage() },
                            onClickPositive = { selectedCode ->
                                viewModel.onDismissLanguage()
                                if (selectedCode != viewModel.languageCode) {
                                    viewModel.updateLanguageCode(selectedCode)
                                    this@SettingsActivity.recreate()
                                }
                            },
                            items = items
                        )
                    }

                    // Help dialogs
                    if (viewModel.showSimpleViewHelp) {
                        HelpDialog(
                            title = stringResource(id = R.string.pref_title_simple_mode),
                            desc = stringResource(id = R.string.pref_help_simple_view),
                            onDismissRequest = { viewModel.onDismissSimpleViewHelp() }
                        )
                    }

                    if (viewModel.showAutoRefreshHelp) {
                        HelpDialog(
                            title = stringResource(id = R.string.pref_title_auto_refresh),
                            desc = stringResource(id = R.string.pref_help_auto_refresh),
                            onDismissRequest = { viewModel.onDismissAutoRefreshHelp() }
                        )
                    }

                    if (viewModel.showDaybreakHelp) {
                        HelpDialog(
                            title = stringResource(id = R.string.pref_title_daybreak_mode),
                            desc = stringResource(id = R.string.pref_help_daybreak_mode),
                            onDismissRequest = { viewModel.onDismissDaybreakHelp() }
                        )
                    }

                    if (viewModel.showPresetRegionHelp) {
                        HelpDialog(
                            title = stringResource(id = R.string.pref_title_preset_regions),
                            desc = stringResource(id = R.string.pref_help_preset_regions),
                            onDismissRequest = { viewModel.onDismissPresetRegionHelp() }
                        )
                    }
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        newBase?.also { context ->
            runBlocking {
                val config = createConfigurationWithStoredLocale(context)
                super.attachBaseContext(context.createConfigurationContext(config))
            }
        } ?: kotlin.run {
            Log.w(TAG, "newBase is null.")
            super.attachBaseContext(null)
        }
    }
}

@Composable
private fun MainScreen(
    activity: Activity? = null,
    viewModel: SettingsViewModel,
) {
    Scaffold(
        topBar = { SettingsTopAppBar(activity) },
        content = { padding ->
            val modifier = Modifier.padding(padding)
            Column(modifier = modifier) {
                // Language
                PreferenceDialogRow(
                    title = stringResource(R.string.pref_title_language),
                    description = stringResource(R.string.pref_desc_language),
                    onClickHelp = null,
                    onClickRow = { viewModel.onClickLanguage() }
                )

                // Disabled
                // Simple View
                PreferenceToggleRow(
                    title = stringResource(R.string.pref_title_simple_mode),
                    description = stringResource(R.string.pref_desc_simple_mode),
                    onClickHelp = { viewModel.onClickSimpleViewHelp() },
                    checked = viewModel.isSimplified,
                    onCheckedChange = { isChecked -> viewModel.updateSimpleViewEnabled(isChecked) }
                )

                // To be released
                // Auto Refresh
                PreferenceToggleRow(
                    title = stringResource(R.string.pref_title_auto_refresh),
                    description = stringResource(R.string.pref_desc_auto_refresh),
                    onClickHelp = { viewModel.onClickAutoRefreshHelp() },
                    checked = viewModel.isAutoRefresh,
                    onCheckedChange = { isChecked -> viewModel.updateAutoRefreshEnabled(isChecked) },
                )

                // Daybreak mode
                PreferenceToggleRow(
                    title = stringResource(R.string.pref_title_daybreak_mode),
                    description = stringResource(R.string.pref_desc_daybreak_mode),
                    onClickHelp = { viewModel.onClickDaybreakHelp() },
                    checked = viewModel.isDaybreak,
                    onCheckedChange = { isChecked -> viewModel.updateDaybreakEnabled(isChecked) },
                )

                // Preset regions
                PreferenceToggleRow(
                    title = stringResource(R.string.pref_title_preset_regions),
                    description = stringResource(R.string.pref_desc_preset_regions),
                    onClickHelp = { viewModel.onClickPresetRegionHelp() },
                    checked = viewModel.isPresetRegion,
                    onCheckedChange = { isChecked -> viewModel.updatePresetRegionEnabled(isChecked) },
                )
            }
        }
    )
}


@Composable
private fun SettingsTopAppBar(activity: Activity? = null) {
    val topBarElevation = 1.dp

    TopAppBar(
        title = { Text(text = stringResource(R.string.title_activity_settings), style = Typography.h3) },
        navigationIcon = {
            IconButton(onClick = { activity?.finish() }) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.desc_back)
                )
            }
        },
        elevation = topBarElevation,
        backgroundColor = MaterialTheme.colors.background,
    )
}

@Composable
fun HelpDialog(
    title: String,
    desc: String,
    image: Painter? = null,
    onDismissRequest: () -> Unit,
) {
    val titlePadding = 0.dp
    val buttonHorizontalPadding = 6.dp

    val color = if (isSystemInDarkTheme()) {
        Gray400
    } else {
        MaterialTheme.colors.surface
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = title,
                modifier = Modifier.padding(titlePadding),
            )
        },
        backgroundColor = color,
        text = {
               Column {
                   Text(text = desc)
                   if (image != null) {
                       Image(painter = image, contentDescription = null)
                   }
               }
        },
        buttons = {
            // The Cancel and Ok buttons
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = buttonHorizontalPadding)
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(id = R.string.dialog_dismiss_close))
                }
            }
        }
    )
}

@Composable
private fun SettingsDivider() {
    val alpha = 0.4f
    val thick = 1.dp

    Divider(color = Gray000.copy(alpha = alpha), thickness = thick)
}

@Composable
fun HelpButton(
    onClickHelp: (() -> Unit),
) {
    val helpAlpha = 0.6f
    val size = 27.dp
    val padding = 2.dp
    val spacerWidth = 4.dp

    Row {
        Spacer(modifier = Modifier.width(spacerWidth))
        Image(
            modifier = Modifier
                .size(size)
                .padding(padding)
                .padding(2.dp)
                .clip(CircleShape)
                .clickable { onClickHelp() }
            ,
            painter = painterResource(id = R.drawable.ic_help),
            contentDescription = stringResource(R.string.help),
            alpha = helpAlpha,
        )
    }
}

@Composable
fun PreferenceDialogRow(
    enabled: Boolean = true,
    title: String,
    description: String,
    onClickHelp: (() -> Unit)? = null,
    onClickRow: () -> Unit
) {
    val clickableMod = if (enabled) Modifier.clickable { onClickRow() } else Modifier

    Column(modifier = clickableMod) {
        Row(
            modifier = Modifier.padding(ROW_PADDING.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PreferenceRowHeader(enabled, title, description, onClickHelp)
        }
        SettingsDivider()
    }
}

@Composable
fun PreferenceToggleRow(
    enabled: Boolean = true,
    title: String,
    description: String,
    onClickHelp: (() -> Unit)?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ROW_PADDING.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(modifier = Modifier.weight(1f)) {
                PreferenceRowHeader(enabled, title, description, onClickHelp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        SettingsDivider()
    }
}

@Composable
private fun PreferenceRowHeader(
    enabled: Boolean,
    title: String,
    description: String,
    onClickHelp: (() -> Unit)? = null,
) {
    val disabledAlpha = 0.6f
    val titleMaxFraction = 0.85

    Column {
        val titleStyle = if (enabled) {
            Typography.h4
        } else {
            Typography.h4.copy(color = Gray000.copy(alpha = disabledAlpha))
        }

        val descStyle = if (enabled) {
            Typography.h6
        } else {
            Typography.h6.copy(color = Gray000.copy(alpha = disabledAlpha))
        }

        val align = TextAlign.Start

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = titleStyle,
                textAlign = align,
                modifier = Modifier.widthIn(max = (LocalConfiguration.current.screenWidthDp * titleMaxFraction).dp)
            )
            if (onClickHelp != null) HelpButton(onClickHelp)
        }

        Text(
            text = description,
            style = descStyle,
            textAlign = align,
        )
    }
}


@Composable
fun RadioGroup(
    radioItems: List<RadioItem>,
    selected: Int,
    titleStyle: TextStyle = Typography.body1,
    descStyle: TextStyle = Typography.h6,
    onSelect: (Int) -> Unit,
    onClickNegative: () -> Unit,
    onClickPositive: (Int) -> Unit,
) {
    Column {
        LazyColumn {
            items(radioItems) { radioItem ->
                RadioRow(onSelect, radioItem, titleStyle, descStyle, selected)
            }
            item {
                // The Cancel and Ok buttons
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onClickNegative) {
                        Text(text = stringResource(R.string.dialog_dismiss_cancel))
                    }
                    TextButton(onClick = { onClickPositive(selected) }) {
                        Text(text = stringResource(R.string.dialog_dismiss_ok))
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioRow(
    onSelect: (Int) -> Unit,
    radioItem: RadioItem,
    titleStyle: TextStyle,
    descStyle: TextStyle,
    selected: Int
) {
    val radioTextStartPadding = 15.dp

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onSelect(radioItem.code) }
    ) {
        Column(
            modifier = Modifier
                .padding(start = radioTextStartPadding)
                .weight(1f)
        ) {
            Text(
                text = radioItem.title,
                style = titleStyle
            )
            if (radioItem.desc != null) {
                Text(
                    text = radioItem.desc,
                    style = descStyle,
                    textAlign = TextAlign.Start,
                )
            }
        }
        RadioButton(
            selected = selected == radioItem.code,
            onClick = { onSelect(radioItem.code) },
        )
    }
}

@Composable
fun RadioSelectDialog(
    title: String,
    items: List<RadioItem>,
    selectedItemIndex: Int,
    onClickNegative: () -> Unit,
    onClickPositive: (Int) -> Unit,
) {
    val backgroundColor = if (isSystemInDarkTheme()) Gray400 else MaterialTheme.colors.surface
    val titleBottomPadding = 7.dp

    AlertDialog(
        title = { Text(
            text = title,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = titleBottomPadding)
        ) },
        onDismissRequest = onClickNegative,
        backgroundColor = backgroundColor,
        buttons = {
            val selected = rememberSaveable { mutableStateOf(selectedItemIndex) }

            RadioGroup(
                radioItems = items,
                selected = selected.value,
                onSelect = { selected.value = it },
                onClickNegative = onClickNegative,
                onClickPositive = onClickPositive,
            )
        }
    )
}

class RadioItem(val code: Int, val title: String, val desc: String? = null)

@Preview(showBackground = true)
@Composable
fun RadioDialogPreview() {
    BetterThanYesterdayTheme {
        RadioSelectDialog(
            title = "구매 도서 목록",
            selectedItemIndex = 1,
            items = listOf(
                RadioItem(0, "트리니티", null),
                RadioItem(0, "통합 민사소송법", "이창한 저"),
                RadioItem(0, "Hide titles and descriptions to give a neat look", "Hide titles and descriptions to give a neat look. Recommended after the numbers got familiar to you, as it might be hard to interpret information."),
            ),
            onClickNegative = {  },
            onClickPositive = {  }
        )
    }
}

@Preview(showBackground = true)
//@Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 180, showBackground = true)
@Composable
fun SettingsRowPreview() {
    BetterThanYesterdayTheme {
        Surface {
            Column {
                PreferenceDialogRow(
                    title = "Hide titles and descriptions to give a neat look",
                    description = "You can set language.",
                    onClickHelp = { }) {
                }
                PreferenceDialogRow(
                    title = "Language",
                    description = "Hide titles and descriptions to give a neat look. Recommended after the numbers got familiar to you, as it might be hard to interpret information.",
                    onClickHelp = { }) {
                }
                PreferenceToggleRow(
                    title = "Hide titles and descriptions to give a neat look",
                    description = "Hide titles and descriptions to give a neat look. Recommended after the numbers got familiar to you, as it might be hard to interpret information.",
                    onClickHelp = { },
                    checked = true,
                    onCheckedChange = { }
                )
                PreferenceToggleRow(
                    enabled = false,
                    title = "Simple View Mode",
                    description = "Hide",
                    onClickHelp = { },
                    checked = true,
                    onCheckedChange = { }
                )
            }
        }
    }
}