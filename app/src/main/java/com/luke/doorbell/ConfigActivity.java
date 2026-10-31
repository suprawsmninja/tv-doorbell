package com.luke.doorbell;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * Headless entry point for ADB:
 * am start -n com.luke.doorbell/.ConfigActivity --es url rtsp://... --es token ... [--ei seconds 30] [--ez test true]
 * Saves any extras given, (re)starts the service and closes.
 */
public class ConfigActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        SharedPreferences.Editor edit = new Settings(this).edit();
        for (String key : new String[] {"url", "token", "label"}) {
            if (in.hasExtra(key)) edit.putString(key, in.getStringExtra(key));
        }
        for (String key : new String[] {"port", "seconds"}) {
            if (in.hasExtra(key)) edit.putInt(key, in.getIntExtra(key, 0));
        }
        edit.apply();

        Intent service = new Intent(this, DoorbellService.class);
        if (in.getBooleanExtra("test", false)) service.setAction(DoorbellService.ACTION_SHOW);
        startForegroundService(service);
        finish();
    }
}
