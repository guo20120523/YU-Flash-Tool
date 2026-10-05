package io.yu.flash

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** No root requests, no device reads/writes. Requires an emulator/device; NOT run locally. */
class UiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun initialHomeDoesNotAutomaticallyRequestRoot() {
        compose.onNodeWithText("一键获取分区表").assertIsDisplayed()
        compose.onNodeWithText("本地分区工具 · 直接 dd 写入").assertIsDisplayed()
    }

    @Test fun allFourDestinationsAndAboutIdentityAreReachable() {
        compose.onNodeWithText("任务", useUnmergedTree = true).performClick()
        compose.onNodeWithText("任务记录").assertIsDisplayed()
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("外观").assertIsDisplayed()
        compose.onNodeWithText("关于", useUnmergedTree = true).performClick()
        compose.onNodeWithText("开发者：昱yu\nQQ：3895958954").assertIsDisplayed()
        compose.onNodeWithText("主页", useUnmergedTree = true).performClick()
        compose.onNodeWithText("一键获取分区表").assertIsDisplayed()
    }

    @Test fun activityRecreationKeepsApplicationUsableWithoutRoot() {
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("一键获取分区表").assertIsDisplayed()
    }
}
