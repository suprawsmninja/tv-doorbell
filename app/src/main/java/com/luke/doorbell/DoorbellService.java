package com.luke.doorbell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/** Keeps the Home Assistant connection and test listener alive, and owns the overlay tile. */
public class DoorbellService extends Service {
    static final String TAG = "Doorbell";
    static final String ACTION_SHOW = "com.luke.doorbell.SHOW";
    static final String ACTION_HIDE = "com.luke.doorbell.HIDE";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Settings settings;
    private Overlay overlay;
    private HttpListener listener;
    private HaClient ha;

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Settings may have changed, so reconnect on every start.
        if (ha != null) ha.close();
        if (overlay != null) overlay.hide();
        ha = new HaClient(settings, () -> overlay.show(0));
        overlay = new Overlay(this, settings, ha);
        ha.start();

        if (listener != null) listener.close();
        listener = new HttpListener(settings.port(), settings.token(), new HttpListener.Commands() {
            @Override public void show(int seconds) { main.post(() -> overlay.show(seconds)); }
            @Override public void hide() { main.post(overlay::hide); }
        });
        listener.start();

        String action = intent == null ? null : intent.getAction();
        if (ACTION_SHOW.equals(action)) overlay.show(0);
        else if (ACTION_HIDE.equals(action)) overlay.hide();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (listener != null) listener.close();
        if (ha != null) ha.close();
        if (overlay != null) overlay.hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel =
                new NotificationChannel("listener", "Doorbell listener", NotificationManager.IMPORTANCE_MIN);
        nm.createNotificationChannel(channel);
        Log.i(TAG, "listening on port " + settings.port());
        return new Notification.Builder(this, channel.getId())
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Doorbell")
                .setContentText("Waiting for the doorbell")
                .build();
    }
}
