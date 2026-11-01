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

    /** Home Assistant base URL, e.g. http://homeassistant.local:8123. */
    String haUrl() { return prefs.getString("ha_url", "http://homeassistant.local:8123"); }

    /** Home Assistant long-lived access token. */
    String haToken() { return prefs.getString("ha_token", ""); }

    /** The doorbell's event entity; a state change with event_type "ring" is a press. */
    String eventEntity() { return prefs.getString("event", "event.doorbell_doorbell"); }

    String cameraEntity() { return prefs.getString("camera", "camera.doorbell_medium"); }

    /** Shared secret for the local test listener. Empty leaves the listener off. */
    String token() { return prefs.getString("token", ""); }

    int port() { return prefs.getInt("port", DEFAULT_PORT); }

    int seconds() { return prefs.getInt("seconds", DEFAULT_SECONDS); }

    String label() { return prefs.getString("label", "Front door"); }

    SharedPreferences.Editor edit() { return prefs.edit(); }
}
