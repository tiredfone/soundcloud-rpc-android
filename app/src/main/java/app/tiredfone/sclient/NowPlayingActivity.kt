package app.tiredfone.sclient

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import coil.request.SuccessResult
import app.tiredfone.sclient.databinding.ActivityNowPlayingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NowPlayingActivity : AppCompatActivity(), PlayerService.PlayerCallback {

    private lateinit var binding: ActivityNowPlayingBinding
    private lateinit var storage: TokenStorage
    private lateinit var api: SoundCloudApi
    private lateinit var audioManager: AudioManager

    private var playerService: PlayerService? = null
    private var serviceBound = false
    private var isLiked = false
    private var shuffleEnabled = false
    private var repeatEnabled = false

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            runOnUiThread {
                binding.btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                runOnUiThread { updateSeekBar() }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as PlayerService.LocalBinder
            playerService = localBinder.getService()
            serviceBound = true
            playerService?.addCallback(this@NowPlayingActivity)
            playerService?.player?.addListener(playerListener)
            updateUI()
            if (playerService?.player?.isPlaying == true) startProgressUpdates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playerService?.player?.removeListener(playerListener)
            playerService?.removeCallback(this@NowPlayingActivity)
            playerService = null
            serviceBound = false
            stopProgressUpdates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = TokenStorage(this)
        api = SoundCloudApi(storage)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        binding.btnBack.setOnClickListener { finish() }

        binding.btnPlayPause.setOnClickListener {
            playerService?.player?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        }

        binding.btnSkipNext.setOnClickListener {
            playerService?.player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
        }

        binding.btnSkipPrevious.setOnClickListener {
            playerService?.player?.let { player ->
                if (player.currentPosition > 3000) {
                    player.seekTo(0)
                } else if (player.hasPreviousMediaItem()) {
                    player.seekToPreviousMediaItem()
                } else {
                    player.seekTo(0)
                }
            }
        }

        binding.btnShuffle.setOnClickListener {
            shuffleEnabled = !shuffleEnabled
            updateShuffleRepeatTint()
        }

        binding.btnRepeat.setOnClickListener {
            repeatEnabled = !repeatEnabled
            updateShuffleRepeatTint()
        }

        binding.btnLike.setOnClickListener { toggleLike() }

        setupSeekBar()
        setupVolumeBar()

        updateShuffleRepeatTint()
        updateLikeButton()
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val player = playerService?.player ?: return
                val duration = player.duration
                if (duration > 0) {
                    player.seekTo((seekBar?.progress?.toLong() ?: 0L) * duration / 100L)
                }
            }
        })
    }

    private fun setupVolumeBar() {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        binding.volumeBar.max = maxVol
        binding.volumeBar.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateShuffleRepeatTint() {
        val orange = getColor(R.color.accent_orange)
        val secondary = getColor(R.color.text_secondary)
        binding.btnShuffle.setColorFilter(if (shuffleEnabled) orange else secondary)
        binding.btnRepeat.setColorFilter(if (repeatEnabled) orange else secondary)
    }

    private fun toggleLike() {
        val track = playerService?.currentTrack ?: return
        lifecycleScope.launch {
            val ok = if (isLiked) api.unlikeTrack(track.id) else api.likeTrack(track.id)
            if (ok) {
                isLiked = !isLiked
                updateLikeButton()
            } else {
                Toast.makeText(this@NowPlayingActivity, "Could not update like", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateLikeButton() {
        if (isLiked) {
            binding.btnLike.setImageResource(R.drawable.ic_heart_filled)
            binding.btnLike.setColorFilter(getColor(R.color.accent_orange))
        } else {
            binding.btnLike.setImageResource(R.drawable.ic_heart)
            binding.btnLike.setColorFilter(getColor(R.color.text_secondary))
        }
    }

    private fun updateUI() {
        val track = playerService?.currentTrack ?: return
        val player = playerService?.player ?: return

        binding.tvTitle.text = track.displayTitle
        binding.tvArtist.text = track.displayArtist

        val artworkUrl = track.artworkHigh ?: track.artworkUrl
        binding.ivArtwork.load(artworkUrl) { crossfade(true) }
        if (artworkUrl != null) updateBackground(artworkUrl)

        binding.btnPlayPause.setImageResource(
            if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        updateSeekBar()
    }

    private fun updateBackground(artworkUrl: String) {
        lifecycleScope.launch {
            val request = ImageRequest.Builder(this@NowPlayingActivity)
                .data(artworkUrl)
                .allowHardware(false)
                .size(200, 200)
                .build()
            val result = imageLoader.execute(request)
            if (result is SuccessResult) {
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@launch
                val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).generate() }
                val swatch = palette.darkVibrantSwatch
                    ?: palette.vibrantSwatch
                    ?: palette.dominantSwatch
                val rgb = swatch?.rgb ?: return@launch
                val darkened = Color.argb(
                    255,
                    (Color.red(rgb) * 0.35f).toInt(),
                    (Color.green(rgb) * 0.35f).toInt(),
                    (Color.blue(rgb) * 0.35f).toInt()
                )
                val gradient = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(darkened, Color.BLACK)
                )
                binding.rootLayout.background = gradient
            }
        }
    }

    private fun updateSeekBar() {
        val player = playerService?.player ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val position = player.currentPosition
        binding.seekBar.progress = (position * 100L / duration).toInt()
        binding.tvCurrentTime.text = formatTime(position)
        binding.tvDuration.text = formatTime(duration)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        val runnable = object : Runnable {
            override fun run() {
                updateSeekBar()
                handler.postDelayed(this, 500)
            }
        }
        progressRunnable = runnable
        handler.post(runnable)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    override fun onTrackChanged(track: ScTrack?) {
        runOnUiThread {
            isLiked = false
            updateLikeButton()
            updateUI()
        }
    }

    override fun onPlayStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            binding.btnPlayPause.setImageResource(
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
        stopProgressUpdates()
        if (serviceBound) {
            playerService?.player?.removeListener(playerListener)
            playerService?.removeCallback(this)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
