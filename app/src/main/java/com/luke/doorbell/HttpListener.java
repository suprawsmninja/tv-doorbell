package com.luke.doorbell;

import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Minimal HTTP/1.0 listener for Home Assistant's rest_command.
 * GET or POST /show[?seconds=N], /hide, /ping. Token as ?token= or "Authorization: Bearer".
 */
final class HttpListener extends Thread {
    interface Commands {
        void show(int seconds);
        void hide();
    }

    private final int port;
    private final String token;
    private final Commands commands;
    private volatile ServerSocket server;

    HttpListener(int port, String token, Commands commands) {
        super("doorbell-http");
        this.port = port;
        this.token = token;
        this.commands = commands;
        setDaemon(true);
    }

    @Override
    public void run() {
        try (ServerSocket s = new ServerSocket(port)) {
            server = s;
            while (!s.isClosed()) {
                try (Socket client = s.accept()) {
                    client.setSoTimeout(3000);
                    handle(client);
                } catch (IOException e) {
                    if (!s.isClosed()) Log.w(DoorbellService.TAG, "request failed", e);
                }
            }
        } catch (IOException e) {
            Log.e(DoorbellService.TAG, "can't listen on " + port, e);
        }
    }

    void close() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void handle(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = in.readLine();
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            reply(client, 400, "bad request");
            return;
        }
        String bearer = "";
        for (String line; (line = in.readLine()) != null && !line.isEmpty(); ) {
            if (line.regionMatches(true, 0, "Authorization: Bearer ", 0, 22)) {
                bearer = line.substring(22).trim();
            }
        }

        Uri uri = Uri.parse("http://tv" + parts[1]);
        String given = uri.getQueryParameter("token");
        if (given == null) given = bearer;
        if (!token.isEmpty() && !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            reply(client, 401, "unauthorized");
            return;
        }

        switch (uri.getPath() == null ? "" : uri.getPath()) {
            case "/show":
                int seconds = 0;
                try {
                    String s = uri.getQueryParameter("seconds");
                    if (s != null) seconds = Integer.parseInt(s);
                } catch (NumberFormatException ignored) {
                }
                commands.show(seconds);
                reply(client, 200, "showing");
                break;
            case "/hide":
                commands.hide();
                reply(client, 200, "hidden");
                break;
            case "/ping":
                reply(client, 200, "ok");
                break;
            default:
                reply(client, 404, "not found");
        }
    }

    private static void reply(Socket client, int code, String message) throws IOException {
        byte[] body = ("{\"result\":\"" + message + "\"}\n").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.0 " + code + " " + message + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
