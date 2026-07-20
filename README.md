# MR Radio

An Android app that streams Tamil FM radio stations aggregated from
[radio-browser.info](https://www.radio-browser.info/), with full notification, lock
screen, Chromecast, and Android Auto support.

## Features

- **Station discovery** — merges three radio-browser.info queries (by language, by name,
  by tag) plus a small hand-curated list, so stations with incomplete metadata still show
  up. Deduplicates mirrors/relays that point at the same stream, filters out sub-128kbps
  junk, and cleans up slug-style names (`tamilrockyfm` → `Tamil Rocky FM`).
- **Reachability check** — dead streams are detected in the background (never blocks the
  UI) and auto-hidden, with a rate-limit guard so a transient network blip can't
  mass-hide the whole list.
- **Favorites / Hidden lists** — star a station to favorite it, or hide ones you don't
  want cluttering the list; both persist across restarts and are reachable from three
  tabs (All / Favorites / Hidden) in the app and mirrored as browse folders in Android
  Auto.
- **Search** — type a name to filter, or type a plain number (e.g. `300`) to filter by
  minimum bitrate.
- **Notification & lock screen controls** — play/pause/prev/next plus a favorite-toggle
  button, backed by a real `MediaLibraryService` so the playlist is shared across every
  surface (phone UI, notification, lock screen, Android Auto).
- **Chromecast** — tap the cast icon to discover and connect to cast devices; playback
  switches to the remote device's `RemoteMediaClient` and back to local automatically
  when the session ends.
- **Android Auto** — full browse tree (All / Favorites / Hidden folders), native
  previous/next, and the same favorite button on the now-playing screen.

## Build

```bash
./gradlew assembleDebug
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A prebuilt debug APK is also kept up to date at
[`releases/mr-radio-debug.apk`](releases/mr-radio-debug.apk).

## Project layout

| File | Responsibility |
|---|---|
| `MainActivity.kt` | UI: station list, search/filter tabs, playback controls, Cast session handling |
| `RadioPlaybackService.kt` | `MediaLibraryService` - owns the ExoPlayer instance and playlist (single source of truth for playback across all surfaces), Android Auto browse tree, notification favorite button |
| `RadioBrowserApi.kt` | Retrofit client for radio-browser.info, merges multiple queries |
| `StationUtils.kt` | Dedup, name prettification, reachability filtering |
| `CustomStations.kt` | Hand-curated stations whose registered API stream URL is dead but whose own site serves a working one |
| `FavoritesStore.kt` / `HiddenStore.kt` | SharedPreferences-backed persistence |
| `StationAdapter.kt` / `item_station.xml` | RecyclerView list row (name, favorite star, hide button, now-playing highlight) |

## Notes

- Streams are aggregated from public radio-browser.info listings, not hosted by this app.
- See `CLAUDE.md` for architecture details and gotchas relevant to making further changes.
