package io.yu.flash.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.yu.flash.storage.AppSettings

internal object Spacing { val small = 8.dp; val medium = 16.dp; val large = 24.dp }
@Composable
internal fun YuTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val colors = when {
        settings.dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), shapes = Shapes(), content = content)
}
