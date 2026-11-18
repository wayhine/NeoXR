package com.neoxr.player

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback

/** Standalone entry point for callers that open a network library directly. */
class NetworkLibraryActivity : AppCompatActivity() {
    private var screen: NetworkLibraryScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url")
        if (url == null || !NetworkMedia.isNetwork(url)) { finish(); return }
        val wrap = SbsFrameLayout.attach(this, R.layout.activity_network_library)
        screen = NetworkLibraryScreen(this, wrap, url, savedInstanceState,
            intent.getBooleanExtra("browse", false)) { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen?.onBack() != true) finish()
            }
        })
    }

    override fun onStart() { super.onStart(); screen?.resume() }
    override fun onStop() { screen?.pause(); super.onStop() }
    override fun onDestroy() { screen?.close(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { screen?.saveState(outState); super.onSaveInstanceState(outState) }
}
