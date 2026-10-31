package com.luke.doorbell;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything the overlay needs, set once over ADB through ConfigActivity. */
final class Settings {
    static final int DEFAULT_PORT = 8765;
    static final int DEFAULT_SECONDS = 30;

    private final SharedPreferences prefs;

    Settings(Context context) {
        prefs = context.getSharedPreferences("doorbell", Context.MODE_PRIVATE);
    }

    /** The camera's RTSP link, e.g. rtsp://192.168.1.50:7447/abc123. */
    String streamUrl() { return prefs.getString("url", ""); }

    /** Shared secret Home Assistant sends with each call. Empty means no check. */
    String token() { return prefs.getString("token", ""); }

    int port() { return prefs.getInt("port", DEFAULT_PORT); }

    int seconds() { return prefs.getInt("seconds", DEFAULT_SECONDS); }

    String label() { return prefs.getString("label", "Front door"); }

    SharedPreferences.Editor edit() { return prefs.edit(); }
}
