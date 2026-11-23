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

    @Test fun arabicTabsOpenAssetEditor() {
        val assetsTab = hasText("الأصول") and hasClickAction()
        compose.waitUntil(20_000) { compose.onAllNodes(assetsTab).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(assetsTab).performClick()
        compose.onNodeWithText("إضافة أصل").performClick()
        compose.onNodeWithText("أصل جديد").assertIsDisplayed()
        compose.onNodeWithText("اسم الأصل").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, compose.activity.window.decorView.layoutDirection)
        }
    }
}
