package io.yu.flash

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import io.yu.flash.ui.Symbol
import io.yu.flash.ui.SymbolIcon
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Resource/render smoke tests only. Never requests root or accesses block devices. */
class SymbolResourceTest {
    @get:Rule val compose = createComposeRule()

    private fun renderAll(dark: Boolean) {
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface {
                    Column {
                        Symbol.entries.chunked(4).forEach { row ->
                            Row {
                                row.forEach { symbol ->
                                    Column {
                                        SymbolIcon(symbol, modifier = Modifier.testTag("${symbol.name}-outline"))
                                        SymbolIcon(symbol, selected = true, modifier = Modifier.testTag("${symbol.name}-filled"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Symbol.entries.forEach { symbol ->
            compose.onNodeWithTag("${symbol.name}-outline").assertExists()
            compose.onNodeWithTag("${symbol.name}-filled").assertExists()
        }
    }

    @Test fun allSymbolVariantsRenderInLightTheme() = renderAll(false)
    @Test fun allSymbolVariantsRenderInDarkTheme() = renderAll(true)

    @Test fun adaptiveLauncherAndOfflineAttributionArePackaged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.getDrawable(R.mipmap.ic_launcher) is AdaptiveIconDrawable)
        val notice = context.assets.open("Material-Symbols-NOTICE.txt").bufferedReader().use { it.readText() }
        assertTrue(notice.contains("Google"))
        assertTrue(notice.contains("737e3324305806514d7909874fa1818ae1808232"))
        assertTrue(notice.contains("END OF TERMS AND CONDITIONS"))
    }
}
