package app.tiredfone.sclient

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.tabs.TabLayout
import app.tiredfone.sclient.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity(), PlayerService.PlayerCallback {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var storage: TokenStorage
    private lateinit var api: SoundCloudApi
    private lateinit var adapter: TrackAdapter
    private lateinit var playlistAdapter: PlaylistAdapter

    private var playerService: PlayerService? = null
    private var serviceBound = false

    private val streamTracks = mutableListOf<ScTrack>()
    private val likeTracks = mutableListOf<ScTrack>()
    private val searchResults = mutableListOf<ScTrack>()
    private val playlists = mutableListOf<ScPlaylist>()

    private var streamNextHref: String? = null
    private var likesNextHref: String? = null
    private var searchNextHref: String? = null
    private var playlistsNextHref: String? = null

    private var isLoadingMore = false
    private var isSearching = false
    private var currentQuery = ""

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as PlayerService.LocalBinder
            playerService = localBinder.getService()
            serviceBound = true
            playerService?.addCallback(this@HomeActivity)
            onTrackChanged(playerService?.currentTrack)
            onPlayStateChanged(playerService?.player?.isPlaying ?: false)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playerService?.removeCallback(this@HomeActivity)
            playerService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = TokenStorage(this)
        api = SoundCloudApi(storage)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setTitleTextColor(getColor(R.color.accent_orange))

        adapter = TrackAdapter { track -> playTrack(track) }
        adapter.onLikeClick = { track, liked ->
            lifecycleScope.launch {
                if (liked) api.likeTrack(track.id) else api.unlikeTrack(track.id)
            }
        }
        adapter.onLongClick = { track -> showAddToPlaylistMenu(track) }

        playlistAdapter = PlaylistAdapter { playlist ->
            val intent = Intent(this, PlaylistTracksActivity::class.java).apply {
                putExtra(PlaylistTracksActivity.EXTRA_PLAYLIST_ID, playlist.id)
                putExtra(PlaylistTracksActivity.EXTRA_PLAYLIST_TITLE, playlist.displayTitle)
            }
            startActivity(intent)
        }

        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)

        setupScrollListener()
        setupTabs()
        setupMiniPlayer()
        setupFab()

        startService(Intent(this, PlayerService::class.java))

        if (storage.isConfigured()) {
            startService(Intent(this, RpcService::class.java))
        }

        loadStream()
        showWhatsNewIfUpdated()
    }

    private fun showWhatsNewIfUpdated() {
        val prefs = getSharedPreferences("rpc_prefs", MODE_PRIVATE)
        val currentCode = packageManager.getPackageInfo(packageName, 0).versionCode
        val lastSeenCode = prefs.getInt("last_seen_version", 0)
        if (currentCode <= lastSeenCode) return
        prefs.edit().putInt("last_seen_version", currentCode).apply()

        val latest = Changelog.entries.firstOrNull() ?: return
        val message = latest.changes.joinToString("\n") { "• $it" }
        AlertDialog.Builder(this)
            .setTitle("What's New in v${latest.version}")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Full Changelog") { _, _ ->
                startActivity(Intent(this, ChangelogActivity::class.java))
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_home, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = "Search tracks…"

        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val q = query?.trim() ?: return false
                if (q.isEmpty()) return false
                currentQuery = q
                isSearching = true
                searchResults.clear()
                searchNextHref = null
                doSearch(q)
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?) = false
        })

        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (isSearching) {
                    isSearching = false
                    currentQuery = ""
                    searchResults.clear()
                    val tab = binding.tabLayout.selectedTabPosition
                    when (tab) {
                        0 -> { binding.recyclerView.adapter = adapter; adapter.setTracks(streamTracks) }
                        1 -> { binding.recyclerView.adapter = adapter; adapter.setTracks(likeTracks) }
                        2 -> binding.recyclerView.adapter = playlistAdapter
                    }
                }
                return true
            }
        })

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_logs -> {
                startActivity(Intent(this, LogViewerActivity::class.java))
                true
            }
            R.id.action_changelog -> {
                startActivity(Intent(this, ChangelogActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupScrollListener() {
        val layoutManager = binding.recyclerView.layoutManager as LinearLayoutManager
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMore) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val total = layoutManager.itemCount
                if (lastVisible >= total - 5) loadMore()
            }
        })
    }

    private fun loadMore() {
        if (isLoadingMore) return
        when {
            isSearching -> {
                val next = searchNextHref ?: return
                isLoadingMore = true
                lifecycleScope.launch {
                    val page = api.searchTracks(currentQuery, next)
                    isLoadingMore = false
                    if (page != null) {
                        val newTracks = page.collection ?: emptyList()
                        searchResults.addAll(newTracks)
                        searchNextHref = page.nextHref
                        adapter.appendTracks(newTracks)
                    }
                }
            }
            binding.tabLayout.selectedTabPosition == 0 -> {
                val next = streamNextHref ?: return
                isLoadingMore = true
                lifecycleScope.launch {
                    val page = api.getStream(next)
                    isLoadingMore = false
                    if (page != null) {
                        val newTracks = page.collection
                            ?.filter { it.type == "track" || it.type == "track_repost" }
                            ?.mapNotNull { it.track }
                            ?: emptyList()
                        streamTracks.addAll(newTracks)
                        streamNextHref = page.nextHref
                        adapter.appendTracks(newTracks)
                    }
                }
            }
            binding.tabLayout.selectedTabPosition == 1 -> {
                val next = likesNextHref ?: return
                isLoadingMore = true
                lifecycleScope.launch {
                    val page = api.getLikes(next)
                    isLoadingMore = false
                    if (page != null) {
                        val newTracks = page.collection ?: emptyList()
                        likeTracks.addAll(newTracks)
                        likesNextHref = page.nextHref
                        adapter.appendTracks(newTracks)
                    }
                }
            }
            binding.tabLayout.selectedTabPosition == 2 -> {
                val next = playlistsNextHref ?: return
                isLoadingMore = true
                lifecycleScope.launch {
                    val page = api.getPlaylists(next)
                    isLoadingMore = false
                    if (page != null) {
                        val newPlaylists = page.collection ?: emptyList()
                        playlists.addAll(newPlaylists)
                        playlistsNextHref = page.nextHref
                        playlistAdapter.appendPlaylists(newPlaylists)
                    }
                }
            }
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Stream"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Likes"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Playlists"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (isSearching) return
                when (tab?.position) {
                    0 -> {
                        binding.recyclerView.adapter = adapter
                        binding.fabCreatePlaylist.visibility = View.GONE
                        if (streamTracks.isNotEmpty()) adapter.setTracks(streamTracks) else loadStream()
                    }
                    1 -> {
                        binding.recyclerView.adapter = adapter
                        binding.fabCreatePlaylist.visibility = View.GONE
                        if (likeTracks.isNotEmpty()) adapter.setTracks(likeTracks) else loadLikes()
                    }
                    2 -> {
                        binding.recyclerView.adapter = playlistAdapter
                        binding.fabCreatePlaylist.visibility = View.VISIBLE
                        if (playlists.isNotEmpty()) playlistAdapter.setPlaylists(playlists) else loadPlaylists()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupFab() {
        binding.fabCreatePlaylist.setOnClickListener {
            val input = EditText(this).apply { hint = "Playlist name" }
            AlertDialog.Builder(this)
                .setTitle("New Playlist")
                .setView(input)
                .setPositiveButton("Create") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        lifecycleScope.launch {
                            val pl = api.createPlaylist(name)
                            if (pl != null) {
                                playlists.add(0, pl)
                                playlistAdapter.setPlaylists(playlists)
                                Toast.makeText(this@HomeActivity, "Playlist created", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@HomeActivity, "Failed to create playlist", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupMiniPlayer() {
        binding.miniPlayer.setOnClickListener {
            startActivity(Intent(this, NowPlayingActivity::class.java))
        }
        binding.miniPlayPause.setOnClickListener {
            playerService?.player?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        }
        binding.miniPlayer.visibility = View.GONE
    }

    private fun loadStream() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val page = api.getStream()
            binding.progressBar.visibility = View.GONE
            if (page != null) {
                val tracks = page.collection
                    ?.filter { it.type == "track" || it.type == "track_repost" }
                    ?.mapNotNull { it.track }
                    ?: emptyList()
                streamNextHref = page.nextHref
                streamTracks.clear()
                streamTracks.addAll(tracks)
                if (binding.tabLayout.selectedTabPosition == 0 && !isSearching) {
                    adapter.setTracks(streamTracks)
                }
            } else {
                Toast.makeText(this@HomeActivity, "Failed to load stream", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadLikes() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val page = api.getLikes()
            binding.progressBar.visibility = View.GONE
            if (page != null) {
                val tracks = page.collection ?: emptyList()
                likesNextHref = page.nextHref
                likeTracks.clear()
                likeTracks.addAll(tracks)
                if (binding.tabLayout.selectedTabPosition == 1 && !isSearching) {
                    adapter.setTracks(likeTracks)
                }
            } else {
                Toast.makeText(this@HomeActivity, "Failed to load likes", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadPlaylists() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val page = api.getPlaylists()
            binding.progressBar.visibility = View.GONE
            if (page != null) {
                val list = page.collection ?: emptyList()
                playlistsNextHref = page.nextHref
                playlists.clear()
                playlists.addAll(list)
                if (binding.tabLayout.selectedTabPosition == 2 && !isSearching) {
                    playlistAdapter.setPlaylists(playlists)
                }
            } else {
                Toast.makeText(this@HomeActivity, "Failed to load playlists", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun doSearch(query: String) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val page = api.searchTracks(query)
            binding.progressBar.visibility = View.GONE
            if (page != null) {
                val tracks = page.collection ?: emptyList()
                searchNextHref = page.nextHref
                searchResults.clear()
                searchResults.addAll(tracks)
                binding.recyclerView.adapter = adapter
                adapter.setTracks(searchResults)
            } else {
                Toast.makeText(this@HomeActivity, "Search failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playTrack(track: ScTrack) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val url = api.resolveStreamUrl(track)
            binding.progressBar.visibility = View.GONE
            if (url == null) {
                Toast.makeText(this@HomeActivity, "Could not load track", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val service = playerService
            if (service != null) {
                service.playTrack(track, url)
            } else {
                PlayerService.pendingTrack = track
                PlayerService.pendingUrl = url
                startService(Intent(this@HomeActivity, PlayerService::class.java).apply {
                    action = PlayerService.ACTION_PLAY
                })
            }
        }
    }

    private fun showAddToPlaylistMenu(track: ScTrack) {
        lifecycleScope.launch {
            if (playlists.isEmpty()) {
                val page = api.getPlaylists()
                if (page != null) {
                    playlists.clear()
                    playlists.addAll(page.collection ?: emptyList())
                    playlistsNextHref = page.nextHref
                }
            }
            val options = (playlists.map { it.displayTitle } + listOf("+ Create new")).toTypedArray()
            AlertDialog.Builder(this@HomeActivity)
                .setTitle("Add to playlist")
                .setItems(options) { _, which ->
                    if (which == options.size - 1) {
                        val input = EditText(this@HomeActivity).apply { hint = "Playlist name" }
                        AlertDialog.Builder(this@HomeActivity)
                            .setTitle("New Playlist")
                            .setView(input)
                            .setPositiveButton("Create") { _, _ ->
                                val name = input.text.toString().trim()
                                if (name.isNotEmpty()) {
                                    lifecycleScope.launch {
                                        val pl = api.createPlaylist(name, listOf(track.id))
                                        if (pl != null) {
                                            playlists.add(0, pl)
                                            playlistAdapter.setPlaylists(playlists)
                                            Toast.makeText(this@HomeActivity, "Playlist created", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(this@HomeActivity, "Failed to create playlist", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        val playlist = playlists[which]
                        lifecycleScope.launch {
                            val existingIds = playlist.tracks?.map { it.id } ?: emptyList()
                            val ok = api.addTrackToPlaylist(playlist.id, track.id, existingIds)
                            Toast.makeText(
                                this@HomeActivity,
                                if (ok) "Added to ${playlist.displayTitle}" else "Failed to add track",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                .show()
        }
    }

    private fun updateMiniPlayer() {
        val track = playerService?.currentTrack
        if (track == null) {
            binding.miniPlayer.visibility = View.GONE
        } else {
            binding.miniPlayer.visibility = View.VISIBLE
            binding.miniTitle.text = track.displayTitle
            binding.miniArtist.text = track.displayArtist
            binding.miniArtwork.load(track.artworkHigh ?: track.artworkUrl) {
                crossfade(true)
            }
            binding.miniPlayPause.setImageResource(
                if (playerService?.player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
    }

    override fun onTrackChanged(track: ScTrack?) {
        runOnUiThread {
            updateMiniPlayer()
            adapter.setCurrentTrack(track?.id)
        }
    }

    override fun onPlayStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            binding.miniPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PlayerService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            playerService?.removeCallback(this)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
