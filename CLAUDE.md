# CLAUDE.md

Guidance for working on this codebase.

## What this is

MR Radio - an Android app streaming Tamil FM radio stations aggregated from
radio-browser.info. Kotlin, Media3 (ExoPlayer + `MediaLibraryService`), Retrofit,
Google Cast SDK. minSdk 24, targetSdk 35.

## Architecture

**`RadioPlaybackService` is the single source of truth for playback.** It owns the
`ExoPlayer` instance and the full station playlist. `MainActivity` (and Android Auto,
and the notification) are all just `MediaController`/`MediaBrowser` clients of this one
service - they never build their own player or media item list. This is deliberate and
non-negotiable: an earlier attempt to let the client (`MainActivity`) call
`setMediaItems()` on its own `MediaController` caused a race (the client's mirrored
state lags the service's real state across the IPC boundary) that silently broke
playback. **Never call `setMediaItems`/`setMediaItem` from `MainActivity`** - only ever
`seekTo()` into the timeline the service already published, matching by `mediaId` (the
station's stream URL), not by index.

Station data flow:
1. `RadioBrowserClient.getTamilStations()` merges three API queries (`language=tamil`,
   `name=tamil`, `tag=tamil`) plus `CustomStations.ALL` - no single query catches every
   legitimate station (some have a blank `language` field, e.g. France's vanavilFM).
2. `StationUtils.dedupe()` - keys off the resolved stream URL first (catches mirrors
   like `eaglefm`/`eagle-fm-hd` pointing at the identical stream), then a normalized
   name key as a second pass.
3. `StationUtils.filterQuality()` - drops anything under 128kbps (defense in depth; the
   API query already asks for `bitrateMin=128`).
4. `StationUtils.prettify()` - hyphen/underscore-split, then a small dictionary-based
   word-break DP for concatenated slugs (`tamilrockyfm` → `Tamil Rocky FM`). Add new
   words to `KNOWN_WORDS` as new bad names turn up; it's a heuristic, not NLP.
5. `StationUtils.filterReachable()` - bounded-concurrency OkHttp probe, runs in the
   background *after* the list is already shown (never blocks first paint). Dead
   stations get auto-hidden via `HiddenStore.autoHide()`.

Both `MainActivity` and `RadioPlaybackService` run this same pipeline independently
(each does its own network fetch) rather than the service being the sole source that
the activity queries via `MediaBrowser`. This was a deliberate scope trade-off, not an
oversight - see "Known gaps" below.

## Known gaps / trade-offs

- **Double fetch**: `MainActivity` and `RadioPlaybackService` each independently fetch
  and process the station list (dedupe/prettify/reachability-check twice). A cleaner
  design would have the activity query the service's browse tree via `MediaBrowser`
  instead. Not done due to scope/time; revisit if station-list consistency issues show
  up between the phone UI and what's actually in the player's playlist.
- **Auto-hide rate limit**: a bad network moment during the reachability check can make
  many stations fail at once - that's a connectivity problem, not proof they're dead.
  Both fetch pipelines skip auto-hiding entirely if more than 25% of stations fail in a
  single run (see `failureRate` checks in `MainActivity.loadStationsFromAPI` and
  `RadioPlaybackService.fetchStations`). If you see stations wrongly disappearing again,
  suspect this threshold or the OkHttp timeout values in `StationUtils.isReachable`.
- **Hidden stations still playable from the service's own playlist**: hiding a station
  only affects what `MainActivity`/Android Auto *display* - the service's internal
  playlist isn't filtered by hidden state, so native prev/next could still land on a
  hidden station. Scoped this way deliberately to avoid a harder cross-process sync
  problem; revisit if it becomes a real complaint.
- **Radio Dhool** (a requested station) couldn't be added - it only embeds a TuneIn
  player, no direct stream URL, and TuneIn's public resolver API (`opml.radiotime.com`)
  no longer works without a partner token.

## Casting architecture

Casting loads a real **queue** (`RemoteMediaClient.queueLoad()` with `MediaQueueItem[]`,
`REPEAT_MODE_REPEAT_ALL`), not a single `MediaLoadRequestData` item - a single item gives
the receiver nothing to advance to, which is why native skip and Assistant ("Hey Google,
next") didn't work before. The queue is built from `MainActivity.displayedStations` (the
list currently on screen, i.e. whatever tab/search/sort/filter is active) starting at the
tapped station, so cast prev/next stays scoped to what the user was actually looking at.
`skipCastBy()` calls `queueNext()`/`queuePrev()` on the receiver's own queue instead of
reloading a new item; `remoteMediaClientCallback.onStatusUpdated()` reads
`mediaStatus.mediaInfo.contentId` (== the station's stream URL, since that's what's passed
as the `MediaInfo` content id) to keep `currentStationId`/highlighting in sync with
whatever the receiver is actually playing, including changes it made on its own (Assistant,
hardware buttons).

`CastOptionsProvider` configures `CastMediaOptions`/`NotificationOptions` so casting gets
its own system notification with working prev/play/next - without this, the *only*
playback notification was the local Media3 one, which controls the local player, not the
Cast receiver actually making sound. `onCastSessionActive()` calls
`mediaController?.stop()` (not `pause()`) for the same reason: leaving the local session
merely paused kept its notification alive as a second, non-functional control surface
alongside the new Cast one.

Hardware volume keys don't reach the Cast device by default (the phone isn't playing a
local audio stream for Android to attach them to) - `MainActivity.dispatchKeyEvent()`
intercepts `KEYCODE_VOLUME_UP/DOWN` while `isCasting` and drives `CastSession.volume`
directly instead.

## Gotchas hit during development (don't rediscover these)

- **`MediaMetadata.MEDIA_TYPE_MUSIC_TRACK` does not exist** in media3-common 1.2.1 (a
  pre-existing bug in the original code). Use `MEDIA_TYPE_RADIO_STATION`.
- **HLS stations poison the whole playlist**: if `cachedMediaItems` contains even one
  `.m3u8` stream and the app doesn't depend on `media3-exoplayer-hls`, ExoPlayer's
  `setMediaItems()` throws for the *entire* batch, not just that item. The
  `media3-exoplayer-hls` dependency is required; `setPlayerPlaylist()` also has a
  fallback that strips `.m3u8`/`.mpd` items and retries once if the batch is rejected
  for any other reason.
- **Cleartext HTTP is required**: most public radio streams are `http://`, not
  `https://`. `network_security_config.xml` permits cleartext; don't remove it.
- **Notification small icon isn't automatic**: `DefaultMediaNotificationProvider` falls
  back to Media3's own generic "circular play" icon unless you explicitly call
  `.setSmallIcon(R.drawable.ic_notification)` on the provider instance (not available on
  the `Builder`). The icon must be a plain white silhouette on transparent - a full-color
  launcher icon renders as a blank/blob shape in the status bar.
- **`android:textColorPrimary` is a `ColorStateList`, not a flat color** - reading
  `TypedValue.data` directly after `resolveAttribute()` gives garbage/invisible text.
  Resolve through `ContextCompat.getColorStateList(context, typedValue.resourceId)`
  instead (see `StationAdapter.defaultNameColor`).
- **`onPlaybackResumption` must be implemented**: a bare external "play" command (from
  a Bluetooth device, Android Auto, or the lock screen) with no media item loaded yet
  throws `UnsupportedOperationException` unless `MediaLibrarySession.Callback` overrides
  `onPlaybackResumption()` to hand back the cached playlist.
- **Media3 custom command buttons** (used for the notification favorite toggle) need
  three pieces wired together: `onConnect()` must add the custom `SessionCommand` to the
  accepted `SessionCommands`, `onCustomCommand()` handles the action, and
  `MediaSession.setCustomLayout()` (called any time the favorite state or current
  station changes) pushes the updated icon. Miss any one of the three and the button
  either doesn't appear or doesn't respond.
- Vector drawable outline shapes: if two overlapping subpaths are meant to form a hollow
  ring/star (filled minus a smaller inner cutout) and both windings agree, the default
  `fillType="nonZero"` renders them as solid, not hollow. Use
  `android:fillType="evenOdd"`.

## Build / verify

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No unit/instrumented test suite exists yet - verification has been done manually on a
physical device via adb (screenshots + logcat). If you add tests, wire a `test`/
`androidTest` task into this section.

## Releases

`releases/mr-radio-debug.apk` is a checked-in copy of the current debug build, kept in
sync manually after each change - there's no CI job doing this automatically.
