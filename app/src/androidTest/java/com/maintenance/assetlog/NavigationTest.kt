package com.maintenance.assetlog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun arabicDashboardOpensVehicleEditor() {
        val assetsTab = hasText("السيارة") and hasClickAction()
        compose.waitUntil(60_000) { compose.onAllNodes(assetsTab).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(assetsTab).performClick()
        compose.onNodeWithText("إدارة المركبات").performClick()
        compose.onNodeWithText("إضافة مركبة أو جهاز").performClick()
        compose.onNodeWithText("مركبة أو جهاز جديد").assertIsDisplayed()
        compose.onNodeWithText("اسم المركبة أو الجهاز").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, compose.activity.window.decorView.layoutDirection)
        }
    }
}
