package com.hsseek.betterthanyesterday

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
            val isSimplified = userPrefsRepo.simpleViewFlow.first()
            viewModel.onClickSimpleView(isSimplified)
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
                            desc = stringResource(id = R.string.pref_desc_simple_help),
                            onDismissRequest = { viewModel.onDismissSimpleViewHelp() }
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
    val modifier = Modifier
    Scaffold(
        topBar = { SettingsTopAppBar(activity) },
        content = { padding ->
            SimpleViewRow(modifier, padding,
                onClickHelp = { viewModel.onClickSimpleViewHelp() },
                isEnabled = viewModel.isSimplified,
                onClickSimpleView = { isChecked -> viewModel.onClickSimpleView(isChecked) }
            )
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
fun SimpleViewRow(
    modifier: Modifier,
    padding: PaddingValues,
    onClickHelp: (() -> Unit),
    isEnabled: Boolean,
    onClickSimpleView: (Boolean) -> Unit,
) {
    PreferenceToggleRow(
        modifier = modifier,
        title = stringResource(R.string.pref_title_simple_mode),
        description = stringResource(R.string.pref_desc_simple_mode),
        onClickHelp = onClickHelp,
        checked = isEnabled,
        onCheckedChange = onClickSimpleView
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
fun PreferenceDialogRow(
    modifier: Modifier,
    enabled: Boolean = true,
    title: String,
    description: String,
    onClickHelp: (() -> Unit)? = null,
    onClickRow: () -> Unit
) {
    val rowMod = if (enabled) modifier.clickable { onClickRow() } else modifier

    Row(modifier = rowMod) {
        Column(modifier = modifier) {
            PreferenceRowHeader(modifier, enabled, title, description, onClickHelp)
            SettingsDivider()
        }
    }
}

@Composable
fun PreferenceToggleRow(
    modifier: Modifier,
    enabled: Boolean = true,
    title: String,
    description: String,
    onClickHelp: (() -> Unit)?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val endPadding = 14.dp

    Column(modifier = modifier) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreferenceRowHeader(modifier, enabled, title, description, onClickHelp)
            Spacer(modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(modifier = Modifier.width(endPadding))
        }
        SettingsDivider()
    }
}

@Composable
private fun PreferenceRowHeader(
    modifier: Modifier,
    enabled: Boolean,
    title: String,
    description: String,
    onClickHelp: (() -> Unit)?,
) {
    val startPadding = 16.dp
    val topPadding = 9.dp
    val bottomPadding = 8.dp

    val helpAlpha = 0.6f
    val disabledAlpha = 0.6f

    Row(
        modifier = modifier
            .padding(
                start = startPadding,
                top = topPadding,
                bottom = bottomPadding
            )
    ) {
        Column {
            val titleStyle = Typography.h4
            val descStyle = Typography.h6

            if (enabled) {
                Text(text = title, style = titleStyle)
                Text(text = description, style = descStyle)
            } else {
                Text(
                    text = title,
                    style = titleStyle.copy(color = Gray000.copy(alpha = disabledAlpha))
                )
                Text(
                    text = description,
                    style = descStyle.copy(color = Gray000.copy(alpha = disabledAlpha))
                )
            }
        }
        if (onClickHelp != null) {
            IconButton(onClick = onClickHelp) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_help),
                    contentDescription = stringResource(R.string.help),
                    tint = Gray000.copy(alpha = helpAlpha),
                )
            }
        }
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
                Spacer(modifier = Modifier.weight(1f))
                RadioButton(
                    selected = selected == radioItem.code,
                    onClick = { onSelect(radioItem.code) },
                )
            }
        }
    }
}

class RadioItem(val code: Int, val titleId: Int, val descId: Int?)

@Preview(showBackground = true)
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

@Preview(showBackground = true, widthDp = 420, heightDp = 180)
//@Preview("Dark Theme", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 180, showBackground = true)
@Composable
fun SettingsRowPreview() {
    val modifier = Modifier

    BetterThanYesterdayTheme {
        Surface {
            Column {
                PreferenceDialogRow(
                    modifier = modifier,
                    title = "Language",
                    description = "You can set language.",
                    onClickHelp = { }) {
                }
                PreferenceToggleRow(
                    modifier = modifier,
                    title = "Simple View Mode",
                    description = "Simple is the best.",
                    onClickHelp = { },
                    checked = true,
                    onCheckedChange = { }
                )
                PreferenceDialogRow(
                    modifier = modifier,
                    enabled = false,
                    title = "Language",
                    description = "You can set language.",
                    onClickHelp = { }) {
                }
            }
        }
    }
}