package com.blissless.megaplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Empty BroadcastReceiver that exists only to be discovered by the AnimeClient
 * app. AnimeClient scans installed packages for receivers registered with the
 * `com.blissless.animeclient.EXTENSION_BEACON` action; finding one tells
 * AnimeClient that this package is a scraper extension.
 */
class ExtensionBeaconReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // No-op — exists only to be discoverable.
    }
}
