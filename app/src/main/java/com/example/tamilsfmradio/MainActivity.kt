package com.example.tamilsfmradio

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.mediarouter.app.MediaRouteButton
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.common.images.WebImage
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata

class MainActivity : AppCompatActivity() {

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private val mediaController: MediaController?
        get() = if (mediaControllerFuture?.isDone == true) mediaControllerFuture?.get() else null

    private lateinit var statusText: TextView
    private lateinit var metadataText: TextView
    private lateinit var prevButton: Button
    private lateinit var playButton: Button
    private lateinit var nextButton: Button
    private lateinit var castButton: MediaRouteButton
    private lateinit var sleepTimerButton: Button
    private lateinit var searchInput: EditText
    private lateinit var showAllButton: Button
    private lateinit var showFavoritesButton: Button
    private lateinit var showHiddenButton: Button
    private lateinit var stationList: RecyclerView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var adapter: RadioStationAdapter

    private enum class ViewMode { ALL, FAVORITES, HIDDEN }

    private var allStations = listOf<RadioStation>()
    private var currentStationId: String? = null
    private var sleepTimerJob: Job? = null
    private var autoReconnectAttempt = 0
    private val maxReconnectAttempts = 3
    private var searchQuery = ""
    private var viewMode = ViewMode.ALL

    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private val isCasting: Boolean
        get() = castSession?.isConnected == true

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        applySystemBarInsets()
        requestNotificationPermissionIfNeeded()
        runOneTimeAutoHideRecovery()

        statusText = findViewById(R.id.statusText)
        metadataText = findViewById(R.id.metadataText)
        prevButton = findViewById(R.id.prevButton)
        playButton = findViewById(R.id.playButton)
        nextButton = findViewById(R.id.nextButton)
        castButton = findViewById(R.id.castButton)
        sleepTimerButton = findViewById(R.id.sleepTimerButton)
        searchInput = findViewById(R.id.searchInput)
        showAllButton = findViewById(R.id.showAllButton)
        showFavoritesButton = findViewById(R.id.showFavoritesButton)
        showHiddenButton = findViewById(R.id.showHiddenButton)
        stationList = findViewById(R.id.stationList)
        loadingSpinner = findViewById(R.id.loadingSpinner)

        stationList.layoutManager = LinearLayoutManager(this)

