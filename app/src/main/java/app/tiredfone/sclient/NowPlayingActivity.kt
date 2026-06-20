package app.tiredfone.sclient

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import coil.load
import app.tiredfone.sclient.databinding.ActivityNowPlayingBinding

class NowPlayingActivity : AppCompatActivity(), PlayerService.PlayerCallback {

    private lateinit var binding: ActivityNowPlayingBinding

    private var playerService: PlayerService? = null
    private var serviceBound = false

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
            if (playerService?.player?.isPlaying == true) {
                startProgressUpdates()
            }
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

        binding.btnBack.setOnClickListener { finish() }

        binding.btnPlayPause.setOnClickListener {
            playerService?.player?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        }

        binding.btnSkipNext.setOnClickListener {
            playerService?.player?.let { player ->
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val player = playerService?.player ?: return
                val duration = player.duration
                if (duration > 0) {
                    val seekTo = (seekBar?.progress?.toLong() ?: 0L) * duration / 100L
                    player.seekTo(seekTo)
                }
            }
        })
    }

    private fun updateUI() {
        val track = playerService?.currentTrack ?: return
        val player = playerService?.player ?: return

        binding.tvTitle.text = track.displayTitle
        binding.tvArtist.text = track.displayArtist
        binding.ivArtwork.load(track.artworkHigh ?: track.artworkUrl) {
            crossfade(true)
        }

        val isPlaying = player.isPlaying
        binding.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        updateSeekBar()
    }

    private fun updateSeekBar() {
        val player = playerService?.player ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val position = player.currentPosition

        val progress = (position * 100L / duration).toInt()
        binding.seekBar.progress = progress

        binding.tvCurrentTime.text = formatTime(position)
        binding.tvDuration.text = formatTime(duration)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
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
        runOnUiThread { updateUI() }
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
        val intent = Intent(this, PlayerService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
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
