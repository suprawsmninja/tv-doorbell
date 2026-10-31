package com.luke.doorbell;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Home Assistant WebSocket connection: watches the doorbell for rings and hands out
 * HLS stream links for the camera. Reconnects on its own. Callbacks run on the main thread.
 */
final class HaClient extends WebSocketListener {
    private static final int RING_SUBSCRIPTION = 1;

    private final Settings settings;
    private final Runnable onRing;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build();
    private final Map<Integer, Consumer<String>> pending = new HashMap<>();

    private WebSocket socket;
    private boolean ready;
    private int nextId = RING_SUBSCRIPTION + 1;
    private long backoffMs = 2000;
    private boolean closed;

    HaClient(Settings settings, Runnable onRing) {
        this.settings = settings;
        this.onRing = onRing;
    }

    void start() {
        if (settings.haToken().isEmpty()) {
            Log.w(DoorbellService.TAG, "no Home Assistant token set");
            return;
        }
        String url = settings.haUrl().replaceFirst("^http", "ws") + "/api/websocket";
        socket = http.newWebSocket(new Request.Builder().url(url).build(), this);
    }

    void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (socket != null) socket.close(1000, null);
    }

    /** Asks Home Assistant for the camera's HLS link; gets null if it can't. */
    void requestStream(Consumer<String> callback) {
        main.post(() -> {
            if (!ready) {
                callback.accept(null);
                return;
            }
            int id = nextId++;
            pending.put(id, callback);
            send("{\"id\":" + id + ",\"type\":\"camera/stream\",\"entity_id\":"
                    + JSONObject.quote(settings.cameraEntity()) + "}");
        });
    }

    /** Fetches a still from the camera to show while the stream starts. */
    void requestSnapshot(Consumer<Bitmap> callback) {
        Request request = new Request.Builder()
                .url(settings.haUrl() + "/api/camera_proxy/" + settings.cameraEntity())
                .header("Authorization", "Bearer " + settings.haToken())
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(DoorbellService.TAG, "snapshot failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) return;
                    Bitmap bitmap = BitmapFactory.decodeStream(response.body().byteStream());
                    if (bitmap != null) main.post(() -> callback.accept(bitmap));
                }
            }
        });
    }

    String absolute(String path) {
        return settings.haUrl() + path;
    }

    private void send(String json) {
        if (socket != null) socket.send(json);
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        main.post(() -> handle(text));
    }

    private void handle(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            switch (msg.getString("type")) {
                case "auth_required":
                    send(new JSONObject().put("type", "auth")
                            .put("access_token", settings.haToken()).toString());
                    break;
                case "auth_ok":
                    ready = true;
                    backoffMs = 2000;
                    send("{\"id\":" + RING_SUBSCRIPTION + ",\"type\":\"subscribe_trigger\",\"trigger\":"
                            + "{\"platform\":\"state\",\"entity_id\":"
                            + JSONObject.quote(settings.eventEntity()) + "}}");
                    Log.i(DoorbellService.TAG, "connected to Home Assistant");
                    break;
                case "auth_invalid":
                    Log.e(DoorbellService.TAG, "Home Assistant rejected the token");
                    break;
                case "event":
                    if (msg.getInt("id") == RING_SUBSCRIPTION && isRing(msg)) onRing.run();
                    break;
                case "result":
                    Consumer<String> callback = pending.remove(msg.getInt("id"));
                    if (callback == null) break;
                    JSONObject result = msg.optJSONObject("result");
                    callback.accept(msg.optBoolean("success") && result != null
                            ? absolute(result.optString("url")) : null);
                    break;
            }
        } catch (JSONException e) {
            Log.w(DoorbellService.TAG, "bad message from Home Assistant", e);
        }
    }

    /** A ring is a new event state, not the entity coming back from unavailable. */
    private static boolean isRing(JSONObject msg) {
        JSONObject trigger = msg.optJSONObject("event") == null ? null
                : msg.optJSONObject("event").optJSONObject("variables") == null ? null
                : msg.optJSONObject("event").optJSONObject("variables").optJSONObject("trigger");
        if (trigger == null) return false;
        JSONObject from = trigger.optJSONObject("from_state");
        JSONObject to = trigger.optJSONObject("to_state");
        if (from == null || to == null || "unavailable".equals(from.optString("state"))) return false;
        JSONObject attributes = to.optJSONObject("attributes");
        return attributes != null && "ring".equals(attributes.optString("event_type"));
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        reconnect();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        Log.w(DoorbellService.TAG, "Home Assistant connection lost: " + t);
        reconnect();
    }

    private void reconnect() {
        main.post(() -> {
            ready = false;
            for (Consumer<String> callback : pending.values()) callback.accept(null);
            pending.clear();
            if (closed) return;
            main.postDelayed(this::start, backoffMs);
            backoffMs = Math.min(backoffMs * 2, 60_000);
        });
    }
}
