package io.yu.flash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.FoldingFeature
import io.yu.flash.ui.*

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val layout by WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this).collectAsState(initial = null)
            val fold = layout?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull { it.isSeparating }
            YuTheme(settings) { YuApp(viewModel, fold) }
        }
    }
}
