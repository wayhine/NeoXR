package com.neoxr.player

import android.content.Context
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomePageMemoryTest {
    @Test fun coldLaunchRestoresLastSelectedTabInBothDirections() {
        RuntimeEnvironment.getApplication().getSharedPreferences("neoxr", Context.MODE_PRIVATE).edit().clear().commit()
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(View.VISIBLE, first.get().findViewById<View>(R.id.libraryPanel).visibility)
        first.get().findViewById<View>(R.id.btnSources).performClick()
        first.pause().stop().destroy()
        val second = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(View.GONE, second.get().findViewById<View>(R.id.libraryPanel).visibility)
        assertEquals(View.VISIBLE, second.get().findViewById<View>(R.id.sites).visibility)
        second.get().findViewById<View>(R.id.btnLibrary).performClick()
        second.pause().stop().destroy()
        val third = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(View.VISIBLE, third.get().findViewById<View>(R.id.libraryPanel).visibility)
        third.pause().stop().destroy()
    }
}
