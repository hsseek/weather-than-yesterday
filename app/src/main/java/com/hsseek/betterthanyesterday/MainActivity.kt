package com.hsseek.betterthanyesterday

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hsseek.betterthanyesterday.ui.theme.BetterThanYesterdayTheme
import com.hsseek.betterthanyesterday.viewmodel.WeatherViewModel

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}

@Composable
fun SummaryScreen(
    modifier: Modifier = Modifier,
    weatherViewModel: WeatherViewModel = viewModel()
) {
    weatherViewModel.weatherData
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BetterThanYesterdayTheme {
        SummaryScreen()
    }
}