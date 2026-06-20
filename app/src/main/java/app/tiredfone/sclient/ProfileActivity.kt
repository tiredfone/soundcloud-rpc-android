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
import coil.load
import coil.transform.CircleCropTransformation
import app.tiredfone.sclient.databinding.ActivityProfileBinding
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity(), PlayerService.PlayerCallback {

    companion object {
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_USERNAME = "username"
    }

    private lateinit var binding: ActivityProfileBinding
    private lateinit var storage: TokenStorage
    private lateinit var api: SoundCloudApi
    private lateinit var adapter: TrackAdapter

    private var playerService: PlayerService? = null
    private var serviceBound = false
    private var userId = 0L
    private var nextHref: String? = null
    private var isLoadingMore = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as PlayerService.LocalBinder
            playerService = localBinder.getService()
            serviceBound = true
            playerService?.addCallback(this@ProfileActivity)
            onTrackChanged(playerService?.currentTrack)
            onPlayStateChanged(playerService?.player?.isPlaying ?: false)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playerService?.removeCallback(this@ProfileActivity)
            playerService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = TokenStorage(this)
        api = SoundCloudApi(storage)

        userId = intent.getLongExtra(EXTRA_USER_ID, 0L)
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = username
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.tvUsername.text = username

        adapter = TrackAdapter { track -> playTrack(track) }
        adapter.onLikeClick = { track, liked ->
            lifecycleScope.launch {
                if (liked) api.likeTrack(track.id) else api.unlikeTrack(track.id)
            }
        }

        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)
        setupScrollListener()
        setupMiniPlayer()
        startService(Intent(this, PlayerService::class.java))

        if (userId != 0L) {
            loadProfile()
            loadTracks()
        }
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            val user = api.getUser(userId) ?: return@launch
            binding.tvUsername.text = user.username ?: ""
            supportActionBar?.title = user.username ?: ""
            binding.ivAvatar.load(user.avatarUrl?.replace("-large.", "-t300x300.")) {
                crossfade(true)
                transformations(CircleCropTransformation())
                placeholder(R.drawable.ic_person)
            }
        }
    }

    private fun loadTracks() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val page = api.getUserTracks(userId)
            binding.progressBar.visibility = View.GONE
            if (page != null) {
                nextHref = page.nextHref
                adapter.setTracks(page.collection ?: emptyList())
            } else {
                Toast.makeText(this@ProfileActivity, "Failed to load tracks", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupScrollListener() {
        val layoutManager = binding.recyclerView.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMore) return
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
            val page = api.getUserTracks(userId, next)
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
                Toast.makeText(this@ProfileActivity, "Could not load track", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val service = playerService
            if (service != null) {
                service.playTrack(track, url)
            } else {
                PlayerService.pendingTrack = track
                PlayerService.pendingUrl = url
                startService(Intent(this@ProfileActivity, PlayerService::class.java).apply {
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