        loadStationsFromAPI()
        initializeController()
        initializeCastButton()
        setupButtonListeners()
        setupSearchAndFilter()
    }

    private val remoteMediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = castSession?.remoteMediaClient ?: return
            playButton.text = if (client.isPlaying) "⏸" else "▶"
        }
    }

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onCastSessionActive(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onCastSessionActive(session)
        override fun onSessionEnded(session: CastSession, error: Int) = onCastSessionInactive()
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            statusText.text = "Cast failed to connect"
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
    }

    private fun initializeCastButton() {
        try {
            val context = CastContext.getSharedInstance(this)
            castContext = context
            CastButtonFactory.setUpMediaRouteButton(applicationContext, castButton)
            context.sessionManager.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
            context.sessionManager.currentCastSession?.let { onCastSessionActive(it) }
        } catch (e: Exception) {
            // No Google Play services / Cast receiver available on this device - the
            // button just won't do anything, which is fine on a phone-only device.
            Log.w("MainActivity", "Cast unavailable: ${e.message}")
        }
    }

    /** Cast just connected (or we resumed an existing session) - move playback there. */
    private fun onCastSessionActive(session: CastSession) {
        castSession = session
        session.remoteMediaClient?.registerCallback(remoteMediaClientCallback)
        mediaController?.pause()
        val station = allStations.firstOrNull { it.url == currentStationId }
        if (station != null) {
            loadOnCast(station)
        }
    }

    /** Cast disconnected - fall back to playing locally on the phone again. */
    private fun onCastSessionInactive() {
        castSession?.remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
        castSession = null
        val stationId = currentStationId
        if (stationId != null) {
            seekToStationWhenReady(stationId)
        }
    }

    private fun loadOnCast(station: RadioStation) {
        val remoteMediaClient = castSession?.remoteMediaClient ?: return
        currentStationId = station.url
        if (::adapter.isInitialized) highlightSelected()
        statusText.text = "Casting: ${station.name}"
        metadataText.text = "${station.name} • ${station.bitrate}kbps"

        val contentType = when {
            station.codec.contains("AAC", ignoreCase = true) -> "audio/aac"
            station.codec.contains("OGG", ignoreCase = true) -> "audio/ogg"
            else -> "audio/mpeg"
        }
        val castMetadata = CastMediaMetadata(CastMediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(CastMediaMetadata.KEY_TITLE, station.name)
            putString(CastMediaMetadata.KEY_ARTIST, "MR Radio")
            if (station.favicon.isNotBlank()) {
                addImage(WebImage(Uri.parse(station.favicon)))
            }
        }
        val mediaInfo = MediaInfo.Builder(station.url)
            .setContentUrl(station.url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(contentType)
            .setMetadata(castMetadata)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()
        remoteMediaClient.load(request).setResultCallback { result ->
            if (!result.status.isSuccess) {
                Log.e("MainActivity", "Cast load failed: ${result.status.statusMessage} (${result.status.statusCode})")
                statusText.text = "Cast couldn't play this station"
            }
        }
        playButton.text = "⏸"
    }

    private fun setupSearchAndFilter() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString().orEmpty()
                refreshDisplayList()
            }
        })

        showAllButton.setOnClickListener {
            viewMode = ViewMode.ALL
            refreshDisplayList()
        }
        showFavoritesButton.setOnClickListener {
            viewMode = ViewMode.FAVORITES
            refreshDisplayList()
        }
        showHiddenButton.setOnClickListener {
            viewMode = ViewMode.HIDDEN
            refreshDisplayList()
        }
    }

    private fun applySystemBarInsets() {
        val rootLayout = findViewById<View>(R.id.rootLayout)
        val initialPadding = Rect(
            rootLayout.paddingLeft,
            rootLayout.paddingTop,
            rootLayout.paddingRight,
            rootLayout.paddingBottom
        )
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialPadding.left + systemBars.left,
                initialPadding.top + systemBars.top,
                initialPadding.right + systemBars.right,
                initialPadding.bottom + systemBars.bottom
            )
            insets
        }
    }

    /**
     * One-time fixup: an earlier build could auto-hide most of the list in one shot if the
     * reachability check happened to run during a bad network moment (fixed now with a
     * rate-limit guard, but the damage from that older logic is already persisted on-device).
     * Clears only the auto-hidden set - anything the user deliberately hid is untouched.
     */
    private fun runOneTimeAutoHideRecovery() {
        val prefs = getSharedPreferences("mr_radio_prefs", MODE_PRIVATE)
        val key = "migrated_clear_bad_autohide_v1"
        if (!prefs.getBoolean(key, false)) {
            HiddenStore.clearAutoHidden(this)
            prefs.edit().putBoolean(key, true).apply()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadStationsFromAPI() {
        Log.d("MainActivity", "Starting to load FM stations from API")
        loadingSpinner.visibility = View.VISIBLE
        statusText.text = "Loading stations..."
        lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    RadioBrowserClient.getTamilStations()
                }
                Log.d("MainActivity", "API returned ${raw.size} stations")
                val deduped = StationUtils.filterQuality(StationUtils.dedupe(raw))
                    .map { it.copy(name = StationUtils.prettify(it.name)) }

                // Show the list right away - don't make the user wait on the
                // reachability check (which can take several seconds). Dead
                // stations get quietly auto-hidden in the background once it finishes.
                // allStations always holds the full set; HiddenStore controls what
                // refreshDisplayList() actually shows.
                allStations = deduped
                if (allStations.isNotEmpty()) {
                    val visibleCount = allStations.count { !HiddenStore.isHidden(this@MainActivity, it.url) }
                    statusText.text = "Loaded $visibleCount Tamil stations"
                    refreshDisplayList()
                } else {
                    statusText.text = "No stations found"
                    Log.w("MainActivity", "API returned no stations")
                }
                loadingSpinner.visibility = View.GONE

                val working = withContext(Dispatchers.IO) {
                    StationUtils.filterReachable(deduped)
                }
                val newlyDead = deduped.filterNot { d -> working.any { it.url == d.url } }
                // A bad network moment can make many stations fail at once - that's a
                // connectivity problem, not evidence they're all actually dead. Only trust
                // the result enough to auto-hide when it's a small, plausible fraction.
                val failureRate = newlyDead.size.toDouble() / deduped.size.coerceAtLeast(1)
                if (newlyDead.isNotEmpty() && failureRate <= 0.25) {
                    Log.d("MainActivity", "Auto-hiding ${newlyDead.size} dead stations")
                    newlyDead.forEach { HiddenStore.autoHide(this@MainActivity, it.url) }
                    val visibleCount = allStations.count { !HiddenStore.isHidden(this@MainActivity, it.url) }
                    statusText.text = "Loaded $visibleCount Tamil stations"
                    refreshDisplayList()
                } else if (newlyDead.isNotEmpty()) {
                    Log.w(
                        "MainActivity",
                        "Skipping auto-hide: ${newlyDead.size}/${deduped.size} failed reachability - looks like a network issue"
                    )
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading stations: ${e.message}", e)
                statusText.text = "Error loading stations: ${e.message}"
                loadingSpinner.visibility = View.GONE
            }
        }
    }

    private fun refreshDisplayList() {
        val favorites = FavoritesStore.getAll(this)
        val hidden = HiddenStore.getAll(this)
        var filtered = when (viewMode) {
            ViewMode.ALL -> allStations.filter { it.url !in hidden }
            ViewMode.FAVORITES -> allStations.filter { it.url in favorites && it.url !in hidden }
            ViewMode.HIDDEN -> allStations.filter { it.url in hidden }
        }
        val searchAsBitrate = searchQuery.trim().toIntOrNull()
        if (searchAsBitrate != null) {
            // A purely numeric search (e.g. "300") means "300+ kbps", not a name match.
            filtered = filtered.filter { it.bitrate >= searchAsBitrate }
        } else if (searchQuery.isNotBlank()) {
            filtered = filtered.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        val sorted = when {
            searchAsBitrate != null -> filtered.sortedByDescending { it.bitrate }
            viewMode == ViewMode.ALL -> filtered.sortedByDescending { it.url in favorites }
            else -> filtered
        }
        if (::adapter.isInitialized) {
            adapter.updateStations(sorted)
            highlightSelected()
        } else {
            adapter = RadioStationAdapter(
                context = this,
                stations = sorted,
                onClick = { station -> playStation(station) },
                onFavoriteToggle = { station ->
                    FavoritesStore.toggle(this, station.url)
                    refreshDisplayList()
                },
                onHideToggle = { station ->
                    HiddenStore.toggle(this, station.url)
                    refreshDisplayList()
                }
            )
            stationList.adapter = adapter
            highlightSelected()
        }
    }

    private fun highlightSelected() {
        val position = adapter.setSelected(currentStationId)
        if (position >= 0) {
            stationList.scrollToPosition(position)
        }
    }

    private fun initializeController() {
        val sessionToken = SessionToken(this, ComponentName(this, RadioPlaybackService::class.java))
        mediaControllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        mediaControllerFuture?.addListener({
            val controller = mediaControllerFuture?.get() ?: return@addListener
            setupController(controller)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupController(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val playWhenReady = controller.playWhenReady
                when (playbackState) {
                    Player.STATE_BUFFERING -> statusText.text = "Buffering..."
                    Player.STATE_READY -> {
                        if (playWhenReady) {
                            statusText.text = "Playing: ${currentTitle(controller)}"
                            playButton.text = "⏸"
                            autoReconnectAttempt = 0
                        } else {
                            playButton.text = "▶"
                        }
                    }
                    Player.STATE_ENDED -> skipToNext()
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (controller.playbackState == Player.STATE_READY) {
                    if (playWhenReady) {
                        statusText.text = "Playing: ${currentTitle(controller)}"
                        playButton.text = "⏸"
                        autoReconnectAttempt = 0
                    } else {
                        playButton.text = "▶"
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Only auto-reconnect on an unexpected stall (playWhenReady still true but
                // playback dropped) - not on a user-initiated pause, which also sets
                // isPlaying=false but comes with playWhenReady=false.
                if (!isPlaying && controller.playWhenReady && controller.playbackState == Player.STATE_READY) {
                    autoReconnectStream()
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                if (mediaMetadata.title != null) {
                    metadataText.text = "${mediaMetadata.title} • ${mediaMetadata.subtitle ?: ""}"
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentStationId = mediaItem?.mediaId
                if (::adapter.isInitialized) {
                    highlightSelected()
                }
            }
        })

        // Restore UI state if already playing
        if (controller.isPlaying) {
            currentStationId = controller.currentMediaItem?.mediaId
            statusText.text = "Playing: ${currentTitle(controller)}"
            playButton.text = "⏸"
            metadataText.text = "${controller.currentMediaItem?.mediaMetadata?.title ?: ""} • ${controller.currentMediaItem?.mediaMetadata?.subtitle ?: ""}"
            if (::adapter.isInitialized) {
                highlightSelected()
            }
        }
    }

    private fun currentTitle(controller: MediaController): String =
        controller.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Unknown"

    private fun setupButtonListeners() {
        prevButton.setOnClickListener { skipToPrevious() }
        nextButton.setOnClickListener { skipToNext() }

        playButton.setOnClickListener {
            if (isCasting) {
                val client = castSession?.remoteMediaClient ?: return@setOnClickListener
                if (client.isPlaying) client.pause() else client.play()
                return@setOnClickListener
            }
            val controller = mediaController ?: return@setOnClickListener
            if (controller.isPlaying) {
                controller.pause()
                playButton.text = "▶"
            } else {
                controller.prepare()
                controller.play()
                playButton.text = "⏸"
            }
        }

        sleepTimerButton.setOnClickListener { showSleepTimerDialog() }
    }

    /**
     * The service owns the single, shared playlist (so lock screen / notification
     * Previous-Next work natively). We never call setMediaItem(s) from here - we just
     * find the station by id in the controller's own timeline and seek to it, retrying
     * briefly if the service hasn't finished publishing its playlist yet.
     */
    private fun playStation(station: RadioStation) {
        if (isCasting) {
            loadOnCast(station)
            return
        }
        currentStationId = station.url
        if (::adapter.isInitialized) highlightSelected()
        statusText.text = "Connecting: ${station.name}"
        metadataText.text = "${station.name} • ${station.bitrate}kbps"
        seekToStationWhenReady(station.url)
    }

    /**
     * The service runs its own independent fetch + reachability-check pipeline (up to ~15s
     * worst case), so the shared playlist may not be published yet when the user taps a
     * station moments after the on-screen list appears. Poll with a generous wall-clock
     * budget rather than a short retry count.
     */
    private fun seekToStationWhenReady(mediaId: String, deadlineMs: Long = System.currentTimeMillis() + 30_000) {
        val controller = mediaController ?: return
        val index = (0 until controller.mediaItemCount).firstOrNull {
            controller.getMediaItemAt(it).mediaId == mediaId
        }
        if (index != null) {
            controller.seekTo(index, 0)
            controller.prepare()
            controller.play()
            return
        }
        if (System.currentTimeMillis() >= deadlineMs) {
            statusText.text = "Station not ready, please try again"
            return
        }
        lifecycleScope.launch {
            delay(400)
            seekToStationWhenReady(mediaId, deadlineMs)
        }
    }

    private fun skipToNext() {
        if (isCasting) {
            skipCastBy(1)
            return
        }
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) return
        controller.seekToNextMediaItem()
        controller.prepare()
        controller.play()
    }

    private fun skipToPrevious() {
        if (isCasting) {
            skipCastBy(-1)
            return
        }
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) return
        controller.seekToPreviousMediaItem()
        controller.prepare()
        controller.play()
    }

    private fun skipCastBy(step: Int) {
        if (allStations.isEmpty()) return
        val currentIndex = allStations.indexOfFirst { it.url == currentStationId }
        val nextIndex = if (currentIndex == -1) 0 else (currentIndex + step + allStations.size) % allStations.size
        loadOnCast(allStations[nextIndex])
    }

    private fun autoReconnectStream() {
        if (autoReconnectAttempt < maxReconnectAttempts) {
            autoReconnectAttempt++
            statusText.text = "Reconnecting... ($autoReconnectAttempt/$maxReconnectAttempts)"
            lifecycleScope.launch {
                delay(2000)
                mediaController?.let {
                    it.prepare()
                    it.play()
                }
            }
        }
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf("Off", "10 min", "30 min", "1 hour", "2 hours")
        val minutes = intArrayOf(0, 10, 30, 60, 120)

        AlertDialog.Builder(this)
            .setTitle("Sleep Timer")
            .setSingleChoiceItems(options, 0) { dialog, which ->
                setSleepTimer(minutes[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes > 0) {
            sleepTimerButton.text = "Timer: ${minutes}m"
            sleepTimerJob = lifecycleScope.launch {
                delay((minutes * 60L * 1000L))
                mediaController?.stop()
                statusText.text = "Sleep timer ended. Good night!"
                sleepTimerButton.text = "Sleep Timer"
            }
        } else {
            sleepTimerButton.text = "Sleep Timer"
        }
    }

    override fun onDestroy() {
        sleepTimerJob?.cancel()
        castSession?.remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
        castContext?.sessionManager?.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
        mediaControllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        super.onDestroy()
    }
}
