package com.luke.doorbell;

import android.content.Context;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

/** The corner tile: a muted, non-focusable overlay window that closes itself. */
@OptIn(markerClass = UnstableApi.class)
final class Overlay {
    private static final long RETRY_MS = 2000;
    private static final int MAX_RETRIES = 3;

    private final Context context;
    private final Settings settings;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoHide = this::hide;

    private View tile;
    private TextView status;
    private ExoPlayer player;
    private int retries;

    Overlay(Context context, Settings settings) {
        this.context = context;
        this.settings = settings;
        this.wm = context.getSystemService(WindowManager.class);
    }

    /** Shows the tile, or keeps it up longer if it is already showing (a second ring). */
    void show(int seconds) {
        handler.removeCallbacks(autoHide);
        handler.postDelayed(autoHide, (seconds > 0 ? seconds : settings.seconds()) * 1000L);
        if (tile != null) return;

        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.e(DoorbellService.TAG, "no overlay permission: appops set com.luke.doorbell SYSTEM_ALERT_WINDOW allow");
            return;
        }

        tile = LayoutInflater.from(context).inflate(R.layout.tile, null);
        status = tile.findViewById(R.id.status);
        ((TextView) tile.findViewById(R.id.label)).setText(settings.label());
        tile.setAlpha(0f);

        float dp = context.getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Math.round(32 * dp);
        lp.y = Math.round(32 * dp);
        wm.addView(tile, lp);
        tile.animate().alpha(1f).setDuration(250).start();

        retries = 0;
        play();
    }

    void hide() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (tile == null) return;
        View gone = tile;
        tile = null;
        gone.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            if (gone.isAttachedToWindow()) wm.removeView(gone);
        }).start();
    }

    private void play() {
        String url = settings.streamUrl();
        if (url.isEmpty()) {
            status.setText("No camera link set");
            return;
        }
        status.setText("Connecting…");
        status.setVisibility(View.VISIBLE);

        // Small buffers: a doorbell is only useful live.
        player = new ExoPlayer.Builder(context)
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(500, 2000, 250, 250)
                        .build())
                .build();
        player.setVolume(0f);
        player.setMediaSource(new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url))));
        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                if (status != null) status.setVisibility(View.GONE);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(DoorbellService.TAG, "stream error", error);
                releasePlayer();
                if (tile == null) return;
                if (++retries <= MAX_RETRIES) {
                    status.setText("Reconnecting…");
                    status.setVisibility(View.VISIBLE);
                    handler.postDelayed(Overlay.this::play, RETRY_MS);
                } else {
                    status.setText("Camera unavailable");
                    status.setVisibility(View.VISIBLE);
                }
            }
        });
        ((PlayerView) tile.findViewById(R.id.video)).setPlayer(player);
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void releasePlayer() {
        if (player == null) return;
        player.release();
        player = null;
    }
}
