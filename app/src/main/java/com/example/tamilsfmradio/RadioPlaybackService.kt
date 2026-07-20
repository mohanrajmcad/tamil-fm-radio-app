package com.example.tamilsfmradio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RadioPlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "RadioPlaybackService"
    }

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var cachedMediaItems = listOf<MediaItem>()

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this).build().apply {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build()
            setAudioAttributes(audioAttributes, true)
            // Keep the network stream alive while the screen is off / device is locked.
            setWakeMode(C.WAKE_MODE_NETWORK)
        }

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        // Without this, Media3 falls back to its own generic "circular play" icon in the
        // status bar/notification instead of anything resembling this app's branding.
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(R.drawable.ic_notification)
        setMediaNotificationProvider(notificationProvider)

        // Fetch stations proactively in the background
        fetchStations()
    }

    private fun fetchStations() {
        Log.d(TAG, "fetchStations: starting")
        serviceScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    RadioBrowserClient.getTamilStations()
                }
                val deduped = StationUtils.filterQuality(StationUtils.dedupe(raw))
                    .map { it.copy(name = StationUtils.prettify(it.name)) }

                // Publish the playlist immediately rather than waiting on the
                // reachability check, so a tap from the UI can seek/play right away.
                cachedMediaItems = deduped.map { it.toMediaItem() }
                setPlayerPlaylist(cachedMediaItems)
                Log.d(TAG, "fetchStations: quick-set ${cachedMediaItems.size} stations")

                val working = withContext(Dispatchers.IO) {
                    StationUtils.filterReachable(deduped)
                }
                if (working.size != deduped.size && working.isNotEmpty()) {
                    Log.d(TAG, "fetchStations: pruned to ${working.size} stations")
                    val newlyDead = deduped.filterNot { d -> working.any { it.url == d.url } }
                    newlyDead.forEach { HiddenStore.hide(this@RadioPlaybackService, it.url) }
                    cachedMediaItems = working.map { it.toMediaItem() }
                    // Don't yank the timeline out from under something that's actually
                    // playing - only swap the live player if it's still in the set,
                    // preserving its position; otherwise the trimmed list just applies
                    // to whatever gets browsed/seeked next.
                    val currentId = player.currentMediaItem?.mediaId
                    val newIndex = working.indexOfFirst { it.url == currentId }
                    if (newIndex >= 0) {
                        setPlayerPlaylist(cachedMediaItems, newIndex, player.currentPosition)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchStations: failed", e)
            }
        }
    }

    /**
     * Give the player the full, deduped station list directly (same process, no controller
     * round-trip) so the system notification and lock screen can show working
     * Previous/Next buttons. A single item in an unsupported format (e.g. a stream type
     * with no matching media source factory) makes ExoPlayer reject the *entire* batch, so
     * on failure we retry once with only plain-looking stream URLs.
     */
    private fun setPlayerPlaylist(items: List<MediaItem>, startIndex: Int = 0, startPositionMs: Long = 0) {
        if (items.isEmpty()) return
        try {
            player.setMediaItems(items, startIndex.coerceIn(items.indices), startPositionMs)
            player.repeatMode = Player.REPEAT_MODE_ALL
        } catch (e: Exception) {
            Log.e(TAG, "setPlayerPlaylist: rejected, retrying with safe subset", e)
            val safeItems = items.filterNot { item ->
                val path = item.mediaId.substringBefore('?').lowercase()
                path.endsWith(".m3u8") || path.endsWith(".mpd")
            }
            if (safeItems.isNotEmpty() && safeItems.size != items.size) {
                player.setMediaItems(safeItems)
                player.repeatMode = Player.REPEAT_MODE_ALL
            }
        }
    }

    private suspend fun prepareStations(): List<RadioStation> {
        val raw = withContext(Dispatchers.IO) {
            RadioBrowserClient.getTamilStations()
        }
        val deduped = StationUtils.filterQuality(StationUtils.dedupe(raw))
            .map { it.copy(name = StationUtils.prettify(it.name)) }
        return withContext(Dispatchers.IO) {
            StationUtils.filterReachable(deduped)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    private val callback = object : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId("ROOT")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsPlayable(false)
                        .setIsBrowsable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .setTitle("MR Radio Stations")
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId == "ROOT") {
                if (cachedMediaItems.isNotEmpty()) {
                    return Futures.immediateFuture(
                        LibraryResult.ofItemList(ImmutableList.copyOf(cachedMediaItems), params)
                    )
                }

                // If cache is empty, fetch stations inline asynchronously
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                serviceScope.launch {
                    try {
                        val stations = prepareStations()
                        cachedMediaItems = stations.map { it.toMediaItem() }
                        setPlayerPlaylist(cachedMediaItems)
                        future.set(
                            LibraryResult.ofItemList(ImmutableList.copyOf(cachedMediaItems), params)
                        )
                    } catch (e: Exception) {
                        future.setException(e)
                    }
                }
                return future
            }

            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolvedItems = mediaItems.map { item ->
                val cached = cachedMediaItems.find { it.mediaId == item.mediaId }
                cached ?: item.buildUpon().setUri(Uri.parse(item.mediaId)).build()
            }.toMutableList()
            return Futures.immediateFuture(resolvedItems)
        }

        // Called when something outside the app (Bluetooth headset, Android Auto,
        // the lock screen) sends a bare "play" command with no media item loaded yet.
        // Without this, Media3 throws UnsupportedOperationException and the request
        // is silently dropped.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            if (cachedMediaItems.isNotEmpty()) {
                future.set(MediaSession.MediaItemsWithStartPosition(cachedMediaItems, 0, 0L))
            } else {
                future.setException(UnsupportedOperationException("No cached stations to resume"))
            }
            return future
        }
    }

    private fun RadioStation.toMediaItem(): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(name)
            .setArtist("MR Radio")
            .setSubtitle("${bitrate}kbps")
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        if (favicon.isNotBlank()) {
            metadataBuilder.setArtworkUri(Uri.parse(favicon))
        }

        return MediaItem.Builder()
            .setMediaId(url)
            .setMediaMetadata(metadataBuilder.build())
            .setUri(Uri.parse(url))
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaLibrarySession.release()
        player.release()
        super.onDestroy()
    }
}
