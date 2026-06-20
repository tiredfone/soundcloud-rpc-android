package com.tiredfone.soundcloudrpc

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.tabs.TabLayout
import com.tiredfone.soundcloudrpc.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity(), PlayerService.PlayerCallback {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var storage: TokenStorage
    private lateinit var api: SoundCloudApi
    private lateinit var adapter: TrackAdapter

    private var playerService: PlayerService? = null
    private var serviceBound = false

    private val streamTracks = mutableListOf<ScTrack>()
    private val likeTracks = mutableListOf<ScTrack>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as PlayerService.LocalBinder
            playerService = localBinder.getService()
            serviceBound = true
            playerService?.addCallback(this@HomeActivity)
            // Refresh mini player with current state
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
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)

        setupTabs()
        setupMiniPlayer()

        // Start PlayerService and bind to it
        startService(Intent(this, PlayerService::class.java))

        loadStream()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_home, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Stream"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Likes"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        if (streamTracks.isNotEmpty()) {
                            adapter.setTracks(streamTracks)
                        } else {
                            loadStream()
                        }
                    }
                    1 -> {
                        if (likeTracks.isNotEmpty()) {
                            adapter.setTracks(likeTracks)
                        } else {
                            loadLikes()
                        }
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
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
                    .filter { it.type == "track" || it.type == "track_repost" }
                    .mapNotNull { it.track }
                streamTracks.clear()
                streamTracks.addAll(tracks)
                adapter.setTracks(streamTracks)
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
                likeTracks.clear()
                likeTracks.addAll(page.collection)
                adapter.setTracks(likeTracks)
            } else {
                Toast.makeText(this@HomeActivity, "Failed to load likes", Toast.LENGTH_SHORT).show()
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

    private fun updateMiniPlayer() {
        val track = playerService?.currentTrack
        if (track == null) {
            binding.miniPlayer.visibility = View.GONE
        } else {
            binding.miniPlayer.visibility = View.VISIBLE
            binding.miniTitle.text = track.title
            binding.miniArtist.text = track.user.username
            binding.miniArtwork.load(track.artworkHigh ?: track.artworkUrl) {
                crossfade(true)
            }
            val isPlaying = playerService?.player?.isPlaying ?: false
            binding.miniPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
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
        val intent = Intent(this, PlayerService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
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
