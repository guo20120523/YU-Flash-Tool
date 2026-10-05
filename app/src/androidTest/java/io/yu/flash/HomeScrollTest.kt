package io.yu.flash

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.yu.flash.core.*
import io.yu.flash.storage.AppSettings
import io.yu.flash.ui.*
import org.junit.Rule
import org.junit.Test

/** Small remaining viewport after watch navigation/insets. Fake rows; no su or device I/O. */
class HomeScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun initialVerboseHomeHasOnePartitionActionAndNoRepeatedPrompt() {
        val vm = MainViewModel(compose.activity.application)
        compose.setContent {
            MaterialTheme { HomeScreen(vm, HomeState(), AppSettings(verbose = true), busy = false) }
        }
        compose.onAllNodesWithText("一键获取分区表", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("点击一键获取分区表").assertCountEquals(0)
        compose.onAllNodesWithText("正在读取分区表").assertCountEquals(0)
    }

    @Test fun loadingHomeHasOneStatusAndDoesNotRepeatDiagnostics() {
        val vm = MainViewModel(compose.activity.application)
        compose.setContent {
            MaterialTheme {
                HomeScreen(vm, HomeState(loading = true, diagnostics = "旧的诊断信息"), AppSettings(verbose = true), busy = false)
            }
        }
        compose.onAllNodesWithText("正在读取分区表").assertCountEquals(1)
        compose.onNodeWithText("一键获取分区表").assertIsNotEnabled()
        compose.onAllNodesWithText("旧的诊断信息").assertCountEquals(0)
    }

    @Test fun shortViewportCanReachPartitionsAndReturnToControls() {
        val vm = MainViewModel(compose.activity.application)
        val partitions = (0..29).map { index ->
            Partition("test_$index", "/dev/block/by-name/test_$index", "/dev/block/fake$index",
                "240:$index", 4096, ImageKind.UNKNOWN, null, Risk.UNKNOWN)
        }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 240.dp, height = 160.dp)) {
                    HomeScreen(vm, HomeState(root = RootState.READY, partitions = partitions), AppSettings(), busy = false)
                }
            }
        }
        val list = compose.onNodeWithTag("home-scroll")
        list.performScrollToNode(hasText("test_0"))
        compose.onNodeWithText("test_0").assertIsDisplayed()
        list.performScrollToNode(hasText("test_29"))
        compose.onNodeWithText("test_29").assertIsDisplayed()
        list.performScrollToNode(hasText("查看分区表诊断"))
        compose.onNodeWithText("查看分区表诊断").assertIsDisplayed()
        list.performScrollToNode(hasText("搜索分区名称"))
        compose.onNodeWithText("搜索分区名称").assertIsDisplayed()
    }
}
