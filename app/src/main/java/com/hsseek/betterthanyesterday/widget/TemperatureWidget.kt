package com.hsseek.betterthanyesterday.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.hsseek.betterthanyesterday.*
import com.hsseek.betterthanyesterday.R
import com.hsseek.betterthanyesterday.util.getCurrentKoreanTime
import com.hsseek.betterthanyesterday.util.getTempDiffColor
import java.util.*


private const val NULL_STRING = "-"

class TemperatureWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode: SizeMode = SizeMode.Exact
    private val titleAlignCriterion = 144
    private val widgetBackground = ImageProvider(R.drawable.app_widget_background)  // abstract
    private val fontWeightCriterion = 25  // abstract

    @Composable
    override fun Content() {
        val prefs = currentState<Preferences>()
        val context = LocalContext.current

        val widgetSize = LocalSize.current
        val smallerDimension = minOf(widgetSize.width.value, widgetSize.height.value)
        val tempDiffFontSize = (smallerDimension * 0.5).toInt().sp
        val tempDiffFontWeight = if (smallerDimension < fontWeightCriterion) FontWeight.Normal else FontWeight.Medium
        val smallSize: Int = maxOf(8, minOf((smallerDimension * 0.1).toInt(), 15))
        val titleAlignment = if (widgetSize.width.value < titleAlignCriterion) TextAlign.Start else TextAlign.Center

        val state = TemperatureWidgetUiState(
            valid = prefs[TemperatureWidgetReceiver.VALID_DATA_KEY] ?: true,
            refreshing = prefs[TemperatureWidgetReceiver.REFRESHING_KEY] ?: false,
            tempDiff = prefs[TemperatureWidgetReceiver.TEMPERATURE_DIFF_PREFS_KEY],
            hourlyTemperature = prefs[TemperatureWidgetReceiver.HOURLY_TEMPERATURE_PREFS_KEY],
            time = getCurrentKoreanTime()
        )
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(widgetBackground)
                .padding(8.dp)
        ) {
            TemperatureWidgetHeader(
                context,
                valid = state.valid,
                refreshing = state.refreshing,
                hourlyTemperature = state.hourlyTemperature,
                cal = state.time,
                fontSize = smallSize.sp,
                iconSize = (smallSize * 1.4f).toInt().dp,
                titleAlignment = titleAlignment
            )
            TemperatureWidgetBody(
                context,
                valid = state.valid,
                refreshing = state.refreshing,
                tempDiff = state.tempDiff,
                largeFontSize = tempDiffFontSize,
                fontWeight = tempDiffFontWeight,
            )
        }
    }

    class TemperatureWidgetUiState(
        val valid: Boolean,
        val refreshing: Boolean,
        val tempDiff: Int?,
        val hourlyTemperature: Int?,
        val time: Calendar,
    )
}

@Composable
fun TemperatureWidgetHeader(
    context: Context,
    valid: Boolean,
    refreshing: Boolean,
    hourlyTemperature: Int?,
    cal: Calendar,
    fontSize: TextUnit = 12.sp,
    iconSize: Dp = 16.dp,
    titleAlignment: TextAlign,
) {
    val textStyle = TextStyle(
        color = ColorProvider(R.color.grey),
        fontSize = fontSize,
        textAlign = titleAlignment,
    )

    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(actionRunCallback<RefreshCallback>()),
        contentAlignment = Alignment.Center
    ) {
        val title = if (valid && !refreshing) {
            val calValue = cal.clone() as Calendar
            calValue.add(Calendar.HOUR_OF_DAY, 1)
            getHourString(
                context = context,
                cal = calValue,
                hourlyTemp = hourlyTemperature,
                isSimplified = true
            )
        } else NULL_STRING

        Text(
            text = title,
            style = textStyle,
            modifier = GlanceModifier.fillMaxWidth(),
        )
        Box(
            modifier = GlanceModifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                modifier = GlanceModifier.size(iconSize),
                contentDescription = context.getString(R.string.desc_refresh)
            )
        }
    }
}

@Composable
fun TemperatureWidgetBody(
    context: Context,
    valid: Boolean,
    refreshing: Boolean,
    tempDiff: Int?,
    largeFontSize: TextUnit = 40.sp,
    normalFontSize: TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.Bold,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionStartActivity(activity = WeatherActivity::class.java)),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) {
            Text(
                text = context.getString(R.string.loading),
                style = TextStyle(
                    color = ColorProvider(R.color.on_background),
                    fontSize = normalFontSize,
                    textAlign = TextAlign.Center,
                )
            )
        } else if (valid) {
            if (tempDiff != null) {
                val color = getTempDiffColor(context, tempDiff)
                val diffString = getTempDiffString(hourlyTempDiff = tempDiff)

                Text(
                    text = diffString,
                    style = TextStyle(
                        color = ColorProvider(color),
                        fontSize = largeFontSize,
                        fontWeight = fontWeight,
                        textAlign = TextAlign.Center,
                    ),
                )
            } else {  // Valid but null temp value (e.g. The initial state)
                Text(
                    text = NULL_STRING,
                    style = TextStyle(
                        color = ColorProvider(R.color.on_background),
                        fontSize = largeFontSize,
                        fontWeight = fontWeight,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        } else {  // Not valid
            Text(
                text = NULL_STRING,
                style = TextStyle(
                    color = ColorProvider(R.color.grey),
                    fontSize = largeFontSize,
                    textAlign = TextAlign.Center,
                )
            )
        }
    }
}