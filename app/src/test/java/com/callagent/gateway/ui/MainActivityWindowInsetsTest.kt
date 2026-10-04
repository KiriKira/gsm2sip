package com.callagent.gateway.ui

import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.callagent.gateway.MainActivity
import com.callagent.gateway.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityWindowInsetsTest {
    @Test
    fun rootAppliesSafeInsetsOnceAndKeepsImeInsetFromBottomNavigation() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<android.view.View>(R.id.rootWindow)
        val bottomNavigation = activity.findViewById<BottomNavigationView>(R.id.bottomNavigation)
        val originalNavigationPadding = Insets.of(
            bottomNavigation.paddingLeft,
            bottomNavigation.paddingTop,
            bottomNavigation.paddingRight,
            bottomNavigation.paddingBottom
        )
        val systemBars = WindowInsetsCompat.Type.systemBars()
        val displayCutout = WindowInsetsCompat.Type.displayCutout()
        val ime = WindowInsetsCompat.Type.ime()
        val systemGestures = WindowInsetsCompat.Type.systemGestures()
        val suppliedInsets = WindowInsetsCompat.Builder()
            .setInsets(systemBars, Insets.of(8, 24, 8, 20))
            .setInsets(displayCutout, Insets.of(12, 0, 18, 0))
            .setInsets(ime, Insets.of(0, 0, 0, 400))
            .setInsets(systemGestures, Insets.of(1, 2, 3, 4))
            .build()

        val dispatchedInsets = ViewCompat.dispatchApplyWindowInsets(root, suppliedInsets)

        assertEquals(12, root.paddingLeft)
        assertEquals(24, root.paddingTop)
        assertEquals(18, root.paddingRight)
        assertEquals(400, root.paddingBottom)
        assertEquals(Insets.NONE, dispatchedInsets.getInsets(systemBars))
        assertEquals(Insets.NONE, dispatchedInsets.getInsets(displayCutout))
        assertEquals(Insets.NONE, dispatchedInsets.getInsets(ime))
        assertEquals(Insets.of(1, 2, 3, 4), dispatchedInsets.getInsets(systemGestures))
        assertEquals(originalNavigationPadding, Insets.of(
            bottomNavigation.paddingLeft,
            bottomNavigation.paddingTop,
            bottomNavigation.paddingRight,
            bottomNavigation.paddingBottom
        ))

        activity.finish()
    }
}
