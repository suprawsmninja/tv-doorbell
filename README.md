# TV Doorbell

A small Android TV / Google TV app that pops up your doorbell camera in a corner
tile when someone rings, over whatever is playing. It gets everything from
Home Assistant, so the camera's own RTSP stream can stay switched off and any
number of TVs can show it at once.

- Listens for the ring over Home Assistant's WebSocket API (no automation needed).
- Plays the camera's HLS stream from Home Assistant, muted, with small buffers
  (about a second behind live once Home Assistant's stream is warm).
- Shows a still from the camera while the stream starts.
- Never takes focus, so the remote keeps controlling what's playing.
- Closes itself after 30 seconds; a second ring keeps it up longer.
- Does nothing while the TV screen is off.

Built and tested with a UniFi G4 Doorbell Pro through Home Assistant's UniFi
Protect integration, on Sony BRAVIA TVs (Google TV 12 and 14). Any Home Assistant
camera plus an `event` entity with `event_type: ring` should work.

## Build

Needs JDK 17 and the Android SDK (platform 34).

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`, signed with the debug key for
sideloading.

## Install

With ADB connected to the TV:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell appops set com.luke.doorbell SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +com.luke.doorbell
```

Google TV has no settings screen for the overlay permission, hence `appops`.
The idle whitelist stops the TV from killing the app in the background.

## Configure

There's no UI. Settings go in over ADB, and the app starts its service and
closes straight away:

```bash
adb shell am start -n com.luke.doorbell/.ConfigActivity \
  --es ha_url http://homeassistant.local:8123 \
  --es ha_token <long-lived access token> \
  --ez test true
```

| Extra | Default | What it is |
|---|---|---|
| `ha_url` | `http://homeassistant.local:8123` | Home Assistant base URL, reachable from the TV |
| `ha_token` | none | Long-lived access token (Profile → Security). It must belong to an admin user, because the app uses `subscribe_trigger` |
| `event` | `event.doorbell_doorbell` | Doorbell event entity |
| `camera` | `camera.doorbell_medium` | Camera entity to stream |
| `label` | `Front door` | Text under the video |
| `seconds` (`--ei`) | `30` | How long the tile stays up |
| `token` | none | Turns on the local test listener (below) with this secret |
| `port` (`--ei`) | `8765` | Port for the test listener |
| `test` (`--ez`) | `false` | Show the tile once, now |

Only the extras you pass change; the rest keep their saved values. The app
starts on boot.

## Test listener

When `token` is set, the app also answers HTTP on the LAN, which is handy for
testing or for triggering it from something other than the doorbell:

```bash
curl "http://<tv-ip>:8765/show?seconds=10&token=<token>"
curl "http://<tv-ip>:8765/hide?token=<token>"
```

`Authorization: Bearer <token>` works too. Leave `token` unset to keep it off.
