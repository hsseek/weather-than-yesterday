package com.hsseek.betterthanyesterday.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.*
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
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
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.viewmodel.TEST_HOUR_OFFSET
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.*
import kotlin.math.abs


private const val NULL_STRING = "-"

abstract class TemperatureWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode: SizeMode = SizeMode.Exact

    protected abstract val widgetBackground: ImageProvider
    protected abstract val refreshAction: Action
    protected abstract val descriptiveTextColorId: Int
    protected abstract val plainTextColorId: Int
    protected abstract val refreshIconId: Int

    abstract fun getWidgetUiState(prefs: Preferences): TemperatureWidgetUiState
    abstract fun getWidgetTempDiffColor(context: Context, tempDiff: Int): Color

    @Composable
    override fun Content() {
        val context = LocalContext.current

        // Variables for Composables
        val titleAlignCriterion = 144
        val fontWeightCriterion = 25

        val widgetSize = LocalSize.current
        val smallerDimension = minOf(widgetSize.width.value, widgetSize.height.value)
        val tempDiffFontSize = (smallerDimension * 0.56).toInt().sp
        val tempDiffFontWeight = if (smallerDimension < fontWeightCriterion) FontWeight.Normal else FontWeight.Bold
        val smallSize: Int = maxOf(8, minOf((widgetSize.width.value * 0.09).toInt(), 15))
        val titleAlignment = if (widgetSize.width.value < titleAlignCriterion) TextAlign.Start else TextAlign.Center

        // The hourOffset from Preferences
        val hourOffset: Int

        runBlocking {
            val prefsRepo = UserPreferencesRepository(context)
            val prefs = prefsRepo.preferencesFlow.first()
//            xy = CoordinatesXy(prefs.forecastRegionNx, prefs.forecastRegionNy)
            hourOffset = TEST_HOUR_OFFSET  // TODO: Retrieve from Preferences.
        }

        val state = getWidgetUiState(currentState())
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
                hourOffset = hourOffset,
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

    @Composable
    fun TemperatureWidgetHeader(
        context: Context,
        valid: Boolean,
        refreshing: Boolean,
        hourOffset: Int,
        hourlyTemperature: Int?,
        cal: Calendar,
        fontSize: TextUnit = 12.sp,
        iconSize: Dp = 16.dp,
        titleAlignment: TextAlign,
    ) {
        val textStyle = TextStyle(
            color = ColorProvider(descriptiveTextColorId),
            fontSize = fontSize,
            textAlign = titleAlignment,
        )

        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(refreshAction),
            contentAlignment = Alignment.Center
        ) {
            val title = if (valid && !refreshing) {
                val calValue = cal.clone() as Calendar
                calValue.add(Calendar.HOUR_OF_DAY, hourOffset)
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
                    provider = ImageProvider(refreshIconId),
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
        val tempDiffFontSize = if (tempDiff != null && abs(tempDiff) >= 10) largeFontSize * 0.8 else largeFontSize

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
                        color = ColorProvider(plainTextColorId),
                        fontSize = normalFontSize,
                        textAlign = TextAlign.Center,
                    )
                )
            } else if (valid) {
                if (tempDiff != null) {
                    val color = getWidgetTempDiffColor(context, tempDiff)
                    val diffString = getTempDiffString(hourlyTempDiff = tempDiff)

                    Text(
                        text = diffString,
                        style = TextStyle(
                            color = ColorProvider(color),
                            fontSize = tempDiffFontSize,
                            fontWeight = fontWeight,
                            textAlign = TextAlign.Center,
                        ),
                    )
                } else {  // Valid but null temp value (e.g. The initial state)
                    Text(
                        text = NULL_STRING,
                        style = TextStyle(
                            color = ColorProvider(plainTextColorId),
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
                        color = ColorProvider(descriptiveTextColorId),
                        fontSize = largeFontSize,
                        textAlign = TextAlign.Center,
                    )
                )
            }
        }
    }
}