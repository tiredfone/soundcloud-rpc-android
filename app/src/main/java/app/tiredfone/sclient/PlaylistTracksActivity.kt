package app.tiredfone.sclient

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import app.tiredfone.sclient.databinding.ActivityPlaylistTracksBinding
import kotlinx.coroutines.launch

class PlaylistTracksActivity : AppCompatActivity(), PlayerService.PlayerCallback {

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_PLAYLIST_TITLE = "playlist_title"
    }

    private lateinit var binding: ActivityPlaylistTracksBinding
    private lateinit var storage: TokenStorage
    private lateinit var api: SoundCloudApi
    private lateinit var adapter: TrackAdapter

    private var playerService: PlayerService? = null
    private var serviceBound = false
    private var playlistId = -1L
    private var nextHref: String? = null
    private var isLoadingMore = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as PlayerService.LocalBinder
            playerService = localBinder.getService()
            serviceBound = true
            playerService?.addCallback(this@PlaylistTracksActivity)
            onTrackChanged(playerService?.currentTrack)
            onPlayStateChanged(playerService?.player?.isPlaying ?: false)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playerService?.removeCallback(this@PlaylistTracksActivity)
            playerService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistTracksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = TokenStorage(this)
        api = SoundCloudApi(storage)

        playlistId = intent.getLongExtra(EXTRA_PLAYLIST_ID, -1L)
        val playlistTitle = intent.getStringExtra(EXTRA_PLAYLIST_TITLE) ?: "Playlist"

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = playlistTitle
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = TrackAdapter { track -> playTrack(track) }
        adapter.onLikeClick = { track, liked ->
            lifecycleScope.launch {
                val ok = if (liked) api.likeTrack(track.id) else api.unlikeTrack(track.id)
                if (!ok) {
                    if (liked) adapter.removeLikedId(track.id) else adapter.addLikedId(track.id)
                    Toast.makeText(this@PlaylistTracksActivity, "Could not ${if (liked) "like" else "unlike"} track", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)

        setupScrollListener()
        setupMiniPlayer()

        binding.swipeRefresh.setColorSchemeColors(getColor(R.color.accent_orange))
        binding.swipeRefresh.setOnRefreshListener {
            nextHref = null
            adapter.setTracks(emptyList())
            loadTracks()
        }

        startService(Intent(this, PlayerService::class.java))

        if (playlistId != -1L) loadTracks()
    }

    private fun loadTracks() {
        lifecycleScope.launch {
            if (!binding.swipeRefresh.isRefreshing) binding.progressBar.visibility = View.VISIBLE
            val page = api.getPlaylistTracks(playlistId, nextHref)
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            if (page != null) {
                nextHref = page.nextHref
                val tracks = page.collection ?: emptyList()
                if (nextHref == null && adapter.itemCount == 0) {
                    adapter.setTracks(tracks)
                } else {
                    adapter.appendTracks(tracks)
                }
            } else {
                Toast.makeText(this@PlaylistTracksActivity, "Failed to load playlist", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupScrollListener() {
        val layoutManager = binding.recyclerView.layoutManager as LinearLayoutManager
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMore || nextHref == null) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val total = layoutManager.itemCount
                if (lastVisible >= total - 5) loadMore()
            }
        })
    }

    private fun loadMore() {
        val next = nextHref ?: return
        if (isLoadingMore) return
        isLoadingMore = true
        lifecycleScope.launch {
            val page = api.getPlaylistTracks(playlistId, next)
            isLoadingMore = false
            if (page != null) {
                nextHref = page.nextHref
                adapter.appendTracks(page.collection ?: emptyList())
            }
        }
    }

    private fun playTrack(track: ScTrack) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val url = api.resolveStreamUrl(track)
            binding.progressBar.visibility = View.GONE
            if (url == null) {
                Toast.makeText(this@PlaylistTracksActivity, "Could not load track", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val service = playerService
            if (service != null) {
                service.playTrack(track, url)
            } else {
                PlayerService.pendingTrack = track
                PlayerService.pendingUrl = url
                startService(Intent(this@PlaylistTracksActivity, PlayerService::class.java).apply {
                    action = PlayerService.ACTION_PLAY
                })
            }
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

    private fun updateMiniPlayer() {
        val track = playerService?.currentTrack
        if (track == null) {
            binding.miniPlayer.visibility = View.GONE
        } else {
            binding.miniPlayer.visibility = View.VISIBLE
            binding.miniTitle.text = track.displayTitle
            binding.miniArtist.text = track.displayArtist
            binding.miniArtwork.load(track.artworkHigh ?: track.artworkUrl) { crossfade(true) }
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
