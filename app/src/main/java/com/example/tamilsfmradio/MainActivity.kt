package com.example.tamilsfmradio

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import coil.load
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.material.button.MaterialButtonToggleGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
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
    private lateinit var topCastButton: MediaRouteButton
    private lateinit var overflowMenuButton: Button
    private lateinit var sleepTimerButton: Button
    private lateinit var miniPlayerBar: View
    private lateinit var miniStationLogo: ImageView
    private lateinit var miniStationTitle: TextView
    private lateinit var miniPlayPauseButton: Button
    private lateinit var nowPlayingOverlay: View
    private lateinit var collapseNowPlayingButton: Button
    private lateinit var bigStationLogo: ImageView
    private lateinit var fullFavoriteButton: Button
    private lateinit var searchInput: EditText
    private lateinit var showAllButton: Button
    private lateinit var showFavoritesButton: Button
    private lateinit var showHiddenButton: Button
    private lateinit var sortToggle: MaterialButtonToggleGroup
    private lateinit var sortByQualityButton: Button
    private lateinit var sortByNameButton: Button
    private lateinit var qualityFilterButton: Button
    private lateinit var stationList: RecyclerView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var adapter: RadioStationAdapter

    private enum class ViewMode { ALL, FAVORITES, HIDDEN }
    private enum class SortMode { NONE, QUALITY, NAME }

    private var allStations = listOf<RadioStation>()
    /** The exact list currently shown on screen (after tab/search/sort/filter) - this is what
     *  gets queued to the Cast receiver, so casting prev/next/Assistant "next" stay within
     *  whatever the user is actually looking at (e.g. just Favorites). */
    private var displayedStations = listOf<RadioStation>()
    private var currentStationId: String? = null
    private var sleepTimerJob: Job? = null
    private var autoReconnectAttempt = 0
    private val maxReconnectAttempts = 3
    private var searchQuery = ""
    private var viewMode = ViewMode.FAVORITES
    private var sortMode = SortMode.NONE
    private var bitrateFilter: Int? = null
    private val volumeStep = 0.05
    private var isNowPlayingExpanded = false

    private val nowPlayingBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = collapseNowPlaying()
    }

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
        topCastButton = findViewById(R.id.topCastButton)
        overflowMenuButton = findViewById(R.id.overflowMenuButton)
        sleepTimerButton = findViewById(R.id.sleepTimerButton)
        miniPlayerBar = findViewById(R.id.miniPlayerBar)
        miniStationLogo = findViewById(R.id.miniStationLogo)
        miniStationTitle = findViewById(R.id.miniStationTitle)
        miniPlayPauseButton = findViewById(R.id.miniPlayPauseButton)
        nowPlayingOverlay = findViewById(R.id.nowPlayingOverlay)
        collapseNowPlayingButton = findViewById(R.id.collapseNowPlayingButton)
        bigStationLogo = findViewById(R.id.bigStationLogo)
        fullFavoriteButton = findViewById(R.id.fullFavoriteButton)
        searchInput = findViewById(R.id.searchInput)
        showAllButton = findViewById(R.id.showAllButton)
        showFavoritesButton = findViewById(R.id.showFavoritesButton)
        showHiddenButton = findViewById(R.id.showHiddenButton)
        sortToggle = findViewById(R.id.sortToggle)
        sortByQualityButton = findViewById(R.id.sortByQualityButton)
        sortByNameButton = findViewById(R.id.sortByNameButton)
        qualityFilterButton = findViewById(R.id.qualityFilterButton)
        stationList = findViewById(R.id.stationList)
        loadingSpinner = findViewById(R.id.loadingSpinner)

        stationList.layoutManager = LinearLayoutManager(this)
        onBackPressedDispatcher.addCallback(this, nowPlayingBackCallback)

        initializeController()
        initializeCastButton()
        setupButtonListeners()
        setupSearchAndFilter()
        setupNowPlayingOverlay()
        overflowMenuButton.setOnClickListener { showOverflowMenu() }

        // The station fetch itself waits on a language choice the very first time the app
        // runs - everything else above (controller, cast, listeners) doesn't depend on which
        // language is picked, so it's set up regardless.
        if (LanguagePrefs.isSet(this)) {
            loadStationsFromAPI()
        } else {
            showLanguagePickerDialog(isFirstLaunch = true)
        }
    }

    /**
     * [isFirstLaunch] blocks dismissal (no station language means nothing to load yet) and
     * skips the "did it actually change" check, since there's no current selection to compare
     * against.
     */
    /**
     * Consolidates Cast/Language/Info into one overflow icon instead of three separate ones
     * cluttering the title row. "Cast to device" delegates to topCastButton.performClick() -
     * MediaRouteButton owns real device-picker/route-selection behavior that a plain menu
     * item can't replicate, so the button stays in the layout (just hidden) purely to keep
     * that working under the hood.
     */
    private fun showOverflowMenu() {
        val popup = PopupMenu(this, overflowMenuButton)
        popup.menu.add(0, 0, 0, "Cast to device")
        popup.menu.add(0, 1, 1, "Language")
        popup.menu.add(0, 2, 2, "About")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> topCastButton.performClick()
                1 -> showLanguagePickerDialog(isFirstLaunch = false)
                2 -> showInfoDialog()
            }
            true
        }
        popup.show()
    }

    private fun showLanguagePickerDialog(isFirstLaunch: Boolean) {
        val languages = AppLanguage.entries.toTypedArray()
        val current = if (isFirstLaunch) null else LanguagePrefs.get(this)
        val labels = languages.map { it.displayName }.toTypedArray()
        val dialogBuilder = AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle(if (isFirstLaunch) "Choose your station language" else "Station language")
            .setCancelable(!isFirstLaunch)
            .setItems(labels) { _, index ->
                val chosen = languages[index]
                if (chosen != current) {
                    LanguagePrefs.set(this, chosen)
                    switchLanguage()
                }
            }
        if (!isFirstLaunch) {
            dialogBuilder.setNegativeButton("Cancel", null)
        }
        dialogBuilder.show()
    }

    /** Re-fetches the browsable list for the newly chosen language and tells the playback
     *  service to do the same with its own independent fetch (see CLAUDE.md's dual-fetch
     *  note) - otherwise native prev/next and Android Auto would keep serving the old
     *  language after the on-screen list has already moved on. Anything already playing is
     *  left alone rather than force-stopped - switching language changes what's browsable,
     *  not what's currently in your ears. */
    private fun switchLanguage() {
        loadStationsFromAPI()
        mediaController?.sendCustomCommand(
            SessionCommand(RadioPlaybackService.ACTION_LANGUAGE_CHANGED, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    /**
     * Uses allStations - already in memory for whatever language is currently loaded - rather
     * than fetching fresh, so this opens instantly with no network call. That means it can
     * only report the currently selected language's numbers; switching language (🌐) and
     * reopening this shows the other one.
     */
    private fun showInfoDialog() {
        val language = LanguagePrefs.get(this)
        val tierCounts = allStations.groupingBy { it.bitrate }.eachCount()
            .toSortedMap(compareByDescending { it })
        val breakdown = if (tierCounts.isEmpty()) {
            "No stations loaded yet."
        } else {
            tierCounts.entries.joinToString("\n") { (bitrate, count) ->
                "• ${bitrate}kbps: $count station${if (count == 1) "" else "s"}"
            }
        }
        val message = "MR Radio streams live FM/internet radio, aggregated from " +
            "radio-browser.info plus a hand-verified station list.\n\n" +
            "Current mode: ${language.displayName}\n" +
            "Stations loaded: ${allStations.size}\n\n" +
            "By quality:\n$breakdown\n\n" +
            "Tap 🌐 to switch language and see that mode's count instead."
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("About MR Radio")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private val remoteMediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = castSession?.remoteMediaClient ?: return
            setPlayIcon(client.isPlaying)

            // The receiver's active queue item is the source of truth for "what's playing"
            // once we hand it a queue (native next/prev, Assistant "next", etc. all move the
            // receiver's queue directly without going through this activity).
            val playingUrl = client.mediaStatus?.mediaInfo?.contentId
            if (playingUrl != null && playingUrl != currentStationId) {
                val station = allStations.firstOrNull { it.url == playingUrl }
                if (station != null) {
                    currentStationId = station.url
                    syncStationHeader()
                    statusText.text = "Casting: ${station.name}"
                    metadataText.text = "${station.name} • ${station.bitrate}kbps"
                }
            }
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
            CastButtonFactory.setUpMediaRouteButton(applicationContext, topCastButton)
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
        // Fully stop (not just pause) the local player - the Cast notification now takes
        // over as the playback control surface, so leaving the local session paused-but-alive
        // just leaves a second, confusing notification with controls that don't do anything.
        mediaController?.stop()
        val station = allStations.firstOrNull { it.url == currentStationId }
        if (station != null) {
            castQueue(station)
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

    private fun buildCastMediaInfo(station: RadioStation): MediaInfo {
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
        return MediaInfo.Builder(station.url)
            .setContentUrl(station.url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(contentType)
            .setMetadata(castMetadata)
            .build()
    }

    /**
     * Loads the receiver with a real queue (not a single stream) built from whatever's
     * currently on screen (`displayedStations`), starting at [station]. A real queue is what
     * makes native/Assistant "next" ("Hey Google, next") and hardware skip work while casting -
     * a single loaded item has nothing for the receiver to advance to.
     */
    private fun castQueue(station: RadioStation) {
        val remoteMediaClient = castSession?.remoteMediaClient ?: return
        loadingSpinner.visibility = View.VISIBLE
        currentStationId = station.url
        syncStationHeader()
        statusText.text = "Connecting to cast: ${station.name}"
        metadataText.text = "${station.name} • ${station.bitrate}kbps"

        val queueStations = displayedStations.ifEmpty { listOf(station) }
        val startIndex = queueStations.indexOfFirst { it.url == station.url }.coerceAtLeast(0)
        val queueItems = queueStations.map {
            MediaQueueItem.Builder(buildCastMediaInfo(it)).setAutoplay(true).build()
        }.toTypedArray()

        remoteMediaClient.queueLoad(
            queueItems,
            startIndex,
            MediaStatus.REPEAT_MODE_REPEAT_ALL,
            null
        ).setResultCallback { result ->
            loadingSpinner.visibility = View.GONE
            if (!result.status.isSuccess) {
                Log.e("MainActivity", "Cast queue load failed: ${result.status.statusMessage} (${result.status.statusCode})")
                statusText.text = "Cast couldn't play this station"
            } else {
                statusText.text = "Casting: ${station.name}"
            }
        }
        setPlayIcon(true)
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
            updateToggleStyles()
            refreshDisplayList()
        }
        showFavoritesButton.setOnClickListener {
            viewMode = ViewMode.FAVORITES
            updateToggleStyles()
            refreshDisplayList()
        }
        showHiddenButton.setOnClickListener {
            viewMode = ViewMode.HIDDEN
            updateToggleStyles()
            refreshDisplayList()
        }

        sortToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                // Toggling the active button off (selectionRequired=false) - fall back to default order.
                if (sortToggle.checkedButtonId == View.NO_ID) sortMode = SortMode.NONE
                updateToggleStyles()
                refreshDisplayList()
                return@addOnButtonCheckedListener
            }
            sortMode = when (checkedId) {
                R.id.sortByQualityButton -> SortMode.QUALITY
                R.id.sortByNameButton -> SortMode.NAME
                else -> SortMode.NONE
            }
            updateToggleStyles()
            refreshDisplayList()
        }

        qualityFilterButton.setOnClickListener { showQualityFilterMenu() }

        updateToggleStyles()
    }

    private val toggleInactiveTextColor: Int by lazy { showAllButton.currentTextColor }

    /**
     * Material3's own checked-state tint on these OutlinedButtons turned out to be almost
     * invisible against this app's dark theme (a very dark colorPrimary purple on a near-black
     * background) - and setting app:backgroundTint/textColor directly in XML got silently
     * overridden by the toggle group's own state handling. Driving it explicitly here
     * guarantees the selected tab/sort option is actually unmistakable.
     */
    private fun updateToggleStyles() {
        styleToggleButton(showAllButton, viewMode == ViewMode.ALL)
        styleToggleButton(showFavoritesButton, viewMode == ViewMode.FAVORITES)
        styleToggleButton(showHiddenButton, viewMode == ViewMode.HIDDEN)
        styleToggleButton(sortByQualityButton, sortMode == SortMode.QUALITY)
        styleToggleButton(sortByNameButton, sortMode == SortMode.NAME)
    }

    private fun styleToggleButton(button: Button, active: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(
            if (active) Color.parseColor("#FF6F00") else Color.TRANSPARENT
        )
        button.setTextColor(if (active) Color.WHITE else toggleInactiveTextColor)
    }

    /**
     * Builds its options from whatever bitrates actually exist in the current station list,
     * rather than a couple of hardcoded guesses (previously just 300/320kbps) - the app already
     * drops anything under 128kbps at the source (see StationUtils.filterQuality), so a fixed
     * "120kbps+" option would be indistinguishable from "Any" anyway. Any threshold the data
     * doesn't actually have isn't offered, and every threshold that does exist always is.
     */
    private fun showQualityFilterMenu() {
        val availableBitrates = allStations.map { it.bitrate }.distinct().sortedDescending()
        val popup = PopupMenu(this, qualityFilterButton)
        popup.menu.add(0, 0, 0, "Any quality")
        availableBitrates.forEachIndexed { index, bitrate ->
            popup.menu.add(0, index + 1, index + 1, "${bitrate}kbps+")
        }
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == 0) {
                bitrateFilter = null
                qualityFilterButton.text = "Quality: Any ▾"
            } else {
                val bitrate = availableBitrates[item.itemId - 1]
                bitrateFilter = bitrate
                qualityFilterButton.text = "Quality: ${bitrate}+ ▾"
            }
            refreshDisplayList()
            true
        }
        popup.show()
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
        val language = LanguagePrefs.get(this)
        Log.d("MainActivity", "Starting to load ${language.displayName} FM stations from API")
        loadingSpinner.visibility = View.VISIBLE
        statusText.text = "Loading stations..."
        lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    RadioBrowserClient.getStations(language)
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
                    statusText.text = "Loaded $visibleCount ${language.displayName} stations"
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
                    statusText.text = "Loaded $visibleCount ${language.displayName} stations"
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
        bitrateFilter?.let { minBitrate ->
            filtered = filtered.filter { it.bitrate >= minBitrate }
        }
        val sorted = when {
            sortMode == SortMode.QUALITY -> filtered.sortedWith(
                compareByDescending<RadioStation> { it.url in favorites }.thenByDescending { it.bitrate }
            )
            sortMode == SortMode.NAME -> filtered.sortedWith(
                compareByDescending<RadioStation> { it.url in favorites }.thenBy { it.name.lowercase() }
            )
            searchAsBitrate != null -> filtered.sortedByDescending { it.bitrate }
            viewMode == ViewMode.ALL -> filtered.sortedByDescending { it.url in favorites }
            else -> filtered
        }
        displayedStations = sorted
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
                onHideToggle = { station -> confirmAndToggleHidden(station) }
            )
            stationList.adapter = adapter
            highlightSelected(scrollToPosition = true)
        }
    }

    /**
     * Only confirms when actually hiding - restoring from the Hidden tab is low-risk and
     * easily reversible (just tap it again from All/Favorites), so asking there would just be
     * friction. Matches the existing "↺ vs 🚫" icon swap, which already treats the Hidden tab
     * as a different, softer action than hiding in the first place.
     */
    private fun confirmAndToggleHidden(station: RadioStation) {
        if (HiddenStore.isHidden(this, station.url)) {
            HiddenStore.toggle(this, station.url)
            refreshDisplayList()
            return
        }
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Hide this station?")
            .setMessage("\"${station.name}\" will move to the Hidden tab. You can restore it from there anytime.")
            .setPositiveButton("Hide") { _, _ ->
                HiddenStore.toggle(this, station.url)
                refreshDisplayList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Updates which row shows as "currently playing". Scrolling to reveal it is opt-in
     * ([scrollToPosition]) - refreshDisplayList() calls this on every favorite/hide toggle,
     * search keystroke, and sort/filter change (i.e. far more often than the station itself
     * actually changes), and unconditionally scrolling there would yank the list back to the
     * playing station's position every time, discarding wherever the user was browsing.
     */
    private fun highlightSelected(scrollToPosition: Boolean = false) {
        val position = adapter.setSelected(currentStationId)
        if (scrollToPosition && position >= 0) {
            stationList.scrollToPosition(position)
        }
    }

    private fun setupNowPlayingOverlay() {
        miniPlayerBar.setOnClickListener { expandNowPlaying() }
        collapseNowPlayingButton.setOnClickListener { collapseNowPlaying() }
        fullFavoriteButton.setOnClickListener {
            val id = currentStationId ?: return@setOnClickListener
            FavoritesStore.toggle(this, id)
            updateFullFavoriteButton()
            refreshDisplayList()
        }
    }

    private fun expandNowPlaying() {
        if (isNowPlayingExpanded) return
        isNowPlayingExpanded = true
        val slideDistance = 48f * resources.displayMetrics.density
        nowPlayingOverlay.visibility = View.VISIBLE
        nowPlayingOverlay.alpha = 0f
        nowPlayingOverlay.translationY = slideDistance
        nowPlayingOverlay.animate().alpha(1f).translationY(0f).setDuration(220).start()
        nowPlayingBackCallback.isEnabled = true
    }

    private fun collapseNowPlaying() {
        if (!isNowPlayingExpanded) return
        isNowPlayingExpanded = false
        val slideDistance = 48f * resources.displayMetrics.density
        nowPlayingOverlay.animate()
            .alpha(0f)
            .translationY(slideDistance)
            .setDuration(180)
            .withEndAction { nowPlayingOverlay.visibility = View.GONE }
            .start()
        nowPlayingBackCallback.isEnabled = false
    }

    /** Mirrors play/pause state onto both the mini-player bar and the full-screen player. */
    private fun setPlayIcon(isPlaying: Boolean) {
        val icon = if (isPlaying) "⏸" else "▶"
        playButton.text = icon
        miniPlayPauseButton.text = icon
    }

    /** Mirrors the current station's title/art onto both the mini-player bar and the
     *  full-screen player, so neither can drift out of sync with the other. */
    private fun updateNowPlayingHeader(title: String, artworkUrl: String?) {
        miniStationTitle.text = title
        val art = artworkUrl?.takeIf { it.isNotBlank() }
        miniStationLogo.load(art) {
            placeholder(R.drawable.ic_radio_placeholder)
            error(R.drawable.ic_radio_placeholder)
            crossfade(true)
        }
        bigStationLogo.load(art) {
            placeholder(R.drawable.ic_radio_placeholder)
            error(R.drawable.ic_radio_placeholder)
            crossfade(true)
        }
    }

    private fun updateFullFavoriteButton() {
        val id = currentStationId
        val isFavorite = id != null && FavoritesStore.isFavorite(this, id)
        fullFavoriteButton.text = if (isFavorite) "★" else "☆"
        fullFavoriteButton.setTextColor(if (isFavorite) Color.parseColor("#FF6F00") else Color.WHITE)
    }

    /**
     * Keeps every "what's currently selected/playing" surface in sync after currentStationId
     * changes - the list highlight, the mini-player bar, the full-screen header, and its
     * favorite toggle - from one place instead of scattered across every call site that can
     * change the current station (local play, cast, native prev/next, session restore).
     */
    private fun syncStationHeader() {
        if (::adapter.isInitialized) highlightSelected(scrollToPosition = true)
        val station = allStations.firstOrNull { it.url == currentStationId }
        if (station != null) {
            updateNowPlayingHeader(station.name, station.favicon)
        }
        updateFullFavoriteButton()
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
                    Player.STATE_BUFFERING -> {
                        loadingSpinner.visibility = View.VISIBLE
                        statusText.text = "Buffering..."
                    }
                    Player.STATE_READY -> {
                        loadingSpinner.visibility = View.GONE
                        if (playWhenReady) {
                            statusText.text = "Playing: ${currentTitle(controller)}"
                            setPlayIcon(true)
                            autoReconnectAttempt = 0
                        } else {
                            setPlayIcon(false)
                        }
                    }
                    Player.STATE_ENDED -> skipToNext()
                    Player.STATE_IDLE -> loadingSpinner.visibility = View.GONE
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                loadingSpinner.visibility = View.GONE
                statusText.text = "Playback error - tap a station to retry"
                Log.w("MainActivity", "Player error: ${error.message}", error)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (controller.playbackState == Player.STATE_READY) {
                    if (playWhenReady) {
                        statusText.text = "Playing: ${currentTitle(controller)}"
                        setPlayIcon(true)
                        autoReconnectAttempt = 0
                    } else {
                        setPlayIcon(false)
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
                // The service puts the live "now playing" song (from ICY stream metadata,
                // where the station sends it) in the artist field, keeping title reserved for
                // the station's own identity - see RadioPlaybackService.updateNowPlayingSong().
                // "MR Radio" is the generic placeholder every station starts with before any
                // ICY data has arrived (if it ever does), so it doesn't count as real song info.
                val songTitle = mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() && it != "MR Radio" }
                val displayTitle = songTitle ?: mediaMetadata.title
                if (displayTitle != null) {
                    metadataText.text = "$displayTitle • ${mediaMetadata.subtitle ?: ""}"
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentStationId = mediaItem?.mediaId
                syncStationHeader()
            }
        })

        // Restore UI state if already playing
        if (controller.isPlaying) {
            currentStationId = controller.currentMediaItem?.mediaId
            statusText.text = "Playing: ${currentTitle(controller)}"
            setPlayIcon(true)
            metadataText.text = "${controller.currentMediaItem?.mediaMetadata?.title ?: ""} • ${controller.currentMediaItem?.mediaMetadata?.subtitle ?: ""}"
            syncStationHeader()
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
                setPlayIcon(false)
            } else {
                controller.prepare()
                controller.play()
                setPlayIcon(true)
            }
        }

        sleepTimerButton.setOnClickListener { showSleepTimerDialog() }
        miniPlayPauseButton.setOnClickListener { playButton.performClick() }
    }

    /**
     * The service owns the single, shared playlist (so lock screen / notification
     * Previous-Next work natively). We never call setMediaItem(s) from here - we just
     * find the station by id in the controller's own timeline and seek to it, retrying
     * briefly if the service hasn't finished publishing its playlist yet.
     */
    private fun playStation(station: RadioStation) {
        if (isCasting) {
            castQueue(station)
            return
        }
        currentStationId = station.url
        syncStationHeader()
        loadingSpinner.visibility = View.VISIBLE
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
        skipLocalWithinTab(1)
    }

    private fun skipToPrevious() {
        if (isCasting) {
            skipCastBy(-1)
            return
        }
        skipLocalWithinTab(-1)
    }

    /**
     * Moves within displayedStations - whatever tab/search/sort/filter is currently on screen
     * - instead of the service's full ALL-stations timeline (via seekToNextMediaItem(), which
     * knows nothing about the phone UI's tabs). So prev/next on the Favorites tab stays within
     * favorites, matching the same scoping already used for the Cast queue.
     */
    private fun skipLocalWithinTab(step: Int) {
        if (displayedStations.isEmpty()) return
        val currentIndex = displayedStations.indexOfFirst { it.url == currentStationId }
        val nextIndex = if (currentIndex == -1) 0 else (currentIndex + step + displayedStations.size) % displayedStations.size
        playStation(displayedStations[nextIndex])
    }

    /**
     * Advances the receiver's own queue rather than reloading a new single item - this keeps
     * native prev/next, the notification, and Assistant ("Hey Google, next") all driving the
     * same queue instead of racing with app-initiated reloads.
     */
    private fun skipCastBy(step: Int) {
        val client = castSession?.remoteMediaClient ?: return
        if (step > 0) client.queueNext(null) else client.queuePrev(null)
    }

    private fun autoReconnectStream() {
        if (autoReconnectAttempt < maxReconnectAttempts) {
            autoReconnectAttempt++
            loadingSpinner.visibility = View.VISIBLE
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

    /**
     * While casting, the phone isn't playing any local audio stream, so the hardware volume
     * keys have nothing to act on by default and Android just adjusts an unrelated stream.
     * Intercept them here and drive the Cast device's own volume instead.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (isCasting && event.action == android.view.KeyEvent.ACTION_DOWN &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            adjustCastVolume(if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) volumeStep else -volumeStep)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun adjustCastVolume(delta: Double) {
        val session = castSession ?: return
        try {
            val newVolume = (session.volume + delta).coerceIn(0.0, 1.0)
            session.volume = newVolume
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to adjust cast volume: ${e.message}")
        }
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf("Off", "10 min", "30 min", "1 hour", "2 hours")
        val minutes = intArrayOf(0, 10, 30, 60, 120)

        AlertDialog.Builder(this, R.style.AlertDialogTheme)
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
