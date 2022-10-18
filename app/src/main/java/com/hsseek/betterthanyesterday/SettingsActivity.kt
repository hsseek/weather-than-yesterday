package com.hsseek.betterthanyesterday

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.ui.theme.*
import com.hsseek.betterthanyesterday.viewmodel.SettingsViewModel
import com.hsseek.betterthanyesterday.viewmodel.SettingsViewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val ROW_PADDING = 14

class SettingsActivity : ComponentActivity() {
    private lateinit var viewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val userPrefsRepo = UserPreferencesRepository(this)

        viewModel = ViewModelProvider(
            this,
            SettingsViewModelFactory(userPrefsRepo)
        )[SettingsViewModel::class.java]

        viewModel.viewModelScope.launch {
            // No need to observe the Preferences as the ViewModel processes the user input directly.
            viewModel.onClickSimpleView(userPrefsRepo.simpleViewFlow.first())
            viewModel.onClickAutoRefresh(userPrefsRepo.autoRefreshFlow.first())
        }

        setContent {
            BetterThanYesterdayTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    MainScreen(this@SettingsActivity, viewModel)

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
                }
            }
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
                // Simple View
                PreferenceToggleRow(
                    title = stringResource(R.string.pref_title_simple_mode),
                    description = stringResource(R.string.pref_desc_simple_mode),
                    onClickHelp = { viewModel.onClickSimpleViewHelp() },
                    checked = viewModel.isSimplified,
                    onCheckedChange = { isChecked -> viewModel.onClickSimpleView(isChecked) }
                )

                // To be released
                // Auto Refresh
                PreferenceToggleRow(
                    title = stringResource(R.string.pref_title_auto_refresh),
                    description = stringResource(R.string.pref_desc_auto_refresh),
                    onClickHelp = { viewModel.onClickAutoRefreshHelp() },
                    checked = viewModel.isAutoRefresh,
                    onCheckedChange = { isChecked -> viewModel.onClickAutoRefresh(isChecked) },
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
    val rowMod = if (enabled) Modifier.clickable { onClickRow() } else Modifier

    Column {
        Row(
            modifier = rowMod.padding(ROW_PADDING.dp),
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = titleStyle,
                textAlign = align,
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
    items: List<RadioItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    titleStyle: androidx.compose.ui.text.TextStyle = Typography.body1,
    descStyle: androidx.compose.ui.text.TextStyle = Typography.h6,
) {
    val radioTextStartPadding = 4.dp

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState()),
    ) {
        items.forEach { radioItem ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSelect(radioItem.code) }
            ) {
                Column(
                    modifier = Modifier.padding(start = radioTextStartPadding)
                ) {
                    Text(
                        text = stringResource(id = radioItem.titleId),
                        style = titleStyle
                    )
                    if (radioItem.descId != null) {
                        Text(
                            text = stringResource(id = radioItem.descId),
                            style = descStyle
                        )
                    }
                }
                RadioButton(
                    selected = selected == radioItem.code,
                    onClick = { onSelect(radioItem.code) },
                )
            }
        }
    }
}

class RadioItem(val code: Int, val titleId: Int, val descId: Int?)

//@Preview(showBackground = true)
@Composable
fun HelpDialogPreview() {
    BetterThanYesterdayTheme {
        HelpDialog(
            title = "Simple mode",
            desc = "Hide titles and descriptions to give a neat look. Recommended after the numbers got familiar to you, as it might be hard to interpret information."
        ) {

        }
    }
}

@Preview(showBackground = true, heightDp = 420)
//@Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 180, showBackground = true)
@Composable
fun SettingsRowPreview() {
    BetterThanYesterdayTheme {
        Surface {
            Column {
                PreferenceDialogRow(
                    title = "Language",
                    description = "You can set language.",
                    onClickHelp = { }) {
                }
                PreferenceDialogRow(
                    title = "Language",
                    description = "Hide titles and descriptions to give a neat look. Recommended after the numbers got familiar to you, as it might be hard to interpret information.",
                    onClickHelp = { }) {
                }
                PreferenceToggleRow(
                    title = "Simple View Mode",
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